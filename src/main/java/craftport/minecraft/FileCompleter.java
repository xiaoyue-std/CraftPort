package craftport.minecraft;

import com.google.gson.JsonObject;
import craftport.base.Json;
import craftport.base.Log;
import craftport.base.Os;
import craftport.net.Downloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 启动前文件补全，移植自 ModDownload.vb（DlClientFix / DlClientAssetIndexGet / 资源对象下载）
 * 与 ModLaunch.McLaunchNatives 的 natives 解压。
 */
public final class FileCompleter {

    /** PCL2 硬编码的 legacy 资源索引回退（sha1 与 URL）。 */
    private static final String LEGACY_INDEX_SHA1 = "c0fd82e8ce9fbc93119e40d96d5a4e62cfa3f729";
    private static final String LEGACY_INDEX_URL =
            "https://launchermeta.mojang.com/mc/assets/legacy/c0fd82e8ce9fbc93119e40d96d5a4e62cfa3f729/legacy.json";

    private FileCompleter() {}

    /** 下载客户端 jar（对应 DlClient）。 */
    public static void completeClient(McVersion version, Path mcRoot, int[] progressSlot) throws IOException {
        Path jar = version.clientJar();
        if (Files.exists(jar) && Files.size(jar) > 0) return;
        String url = version.clientJarUrl();
        if (url.isBlank()) throw new IOException("版本 " + version.name + " 缺少客户端 jar 且没有下载地址");
        Downloader.download(new Downloader.DownloadItem(url, jar, version.clientJarSha1(), 0,
                version.name + " 客户端"), null);
    }

    /** 下载全部 libraries（含各平台 natives，对应 DlMod / 库补全）。 */
    public static void completeLibraries(McVersion version, Path mcRoot) throws IOException {
        Path librariesDir = mcRoot.resolve("libraries");
        List<Library> libs = version.libraries();
        List<Downloader.DownloadItem> items = new ArrayList<>();
        for (Library lib : libs) {
            if (!lib.hasExplicitUrl()) continue; // 运行时生成的库（如 Forge :client）跳过
            items.add(new Downloader.DownloadItem(lib.url(), lib.localPath(librariesDir),
                    lib.sha1(), lib.size(), lib.name()));
            String nativesCls = lib.nativesClassifier();
            if (nativesCls != null) {
                items.add(new Downloader.DownloadItem(lib.nativesUrl(nativesCls),
                        librariesDir.resolve(lib.pathWithClassifier(nativesCls)),
                        lib.nativesSha1(nativesCls), 0, lib.name() + " natives"));
            }
        }
        Downloader.downloadBatch(items, null);
    }

    /** 下载资源索引与资源对象（对应 DlClientAssetIndexGet / 资源对象下载）。 */
    public static void completeAssets(McVersion version, Path mcRoot, Path gameDir) throws IOException {
        Path assetsDir = mcRoot.resolve("assets");
        Files.createDirectories(assetsDir.resolve("indexes"));

        // 1. 资源索引
        JsonObject assetIndex = version.assetIndex();
        String indexId;
        Path indexFile;
        if (assetIndex != null && !Json.str(assetIndex, "id", "").isBlank()) {
            indexId = Json.str(assetIndex, "id", "");
            indexFile = assetsDir.resolve("indexes").resolve(indexId + ".json");
            if (!JsonUtil.isJsonFile(indexFile)) {
                String url = Json.str(assetIndex, "url", "");
                String sha1 = Json.str(assetIndex, "sha1", "");
                if (url.isBlank()) throw new IOException("资源索引下载地址为空");
                Downloader.download(new Downloader.DownloadItem(url, indexFile, sha1, 0, "资源索引 " + indexId), null);
            }
        } else {
            // 回退 legacy 索引（对应 PCL2 的硬编码回退）
            indexId = "legacy";
            indexFile = assetsDir.resolve("indexes").resolve("legacy.json");
            if (!JsonUtil.isJsonFile(indexFile)) {
                Downloader.download(new Downloader.DownloadItem(LEGACY_INDEX_URL, indexFile,
                        LEGACY_INDEX_SHA1, 0, "资源索引 legacy"), null);
            }
        }

        JsonObject index = Json.parseObject(Files.readString(indexFile));
        if (index == null || !index.has("objects")) throw new IOException("资源索引损坏: " + indexFile);
        boolean virtual = Json.bool(index, "virtual", false);
        boolean mapToResources = Json.bool(index, "map_to_resources", false);

        // 2. 资源对象
        List<Downloader.DownloadItem> items = new ArrayList<>();
        Path objectsDir = assetsDir.resolve("objects");
        Path virtualDir = assetsDir.resolve("virtual").resolve(indexId);
        Path resourcesDir = gameDir.resolve("resources");
        for (var entry : index.getAsJsonObject("objects").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = Json.str(obj, "hash", "");
            if (hash.length() < 4) continue;
            String prefix = hash.substring(0, 2);
            Path target = objectsDir.resolve(prefix).resolve(hash);
            if (!Files.exists(target)) {
                items.add(new Downloader.DownloadItem(
                        "https://resources.download.minecraft.net/" + prefix + "/" + hash,
                        target, hash, Json.longOf(obj, "size", 0), entry.getKey()));
            }
        }
        if (!items.isEmpty()) {
            Log.info("需下载资源文件 " + items.size() + " 个");
            Downloader.downloadBatch(items, done -> {
                if (done % 200 == 0) Log.info("资源下载进度 " + done + "/" + items.size());
            });
        }

        // 3. virtual / map_to_resources 硬链接式复制（对应 PCL2 的虚拟资源处理）
        if (virtual) copyTree(objectsDir, virtualDir, index);
        if (mapToResources) copyTree(objectsDir, resourcesDir, index);
    }

    private static void copyTree(Path objectsDir, Path targetDir, JsonObject index) throws IOException {
        for (var entry : index.getAsJsonObject("objects").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = Json.str(obj, "hash", "");
            Path src = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            Path dest = targetDir.resolve(entry.getKey());
            if (Files.exists(src) && (!Files.exists(dest) || Files.size(dest) != Files.size(src))) {
                Files.createDirectories(dest.getParent());
                try (InputStream in = Files.newInputStream(src)) {
                    Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 解压 natives（对应 McLaunchNatives）：
     * 只解压当前平台后缀的本体文件（.dll/.so/.dylib），已存在且同大小则跳过，最后清理多余文件。
     */
    public static void extractNatives(McVersion version, Path mcRoot) throws IOException {
        Path targetDir = version.nativesDir();
        Files.createDirectories(targetDir);
        String suffix = Os.nativeLibSuffix();
        List<Path> validFiles = new ArrayList<>();
        for (Library lib : version.libraries()) {
            String cls = lib.nativesClassifier();
            if (cls == null) continue;
            Path jar = mcRoot.resolve("libraries").resolve(lib.pathWithClassifier(cls));
            if (!Files.exists(jar)) continue;
            List<String> excludes = lib.extractExcludes();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (!entryName.endsWith(suffix)) continue; // 只解压当前平台库文件
                    String fileName = Path.of(entryName.replace('\\', '/')).getFileName().toString();
                    boolean excluded = excludes.stream().anyMatch(fileName::startsWith);
                    if (excluded) continue;
                    Path target = targetDir.resolve(fileName);
                    if (!Files.exists(target) || Files.size(target) != entry.getSize()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        Log.debug("解压 native: " + fileName);
                    }
                    validFiles.add(target);
                }
            }
        }
        // 清理目录内多余文件（对应 PCL2 的"删除目录内多余文件"）
        try (var stream = Files.list(targetDir)) {
            for (Path p : stream.toList()) {
                if (!validFiles.contains(p)) {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                }
            }
        }
    }

    /** 小工具。 */
    private static final class JsonUtil {
        static boolean isJsonFile(Path p) {
            try {
                return Files.isRegularFile(p) && Json.parseObject(Files.readString(p)) != null;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
