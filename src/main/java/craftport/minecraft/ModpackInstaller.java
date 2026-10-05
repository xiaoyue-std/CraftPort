package craftport.minecraft;

import com.google.gson.JsonObject;
import craftport.base.Json;
import craftport.base.Log;
import craftport.net.Downloader;

import java.io.IOException;
import java.io.InputStream;
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
 *  3. 客户端模式：安装基础版本 + 加载器，mod 装入版本隔离目录
 *     服务端模式：mods/overrides 直接装入指定服务端目录
 *     一键部署模式：先按 manifest 自动部署对应类型的服务端，再装入全部内容
 *  4. 批量拉取成员 mod（POST /v1/mods/files，每批 100）→ forgecdn 直链下载
 *  5. 复制 overrides（config 等）到目标目录
 *  6. 打包配套客户端 zip（mods + config），玩家解压进自己的实例即可
 */
public final class ModpackInstaller {

    /** 安装结果摘要。 */
    public record InstallResult(String packName, String mc, String loader, Path targetDir,
                                int modsInstalled, Path clientPack) {}

    /** 解析后的整合包内容（staging 目录由调用方负责清理）。 */
    private record Prepared(Path staging, Path extracted, JsonObject manifest,
                            String packName, String mc,
                            ModLoader.Kind loaderKind, String loaderVersion) {}

    private ModpackInstaller() {}

    // ==================== 客户端 / 已有服务端安装 ====================

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
        Prepared p = prepare(pack, packFile);
        try {
            Path targetDir;
            if (server) {
                targetDir = serverDir;
                Files.createDirectories(targetDir);
            } else {
                if (!Files.isRegularFile(mcRoot.resolve("versions").resolve(p.mc()).resolve(p.mc() + ".json"))) {
                    System.out.println("Base version " + p.mc() + " not installed - installing...");
                    InstallService.install(p.mc(), mcRoot);
                }
                if (p.loaderKind() != null) {
                    String entry = resolveLoaderEntry(p.loaderKind(), p.mc(), p.loaderVersion());
                    System.out.println("Installing loader " + p.loaderKind() + " " + p.loaderVersion() + " ...");
                    String versionName = ModLoader.install(p.loaderKind(), p.mc(), entry, mcRoot);
                    targetDir = mcRoot.resolve("versions").resolve(versionName);
                } else {
                    targetDir = mcRoot.resolve("versions").resolve(p.mc());
                }
            }
            return finishInstall(p, mcRoot, targetDir, modProgress);
        } finally {
            deleteRecursively(p.staging());
        }
    }

    // ==================== 一键服务端部署（主方向） ====================

    /**
     * CurseForge 整合包一键服务端部署：
     * 从 manifest 自动推断 MC 版本与加载器类型 → 部署对应服务器（Java 自动解析/下载）
     * → 全部成员 mod + overrides 装入 → 生成配套客户端包。
     *
     * @param serverDir 目标目录；null 时用默认 {游戏根}/servers/{mc}-{type}
     */
    public static InstallResult deployServer(CurseForge.ModInfo pack, CurseForge.FileInfo packFile,
                                             Path mcRoot, Path serverDir, boolean acceptEula,
                                             BiConsumer<Integer, Integer> modProgress) throws IOException {
        Prepared p = prepare(pack, packFile);
        try {
            ServerDeployer.Kind kind = p.loaderKind() != null
                    ? (p.loaderKind() == ModLoader.Kind.FABRIC ? ServerDeployer.Kind.FABRIC
                                                               : ServerDeployer.Kind.FORGE)
                    : ServerDeployer.Kind.VANILLA;
            Path dir = serverDir != null ? serverDir
                    : ServerDeployer.defaultDir(p.mc(), kind);
            System.out.println("Deploying " + kind.name().toLowerCase() + " server " + p.mc() + " into " + dir + " ...");
            ServerDeployer.deploy(kind, p.mc(), dir, acceptEula, true, null);

            InstallResult result = finishInstall(p, mcRoot, dir, modProgress);
            System.out.println("Server ready. Start it with: server start --dir \"" + dir + "\"");
            return new InstallResult(result.packName(), result.mc(), result.loader(),
                    dir, result.modsInstalled(), result.clientPack());
        } finally {
            deleteRecursively(p.staging());
        }
    }

    // ==================== 公共安装步骤 ====================

    /** 成员 mod 下载 + overrides 复制 + 客户端包生成（deploy 与 install 共用的收尾）。 */
    private static InstallResult finishInstall(Prepared p, Path mcRoot, Path targetDir,
                                               BiConsumer<Integer, Integer> modProgress) throws IOException {
        Path modsDir = targetDir.resolve("mods");
        Files.createDirectories(modsDir);

        // 1. 成员 mod（批量元数据 + forgecdn 直链，多线程）
        List<Long> fileIds = new ArrayList<>();
        if (p.manifest().has("files") && p.manifest().get("files").isJsonArray()) {
            for (var e : p.manifest().getAsJsonArray("files")) {
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
            }, "craftport-modpack");
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

        // 2. overrides（config 等）复制到目标目录
        Path overrides = p.extracted().resolve("overrides");
        if (Files.isDirectory(overrides)) {
            copyTree(overrides, targetDir);
            System.out.println("Overrides copied (config etc.).");
        }

        // 3. 配套客户端包（mods + config 打包为 zip，玩家解压进客户端实例）
        Path clientPack = null;
        Path packDir = mcRoot.resolve("clientpacks");
        Files.createDirectories(packDir);
        clientPack = packDir.resolve(slugify(p.packName()) + "-client.zip");
        zipDirs(clientPack, List.of(modsDir, targetDir.resolve("config")));

        // 4. 按实际落盘统计
        int onDisk;
        try (var s2 = Files.list(modsDir)) {
            onDisk = (int) s2.filter(f -> f.getFileName().toString().endsWith(".jar")).count();
        }
        if (onDisk < total) {
            System.out.println("WARNING: " + (total - onDisk) + " mod file(s) missing after install.");
        }
        String loaderLabel = p.loaderKind() != null
                ? p.loaderKind().name().toLowerCase() + " " + p.loaderVersion() : "vanilla";
        return new InstallResult(p.packName(), p.mc(), loaderLabel, targetDir, onDisk, clientPack);
    }

    /** 下载整合包 zip、解包并解析 manifest（staging 目录随 finally 清理）。 */
    private static Prepared prepare(CurseForge.ModInfo pack, CurseForge.FileInfo packFile) throws IOException {
        Path staging = Files.createTempDirectory("craftport-modpack");
        System.out.println("Downloading modpack zip (" + String.format("%.1fMB", packFile.size() / 1048576.0) + ")...");
        Path zip = staging.resolve("pack.zip");
        Downloader.download(new Downloader.DownloadItem(
                CurseForge.cdnUrl(packFile.id(), packFile.name()), zip, "", packFile.size(), packFile.name()), null);
        Path extracted = staging.resolve("unpacked");
        extractZip(zip, extracted);

        JsonObject manifest = Json.parseObject(Files.readString(extracted.resolve("manifest.json"),
                StandardCharsets.UTF_8));
        if (manifest == null || !manifest.has("minecraft")) {
            throw new IOException("manifest.json missing or invalid (not a CurseForge modpack?)");
        }
        String packName = Json.str(manifest, "name", pack.name());
        JsonObject minecraft = manifest.getAsJsonObject("minecraft");
        String mc = Json.str(minecraft, "version", "");
        if (mc.isBlank()) throw new IOException("manifest has no Minecraft version");
        ModLoader.Kind loaderKind = null;
        String loaderVersion = "";
        if (minecraft.has("modLoaders") && minecraft.getAsJsonArray("modLoaders").size() > 0) {
            String id = Json.str(minecraft.getAsJsonArray("modLoaders").get(0).getAsJsonObject(), "id", "");
            int dash = id.indexOf('-');
            String kind = dash > 0 ? id.substring(0, dash) : id;
            loaderVersion = dash > 0 ? id.substring(dash + 1) : "";
            if (kind.equals("fabric")) loaderKind = ModLoader.Kind.FABRIC;
            else if (kind.equals("forge")) loaderKind = ModLoader.Kind.FORGE;
        }
        return new Prepared(staging, extracted, manifest, packName, mc, loaderKind, loaderVersion);
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
