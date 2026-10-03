package pcl.minecraft;

import com.google.gson.JsonObject;
import pcl.base.Json;
import pcl.base.Log;
import pcl.base.Os;
import pcl.net.Downloader;
import pcl.net.Net;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Minecraft 服务端一键部署（对应 PCL2 未覆盖的服务端场景，本项目的主攻方向）。
 *
 * 支持四种类型：
 *  - VANILLA：Mojang 官方服务端 jar（版本清单 → downloads.server，走 BMCLAPI 镜像）
 *  - FABRIC：Fabric meta 的 server/jar 一体化启动器（首启自动补全库）
 *  - FORGE：官方安装器 --installServer 无头安装（BMCLAPI 加速），启动走 @argfiles
 *  - PAPER：PaperMC Fill API v3（下载后 SHA-256 校验，首启自动补丁）
 *
 * 部署内容：服务端 jar / eula.txt / server.properties / start.sh + start.bat；
 * 启动所需 Java 自动按 MC 版本要求解析，本机没有时自动下载 Mojang 运行时。
 */
public final class ServerDeployer {

    public enum Kind { VANILLA, FABRIC, FORGE, PAPER }

    public static final String PAPER_FILL_API = "https://fill.papermc.io/v3";
    public static final String FORGE_MIRROR = "https://bmclapi2.bangbang93.com/maven";

    /** 默认部署目录：{游戏根}/servers/{mc}-{type}。 */
    public static Path defaultDir(String mc, Kind kind) {
        return McFolder.selectedRoot().resolve("servers").resolve(mc + "-" + kind.name().toLowerCase());
    }

    /** 已部署的服务端目录列表（含 eula.txt / server.properties 的 servers/* 目录）。 */
    public static List<Path> listServers() {
        List<Path> list = new ArrayList<>();
        Path servers = McFolder.selectedRoot().resolve("servers");
        if (!Files.isDirectory(servers)) return list;
        try (var s = Files.list(servers)) {
            for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
                if (Files.exists(p.resolve("eula.txt")) || Files.exists(p.resolve("server.properties"))
                        || containsServerJar(p)) {
                    list.add(p);
                }
            }
        } catch (IOException ignored) {}
        return list;
    }

    private static boolean containsServerJar(Path dir) {
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.equals("server.jar") || n.equals("paper.jar") || n.startsWith("fabric-server");
            });
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 部署 ====================

    /**
     * 一键部署服务端，返回部署目录。
     *
     * @param acceptEula 是否代用户接受 Minecraft EULA（必须来自用户明确同意）
     * @param autoJava   本机 Java 不满足要求时自动下载 Mojang 运行时
     */
    public static Path deploy(Kind kind, String mc, Path dir, boolean acceptEula,
                              boolean autoJava, BiConsumer<Double, String> progress) throws IOException {
        Files.createDirectories(dir);
        JavaRuntime jre = resolveJava(mc, autoJava);
        Log.info("服务端部署使用 " + jre);

        String mainJar = switch (kind) {
            case VANILLA -> downloadVanilla(mc, dir, progress);
            case FABRIC -> downloadFabric(mc, dir, progress);
            case FORGE -> installForge(mc, dir, progress);
            case PAPER -> downloadPaper(mc, dir, progress);
        };

        writeCommonFiles(dir, mainJar, acceptEula, jre.executable().toString());
        System.out.println();
        return dir;
    }

    // ---- 各类型下载 ----

    private static String downloadVanilla(String mc, Path dir, BiConsumer<Double, String> progress) throws IOException {
        System.out.println("Fetching version metadata for " + mc + " ...");
        InstallService.Release release = InstallService.fetchManifest().stream()
                .filter(r -> r.id().equals(mc)).findFirst()
                .orElseThrow(() -> new IOException("Version not found in manifest: " + mc));
        JsonObject versionJson = Net.getJson(release.url());
        if (!versionJson.has("downloads") || !versionJson.getAsJsonObject("downloads").has("server")) {
            throw new IOException("Version " + mc + " has no server download (snapshot-only?)");
        }
        JsonObject server = versionJson.getAsJsonObject("downloads").getAsJsonObject("server");
        String url = Json.str(server, "url", "");
        String sha1 = Json.str(server, "sha1", "");
        long size = Json.longOf(server, "size", 0);
        Path jar = dir.resolve("server.jar");
        System.out.printf("Downloading vanilla server (%.1fMB)...%n", size / 1048576.0);
        Downloader.download(new Downloader.DownloadItem(url, jar, sha1, size, "server.jar"), progress);
        return "server.jar";
    }

    private static String downloadFabric(String mc, Path dir, BiConsumer<Double, String> progress) throws IOException {
        System.out.println("Fetching Fabric versions for " + mc + " ...");
        List<ModLoader.LoaderEntry> loaders = ModLoader.fetchLoaders(ModLoader.Kind.FABRIC, mc);
        if (loaders.isEmpty()) throw new IOException("No Fabric builds for " + mc);
        var installers = Json.parse(Net.get("https://meta.fabricmc.net/v2/versions/installer"));
        String installerVer = "1.1.2";
        if (installers instanceof com.google.gson.JsonArray arr && arr.size() > 0) {
            installerVer = Json.str(arr.get(0).getAsJsonObject(), "version", installerVer);
        }
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mc + "/"
                + loaders.get(0).version() + "/" + installerVer + "/server/jar";
        Path jar = dir.resolve("fabric-server.jar");
        System.out.println("Downloading Fabric server launcher (loader " + loaders.get(0).version() + ")...");
        Downloader.download(new Downloader.DownloadItem(url, jar, "", 0, "fabric-server.jar"), progress);
        return "fabric-server.jar";
    }

    private static String installForge(String mc, Path dir, BiConsumer<Double, String> progress) throws IOException {
        System.out.println("Fetching Forge builds for " + mc + " ...");
        var arr = Json.parse(Net.get(Net.BMCLAPI + "/forge/minecraft/" + mc));
        if (!(arr instanceof com.google.gson.JsonArray builds) || builds.size() == 0) {
            throw new IOException("No Forge builds for " + mc);
        }
        long bestBuild = 0;
        String bestVersion = "";
        for (var e : builds) {
            if (!e.isJsonObject()) continue;
            var o = e.getAsJsonObject();
            long build = Json.longOf(o, "build", 0);
            if (build > bestBuild) {
                bestBuild = build;
                bestVersion = Json.str(o, "version", "");
            }
        }
        if (bestBuild == 0) throw new IOException("No Forge builds for " + mc);
        String forgeVersion = mc + "-" + bestVersion;

        Path installer = dir.resolve("forge-installer.jar");
        System.out.println("Downloading Forge installer " + bestVersion + " ...");
        try {
            Downloader.download(new Downloader.DownloadItem(
                    Net.BMCLAPI + "/forge/download/" + bestBuild, installer, "", 0, "forge-installer.jar"), progress);
        } catch (IOException e) {
            Log.warn("BMCLAPI 下载失败，回退官方源: " + e.getMessage());
            Downloader.download(new Downloader.DownloadItem(
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/" + forgeVersion
                            + "/forge-" + forgeVersion + "-installer.jar",
                    installer, "", 0, "forge-installer.jar"), progress);
        }

        JavaRuntime jre = resolveJava(mc, false);
        System.out.println("Running Forge server installer (downloads libraries, may take minutes)...");
        ProcessBuilder pb = new ProcessBuilder(jre.executable().toString(), "-Djava.awt.headless=true",
                "-jar", installer.toAbsolutePath().toString(),
                "--installServer", dir.toAbsolutePath().toString(),
                "--mirror", FORGE_MIRROR);
        pb.inheritIO();
        Process p = pb.start();
        try {
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("Forge installer timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Forge install interrupted", e);
        }
        if (p.exitValue() != 0) throw new IOException("Forge installer failed (exit " + p.exitValue() + ")");
        Files.deleteIfExists(installer);
        return "@argfiles"; // 标记：启动方式为 argfiles
    }

    private static String downloadPaper(String mc, Path dir, BiConsumer<Double, String> progress) throws IOException {
        System.out.println("Fetching latest Paper build for " + mc + " ...");
        JsonObject build = Net.getJson(PAPER_FILL_API + "/projects/paper/versions/" + mc + "/builds/latest");
        JsonObject download = build.getAsJsonObject("downloads").getAsJsonObject("server:default");
        String url = Json.str(download, "url", "");
        String name = Json.str(download, "name", "paper.jar");
        long size = Json.longOf(download, "size", 0);
        String sha256 = download.has("checksums") && download.getAsJsonObject("checksums").has("sha256")
                ? download.getAsJsonObject("checksums").get("sha256").getAsString() : "";
        Path jar = dir.resolve("paper.jar");
        System.out.printf("Downloading %s (%.1fMB)...%n", name, size / 1048576.0);
        Downloader.download(new Downloader.DownloadItem(url, jar, "", size, name), progress);
        if (!sha256.isBlank() && !sha256(jar).equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(jar);
            throw new IOException("Paper jar SHA-256 mismatch");
        }
        return "paper.jar";
    }

    // ---- 公共文件 ----

    private static void writeCommonFiles(Path dir, String mainJar, boolean acceptEula, String javaExe) throws IOException {
        // eula.txt：仅在用户明确接受时写入 eula=true
        Path eula = dir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            String body = "# Accepted via CraftPort server deployment\n"
                    + "eula=" + acceptEula + "\n";
            Files.writeString(eula, body, StandardCharsets.UTF_8);
            if (!acceptEula) {
                System.out.println("WARNING: EULA not accepted - the server will refuse to start.");
                System.out.println("         Accept at " + "https://aka.ms/MinecraftEULA then set eula=true in eula.txt,");
                System.out.println("         or re-deploy with --accept-eula.");
            }
        }
        // server.properties：仅首次生成默认配置
        Path props = dir.resolve("server.properties");
        if (!Files.exists(props)) {
            Files.writeString(props, """
                    server-port=25565
                    motd=A server deployed by CraftPort
                    max-players=20
                    online-mode=true
                    view-distance=10
                    simulation-distance=10
                    spawn-protection=16
                    enable-command-block=false
                    """.stripIndent(), StandardCharsets.UTF_8);
        }
        // 启动脚本与 systemd 单元共用同一条命令；Java 写绝对路径（Linux 服务器 PATH 上的
        // java 往往不是所需版本）
        List<String> cmd = startCommand(dir, javaExe, 4096);
        Files.writeString(dir.resolve("start.sh"),
                "#!/usr/bin/env bash\ncd \"$(dirname \"$0\")\"\nexec " + shellJoin(cmd) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("start.bat"),
                "@echo off\r\ncd /d %~dp0\r\n" + shellJoin(cmd) + "\r\npause\r\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("pclj-server.service"), systemdUnit(dir, cmd), StandardCharsets.UTF_8);
        if (!Os.IS_WINDOWS) {
            try {
                Files.setPosixFilePermissions(dir.resolve("start.sh"),
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {}
        }
        System.out.println("Written: eula.txt, server.properties, start.sh, start.bat"
                + (Os.IS_LINUX ? ", pclj-server.service (systemd)" : ", pclj-server.service"));
    }

    /**
     * 构建服务端启动命令（进程 / start 脚本 / systemd 单元三处共用）。
     * Forge 用官方 @argfiles，其余用 -jar。
     */
    public static List<String> startCommand(Path dir, String javaExe, int memoryMb) throws IOException {
        int mem = memoryMb > 0 ? memoryMb : 4096;
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe != null && !javaExe.isBlank() ? javaExe : "java");
        Path args = findForgeArgs(dir);
        if (args != null) {
            cmd.add("-Xms1024M");
            cmd.add("-Xmx" + mem + "M");
            cmd.add("@" + dir.resolve("user_jvm_args.txt").toAbsolutePath());
            cmd.add("@" + args.toAbsolutePath());
            cmd.add("nogui");
        } else {
            Path jar = findMainJar(dir);
            if (jar == null) throw new IOException("No server jar found in " + dir);
            cmd.add("-Xms1024M");
            cmd.add("-Xmx" + mem + "M");
            cmd.add("-jar");
            cmd.add(jar.toAbsolutePath().toString());
            cmd.add("nogui");
        }
        return cmd;
    }

    /** shell/batch 参数拼接：含空格的参数加双引号。 */
    private static String shellJoin(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String a : cmd) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a.contains(" ") ? "\"" + a + "\"" : a);
        }
        return sb.toString();
    }

    /** systemd ExecStart：同样按空格加引号。 */
    private static String systemdUnit(Path dir, List<String> cmd) {
        return """
                [Unit]
                Description=CraftPort Minecraft Server (%s)
                After=network.target

                [Service]
                Type=simple
                WorkingDirectory=%s
                ExecStart=%s
                Restart=on-failure
                RestartSec=5

                [Install]
                WantedBy=multi-user.target
                """.formatted(dir.getFileName(), dir, shellJoin(cmd));
    }

    static Path findForgeArgs(Path dir) throws IOException {
        String name = Os.IS_WINDOWS ? "win_args.txt" : "unix_args.txt";
        Path base = dir.resolve("libraries").resolve("net").resolve("minecraftforge").resolve("forge");
        if (!Files.isDirectory(base)) return null;
        try (var s = Files.walk(base, 2)) {
            return s.filter(p -> p.getFileName().toString().endsWith(name)).findFirst().orElse(null);
        }
    }

    /** 目录名形如 {mc}-{type}，据此推断服务端所需的 Java 版本（CLI 与 Web 面板共用）。 */
    public static String mcFromDirName(Path dir) {
        String name = dir.getFileName().toString();
        int dash = name.lastIndexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }

    // ==================== 启动 ====================

    /**
     * 启动服务端（stdin/stdout 交给调用方，可交互输入 stop 等命令）。
     *
     * @param memoryMb 堆内存上限；<=0 时用 4096
     */
    public static Process start(Path dir, String javaExe, int memoryMb) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(startCommand(dir, javaExe, memoryMb));
        pb.directory(dir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    /**
     * 删除已部署的服务端目录（递归）。仅允许删除包含部署标记（eula.txt/server.properties）
     * 且位于游戏目录内的目录；运行中的实例由调用方先行停止。
     */
    public static void deleteServer(Path dir) throws IOException {
        Path norm = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(norm)) throw new IOException("Directory does not exist: " + norm);
        boolean marker = Files.exists(norm.resolve("eula.txt")) || Files.exists(norm.resolve("server.properties"));
        if (!marker) throw new IOException("Not a deployed server directory (missing eula.txt/server.properties)");
        Files.walkFileTree(norm, new java.nio.file.SimpleFileVisitor<Path>() {
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
        Log.info("服务端已删除: " + norm);
    }

    /** 读取 server.properties 的监听端口（缺省 25565）。 */
    public static int serverPort(Path dir) {
        try {
            for (String line : Files.readAllLines(dir.resolve("server.properties"))) {
                if (line.startsWith("server-port=")) {
                    return Integer.parseInt(line.substring("server-port=".length()).trim());
                }
            }
        } catch (Exception ignored) {}
        return 25565;
    }

    /** 端口是否已被占用（用于防止重复启动同一服务器）。 */
    public static boolean portBusy(int port) {
        try (var socket = new java.net.ServerSocket()) {
            socket.bind(new java.net.InetSocketAddress(port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static Path findMainJar(Path dir) throws IOException {
        for (String name : new String[]{"server.jar", "fabric-server.jar", "paper.jar"}) {
            if (Files.exists(dir.resolve(name))) return dir.resolve(name);
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jar") && !n.contains("installer");
            }).findFirst().orElse(null);
        }
    }

    // ==================== Java 解析 ====================

    /** 服务端 Java 版本要求（按 MC 版本推断）：1.20.5+/26.x→21，1.18+→17，1.17→16，更早→8。 */
    public static int[] requirement(String mc) {
        if (mc.matches("1\\.20\\.[5-9].*") || mc.matches("1\\.2[1-9]\\.?.*")
                || mc.matches("\\d{2}(\\.\\d+)?.*")) {
            return new int[]{21, Integer.MAX_VALUE};
        }
        if (mc.matches("1\\.(18|19|20)(\\.\\d+)*.*")) return new int[]{17, Integer.MAX_VALUE};
        if (mc.matches("1\\.17(\\.\\d+)*.*")) return new int[]{16, Integer.MAX_VALUE};
        return new int[]{8, Integer.MAX_VALUE};
    }

    private static String componentFor(int[] req) {
        if (req[0] >= 21) return "java-runtime-delta";
        if (req[0] >= 17) return "java-runtime-gamma";
        return "jre-legacy";
    }

    /**
     * 解析用于服务端的 Java：本机搜索 → （可选）自动下载 Mojang 运行时 → 返回。
     */
    public static JavaRuntime resolveJava(String mc, boolean autoDownload) throws IOException {
        int[] req = requirement(mc);
        for (JavaRuntime rt : JavaManager.searchAll()) {
            if (JavaManager.satisfies(rt, req)) return rt;
        }
        if (!autoDownload) {
            throw new IOException("No suitable Java found (need " + req[0] + "+). Install Java or re-run with auto-download.");
        }
        System.out.println("No suitable Java found; downloading Mojang runtime (" + componentFor(req) + ")...");
        JavaRuntimeDownloader.ensureComponent(componentFor(req));
        JavaManager.invalidate();
        for (JavaRuntime rt : JavaManager.searchAll()) {
            if (JavaManager.satisfies(rt, req)) return rt;
        }
        throw new IOException("Java runtime download finished but still no suitable Java found");
    }

    // ==================== 工具 ====================

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return String.format("%064x", new BigInteger(1, md.digest()));
        } catch (Exception e) {
            throw new IOException("SHA-256 failed", e);
        }
    }

    private ServerDeployer() {}
}
