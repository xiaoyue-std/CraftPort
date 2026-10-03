package pcl.cli;

import pcl.base.Config;
import pcl.base.Log;
import pcl.base.Os;
import pcl.base.Task;
import pcl.minecraft.ArgsBuilder;
import pcl.minecraft.CurseForge;
import pcl.minecraft.InstallService;
import pcl.minecraft.JavaManager;
import pcl.minecraft.JavaRuntime;
import pcl.minecraft.LaunchPipeline;
import pcl.minecraft.LoginService;
import pcl.minecraft.McFolder;
import pcl.minecraft.McVersion;
import pcl.minecraft.ModpackInstaller;
import pcl.minecraft.Modrinth;
import pcl.minecraft.ModLoader;
import pcl.minecraft.ServerDeployer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * CraftPort — command line interface (English only).
 *
 * Usage: java -jar CraftPort-cli.jar [command] [args]
 * Run without arguments for the interactive menu.
 *
 * Reuses the exact core pipeline of the GUI edition (installation, loaders,
 * CurseForge, Java management, launch); only the presentation layer differs.
 */
public final class CliMain {

    private static final Scanner IN = new Scanner(System.in);

    // ANSI 颜色（Linux 终端；NO_COLOR 环境变量或非交互时禁用）
    private static final boolean COLOR = Os.IS_LINUX
            && System.console() != null && System.getenv("NO_COLOR") == null;

    private static String paint(String text, String code) {
        return COLOR ? "\033[" + code + "m" + text + "\033[0m" : text;
    }
    private static String green(String s) { return paint(s, "1;32"); }
    private static String red(String s) { return paint(s, "1;31"); }
    private static String yellow(String s) { return paint(s, "33"); }
    private static String cyan(String s) { return paint(s, "36"); }

    private CliMain() {}

    public static void main(String[] args) {
        // CLI mode: core (Chinese) logs go to file only; this class prints English output
        Log.setConsoleEnabled(false);
        Log.initFile(Os.dataDir());
        int code;
        try {
            code = args.length == 0 ? interactive() : dispatch(args);
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e);
            code = 1;
        }
        System.exit(code);
    }

    // ==================== Command dispatch ====================

    private static int dispatch(String[] args) {
        String cmd = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        try {
            switch (cmd) {
                case "help", "--help", "-h" -> { printHelp(); return 0; }
                case "versions" -> { cmdVersions(); return 0; }
                case "releases" -> { cmdReleases(rest); return 0; }
                case "install" -> { cmdInstall(rest); return 0; }
                case "fabric" -> { cmdFabric(rest); return 0; }
                case "forge" -> { cmdForge(rest); return 0; }
                case "mods" -> { return cmdMods(rest); }
                case "modpack" -> { return cmdModpack(rest); }
                case "launch" -> { return cmdLaunch(rest); }
                case "server" -> { return cmdServer(rest); }
                case "login" -> { cmdLogin(rest); return 0; }
                case "dir" -> { cmdDir(rest); return 0; }
                case "runtimes" -> { cmdRuntimes(); return 0; }
                case "status" -> { cmdStatus(); return 0; }
                default -> {
                    System.out.println("Unknown command: " + cmd + " (try 'help')");
                    return 1;
                }
            }
        } catch (Exception e) {
            System.out.println(red("Error: " + e.getMessage()));
            if (e.getMessage() == null) e.printStackTrace();
            if (e.getMessage() != null) linuxJavaHint(e.getMessage());
            return 1;
        }
    }

    private static void printHelp() {
        System.out.println("""
                CraftPort (CLI)

                Usage: java -jar CraftPort-cli.jar <command> [args]
                       java -jar CraftPort.jar cli <command> [args]

                Commands:
                  versions                      List installed versions in the game directory
                  releases [count]              List available vanilla versions (default 20)
                  install <mcVersion>           Install a vanilla version
                  fabric <mcVersion> [loader]   Install Fabric ("latest" by default)
                  forge <mcVersion> [version]   Install Forge 1.13+ ("latest" by default)
                  mods search <query> [--mc v]  Search content (CurseForge; --source modrinth)
                                                --type mod|resourcepack|shader|datapack
                  mods install <query> [--mc v] Install into mods/ resourcepacks/
                                                shaderpacks/ datapacks/ (--dir overrides)
                                                [--source modrinth] [--loader fabric|forge]
                                                [--pick N] [--file N]
                  modpack search <query> [--mc v]      Search CurseForge modpacks
                  modpack install <query> [--mc v]     Install a modpack (client version +
                                client-pack zip; or --server --dir <dir> for servers)
                                [--pick N] [--file N]
                  launch <version> [--username u] [--server host[:port]]
                                [--no-isolation] [--no-wait]
                                                Launch a version (offline login)
                  server deploy <mcVersion> [--type vanilla|fabric|forge|paper]
                                [--dir path] [--accept-eula] [--no-auto-java]
                                                One-click server deployment (jar + eula +
                                                server.properties + start scripts)
                  server start [dir] [--memory MB] [--java path]
                                                Start the server (interactive console;
                                                default dir = the only deployed server)
                  server web [--port 8765] [--host 127.0.0.1]
                                                Start the web management panel (localhost)
                  server delete --dir <dir>      Delete a deployed server (cannot be undone)
                  server list                   List deployed servers
                  server service install [--dir path]
                                                Install the systemd unit (Linux only)
                  login <username>              Save offline login credentials
                  dir [path]                    Show or set the game (.minecraft) directory
                  runtimes                      List detected Java runtimes
                  status                        Show current configuration

                Run without arguments for an interactive menu.""".stripIndent());
    }

    // ==================== Commands ====================

    private static void cmdVersions() {
        List<McVersion> versions = McFolder.scanVersions(McFolder.selectedRoot());
        if (versions.isEmpty()) {
            System.out.println("No versions installed in " + McFolder.selectedRoot());
            System.out.println("Tip: run 'install <mcVersion>' or choose a different 'dir'.");
            return;
        }
        System.out.println("Game directory: " + McFolder.selectedRoot());
        for (McVersion v : versions) {
            String tag = v.isOld() ? "old" : v.isSnapshot() ? "snapshot" : "release";
            System.out.printf("  %-30s [%s]%n", v.name(), tag);
        }
        System.out.println(versions.size() + " version(s).");
    }

    private static void cmdReleases(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        System.out.println("Fetching version manifest...");
        List<InstallService.Release> manifest = InstallService.fetchManifest();
        System.out.println("Latest releases (" + count + " of " + manifest.size() + " total):");
        int shown = 0;
        for (InstallService.Release r : manifest) {
            if (!r.isRelease()) continue;
            System.out.printf("  %-16s %s%n", r.id(), r.releaseTime().substring(0, 10));
            if (++shown >= count) break;
        }
    }

    private static void cmdInstall(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: install <mcVersion>");
        Path root = McFolder.selectedRoot();
        System.out.println("Installing " + args[0] + " into " + root + " ...");
        McFolder.ensureProfile(root);
        InstallService.install(args[0], root);
        System.out.println("Installed " + args[0] + ". Libraries and assets are completed automatically on first launch.");
    }

    private static void cmdFabric(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: fabric <mcVersion> [loaderVersion|latest]");
        String loader = args.length > 1 ? args[1] : "latest";
        String name = installLoader(ModLoader.Kind.FABRIC, args[0], loader);
        System.out.println("Installed " + name + " (deps are completed automatically on first launch).");
    }

    private static void cmdForge(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: forge <mcVersion> [forgeVersion|latest]");
        String loader = args.length > 1 ? args[1] : "latest";
        String name = installLoader(ModLoader.Kind.FORGE, args[0], loader);
        System.out.println("Installed " + name + ".");
    }

    private static String installLoader(ModLoader.Kind kind, String mc, String loader) throws Exception {
        Path root = McFolder.selectedRoot();
        McFolder.ensureProfile(root);
        System.out.println("Fetching " + kind + " versions for " + mc + " ...");
        List<ModLoader.LoaderEntry> loaders = ModLoader.fetchLoaders(kind, mc);
        if (loaders.isEmpty()) throw new IllegalStateException("No " + kind + " builds available for " + mc);
        String entry = loaders.get(0).version();
        if (!"latest".equalsIgnoreCase(loader)) {
            entry = loaders.stream().map(ModLoader.LoaderEntry::version)
                    .filter(v -> v.startsWith(loader)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No " + kind + " build starts with " + loader));
        }
        System.out.println("Installing " + kind + " " + entry.split("@")[0] + " on " + mc + " ...");
        return ModLoader.install(kind, mc, entry, root);
    }

    private static int cmdMods(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException(
                "Usage: mods search|install <query> [--mc v] [--source curseforge|modrinth] "
                        + "[--type mod|resourcepack|shader|datapack] [--loader fabric|forge] "
                        + "[--pick N] [--file N] [--dir targetDir]");
        String action = args[0];
        String query = args[1];
        Map<String, String> flags = parseFlags(args, 2);
        String source = flags.getOrDefault("source", "curseforge").toLowerCase();
        String type = flags.getOrDefault("type", "mod").toLowerCase();
        String mc = flags.get("mc");
        String loader = "mod".equals(type) ? flags.get("loader") : null;
        int pick = Integer.parseInt(flags.getOrDefault("pick", "0"));
        int fileIdx = Integer.parseInt(flags.getOrDefault("file", "0"));

        if (!java.util.Set.of("mod", "resourcepack", "shader", "datapack").contains(type)) {
            throw new IllegalArgumentException("Unknown --type: " + type
                    + " (mod | resourcepack | shader | datapack; for modpacks use the 'modpack' command)");
        }
        if ("datapack".equals(type) && source.equals("curseforge")) {
            throw new IllegalArgumentException("CurseForge has no datapack section; use --source modrinth");
        }

        System.out.println("Searching " + source + " (" + type + ") for \"" + query + "\" ...");

        if (source.equals("modrinth")) {
            List<Modrinth.ModInfo> mods = Modrinth.search(query, type);
            if (mods.isEmpty()) { System.out.println("No results found."); return 1; }
            for (int i = 0; i < Math.min(10, mods.size()); i++) System.out.println("  [" + i + "] " + mods.get(i));
            Modrinth.ModInfo mod = mods.get(Math.min(pick, mods.size() - 1));
            System.out.println("Fetching files for " + mod.title() + " ...");
            List<Modrinth.FileInfo> files = Modrinth.files(mod.projectId(), mc, loader);
            if (files.isEmpty()) {
                System.out.println("No compatible files found" + (mc == null ? "." : " for " + mc + "."));
                return "search".equals(action) ? 0 : 1;
            }
            for (int i = 0; i < Math.min(10, files.size()); i++) System.out.println("  [" + i + "] " + files.get(i));
            if ("search".equals(action)) return 0;
            Modrinth.FileInfo file = files.get(Math.min(fileIdx, files.size() - 1));
            Path destDir = resolveContentDir(flags, mc, type);
            System.out.println("Downloading " + file.name() + " ...");
            Modrinth.download(file, destDir.resolve(file.name()));
            System.out.println(green("Installed: " + destDir.resolve(file.name())));
            return 0;
        }

        // CurseForge 源
        List<CurseForge.ModInfo> mods = CurseForge.search(query, cfCategory(type));
        if (mods.isEmpty()) { System.out.println("No results found."); return 1; }
        for (int i = 0; i < Math.min(10, mods.size()); i++) System.out.println("  [" + i + "] " + mods.get(i));
        if ("search".equals(action)) {
            System.out.println("(install with: mods install \"" + query + "\" --pick N)");
            return 0;
        }
        if (pick >= Math.min(10, mods.size())) throw new IllegalArgumentException("Pick index out of range: " + pick);
        CurseForge.ModInfo mod = mods.get(pick);

        System.out.println("Fetching files for " + mod.name() + (mc == null ? "" : " (compatible with " + mc + ")") + " ...");
        List<CurseForge.FileInfo> files = CurseForge.files(mod.id(), mc);
        if (files.isEmpty()) {
            System.out.println("No compatible files found" + (mc == null ? "." : " for " + mc + "."));
            return 1;
        }
        for (int i = 0; i < Math.min(10, files.size()); i++) System.out.println("  [" + i + "] " + files.get(i));
        if (fileIdx >= Math.min(10, files.size())) throw new IllegalArgumentException("File index out of range: " + fileIdx);
        CurseForge.FileInfo file = files.get(fileIdx);

        Path destDir = resolveContentDir(flags, mc, type);
        Files.createDirectories(destDir);
        Path dest = destDir.resolve(file.name());
        System.out.println("Downloading " + file.displayName() + " ...");
        DownloaderProgress p = new DownloaderProgress();
        CurseForge.download(file, dest, p::on);
        p.finish();
        System.out.println(green("Installed: " + dest));
        return 0;
    }

    /** CF --type → 分类。 */
    private static CurseForge.Category cfCategory(String type) {
        return switch (type) {
            case "resourcepack" -> CurseForge.Category.RESOURCE_PACK;
            case "shader" -> CurseForge.Category.SHADER;
            default -> CurseForge.Category.MOD;
        };
    }

    /**
     * 内容目标目录（对应 PCL2 的分类落位）：
     * mod → mods/，resourcepack → resourcepacks/，shader → shaderpacks/，datapack → datapacks/。
     * --dir 显式指定时优先；有 --mc 则放版本隔离目录，否则放游戏根。
     */
    private static Path resolveContentDir(Map<String, String> flags, String mc, String type) throws java.io.IOException {
        if (flags.containsKey("dir")) {
            Path d = Path.of(flags.get("dir"));
            Files.createDirectories(d);
            return d;
        }
        String sub = switch (type) {
            case "resourcepack" -> "resourcepacks";
            case "shader" -> "shaderpacks";
            case "datapack" -> "datapacks";
            default -> "mods";
        };
        Path base = (mc != null && !mc.isBlank())
                ? McFolder.selectedRoot().resolve("versions").resolve(mc)
                : McFolder.selectedRoot();
        Path d = base.resolve(sub);
        if (mc == null || mc.isBlank()) {
            System.out.println("Note: no --mc given; installing into " + d);
        }
        Files.createDirectories(d);
        return d;
    }

    private static int cmdModpack(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException(
                "Usage: modpack search|install|deploy <query> [--mc v] [--pick N] [--file N] "
                        + "[--server --dir serverDir]  |  deploy: [--accept-eula] [--dir d] [--start]");
        String action = args[0];
        String query = args[1];
        Map<String, String> flags = parseFlags(args, 2);
        String mc = flags.get("mc");
        int pick = Integer.parseInt(flags.getOrDefault("pick", "0"));
        int fileIdx = Integer.parseInt(flags.getOrDefault("file", "0"));
        boolean server = flags.containsKey("server");

        System.out.println("Searching CurseForge modpacks for \"" + query + "\" ...");
        List<CurseForge.ModInfo> packs = CurseForge.searchModpacks(query);
        if (packs.isEmpty()) { System.out.println("No modpacks found."); return 1; }
        for (int i = 0; i < Math.min(10, packs.size()); i++) System.out.println("  [" + i + "] " + packs.get(i));
        if ("search".equals(action)) return 0;
        if (pick >= Math.min(10, packs.size())) throw new IllegalArgumentException("Pick index out of range: " + pick);
        CurseForge.ModInfo pack = packs.get(pick);

        System.out.println("Fetching modpack files for " + pack.name() + (mc == null ? "" : " (compatible with " + mc + ")") + " ...");
        List<CurseForge.FileInfo> files = CurseForge.files(pack.id(), mc);
        if (files.isEmpty()) {
            System.out.println("No compatible modpack files found" + (mc == null ? "." : " for " + mc + "."));
            return 1;
        }
        for (int i = 0; i < Math.min(10, files.size()); i++) System.out.println("  [" + i + "] " + files.get(i));
        if (fileIdx >= Math.min(10, files.size())) throw new IllegalArgumentException("File index out of range: " + fileIdx);
        CurseForge.FileInfo packFile = files.get(fileIdx);

        long t0 = System.currentTimeMillis();
        ModpackInstaller.InstallResult result;
        if ("deploy".equals(action)) {
            // 一键服务端部署：从整合包自动推断版本与加载器，部署 + 装入全部内容
            boolean acceptEula = flags.containsKey("accept-eula");
            if (!acceptEula) {
                System.out.println(yellow("WARNING: EULA not accepted - add --accept-eula or the server will refuse to start."));
            }
            Path serverDir = flags.containsKey("dir") ? Path.of(flags.get("dir")) : null;
            result = ModpackInstaller.deployServer(pack, packFile, McFolder.selectedRoot(),
                    serverDir, acceptEula, (done, total) -> {
                        if (done % 5 == 0 || done == total) progress(total == 0 ? 0 : (double) done / total);
                    });
            System.out.println(green("Server deployed at: " + result.targetDir()));
            System.out.println("Start it with: server start --dir \"" + result.targetDir() + "\"");
            if (flags.containsKey("start")) {
                System.out.println("Starting server now (interactive console, 'stop' to exit)...");
                String javaExe = ServerDeployer.resolveJava(result.mc(), true).executable().toString();
                Process p = ServerDeployer.start(result.targetDir(), javaExe, 4096);
                int code = p.waitFor();
                System.out.println("Server exited with code " + code + ".");
            }
        } else {
            Path serverDir = server ? Path.of(flags.getOrDefault("dir", "")) : null;
            if (server && (serverDir == null || serverDir.toString().isBlank())) {
                throw new IllegalArgumentException("Server mode needs --dir <server directory>");
            }
            System.out.println("Installing modpack \"" + pack.name() + "\" (" + packFile.displayName() + ") "
                    + (server ? "into server " + serverDir : "as a client version") + " ...");
            result = ModpackInstaller.install(pack, packFile,
                    McFolder.selectedRoot(), server, serverDir,
                    (done, total) -> {
                        if (done % 5 == 0 || done == total) progress(total == 0 ? 0 : (double) done / total);
                    });
            System.out.println(green("Modpack installed: " + result.packName()
                    + " (" + result.mc() + ", " + result.loader() + ", " + result.modsInstalled() + " mods)"));
            System.out.println("Mods directory: " + result.targetDir());
        }
        if (result.clientPack() != null) {
            System.out.println(green("Client pack (give this to players): " + result.clientPack()));
        }
        System.out.println("Elapsed: " + (System.currentTimeMillis() - t0) / 1000 + "s");
        return 0;
    }

    private static int cmdLaunch(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: launch <version> [--username u] [--server host[:port]] [--no-isolation] [--no-wait]");
        Map<String, String> flags = parseFlags(args, 1);
        String versionName = args[0];

        McVersion version = McVersion.load(
                McFolder.selectedRoot().resolve("versions").resolve(versionName), versionName);
        if (version == null) throw new IllegalArgumentException("Version not found or json corrupted: " + versionName);

        String username = flags.getOrDefault("username", Config.cacheUsername);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("No username. Run 'login <name>' first or pass --username <name>.");
        }
        LoginService.LoginResult login = LoginService.offlineLogin(username);

        boolean isolation = flags.containsKey("no-isolation")
                ? false
                : Config.getInt(Config.LAUNCH_ARGUMENT_INDIE, 4) > 0;
        Path mcRoot = McFolder.selectedRoot();
        Path gameDir = version.gameDirectory(mcRoot, isolation);

        ArgsBuilder.LaunchOptions options = new ArgsBuilder.LaunchOptions(
                version, mcRoot, null, login, gameDir, version.nativesDir(),
                Config.get(Config.LAUNCH_ADVANCE_GAME, ""),
                Config.getInt(Config.LAUNCH_ARGUMENT_WIDTH, 854),
                Config.getInt(Config.LAUNCH_ARGUMENT_HEIGHT, 480),
                false,
                flags.get("server"));

        System.out.println("Launching " + versionName + " as " + username
                + (isolation ? " (version isolation)" : "") + " ...");
        GameLogForwarder.forward();
        long t0 = System.currentTimeMillis();
        LaunchPipeline.LaunchResult result = LaunchPipeline.launch(options, (p, stage) -> progress(p));
        System.out.println();
        System.out.printf("Game started in %d ms (PID %d).%n",
                System.currentTimeMillis() - t0, result.process().pid());

        if (flags.containsKey("no-wait")) {
            System.out.println("CLI exits now; the game keeps running.");
            return 0;
        }
        System.out.println("Streaming game output until it exits (Ctrl+C to detach)...");
        int code = result.process().waitFor();
        System.out.println("Game exited with code " + code + ".");
        return code == 0 ? 0 : 1;
    }

    private static void cmdLogin(String[] args) {
        if (args.length < 1) throw new IllegalArgumentException("Usage: login <username>");
        LoginService.LoginResult r = LoginService.offlineLogin(args[0]);
        Config.cacheUsername = r.username();
        Config.cacheUuid = r.uuid();
        Config.cacheAccessToken = r.accessToken();
        Config.save();
        System.out.println("Logged in as " + r.username() + " (UUID " + r.uuid() + ").");
    }

    private static void cmdDir(String[] args) {
        if (args.length > 0) {
            Path p = Path.of(args[0]);
            if (!Files.isDirectory(p)) throw new IllegalArgumentException("Directory does not exist: " + p);
            McFolder.setRoot(p);
            System.out.println("Game directory set to " + p);
        }
        System.out.println("Game directory: " + McFolder.selectedRoot());
        System.out.println("Available: " + McFolder.availableRoots());
    }

    private static void cmdRuntimes() {
        System.out.println("Detecting Java runtimes...");
        List<JavaRuntime> runtimes = JavaManager.searchAll();
        if (runtimes.isEmpty()) {
            System.out.println("No Java runtimes found (JDK 21 will be downloaded automatically when needed).");
            return;
        }
        for (JavaRuntime rt : runtimes) System.out.println("  " + rt);
    }

    private static void cmdStatus() {
        System.out.println("Game directory : " + McFolder.selectedRoot());
        System.out.println("Username       : " + (Config.cacheUsername.isBlank() ? "(not logged in)" : Config.cacheUsername));
        System.out.println("Versions       : " + McFolder.scanVersions(McFolder.selectedRoot()).size());
        System.out.println("Download source: " + Config.getInt(Config.TOOL_DOWNLOAD_SOURCE, 1)
                + " (0=mirror, 1=auto, 2=official)");
        System.out.println("Data directory : " + Os.dataDir());
    }

    // ==================== Server deployment ====================

    private static int cmdServer(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: server deploy|start|list ...");
        // deploy 有一个位置参数（mcVersion）需要跳过，start/list 没有
        Map<String, String> flags = args.length > 1 ? parseFlags(args, "deploy".equals(args[0]) ? 2 : 1) : Map.of();
        switch (args[0]) {
            case "web" -> {
                int port = Integer.parseInt(flags.getOrDefault("port", "8765"));
                String host = flags.getOrDefault("host", "127.0.0.1");
                WebPanel.start(port, host);
                // 阻塞主线程保活（否则末尾 System.exit 会立刻杀掉面板）
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
            case "delete" -> {
                Path dir = resolveServerDir(flags.get("dir"));
                Path serversRoot = McFolder.selectedRoot().resolve("servers").normalize();
                if (!dir.toAbsolutePath().normalize().startsWith(serversRoot) || dir.equals(serversRoot)) {
                    throw new IllegalArgumentException("Only servers under " + serversRoot + " can be deleted");
                }
                if (ServerDeployer.portBusy(ServerDeployer.serverPort(dir))) {
                    throw new IllegalArgumentException("Port in use - stop the server first");
                }
                ServerDeployer.deleteServer(dir);
                System.out.println("Deleted: " + dir);
            }
            case "list" -> {
                List<Path> servers = ServerDeployer.listServers();
                if (servers.isEmpty()) {
                    System.out.println("No deployed servers. Deploy one with: server deploy <mcVersion>");
                    return 0;
                }
                System.out.println("Deployed servers:");
                for (Path p : servers) System.out.println("  " + p);
            }
            case "deploy" -> {
                if (args.length < 2) throw new IllegalArgumentException("Usage: server deploy <mcVersion> [--type vanilla|fabric|forge|paper] [--dir path] [--accept-eula]");
                String mc = args[1];
                ServerDeployer.Kind kind = ServerDeployer.Kind.valueOf(
                        flags.getOrDefault("type", "vanilla").toUpperCase());
                Path dir = flags.containsKey("dir")
                        ? Path.of(flags.get("dir"))
                        : ServerDeployer.defaultDir(mc, kind);
                boolean acceptEula = flags.containsKey("accept-eula");
                warnIfRoot();
                System.out.println("Deploying " + kind.name().toLowerCase() + " server " + mc + " into " + dir + " ...");
                ServerDeployer.deploy(kind, mc, dir, acceptEula, !flags.containsKey("no-auto-java"),
                        (p, url) -> progress(p));
                System.out.println(green("Deployed at: " + dir));
                System.out.println("Start it with: server start --dir \"" + dir + "\"");
                if (Os.IS_LINUX) {
                    System.out.println("Run as a service with: server service install --dir \"" + dir + "\"");
                }
                if (!acceptEula) {
                    System.out.println(yellow("Note: EULA not accepted yet - edit eula.txt or re-deploy with --accept-eula."));
                }
            }
            case "service" -> {
                return cmdServerService(args, flags);
            }
            case "start" -> {
                Path dir = resolveServerDir(flags.get("dir"));
                // 端口预检：防止重复启动
                int port = ServerDeployer.serverPort(dir);
                if (ServerDeployer.portBusy(port)) {
                    System.out.println(red("Port " + port + " is already in use - the server may already be running."));
                    System.out.println("Check with: server list, or stop the other instance first.");
                    return 1;
                }
                warnIfRoot();
                String mc = ServerDeployer.mcFromDirName(dir);
                String javaPath = flags.containsKey("java")
                        ? flags.get("java")
                        : ServerDeployer.resolveJava(mc, true).executable().toString();
                int memory = Integer.parseInt(flags.getOrDefault("memory", "4096"));
                System.out.println("Starting server in " + dir + " (Java " + javaPath + ", -Xmx" + memory + "M)...");
                Process p = ServerDeployer.start(dir, javaPath, memory);
                System.out.println(green("Server started (PID " + p.pid() + ").") + " Type console commands (e.g. 'stop').");
                int code = p.waitFor();
                System.out.println("Server exited with code " + code + ".");
                return code == 0 ? 0 : 1;
            }
            default -> throw new IllegalArgumentException("Unknown server subcommand: " + args[0]);
        }
        return 0;
    }

    /** 未指定 --dir 时的服务端目录解析：唯一部署目录直接用，多个/零个则报错并列出。 */
    private static Path resolveServerDir(String dirFlag) {
        if (dirFlag != null && !dirFlag.isBlank()) return Path.of(dirFlag);
        List<Path> servers = ServerDeployer.listServers();
        if (servers.size() == 1) return servers.get(0);
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("No deployed servers. Run: server deploy <mcVersion>");
        }
        StringBuilder sb = new StringBuilder("Multiple servers deployed; specify one with --dir:");
        for (Path p : servers) sb.append("\n  ").append(p);
        throw new IllegalArgumentException(sb.toString());
    }

    /** root 运行警告（Linux 服务端不应使用 root 账户）。 */
    private static void warnIfRoot() {
        if (Os.IS_LINUX && "root".equals(System.getProperty("user.name"))) {
            System.out.println(yellow("WARNING: running as root. Consider a dedicated user,"));
            System.out.println(yellow("         e.g. `sudo useradd -r -m minecraft` and User=minecraft in the service unit."));
        }
    }

    /** Linux 上缺 Java 时的发行版安装提示。 */
    private static void linuxJavaHint(String message) {
        if (!Os.IS_LINUX || !message.contains("Java")) return;
        System.out.println(yellow("Hint: install a suitable JDK with your package manager:"));
        System.out.println("  Debian/Ubuntu : sudo apt install openjdk-21-jre-headless");
        System.out.println("  Fedora/RHEL   : sudo dnf install java-21-openjdk-headless");
        System.out.println("  Arch          : sudo pacman -S jdk21-openjdk");
        System.out.println("  Or let CraftPort download Mojang's runtime: drop --no-auto-java");
    }

    /**
     * server service install：把部署目录里生成的 systemd 单元装入系统。
     * 非 root 时打印确切的 sudo 命令。
     */
    private static int cmdServerService(String[] args, Map<String, String> flags) throws Exception {
        if (args.length < 2 || !"install".equals(args[1])) {
            throw new IllegalArgumentException("Usage: server service install [--dir path]");
        }
        if (!Os.IS_LINUX) {
            System.out.println(yellow("systemd is only available on Linux; use start.sh / start.bat instead."));
            return 1;
        }
        Path dir = resolveServerDir(flags.get("dir"));
        Path unit = dir.resolve("pclj-server.service");
        if (!Files.exists(unit)) throw new IllegalArgumentException("Unit file not found: " + unit + " (re-deploy first)");
        String svcName = "pclj-" + dir.getFileName() + ".service";
        Path systemDir = Path.of("/etc/systemd/system");
        Path target = systemDir.resolve(svcName);

        System.out.println("Service unit: " + unit);
        if (Files.isDirectory(systemDir) && Files.isWritable(systemDir)) {
            Files.copy(unit, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println(green("Installed: " + target));
            System.out.println("Activate it with:");
            System.out.println("  sudo systemctl daemon-reload");
            System.out.println("  sudo systemctl enable --now " + svcName);
        } else {
            System.out.println("Copy and activate with:");
            System.out.println("  sudo cp \"" + unit + "\" " + target);
            System.out.println("  sudo systemctl daemon-reload");
            System.out.println("  sudo systemctl enable --now " + svcName);
        }
        System.out.println("Tips:");
        System.out.println("  - add `User=minecraft` under [Service] to run as a non-root user");
        System.out.println("  - open the firewall if needed: sudo ufw allow "
                + ServerDeployer.serverPort(dir) + "/tcp");
        return 0;
    }

    // ==================== Interactive menu ====================

    private static int interactive() {
        System.out.println("CraftPort — CLI (type a number)");
        while (true) {
            System.out.println("""

                    1) List installed versions
                    2) Install vanilla version
                    3) Install Fabric
                    4) Install Forge
                    5) Browse & install mods (CurseForge)
                    6) Launch a version
                    7) Set game directory
                    8) Show Java runtimes
                    9) Deploy a server (one-click)
                   10) Start a deployed server
                    0) Exit""".stripIndent());
            System.out.print("> ");
            String line = IN.nextLine().trim();
            try {
                switch (line) {
                    case "1" -> cmdVersions();
                    case "2" -> {
                        cmdReleases(new String[]{"10"});
                        System.out.print("Version id to install (empty to cancel): ");
                        String id = IN.nextLine().trim();
                        if (!id.isEmpty()) cmdInstall(new String[]{id});
                    }
                    case "3", "4" -> {
                        List<McVersion> installed = McFolder.scanVersions(McFolder.selectedRoot());
                        if (installed.isEmpty()) { System.out.println("Install a vanilla version first."); break; }
                        for (int i = 0; i < installed.size(); i++) System.out.printf("  [%d] %s%n", i, installed.get(i).name());
                        System.out.print("Base version index: ");
                        int idx = Integer.parseInt(IN.nextLine().trim());
                        McVersion base = installed.get(idx);
                        if ("3".equals(line)) cmdFabric(new String[]{base.name(), "latest"});
                        else cmdForge(new String[]{base.name(), "latest"});
                    }
                    case "5" -> {
                        System.out.print("Mod name to search: ");
                        String q = IN.nextLine().trim();
                        if (q.isEmpty()) break;
                        System.out.print("Minecraft version filter (empty = any): ");
                        String mc = IN.nextLine().trim();
                        List<String> a = new java.util.ArrayList<>(List.of("mods", "install", q));
                        if (!mc.isEmpty()) a.addAll(List.of("--mc", mc));
                        return cmdMods(a.toArray(new String[0]));
                    }
                    case "6" -> {
                        List<McVersion> installed = McFolder.scanVersions(McFolder.selectedRoot());
                        if (installed.isEmpty()) { System.out.println("No versions to launch."); break; }
                        for (int i = 0; i < installed.size(); i++) System.out.printf("  [%d] %s%n", i, installed.get(i).name());
                        System.out.print("Version index: ");
                        int idx = Integer.parseInt(IN.nextLine().trim());
                        System.out.print("Username [" + Config.cacheUsername + "]: ");
                        String u = IN.nextLine().trim();
                        List<String> a = new java.util.ArrayList<>(List.of("launch", installed.get(idx).name()));
                        if (!u.isEmpty()) a.addAll(List.of("--username", u));
                        return cmdLaunch(a.toArray(new String[0]));
                    }
                    case "7" -> {
                        System.out.print("Game directory [" + McFolder.selectedRoot() + "]: ");
                        String d = IN.nextLine().trim();
                        if (!d.isEmpty()) cmdDir(new String[]{d});
                        else cmdDir(new String[0]);
                    }
                    case "8" -> cmdRuntimes();
                    case "9" -> {
                        System.out.print("Minecraft version (e.g. 1.20.6): ");
                        String mc = IN.nextLine().trim();
                        if (mc.isEmpty()) break;
                        System.out.print("Type [vanilla/fabric/forge/paper, default vanilla]: ");
                        String type = IN.nextLine().trim();
                        if (type.isEmpty()) type = "vanilla";
                        System.out.print("Accept the Minecraft EULA (https://aka.ms/MinecraftEULA)? [y/N]: ");
                        boolean eula = IN.nextLine().trim().equalsIgnoreCase("y");
                        List<String> a = new java.util.ArrayList<>(List.of("deploy", mc, "--type", type));
                        if (eula) a.add("--accept-eula");
                        cmdServer(a.toArray(new String[0]));
                        System.out.print("Start the server now? [y/N]: ");
                        if (IN.nextLine().trim().equalsIgnoreCase("y")) {
                            ServerDeployer.Kind k = ServerDeployer.Kind.valueOf(type.toUpperCase());
                            cmdServer(new String[]{"start", "--dir", ServerDeployer.defaultDir(mc, k).toString()});
                        }
                    }
                    case "10" -> {
                        List<Path> servers = ServerDeployer.listServers();
                        if (servers.isEmpty()) { System.out.println("No deployed servers."); break; }
                        for (int i = 0; i < servers.size(); i++) System.out.printf("  [%d] %s%n", i, servers.get(i));
                        System.out.print("Server index: ");
                        int idx = Integer.parseInt(IN.nextLine().trim());
                        System.out.print("Memory in MB [4096]: ");
                        String mem = IN.nextLine().trim();
                        List<String> a = new java.util.ArrayList<>(List.of("start", "--dir", servers.get(idx).toString()));
                        if (!mem.isEmpty()) a.addAll(List.of("--memory", mem));
                        return cmdServer(a.toArray(new String[0]));
                    }
                    case "0" -> { System.out.println("Bye!"); return 0; }
                    default -> System.out.println("Unknown option: " + line);
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    // ==================== Helpers ====================

    private static Map<String, String> parseFlags(String[] args, int from) {
        Map<String, String> flags = new java.util.LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    flags.put(key, args[++i]);
                } else {
                    flags.put(key, "true");
                }
            }
        }
        return flags;
    }

    private static void progress(double p) {
        System.out.print("\r" + cyan(String.format("[%3d%%]", (int) (p * 100))));
        System.out.flush();
    }

    private static final class DownloaderProgress {
        private long last = 0;
        void on(double p, String url) {
            long now = System.currentTimeMillis();
            if (now - last > 300 || p >= 1) {
                last = now;
                progress(p);
            }
        }
        void finish() { System.out.println(); }
    }

    /** forwards game stdout/stderr to the terminal (game logs are English anyway). */
    private static final class GameLogForwarder {
        static void forward() {
            pcl.minecraft.GameProcess.addLogListener(line -> System.out.println("  " + line));
        }
    }
}
