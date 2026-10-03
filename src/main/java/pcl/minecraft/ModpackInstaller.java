package pcl.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pcl.base.Json;
import pcl.base.Log;
import pcl.base.Task;
import pcl.net.Downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * CurseForge 整合包安装（对应 ModModpack.vb 的核心流程）。
 *
 * CurseForge 整合包 = zip{manifest.json, overrides/}：
 *  1. 下载整合包 zip 并解包（防 zip-slip）
 *  2. 解析 manifest：MC 版本、加载器（forge-x / fabric-x）、成员 mod 文件清单（projectID/fileID）
 *  3. 客户端模式：安装基础版本 + 加载器（复用 InstallService / ModLoader），
 *     mod 装入版本隔离目录；服务端模式：直接装入指定服务端目录的 mods/
 *  4. 批量拉取成员 mod（POST /v1/mods/files，每批 100）→ forgecdn 直链下载
 *  5. 复制 overrides（config 等）到目标目录
 *  6. 打包配套客户端 zip（mods + config），玩家解压进自己的实例即可
 */
public final class ModpackInstaller {

    /** 安装结果摘要。 */
    public record InstallResult(String packName, String mc, String loader, Path modsDir,
                                int modsInstalled, Path clientPack) {}

    private ModpackInstaller() {}

    // ==================== 安装 ====================

    /**
     * 安装整合包。
     *
     * @param server     false = 客户端模式（自动装基础版本与加载器，生成配套客户端包）
     *                   true  = 服务端模式（mods/overrides 直接装入 serverDir）
     * @param serverDir  服务端模式的目标目录
     */
    public static InstallResult install(CurseForge.ModInfo pack, CurseForge.FileInfo packFile,
                                        Path mcRoot, boolean server, Path serverDir,
                                        BiConsumer<Integer, Integer> modProgress) throws IOException {
        Path staging = Files.createTempDirectory("pclj-modpack");
        try {
            // 1. 下载并解包
            System.out.println("Downloading modpack zip (" + String.format("%.1fMB", packFile.size() / 1048576.0) + ")...");
            Path zip = staging.resolve("pack.zip");
            Downloader.download(new Downloader.DownloadItem(
                    CurseForge.cdnUrl(packFile.id(), packFile.name()), zip, "", packFile.size(), packFile.name()), null);
            Path extracted = staging.resolve("unpacked");
            extractZip(zip, extracted);

            // 2. 解析 manifest
            JsonObject manifest = Json.parseObject(Files.readString(extracted.resolve("manifest.json"),
                    StandardCharsets.UTF_8));
            if (manifest == null || !manifest.has("minecraft")) {
                throw new IOException("manifest.json missing or invalid (not a CurseForge modpack?)");
            }
            String packName = Json.str(manifest, "name", pack.name());
            JsonObject minecraft = manifest.getAsJsonObject("minecraft");
            String mc = Json.str(minecraft, "version", "");
            if (mc.isBlank()) throw new IOException("manifest has no Minecraft version");
            String loaderLabel = "vanilla";
            ModLoader.Kind loaderKind = null;
            String loaderVersion = "";
            if (minecraft.has("modLoaders") && minecraft.getAsJsonArray("modLoaders").size() > 0) {
                String id = Json.str(minecraft.getAsJsonArray("modLoaders").get(0).getAsJsonObject(), "id", "");
                int dash = id.indexOf('-');
                String kind = dash > 0 ? id.substring(0, dash) : id;
                loaderVersion = dash > 0 ? id.substring(dash + 1) : "";
                if (kind.equals("fabric")) loaderKind = ModLoader.Kind.FABRIC;
                else if (kind.equals("forge")) loaderKind = ModLoader.Kind.FORGE;
                loaderLabel = kind + " " + loaderVersion;
            }

            // 3. 目标目录
            Path targetDir;
            if (server) {
                targetDir = serverDir;
                Files.createDirectories(targetDir);
            } else {
                if (!Files.isRegularFile(mcRoot.resolve("versions").resolve(mc).resolve(mc + ".json"))) {
                    System.out.println("Base version " + mc + " not installed - installing...");
                    InstallService.install(mc, mcRoot);
                }
                if (loaderKind != null) {
                    String entry = resolveLoaderEntry(loaderKind, mc, loaderVersion);
                    System.out.println("Installing loader " + loaderLabel + " ...");
                    String versionName = ModLoader.install(loaderKind, mc, entry, mcRoot);
                    targetDir = mcRoot.resolve("versions").resolve(versionName);
                } else {
                    targetDir = mcRoot.resolve("versions").resolve(mc);
                }
            }
            Path modsDir = targetDir.resolve("mods");
            Files.createDirectories(modsDir);

            // 4. 下载成员 mod（批量接口 + forgecdn 直链，多线程）
            List<Long> fileIds = new ArrayList<>();
            if (manifest.has("files") && manifest.get("files").isJsonArray()) {
                for (var e : manifest.getAsJsonArray("files")) {
                    if (!e.isJsonObject()) continue;
                    long fid = Json.longOf(e.getAsJsonObject(), "fileID", 0);
                    if (fid > 0) fileIds.add(fid);
                }
            }
            System.out.println("Fetching " + fileIds.size() + " mod files metadata...");
            Map<Long, CurseForge.FileInfo> members = new HashMap<>();
            if (!fileIds.isEmpty()) {
                for (CurseForge.FileInfo f : CurseForge.filesBatch(fileIds)) members.put(f.id(), f);
            }
            int total = members.size();
            int[] done = {0};
            List<Map.Entry<Long, CurseForge.FileInfo>> items = new ArrayList<>(members.entrySet());
            int threads = Math.min(6, Math.max(1, items.size()));
            List<List<Map.Entry<Long, CurseForge.FileInfo>>> buckets = new ArrayList<>();
            for (int i = 0; i < threads; i++) buckets.add(new ArrayList<>());
            for (int i = 0; i < items.size(); i++) buckets.get(i % threads).add(items.get(i));
            List<IOException> errors = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> workers = new ArrayList<>();
            for (List<Map.Entry<Long, CurseForge.FileInfo>> bucket : buckets) {
                Thread t = new Thread(() -> {
                    for (var entry : bucket) {
                        CurseForge.FileInfo f = entry.getValue();
                        Path dest = modsDir.resolve(f.name());
                        try {
                            if (!Files.exists(dest) || Files.size(dest) != f.size()) {
                                // CurseForge.download: forgecdn 直链 + mcimirror 兜底
                                CurseForge.download(f, dest, null);
                            }
                        } catch (IOException | RuntimeException e) {
                            errors.add(new IOException(f.name() + ": " + e.getMessage(), e));
                        }
                        synchronized (done) {
                            done[0]++;
                            if (modProgress != null) modProgress.accept(done[0], total);
                        }
                    }
                }, "pclj-modpack");
                workers.add(t);
                t.start();
            }
            for (Thread t : workers) {
                try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (!errors.isEmpty()) {
                throw new IOException(errors.size() + " mod file(s) failed, first: "
                        + errors.get(0).getMessage());
            }

            // 5. overrides（config 等）复制到目标目录
            Path overrides = extracted.resolve("overrides");
            if (Files.isDirectory(overrides)) {
                copyTree(overrides, targetDir);
                System.out.println("Overrides copied (config etc.).");
            }

            // 6. 配套客户端包（mods + config 打包为 zip，玩家解压进客户端实例）
            Path clientPack = null;
            Path packDir = mcRoot.resolve("clientpacks");
            Files.createDirectories(packDir);
            clientPack = packDir.resolve(slugify(packName) + "-client.zip");
            zipDirs(clientPack, List.of(modsDir, targetDir.resolve("config")));

            int onDisk;
            try (var s2 = Files.list(modsDir)) {
                onDisk = (int) s2.filter(p2 -> p2.getFileName().toString().endsWith(".jar")).count();
            }
            if (onDisk < total) {
                System.out.println("WARNING: " + (total - onDisk) + " mod file(s) missing after install.");
            }
            return new InstallResult(packName, mc, loaderLabel, modsDir, onDisk, clientPack);
        } finally {
            deleteRecursively(staging);
        }
    }

    /** Forge 成员版本号 → 我们的 entry 格式（version@build）。 */
    private static String resolveLoaderEntry(ModLoader.Kind kind, String mc, String loaderVersion) throws IOException {
        if (kind == ModLoader.Kind.FABRIC) return loaderVersion;
        List<ModLoader.LoaderEntry> loaders = ModLoader.fetchLoaders(ModLoader.Kind.FORGE, mc);
        return loaders.stream().map(ModLoader.LoaderEntry::version)
                .filter(v -> v.startsWith(loaderVersion + "@") || v.equals(loaderVersion))
                .findFirst()
                .orElseThrow(() -> new IOException("Forge version " + loaderVersion + " not found on mirror"));
    }

    // ==================== zip 工具 ====================

    /** 解包 zip（防 zip-slip：全部条目必须落在目标目录内）。 */
    private static void extractZip(Path zip, Path target) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            Path norm = target.toAbsolutePath().normalize();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path out = norm.resolve(entry.getName()).normalize();
                if (!out.startsWith(norm)) throw new IOException("Zip entry escapes target: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** 递归复制目录（overrides → 目标）。 */
    private static void copyTree(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(src.relativize(d).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /** 把若干目录打包为 zip（保留各自一级目录名，玩家解压即得 mods/ config/）。 */
    public static void zipDirs(Path zipFile, List<Path> dirs) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                String base = dir.getFileName().toString() + "/";
                Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        zip.putNextEntry(new ZipEntry(base + dir.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, zip);
                        zip.closeEntry();
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        if (!d.equals(dir)) zip.putNextEntry(new ZipEntry(base + dir.relativize(d).toString().replace('\\', '/') + "/"));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        System.out.println("Client pack written: " + zipFile);
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.delete(d);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }
}
