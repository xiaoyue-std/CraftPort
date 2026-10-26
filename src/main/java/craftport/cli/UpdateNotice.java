package craftport.cli;

import craftport.base.BuildInfo;
import craftport.base.Log;
import craftport.base.Os;
import craftport.base.Updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动时的更新提示：后台查一次 GitHub（Atom feed，不限流），有新提交则打印一行提示。
 * 全程静默失败——网络不可用、离线环境都不影响正常使用。
 *
 * 结果（含失败）缓存在数据目录，冷却期内不再重试：
 *  - 检查成功：缓存 6 小时，期间不重复请求
 *  - 检查失败（超时 / 断网）：同样缓存 6 小时，避免每次启动都卡在同一个超时上
 * 手工执行 `update --check` 不受缓存影响，总是实时查询。
 */
public final class UpdateNotice {

    /** 检查成功的缓存时长。 */
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    /** 检查超时/失败后的冷却时长：短时间内不再尝试，避免每次启动都卡在同一个超时上。 */
    private static final long FAILURE_COOLDOWN_MS = 60 * 60 * 1000L;
    /** 启动时最多等待检查的时间，超时则照常启动（后台线程是守护线程，随进程退出）。 */
    private static final long STARTUP_WAIT_MS = 2000;

    private UpdateNotice() {}

    /** 缓存的检查结果；ok=false 表示上次检查失败。 */
    private record State(long time, boolean ok, boolean available, String local,
                         String remote, String title) {}

    /** 本次启动的检查结果（后台线程写入，主线程超时时读）。 */
    private static final java.util.concurrent.atomic.AtomicReference<State> RESULT =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 启动时调用：检查更新并在有新版本时打印提示。 */
    public static void checkOnStartup() {
        RESULT.set(null);
        Thread t = new Thread(() -> {
            try {
                State cached = readState();
                State state = usableCache(cached) ? cached : runCheck();
                if (state.ok() && state.available() && !BuildInfo.isDev()) {
                    System.out.println();
                    System.out.println("New version available (current: " + BuildInfo.label()
                            + ", latest: " + shortSha(state.remote()) + ")");
                    System.out.println("  Run 'update' to build and install it, or 'update --check' for details.");
                }
            } catch (Exception e) {
                Log.debug("启动更新检查失败: " + e.getMessage());
            }
        }, "CraftPort-UpdateCheck");
        t.setDaemon(true);
        t.start();
        try {
            t.join(STARTUP_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 本次启动超时：进程退出前记一笔失败缓存，随后 60 分钟内不再尝试，
        // 这样连续执行命令时不会每次都等满超时（命中缓存后立即返回）。
        if (RESULT.get() == null) {
            writeState(new State(System.currentTimeMillis(), false, false, BuildInfo.commit(), "", ""));
        }
    }

    /** 缓存是否仍可用于本次启动：冷却期内即可；成功结果对 dev 构建同样有效
     *  （dev 只是不展示提示，不必每次启动都重新联网）。 */
    private static boolean usableCache(State s) {
        if (s == null) return false;
        long age = System.currentTimeMillis() - s.time();
        if (!s.ok()) return age <= FAILURE_COOLDOWN_MS;
        if (age > CHECK_INTERVAL_MS) return false;
        // 正式构建还要求缓存对应的是当前提交（同版本不重复查询）
        return BuildInfo.isDev() || BuildInfo.commit().equals(s.local());
    }

    /** 实际查询；成功与失败都写入缓存并发布给主线程。 */
    private static State runCheck() {
        State s;
        try {
            Updater.UpdateCheck c = Updater.check(Updater.DEFAULT_REF);
            s = new State(System.currentTimeMillis(), true, c.updateAvailable(),
                    c.localCommit(), c.remote().sha(), c.remote().title());
        } catch (Exception e) {
            Log.debug("更新检查失败（本次启动不再重试）: " + e.getMessage());
            s = new State(System.currentTimeMillis(), false, false, BuildInfo.commit(), "", "");
        }
        writeState(s);
        RESULT.set(s);
        return s;
    }

    private static Path stateFile() {
        return Os.dataDir().resolve("update-check.txt");
    }

    private static void writeState(State s) {
        String body = String.join("\n",
                "time=" + s.time(),
                "ok=" + s.ok(),
                "available=" + s.available(),
                "local=" + s.local(),
                "remote=" + s.remote(),
                "title=" + s.title().replace("\n", " "));
        try {
            Files.createDirectories(stateFile().getParent());
            Files.writeString(stateFile(), body);
        } catch (IOException e) {
            Log.debug("写入更新检查缓存失败: " + e.getMessage());
        }
    }

    private static State readState() {
        String time = readValue("time");
        if (time == null) return null;
        try {
            return new State(Long.parseLong(time),
                    Boolean.parseBoolean(readValue("ok")),
                    Boolean.parseBoolean(readValue("available")),
                    nullToEmpty(readValue("local")),
                    nullToEmpty(readValue("remote")),
                    nullToEmpty(readValue("title")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readValue(String key) {
        try {
            if (!Files.isRegularFile(stateFile())) return null;
            for (String line : Files.readAllLines(stateFile())) {
                if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
