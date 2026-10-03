package pcl.minecraft;

import pcl.base.Log;
import pcl.base.Os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 游戏进程管理，移植自 ModLaunch.vb 的进程启动与日志监听。
 * Linux 适配：不经过 cmd 包装，直接 ProcessBuilder；编码默认 UTF-8。
 */
public final class GameProcess {

    private static volatile Process current;
    private static final List<String> logBuffer = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final List<Consumer<String>> logListeners = new ArrayList<>();
    private static volatile int lastExitCode = Integer.MIN_VALUE;

    private GameProcess() {}

    /** 接管已启动的游戏进程（对应 McLaunchSteps 的日志监听与退出监听）。 */
    public static void attach(Process process) {
        current = process;
        Thread logThread = new Thread(() -> {
            Charset charset = Os.IS_WINDOWS ? Charset.forName("GBK") : java.nio.charset.StandardCharsets.UTF_8;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            } catch (Exception ignored) {}
        }, "CraftPort-GameLog");
        logThread.setDaemon(true);
        logThread.start();

        Thread errThread = new Thread(() -> {
            Charset charset = Os.IS_WINDOWS ? Charset.forName("GBK") : java.nio.charset.StandardCharsets.UTF_8;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getErrorStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog("[STDERR] " + line);
                }
            } catch (Exception ignored) {}
        }, "CraftPort-GameErr");
        errThread.setDaemon(true);
        errThread.start();

        // 游戏退出监听
        Thread exitThread = new Thread(() -> {
            try {
                lastExitCode = current.waitFor();
                appendLog("游戏已退出，退出码 " + lastExitCode);
                Log.info("游戏进程结束，退出码 " + lastExitCode);
            } catch (InterruptedException ignored) {}
        }, "CraftPort-GameExit");
        exitThread.setDaemon(true);
        exitThread.start();
    }

    private static void appendLog(String line) {
        Log.info("[游戏] " + line);
        synchronized (logBuffer) {
            logBuffer.add(line);
            if (logBuffer.size() > 2000) logBuffer.remove(0);
        }
        for (Consumer<String> l : new ArrayList<>(logListeners)) l.accept(line);
    }

    public static void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
        synchronized (logBuffer) {
            for (String line : logBuffer) listener.accept(line);
        }
    }

    public static void removeLogListener(Consumer<String> listener) {
        logListeners.remove(listener);
    }

    /** 是否正在运行。 */
    public static boolean isRunning() {
        Process p = current;
        return p != null && p.isAlive();
    }

    /** 强制结束游戏（对应 McLaunchKill）。 */
    public static void kill() {
        Process p = current;
        if (p != null && p.isAlive()) {
            p.destroy();
            Log.info("已发送游戏结束信号");
        }
    }

    public static int lastExitCode() { return lastExitCode; }
}
