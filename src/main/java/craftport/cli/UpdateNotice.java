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
 * 全程静默失败——网络不可用、离线环境都不影响正常使用；结果缓存在数据目录，
 * 最多每 6 小时查一次，避免每次启动都发请求。
 */
public final class UpdateNotice {

    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private UpdateNotice() {}

    /** 启动时调用：检查更新并在有新版本时打印提示；最多等待 3 秒。 */
    public static void checkOnStartup() {
        Thread t = new Thread(() -> {
            try {
                // 源码构建（无提交信息）无法用缓存判断新旧，每次都查
                Updater.UpdateCheck state = BuildInfo.isDev() ? null : readState();
                if (state == null || !BuildInfo.commit().equals(state.localCommit())
                        || System.currentTimeMillis() - readTime() > CHECK_INTERVAL_MS) {
                    state = Updater.check(Updater.DEFAULT_REF);
                    writeState(state);
                }
                if (state.updateAvailable() && !BuildInfo.isDev()) {
                    System.out.println();
                    System.out.println("New version available (current: " + BuildInfo.label()
                            + ", latest: " + state.remote().shortSha() + ")");
                    System.out.println("  Run 'update' to build and install it, or 'update --check' for details.");
                }
            } catch (Exception e) {
                Log.debug("启动更新检查失败: " + e.getMessage());
            }
        }, "CraftPort-UpdateCheck");
        t.setDaemon(true);
        t.start();
        try {
            t.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path stateFile() {
        return Os.dataDir().resolve("update-check.txt");
    }

    private static void writeState(Updater.UpdateCheck check) {
        String body = String.join("\n",
                "time=" + System.currentTimeMillis(),
                "available=" + check.updateAvailable(),
                "local=" + check.localCommit(),
                "remote=" + check.remote().sha(),
                "title=" + check.remote().title().replace("\n", " "));
        try {
            Files.createDirectories(stateFile().getParent());
            Files.writeString(stateFile(), body);
        } catch (IOException e) {
            Log.debug("写入更新检查缓存失败: " + e.getMessage());
        }
    }

    private static long readTime() {
        String v = readValue("time");
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Updater.UpdateCheck readState() {
        String remote = readValue("remote");
        if (remote == null || remote.isBlank()) return null;
        return new Updater.UpdateCheck(
                Boolean.parseBoolean(readValue("available")),
                new Updater.RemoteCommit(remote, readValue("title")),
                readValue("local"));
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
}
