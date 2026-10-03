package pcl.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pcl.base.Log;
import pcl.base.Os;
import pcl.minecraft.ServerDeployer;
import pcl.minecraft.ServerManager;

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
                case "/api/deploy" -> apiDeploy(ex);
                case "/api/deploy/status" -> apiDeployStatus(ex);
                case "/api/start" -> apiStart(ex);
                case "/api/stop" -> apiStop(ex);
                case "/api/kill" -> apiKill(ex);
                case "/api/logs" -> apiLogs(ex);
                case "/api/command" -> apiCommand(ex);
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

    private static void apiDeploy(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        String mc = body.get("mc").getAsString();
        String type = body.get("type").getAsString().toLowerCase();
        boolean eula = body.has("eula") && body.get("eula").getAsBoolean();
        ServerDeployer.Kind kind = ServerDeployer.Kind.valueOf(type.toUpperCase());

        synchronized (WebPanel.class) {
            if (deployState != null && !deployState.done) {
                sendJson(ex, 409, Map.of("error", "A deployment is already in progress"));
                return;
            }
            deployState = new DeployState();
        }
        Path dir = ServerDeployer.defaultDir(mc, kind);
        Thread t = new Thread(() -> {
            DeployState s = deployState;
            try {
                s.phase = "downloading/installing";
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
        int memory = body.has("memory") ? body.get("memory").getAsInt() : 4096;

        int port = ServerDeployer.serverPort(dir);
        if (ServerDeployer.portBusy(port)) {
            sendJson(ex, 409, Map.of("error", "Port " + port + " already in use"));
            return;
        }
        ServerManager.Instance existing = ServerManager.get(dir);
        if (existing != null && existing.process.isAlive()) {
            sendJson(ex, 409, Map.of("error", "Already running in this panel"));
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
        else sendJson(ex, 409, Map.of("error", "Not running in this panel"));
    }

    private static void apiKill(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
        Path dir = Path.of(body.get("dir").getAsString());
        ServerManager.kill(dir);
        sendJson(ex, 200, Map.of("ok", true));
    }

    private static void apiLogs(HttpExchange ex) throws IOException {
        var query = parseQuery(ex.getRequestURI().getQuery());
        Path dir = Path.of(query.get("dir"));
        int since = Integer.parseInt(query.getOrDefault("since", "0"));
        ServerManager.Instance inst = ServerManager.get(dir);
        if (inst == null) { sendJson(ex, 409, Map.of("error", "Not running in this panel")); return; }
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

    // ==================== 工具 ====================

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
