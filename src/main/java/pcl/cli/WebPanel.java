package pcl.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pcl.base.Log;
import pcl.base.Os;
import pcl.minecraft.CurseForge;
import pcl.minecraft.McFolder;
import pcl.minecraft.ModpackInstaller;
import pcl.minecraft.ModsService;
import pcl.minecraft.ServerDeployer;
import pcl.minecraft.ServerManager;
import pcl.minecraft.ServerPing;
import pcl.minecraft.ServerProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 服务端 Web 管理面板（CLI 手动启动：server web）。
 * 只绑定本地回环，零依赖（JDK HttpServer + Gson）。
 * 页面为单文件 SPA（resources/pcl/web.html），API 走 JSON。
 */
public final class WebPanel {

    private static final Gson GSON = new Gson();

    /** 当前部署任务状态（同一时刻只允许一个）。 */
    private static final class DeployState {
        volatile String phase = "starting";
        volatile double percent;
        volatile boolean done;
        volatile String error;
        volatile String resultDir;
    }

    private static volatile DeployState deployState;

    private WebPanel() {}

    public static void start(int port, String host) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", WebPanel::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Web panel running at http://" + host + ":" + port + "/");
        System.out.println("Press Ctrl+C in the CLI to stop the panel (managed servers keep running).");
    }

    // ==================== 路由 ====================

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            switch (path) {
                case "/", "/index.html" -> servePage(ex);
                case "/api/servers" -> apiServers(ex);
                case "/api/versions" -> apiVersions(ex);
                case "/api/deploy" -> apiDeploy(ex);
                case "/api/deploy/status" -> apiDeployStatus(ex);
                case "/api/deploy-modpack" -> apiDeployModpack(ex);
                case "/api/start" -> apiStart(ex);
                case "/api/stop" -> apiStop(ex);
                case "/api/kill" -> apiKill(ex);
                case "/api/delete" -> apiDelete(ex);
                case "/api/logs" -> apiLogs(ex);
                case "/api/command" -> apiCommand(ex);
                case "/api/props" -> apiProps(ex);
                case "/api/mods/search" -> apiModsSearch(ex);
                case "/api/mods/files" -> apiModsFiles(ex);
                case "/api/mods/install" -> apiModsInstall(ex);
                case "/api/mods/status" -> apiModsStatus(ex);
                case "/api/mods/mcmod" -> apiMcmod(ex);
                case "/api/settings" -> apiSettings(ex);
                case "/api/host" -> apiHost(ex);
                case "/api/server-info" -> apiServerInfo(ex);
                default -> sendJson(ex, 404, Map.of("error", "Not found"));
            }
        } catch (Exception e) {
            Log.error("Web panel error: " + path, e);
            sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private static void servePage(HttpExchange ex) throws IOException {
        byte[] page;
        try (InputStream in = WebPanel.class.getResourceAsStream("/pcl/web.html")) {
            if (in == null) throw new IOException("web.html resource missing");
            page = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, page.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(page); }
    }

    // ==================== API ====================

    private static void apiServers(HttpExchange ex) throws IOException {
        List<Map<String, Object>> servers = new ArrayList<>();
        for (Path dir : ServerDeployer.listServers()) {
            ServerManager.Instance inst = ServerManager.get(dir);
            int port = ServerDeployer.serverPort(dir);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("dir", dir.toString());
            m.put("name", dir.getFileName().toString());
            m.put("port", port);
            m.put("eula", Files.exists(dir.resolve("eula.txt"))
                    && Files.readString(dir.resolve("eula.txt")).contains("eula=true"));
            m.put("running", inst != null && inst.process.isAlive());
            if (inst != null && inst.exitCode != null) m.put("exitCode", inst.exitCode);
            if (inst == null && ServerDeployer.portBusy(port)) m.put("external", true);
            servers.add(m);
        }
        sendJson(ex, 200, Map.of("servers", servers));
    }

    /** 可部署的正式版列表（全部正式版,供面板按系列分组选择）。 */
    private static void apiVersions(HttpExchange ex) throws IOException {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (pcl.minecraft.InstallService.Release r : pcl.minecraft.InstallService.fetchManifest()) {
            if (!r.isRelease()) continue;
            versions.add(Map.of("id", r.id(), "date",
                    r.releaseTime().length() >= 10 ? r.releaseTime().substring(0, 10) : ""));
            if (versions.size() >= 500) break; // 安全上限
        }
        sendJson(ex, 200, Map.of("versions", versions));
    }

    private static void apiDeploy(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        String mc = body.get("mc").getAsString();
        String type = body.get("type").getAsString().toLowerCase();
        boolean eula = body.has("eula") && body.get("eula").getAsBoolean();
        ServerDeployer.Kind kind = ServerDeployer.Kind.valueOf(type.toUpperCase());

        synchronized (WebPanel.class) {
            if (deployState != null && !deployState.done) {
                sendJson(ex, 409, Map.of("error", "A deployment is already in progress",
                        "code", "DEPLOY_IN_PROGRESS"));
                return;
            }
            deployState = new DeployState();
        }
        Path dir = ServerDeployer.defaultDir(mc, kind);
        Thread t = new Thread(() -> {
            DeployState s = deployState;
            try {
                s.phase = "installing";
                Path result = ServerDeployer.deploy(kind, mc, dir, eula, true,
                        (p, url) -> s.percent = p);
                s.percent = 1;
                s.phase = "done";
                s.resultDir = result.toString();
            } catch (Exception e) {
                s.phase = "failed";
                s.error = String.valueOf(e.getMessage());
            } finally {
                s.done = true;
            }
        }, "pclj-web-deploy");
        t.setDaemon(true);
        t.start();
        sendJson(ex, 200, Map.of("ok", true, "dir", dir.toString()));
    }

    /**
     * CurseForge 整合包一键服务端部署：{query, mc?, pick?, eula}。
     * 从整合包 manifest 自动推断 MC 版本与加载器，部署 + 装入全部内容。
     */
    private static void apiDeployModpack(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        String query = body.get("query").getAsString();
        String mc = body.has("mc") && !body.get("mc").isJsonNull() ? body.get("mc").getAsString() : null;
        int pick = body.has("pick") ? body.get("pick").getAsInt() : 0;
        boolean eula = body.has("eula") && body.get("eula").getAsBoolean();

        synchronized (WebPanel.class) {
            if (deployState != null && !deployState.done) {
                sendJson(ex, 409, Map.of("error", "A deployment is already in progress",
                        "code", "DEPLOY_IN_PROGRESS"));
                return;
            }
            deployState = new DeployState();
        }
        Thread t = new Thread(() -> {
            DeployState s = deployState;
            try {
                s.phase = "searching";
                List<CurseForge.ModInfo> packs = CurseForge.searchModpacks(query);
                if (packs.isEmpty()) throw new IOException("No modpacks found for: " + query);
                CurseForge.ModInfo pack = packs.get(Math.min(pick, packs.size() - 1));
                s.phase = "fetching-files";
                List<CurseForge.FileInfo> files = CurseForge.files(pack.id(), mc);
                if (files.isEmpty()) throw new IOException("No compatible modpack files" +
                        (mc == null ? "" : " for " + mc));
                CurseForge.FileInfo packFile = files.get(0);
                s.phase = "installing";
                ModpackInstaller.InstallResult result = ModpackInstaller.deployServer(pack, packFile,
                        McFolder.selectedRoot(), null, eula,
                        (done, total) -> s.percent = total == 0 ? 0 : (double) done / total);
                s.percent = 1;
                s.phase = "done";
                s.resultDir = result.targetDir().toString();
            } catch (Exception e) {
                s.phase = "failed";
                s.error = String.valueOf(e.getMessage());
            } finally {
                s.done = true;
            }
        }, "pclj-web-mpdeploy");
        t.setDaemon(true);
        t.start();
        sendJson(ex, 200, Map.of("ok", true));
    }

    private static void apiDeployStatus(HttpExchange ex) throws IOException {
        DeployState s = deployState;
        if (s == null) { sendJson(ex, 200, Map.of("active", false)); return; }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("active", !s.done);
        m.put("phase", s.phase);
        m.put("percent", s.percent);
        m.put("done", s.done);
        if (s.error != null) m.put("error", s.error);
        if (s.resultDir != null) m.put("resultDir", s.resultDir);
        sendJson(ex, 200, m);
    }

    private static void apiStart(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        Path dir = Path.of(body.get("dir").getAsString());
        int memory = body.has("memory") ? body.get("memory").getAsInt()
                : pcl.base.Config.getInt(pcl.base.Config.SERVER_MEMORY_MB, 4096);

        int port = ServerDeployer.serverPort(dir);
        if (ServerDeployer.portBusy(port)) {
            sendJson(ex, 409, Map.of("error", "Port " + port + " already in use",
                    "code", "PORT_BUSY", "port", port));
            return;
        }
        ServerManager.Instance existing = ServerManager.get(dir);
        if (existing != null && existing.process.isAlive()) {
            sendJson(ex, 409, Map.of("error", "Already running in this panel",
                    "code", "ALREADY_RUNNING"));
            return;
        }
        String mc = ServerDeployer.mcFromDirName(dir);
        String javaExe = ServerDeployer.resolveJava(mc, true).executable().toString();
        ServerManager.Instance inst = ServerManager.start(dir, javaExe, memory);
        sendJson(ex, 200, Map.of("ok", true, "pid", inst.process.pid()));
    }

    private static void apiStop(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        Path dir = Path.of(body.get("dir").getAsString());
        boolean sent = ServerManager.stop(dir);
        if (sent) sendJson(ex, 200, Map.of("ok", true, "message", "stop command sent"));
        else sendJson(ex, 409, Map.of("error", "Not running in this panel", "code", "NOT_MANAGED"));
    }

    private static void apiKill(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        Path dir = Path.of(body.get("dir").getAsString());
        ServerManager.kill(dir);
        sendJson(ex, 200, Map.of("ok", true));
    }

    /** 删除已部署的服务端目录（仅限游戏目录内 servers/ 下的部署，运行中拒绝）。 */
    private static void apiDelete(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        Path dir = Path.of(body.get("dir").getAsString()).toAbsolutePath().normalize();

        if (!withinServersRoot(dir)) {
            sendJson(ex, 400, Map.of("error", "Only servers deployed under the servers/ directory can be deleted here",
                    "code", "NOT_DELETABLE"));
            return;
        }
        ServerManager.Instance inst = ServerManager.get(dir);
        if (inst != null && inst.process.isAlive()) {
            sendJson(ex, 409, Map.of("error", "Stop the server before deleting it",
                    "code", "SERVER_RUNNING"));
            return;
        }
        int port = ServerDeployer.serverPort(dir);
        if (Files.exists(dir.resolve("eula.txt")) || Files.exists(dir.resolve("server.properties"))) {
            if (ServerDeployer.portBusy(port)) {
                sendJson(ex, 409, Map.of("error", "Port " + port + " in use - server may be running elsewhere",
                        "code", "SERVER_RUNNING"));
                return;
            }
        }
        try {
            ServerDeployer.deleteServer(dir);
        } catch (IOException e) {
            sendJson(ex, 400, Map.of("error", String.valueOf(e.getMessage()), "code", "DELETE_FAILED"));
            return;
        }
        sendJson(ex, 200, Map.of("ok", true));
    }

    private static void apiLogs(HttpExchange ex) throws IOException {
        var query = parseQuery(ex.getRequestURI().getQuery());
        Path dir = Path.of(query.get("dir"));
        int since = Integer.parseInt(query.getOrDefault("since", "0"));
        ServerManager.Instance inst = ServerManager.get(dir);
        if (inst == null) { sendJson(ex, 409, Map.of("error", "Not running in this panel",
                "code", "NOT_MANAGED")); return; }
        List<String> lines = inst.tail(since);
        sendJson(ex, 200, Map.of("next", inst.logSize(), "lines", lines, "alive", inst.process.isAlive()));
    }

    private static void apiCommand(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        Path dir = Path.of(body.get("dir").getAsString());
        String cmd = body.get("cmd").getAsString();
        ServerManager.sendCommand(dir, cmd);
        sendJson(ex, 200, Map.of("ok", true));
    }

    /** server.properties 编辑：GET ?dir= 读取；POST {dir, props} 写回（运行中拒绝）。 */
    private static void apiProps(HttpExchange ex) throws IOException {
        switch (ex.getRequestMethod()) {
            case "GET" -> {
                var query = parseQuery(ex.getRequestURI().getQuery());
                Path dir = Path.of(query.get("dir")).toAbsolutePath().normalize();
                if (!withinServersRoot(dir)) {
                    sendJson(ex, 400, Map.of("error", "Only deployed servers under the servers/ directory are accessible",
                            "code", "NOT_DELETABLE"));
                    return;
                }
                if (!Files.exists(dir.resolve("server.properties"))) {
                    sendJson(ex, 404, Map.of("error", "server.properties not found", "code", "NO_PROPERTIES"));
                    return;
                }
                var arr = new java.util.ArrayList<Map<String, String>>();
                ServerProperties.load(dir).forEach((k, v) -> arr.add(Map.of("k", k, "v", v)));
                sendJson(ex, 200, Map.of("props", arr));
            }
            case "POST" -> {
                JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
                Path dir = Path.of(body.get("dir").getAsString()).toAbsolutePath().normalize();
                if (!withinServersRoot(dir)) {
                    sendJson(ex, 400, Map.of("error", "Only deployed servers under the servers/ directory are accessible",
                            "code", "NOT_DELETABLE"));
                    return;
                }
                ServerManager.Instance inst = ServerManager.get(dir);
                if (inst != null && inst.process.isAlive()) {
                    sendJson(ex, 409, Map.of("error", "Stop the server before editing properties",
                            "code", "EDIT_RUNNING"));
                    return;
                }
                java.util.LinkedHashMap<String, String> props = new java.util.LinkedHashMap<>();
                for (var e : body.getAsJsonObject("props").entrySet()) {
                    props.put(e.getKey(), e.getValue().getAsString());
                }
                ServerProperties.save(dir, props);
                sendJson(ex, 200, Map.of("ok", true, "count", props.size()));
            }
            default -> sendJson(ex, 405, Map.of("error", "Method not allowed"));
        }
    }

    /** 当前 Mods 安装任务状态（同一时刻只允许一个）。 */
    private static final class ModsState {
        volatile String phase = "downloading";
        volatile double percent;
        volatile boolean done;
        volatile String error;
        volatile List<String> installed = List.of();
        volatile List<String> skipped = List.of();
        volatile List<String> failed = List.of();
        volatile String resultDir;
    }

    private static volatile ModsState modsState;

    /** Mod/内容搜索：GET ?query=&source=curseforge|modrinth&type=mod|...;query 为空 = 当前类型热门榜。 */
    private static void apiModsSearch(HttpExchange ex) throws IOException {
        var q = parseQuery(ex.getRequestURI().getQuery());
        String query = q.getOrDefault("query", "").trim();
        String source = q.getOrDefault("source", "curseforge");
        String type = q.getOrDefault("type", "mod");
        if (!ModsService.validType(type)) {
            sendJson(ex, 400, Map.of("error", "Unknown type: " + type, "code", "BAD_TYPE"));
            return;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (ModsService.Item m : ModsService.search(source, type, query)) {
            Map<String, Object> m2 = new java.util.LinkedHashMap<>();
            m2.put("source", m.source());
            m2.put("id", m.id());
            m2.put("name", m.name());
            m2.put("summary", m.summary());
            m2.put("downloads", m.downloads());
            m2.put("icon", m.icon());
            m2.put("wikiUrl", m.wikiUrl());
            results.add(m2);
        }
        sendJson(ex, 200, Map.of("results", results));
    }

    /** 指定 mod 的兼容版本列表:GET ?dir=&source=&type=&id=[&mc=&loader=]。 */
    private static void apiModsFiles(HttpExchange ex) throws IOException {
        var q = parseQuery(ex.getRequestURI().getQuery());
        String source = q.getOrDefault("source", "curseforge");
        String type = q.getOrDefault("type", "mod");
        String id = q.getOrDefault("id", "");
        if (!ModsService.validType(type) || id.isEmpty()) {
            sendJson(ex, 400, Map.of("error", "type/id required", "code", "BAD_TYPE"));
            return;
        }
        String mc = q.containsKey("mc") && !q.get("mc").isBlank() ? q.get("mc")
                : (q.containsKey("dir") ? ServerDeployer.mcFromDirName(Path.of(q.get("dir"))) : "");
        String loader = q.getOrDefault("loader", "");
        List<Map<String, Object>> files = new ArrayList<>();
        for (ModsService.FileEntry f : ModsService.files(source, type, id, mc, loader)) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("fileId", f.fileId());
            m.put("name", f.name());
            m.put("date", f.date());
            m.put("size", f.size());
            m.put("versions", f.versions());
            files.add(m);
        }
        sendJson(ex, 200, Map.of("files", files));
    }

    /** 安装（含自动依赖补全）：POST {dir, source, type, id, mc?, loader?, fileId?}，异步执行。 */
    private static void apiModsInstall(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        Path dir = Path.of(body.get("dir").getAsString()).toAbsolutePath().normalize();
        if (!withinServersRoot(dir)) {
            sendJson(ex, 400, Map.of("error", "Only deployed servers under the servers/ directory are accessible",
                    "code", "NOT_DELETABLE"));
            return;
        }
        String source = body.get("source").getAsString();
        String type = body.get("type").getAsString().toLowerCase();
        String id = body.get("id").getAsString();
        if (!ModsService.validType(type)) {
            sendJson(ex, 400, Map.of("error", "Unknown type: " + type, "code", "BAD_TYPE"));
            return;
        }
        String mc = body.has("mc") && !body.get("mc").isJsonNull() && !body.get("mc").getAsString().isBlank()
                ? body.get("mc").getAsString() : ServerDeployer.mcFromDirName(dir);
        String loader = body.has("loader") && !body.get("loader").isJsonNull()
                && !body.get("loader").getAsString().isBlank() ? body.get("loader").getAsString() : null;

        String sub = switch (type) {
            case "resourcepack" -> "resourcepacks";
            case "shader" -> "shaderpacks";
            case "datapack" -> "datapacks";
            default -> "mods";
        };
        Path destDir = dir.resolve(sub);

        synchronized (WebPanel.class) {
            if (modsState != null && !modsState.done) {
                sendJson(ex, 409, Map.of("error", "Another mod install is in progress",
                        "code", "MODS_BUSY"));
                return;
            }
            ModsState s = new ModsState();
            s.phase = "downloading";
            modsState = s;
        }
        Thread t = new Thread(() -> {
            ModsState s = modsState;
            try {
                String fileId = body.has("fileId") && !body.get("fileId").isJsonNull()
                        && !body.get("fileId").getAsString().isBlank() ? body.get("fileId").getAsString() : null;
                ModsService.InstallReport r = ModsService.installWithDeps(source, type, id, fileId, mc, loader,
                        destDir, (done, total) -> s.percent = total == 0 ? 1 : (double) done / total);
                s.percent = 1;
                s.installed = r.installed();
                s.skipped = r.skipped();
                s.failed = r.failed();
                s.resultDir = destDir.toString();
                s.phase = "done";
            } catch (Exception e) {
                s.phase = "failed";
                s.error = String.valueOf(e.getMessage());
            } finally {
                s.done = true;
            }
        }, "pclj-web-mods");
        t.setDaemon(true);
        t.start();
        sendJson(ex, 200, Map.of("ok", true, "destDir", destDir.toString()));
    }

    private static void apiModsStatus(HttpExchange ex) throws IOException {
        ModsState s = modsState;
        if (s == null) { sendJson(ex, 200, Map.of("active", false)); return; }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("active", !s.done);
        m.put("phase", s.phase);
        m.put("percent", s.percent);
        m.put("done", s.done);
        m.put("installed", s.installed);
        m.put("skipped", s.skipped);
        m.put("failed", s.failed);
        if (s.error != null) m.put("error", s.error);
        if (s.resultDir != null) m.put("resultDir", s.resultDir);
        sendJson(ex, 200, m);
    }

    /** mc百科词条直达链接：GET ?name=<模组名> → {url}；解析失败退回搜索页 URL。 */
    private static void apiMcmod(HttpExchange ex) throws IOException {
        var q = parseQuery(ex.getRequestURI().getQuery());
        String name = q.getOrDefault("name", "").trim();
        if (name.isEmpty()) { sendJson(ex, 400, Map.of("error", "name required")); return; }
        String url = null;
        try {
            url = ModsService.resolveMcmod(name);
        } catch (Exception e) {
            Log.warn("mcmod 解析失败: " + name + ": " + String.valueOf(e.getMessage()));
        }
        // 失败不缓存,下次点击可重试;成功结果由 ModsService 缓存
        sendJson(ex, 200, Map.of("url", url != null ? url : ModsService.mcmodSearchUrl(name)));
    }

    /** 设置读写：GET 返回面板相关设置;POST {serverMemoryMb, serverJvmArgs, downloadSpeedLimit, serverDeployDir}。 */
    private static void apiSettings(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            JsonObject b = GSON.fromJson(body(ex), JsonObject.class);
            if (b.has("serverMemoryMb")) {
                int mem = b.get("serverMemoryMb").getAsInt();
                if (mem < 512 || mem > 65536) {
                    sendJson(ex, 400, Map.of("error", "Memory must be 512-65536 MB", "code", "BAD_SETTING"));
                    return;
                }
                pcl.base.Config.setInt(pcl.base.Config.SERVER_MEMORY_MB, mem);
            }
            if (b.has("serverJvmArgs")) {
                pcl.base.Config.set(pcl.base.Config.SERVER_JVM_ARGS,
                        b.get("serverJvmArgs").getAsString().trim());
            }
            if (b.has("downloadSpeedLimit")) {
                int kb = b.get("downloadSpeedLimit").getAsInt();
                if (kb < 0 || kb > 1048576) {
                    sendJson(ex, 400, Map.of("error", "Speed limit must be 0-1048576 KB/s", "code", "BAD_SETTING"));
                    return;
                }
                pcl.base.Config.setInt(pcl.base.Config.DOWNLOAD_SPEED_LIMIT, kb);
            }
            if (b.has("serverDeployDir")) {
                String dir = b.get("serverDeployDir").getAsString().trim();
                if (!dir.isEmpty() && !Files.isDirectory(Path.of(dir))) {
                    sendJson(ex, 400, Map.of("error", "Deploy directory does not exist: " + dir, "code", "BAD_SETTING"));
                    return;
                }
                pcl.base.Config.set(pcl.base.Config.SERVER_DEPLOY_DIR, dir);
            }
            sendJson(ex, 200, Map.of("ok", true));
            return;
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("serverMemoryMb", pcl.base.Config.getInt(pcl.base.Config.SERVER_MEMORY_MB, 4096));
        m.put("serverJvmArgs", pcl.base.Config.get(pcl.base.Config.SERVER_JVM_ARGS, ""));
        m.put("downloadSpeedLimit", pcl.base.Config.getInt(pcl.base.Config.DOWNLOAD_SPEED_LIMIT, 0));
        m.put("serverDeployDir", pcl.base.Config.get(pcl.base.Config.SERVER_DEPLOY_DIR, ""));
        m.put("downloadThreads", pcl.net.Downloader.threadLimit());
        sendJson(ex, 200, m);
    }

    /** 本机资源占用：CPU / 物理内存 / 游戏目录所在磁盘。 */
    private static void apiHost(HttpExchange ex) throws IOException {
        var os = (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        double cpu = os.getCpuLoad(); // 0..1，刚启动可能为 -1
        long memTotal = os.getTotalPhysicalMemorySize();
        long memFree = os.getFreePhysicalMemorySize();
        long diskTotal = 0, diskUsable = 0;
        try {
            var store = java.nio.file.Files.getFileStore(McFolder.selectedRoot());
            diskTotal = store.getTotalSpace();
            diskUsable = store.getUsableSpace();
        } catch (IOException ignored) {}
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("cpu", cpu < 0 ? -1 : Math.round(cpu * 100));
        m.put("memUsedMb", (memTotal - memFree) / 1048576);
        m.put("memTotalMb", memTotal / 1048576);
        m.put("diskUsedGb", (diskTotal - diskUsable) / 1073741824L);
        m.put("diskTotalGb", diskTotal / 1073741824L);
        sendJson(ex, 200, m);
    }

    /** 服务器详情：基本信息 + Server List Ping 查询在线人数与 MOTD（外部启动的实例同样有效）。 */
    private static void apiServerInfo(HttpExchange ex) throws IOException {
        var q = parseQuery(ex.getRequestURI().getQuery());
        Path dir = Path.of(q.get("dir")).toAbsolutePath().normalize();
        if (!withinServersRoot(dir)) {
            sendJson(ex, 400, Map.of("error", "Only deployed servers under the servers/ directory are accessible",
                    "code", "NOT_DELETABLE"));
            return;
        }
        String name = dir.getFileName().toString();
        String mc = ServerDeployer.mcFromDirName(dir);
        String type = name.contains("-") ? name.substring(name.lastIndexOf('-') + 1) : "";
        int port = ServerDeployer.serverPort(dir);
        ServerManager.Instance inst = ServerManager.get(dir);

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("dir", dir.toString());
        m.put("mc", mc);
        m.put("type", type);
        m.put("port", port);
        m.put("eula", Files.exists(dir.resolve("eula.txt"))
                && Files.readString(dir.resolve("eula.txt")).contains("eula=true"));
        m.put("running", inst != null && inst.process.isAlive());
        if (inst != null) {
            m.put("pid", inst.process.pid());
            if (inst.exitCode != null) m.put("exitCode", inst.exitCode);
        }
        if (inst == null && ServerDeployer.portBusy(port)) m.put("external", true);

        var ping = ServerPing.pingLocal(port, 1500);
        if (ping != null) {
            m.put("playersOnline", ping.online());
            m.put("playersMax", ping.max());
            if (!ping.motd().isBlank()) m.put("motd", ping.motd());
            if (!ping.version().isBlank()) m.put("serverVersion", ping.version());
        }
        sendJson(ex, 200, m);
    }

    // ==================== 工具 ====================

    /** 面板文件类 API 的作用域限制：默认 servers/ 与自定义部署根目录下的已部署服务端。 */
    private static boolean withinServersRoot(Path dir) {
        for (Path root : ServerDeployer.deployRoots()) {
            Path r = root.toAbsolutePath().normalize();
            if (dir.startsWith(r) && !dir.equals(r)) return true;
        }
        return false;
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static void sendJson(HttpExchange ex, int code, Object payload) throws IOException {
        byte[] data = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }
}
