package craftport.minecraft;

import craftport.base.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面板托管的服务端进程注册表（Web 面板专用）。
 * 与 CLI 交互式 `server start`（inheritIO）不同：这里捕获输出流以支持浏览器实时日志，
 * 并保留 stdin 以便发送控制台命令（stop / say / list 等）。
 */
public final class ServerManager {

    /** 一个由面板启动、托管中的服务端实例。 */
    public static final class Instance {
        public final Path dir;
        public final Process process;
        public final long startedAt = System.currentTimeMillis();
        private final List<String> log = new ArrayList<>();
        public volatile Integer exitCode;

        Instance(Path dir, Process process) {
            this.dir = dir;
            this.process = process;
        }

        public synchronized void append(String line) {
            log.add(line);
            if (log.size() > 4000) log.subList(0, 400).clear();
        }

        /** 返回 since 下标之后的日志（面板轮询用）。 */
        public synchronized List<String> tail(int since) {
            return new ArrayList<>(log.subList(Math.min(since, log.size()), log.size()));
        }

        public synchronized int logSize() { return log.size(); }
    }

    private static final Map<String, Instance> RUNNING = new ConcurrentHashMap<>();

    private static String key(Path dir) {
        return dir.toAbsolutePath().normalize().toString();
    }

    /** 启动托管实例。同一目录已在托管中时返回现有实例。 */
    public static Instance start(Path dir, String javaExe, int memoryMb) throws IOException {
        String k = key(dir);
        Instance existing = RUNNING.get(k);
        if (existing != null && existing.process.isAlive()) return existing;
        RUNNING.remove(k);

        List<String> cmd = ServerDeployer.startCommand(dir, javaExe, memoryMb);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true); // stderr 并入 stdout，浏览器日志完整
        Process p = pb.start();

        Instance inst = new Instance(dir.toAbsolutePath().normalize(), p);
        RUNNING.put(k, inst);
        Log.info("面板托管启动: " + inst.dir);

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) inst.append(line);
            } catch (IOException ignored) {
            }
            try {
                inst.exitCode = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.info("托管服务端退出: " + inst.dir + " code=" + inst.exitCode);
        }, "pclj-panel-log");
        reader.setDaemon(true);
        reader.start();
        return inst;
    }

    public static Instance get(Path dir) {
        return RUNNING.get(key(dir));
    }

    public static Collection<Instance> all() {
        return RUNNING.values();
    }

    /** 向服务端控制台发送命令（stop / say / list ...）。 */
    public static void sendCommand(Path dir, String cmd) throws IOException {
        Instance i = get(dir);
        if (i == null || !i.process.isAlive()) throw new IOException("Server is not managed/running here");
        i.process.getOutputStream().write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
        i.process.getOutputStream().flush();
    }

    /** 优雅停止：向 stdin 发送 stop；返回是否发出了指令（进程不存在返回 false）。 */
    public static boolean stop(Path dir) throws IOException {
        Instance i = get(dir);
        if (i == null || !i.process.isAlive()) return false;
        sendCommand(dir, "stop");
        return true;
    }

    /** 强制终止（stop 无响应时的兜底）。 */
    public static void kill(Path dir) {
        Instance i = get(dir);
        if (i != null && i.process.isAlive()) i.process.destroyForcibly();
    }

    private ServerManager() {}
}
