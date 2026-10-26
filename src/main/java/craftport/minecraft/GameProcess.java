package craftport.minecraft;

import craftport.base.Log;
import craftport.base.Os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 游戏进程管理：接管已启动的进程，转发日志并记录退出码。
 * Linux 适配：不经过 cmd 包装，直接 ProcessBuilder；编码默认 UTF-8。
 */
public final class GameProcess {

    private static volatile Process current;
    private static final List<String> logBuffer = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final List<Consumer<String>> logListeners = new ArrayList<>();

    private GameProcess() {}

    /** 接管已启动的游戏进程（监听日志与退出）。 */
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
                int code = current.waitFor();
                appendLog("游戏已退出，退出码 " + code);
                Log.info("游戏进程结束，退出码 " + code);
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

    /** 注册日志监听；注册时先回放缓冲区内的历史行。 */
    public static void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
        synchronized (logBuffer) {
            for (String line : logBuffer) listener.accept(line);
        }
    }
}
