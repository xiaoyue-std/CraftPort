package pcl.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pcl.base.Json;
import pcl.base.Log;
import pcl.net.Downloader;
import pcl.net.Net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 模组加载器安装引擎（对应 ModDownload.vb / ModInstall 的 Forge、Fabric、OptiFine 安装）。
 *
 * 实现方式：
 *  - Fabric：直接下载 Fabric 官方 meta 生成的 profile json（含全部库声明），库在启动前自动补全
 *  - Forge（1.13+）：解析安装器内的 version.json 与库清单，下载 maven 库（BMCLAPI 镜像），
 *    提取 data/client.lzma（旧版为 binpatches.pack.lzma）到版本文件夹并在启动时注入 classpath，
 *    由 Forge bootstrap 在运行时完成二进制补丁；1.12 及更早版本需要安装期补丁，暂不支持
 *  - OptiFine：调用官方安装器的 optifine.Installer#doInstall(File)（子进程无头执行，
 *    内部处理新版 xdelta 补丁），完成后重新扫描版本
 */
public final class ModLoader {

    /** 加载器种类。 */
    public enum Kind { FABRIC, FORGE, OPTIFINE }

    /** 加载器版本条目（列表展示用）。version 为安装用内部标识，display 为列表展示文本。 */
    public record LoaderEntry(String version, String display, String detail) {
        @Override
        public String toString() { return display + (detail == null || detail.isBlank() ? "" : "   [" + detail + "]"); }
    }

    private ModLoader() {}

    // ==================== 版本列表 ====================

    /** 获取指定 MC 版本可用的加载器列表（按新旧排序）。 */
    public static List<LoaderEntry> fetchLoaders(Kind kind, String mcVersion) throws IOException {
        List<LoaderEntry> list = new ArrayList<>();
        switch (kind) {
            case FABRIC -> {
                // Fabric meta：https://meta.fabricmc.net/v2/versions/loader/{mc}
                String text = Net.get("https://meta.fabricmc.net/v2/versions/loader/" + mcVersion);
                if (Json.parse(text) instanceof JsonArray arr) {
                    for (JsonElement e : arr) {
                        if (!e.isJsonObject()) continue;
                        JsonObject loader = e.getAsJsonObject().getAsJsonObject("loader");
                        if (loader == null) continue;
                        String version = Json.str(loader, "version", "");
                        if (version.isBlank()) continue;
                        boolean stable = Json.bool(loader, "stable", false);
                        list.add(new LoaderEntry(version, version, stable ? "稳定" : "测试"));
                    }
                }
            }
            case FORGE -> {
                // BMCLAPI：https://bmclapi2.bangbang93.com/forge/minecraft/{mc}
                String text = Net.get(Net.BMCLAPI + "/forge/minecraft/" + mcVersion);
                if (Json.parse(text) instanceof JsonArray arr) {
                    for (JsonElement e : arr) {
                        if (!e.isJsonObject()) continue;
                        JsonObject o = e.getAsJsonObject();
                        String version = Json.str(o, "version", "");
                        long build = Json.longOf(o, "build", 0);
                        if (version.isBlank() || build == 0) continue;
                        list.add(new LoaderEntry(version + "@" + build, version, ""));
                    }
                }
                // 新版本在前（build 越大越新）
                list.sort((a, b) -> Long.compare(
                        Long.parseLong(b.version().substring(b.version().indexOf('@') + 1)),
                        Long.parseLong(a.version().substring(a.version().indexOf('@') + 1))));
            }
            case OPTIFINE -> {
                // BMCLAPI：https://bmclapi2.bangbang93.com/optifine/{mc}
                String text = Net.get(Net.BMCLAPI + "/optifine/" + mcVersion);
                if (Json.parse(text) instanceof JsonArray arr) {
                    for (JsonElement e : arr) {
                        if (!e.isJsonObject()) continue;
                        JsonObject o = e.getAsJsonObject();
                        String type = Json.str(o, "type", "");
                        String patch = Json.str(o, "patch", "");
                        if (type.isBlank()) continue;
                        boolean preview = patch.startsWith("pre");
                        list.add(new LoaderEntry(type + "/" + patch,
                                type.replace('_', ' ') + " " + patch, preview ? "预览版" : "正式"));
                    }
                }
                // 正式版在前
                list.sort((a, b) -> Boolean.compare(a.detail().equals("预览版"), b.detail().equals("预览版")));
            }
        }
        return list;
    }

    // ==================== 安装 ====================

    /**
     * 安装加载器到已存在的 MC 版本之上。
     *
     * @param mcVersion 目标 MC 版本（须已安装）
     * @param entry     加载器版本（fetchLoaders 返回的条目）
     * @return 新版本文件夹名
     */
    public static String install(Kind kind, String mcVersion, String entry, Path mcRoot) throws IOException {
        // 前置：目标 MC 版本必须已安装（加载器 json 依赖原版本的库与 jar）
        Path mcFolder = mcRoot.resolve("versions").resolve(mcVersion);
        if (!Files.isRegularFile(mcFolder.resolve(mcVersion + ".json"))) {
            throw new IOException("请先安装 Minecraft " + mcVersion + " 再安装加载器");
        }
        return switch (kind) {
            case FABRIC -> installFabric(mcVersion, entry, mcRoot);
            case FORGE -> installForge(mcVersion, entry, mcRoot);
            case OPTIFINE -> installOptiFine(mcVersion, entry, mcRoot);
        };
    }

    // ---- Fabric ----

    private static String installFabric(String mcVersion, String loaderVersion, Path mcRoot) throws IOException {
        // Fabric 官方 meta 直接给出可启动的 profile json
        String name = mcVersion + "-fabric";
        String profile = Net.get("https://meta.fabricmc.net/v2/versions/loader/"
                + mcVersion + "/" + loaderVersion + "/profile/json");
        JsonObject json = Json.parseObject(profile);
        if (json == null) throw new IOException("Fabric profile 获取失败");
        json.addProperty("id", name);
        Path folder = mcRoot.resolve("versions").resolve(name);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name + ".json"), Json.GSON.toJson(json), StandardCharsets.UTF_8);
        Log.info("Fabric 安装完成: " + name + " (loader " + loaderVersion + ")");
        return name;
    }

    // ---- Forge ----

    /** BMCLAPI 提供的 Forge 安装器专用 maven 镜像（官方安装器原生支持 --mirror 参数）。 */
    private static final String FORGE_INSTALLER_MIRROR = "https://bmclapi2.bangbang93.com/maven";

    private static String installForge(String mcVersion, String entry, Path mcRoot) throws IOException {
        String version = entry.substring(0, entry.indexOf('@'));
        String build = entry.substring(entry.indexOf('@') + 1);
        // 官方安装器需要原版客户端 jar 作为补丁基文件
        Path clientJar = mcRoot.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
        if (!Files.isRegularFile(clientJar)) {
            throw new IOException("Forge 安装需要原版客户端 jar，请先启动一次 " + mcVersion
                    + "（自动补全客户端文件）后再安装");
        }
        Path installer = mcRoot.resolve("forge-installer-" + version + ".jar");

        // 1. 下载官方安装器（BMCLAPI 优先，回退官方 maven）
        try {
            Downloader.download(new Downloader.DownloadItem(
                    Net.BMCLAPI + "/forge/download/" + build, installer, "", 0, "Forge 安装器"), null);
        } catch (IOException e) {
            Log.warn("BMCLAPI 下载 Forge 安装器失败，回退官方源: " + e.getMessage());
            Downloader.download(new Downloader.DownloadItem(
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                            + mcVersion + "-" + version + "/forge-" + mcVersion + "-" + version + "-installer.jar",
                    installer, "", 0, "Forge 安装器"), null);
        }

        // 2. 运行官方无头安装（--installClient：完整执行补丁 processors、下载 maven 库、生成版本文件夹；
        //    --mirror 指向 BMCLAPI 加速库下载）
        String javaExe = ProcessHandle.current().info().command().orElse("java");
        Log.info("运行 Forge 官方无头安装（首次会下载处理器与库，可能需要数分钟）…");
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-Djava.awt.headless=true", "-jar",
                installer.toAbsolutePath().toString(),
                "--installClient", mcRoot.toAbsolutePath().toString(),
                "--mirror", FORGE_INSTALLER_MIRROR);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("Forge 安装超时（15 分钟）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Forge 安装被中断", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("Forge 官方安装器执行失败（退出码 " + p.exitValue() + "）："
                    + output.substring(Math.max(0, output.length() - 500)));
        }

        // 3. 定位安装器生成的版本文件夹（命名 {mc}-forge-{version}）
        String name = mcVersion + "-forge-" + version;
        if (!Files.isRegularFile(mcRoot.resolve("versions").resolve(name).resolve(name + ".json"))) {
            // 兜底：按目录差集找新文件夹
            String generated = findGeneratedOptiFineLike(mcRoot, "forge");
            if (generated != null) name = generated;
            else throw new IOException("Forge 安装器未生成版本文件夹");
        }
        try { Files.deleteIfExists(installer); } catch (IOException ignored) {}
        Log.info("Forge 安装完成: " + name);
        return name;
    }

    // ---- OptiFine ----

    private static String installOptiFine(String mcVersion, String entry, Path mcRoot) throws IOException {
        // 官方安装器（含 xdelta 补丁流程）需要原版客户端 jar 作为基文件
        Path clientJar = mcRoot.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
        if (!Files.isRegularFile(clientJar)) {
            throw new IOException("OptiFine 安装需要原版客户端 jar，请先启动一次 " + mcVersion
                    + "（自动补全客户端文件）后再安装");
        }
        // 记录安装前已有的版本文件夹，用于识别新生成的版本
        var before = new java.util.HashSet<String>();
        Path versionsDir = mcRoot.resolve("versions");
        if (Files.isDirectory(versionsDir)) {
            try (var s = Files.list(versionsDir)) {
                s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).forEach(before::add);
            }
        }
        String type = entry.substring(0, entry.indexOf('/'));
        String patch = entry.substring(entry.indexOf('/') + 1);
        Path installer = mcRoot.resolve("OptiFine_" + mcVersion + "_" + type + "_" + patch + ".jar");

        // 1. 下载官方安装器（BMCLAPI 镜像）
        Downloader.download(new Downloader.DownloadItem(
                Net.BMCLAPI + "/optifine/" + mcVersion + "/" + type + "/" + patch,
                installer, "", 0, "OptiFine 安装器"), null);

        // 2. 子进程调用官方无头安装（doInstall 会处理新版 xdelta 补丁并生成 OptiFine 版本）
        String javaExe = ProcessHandle.current().info().command().orElse("java");
        String runnerJar;
        try {
            runnerJar = Path.of(OptiFineInstallerRunner.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IOException("无法定位启动器自身 jar", e);
        }
        Log.info("运行 OptiFine 官方无头安装…");
        // headless 模式下官方安装器的 Swing 错误弹窗会变为异常，子进程可快速失败而非挂起
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-Djava.awt.headless=true", "-cp", runnerJar
                + System.getProperty("path.separator") + installer.toAbsolutePath(),
                "pcl.minecraft.OptiFineInstallerRunner", mcRoot.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("OptiFine 安装超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OptiFine 安装被中断", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("OptiFine 官方安装器执行失败（退出码 " + p.exitValue() + "）："
                    + output.substring(0, Math.min(400, output.length())));
        }

        // 3. 定位安装器生成的版本文件夹：先看新增目录，再按命名模式匹配（重装覆盖时目录已存在）
        String generated = findNewVersionFolder(versionsDir, before);
        if (generated == null) generated = findGeneratedOptiFineLike(mcRoot, "optifine");
        if (generated == null) throw new IOException("OptiFine 安装器未生成版本文件夹");
        try { Files.deleteIfExists(installer); } catch (IOException ignored) {}
        Log.info("OptiFine 安装完成: " + generated);
        return generated;
    }

    /** 按命名模式查找加载器生成的版本文件夹（如 1.20.6-OptiFine_HD_U_I9_pre1 / 1.20.6-forge-x）。 */
    private static String findGeneratedOptiFineLike(Path mcRoot, String kind) throws IOException {
        Path versions = mcRoot.resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        String keyword = kind.equals("forge") ? "-forge-" : "OptiFine";
        String mcHint = "";
        try (var s = Files.list(versions)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> {
                        Path json = versions.resolve(n).resolve(n + ".json");
                        return n.contains(keyword) && Files.isRegularFile(json);
                    })
                    .findFirst().orElse(null);
        }
    }

    /** 找出 dirs 中新增的文件夹名（与 before 差集的第一个）。 */
    private static String findNewVersionFolder(Path versionsDir, java.util.Set<String> before) throws IOException {
        if (!Files.isDirectory(versionsDir)) return null;
        try (var s = Files.list(versionsDir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !before.contains(n))
                    .findFirst().orElse(null);
        }
    }
}
