package pcl.base;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志模块，移植自 PCL2 的 Modules/Base/PclLogger.vb。
 * 输出到控制台与数据目录下的 Logs/latest.log，Linux/Windows 通用。
 */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss");
    private static Path logFile;
    private static boolean fileReady;
    /** 控制台输出开关（CLI 模式关闭：核心日志只写文件，终端输出交给 CLI 自己的英文提示）。 */
    private static volatile boolean consoleEnabled = true;

    public enum Level { DEBUG, INFO, WARN, ERROR }

    /** 关闭/开启控制台输出（文件日志不受影响）。 */
    public static void setConsoleEnabled(boolean enabled) { consoleEnabled = enabled; }

    private Log() {}

    /** 数据目录初始化后调用，开启文件日志。 */
    public static void initFile(Path dataDir) {
        try {
            Path dir = dataDir.resolve("Logs");
            Files.createDirectories(dir);
            logFile = dir.resolve("latest.log");
            // 保留最近一次日志为 previous.log（对应 PCL 的日志轮换习惯）
            if (Files.exists(logFile)) {
                Files.move(logFile, dir.resolve(FILE.format(LocalDateTime.now()) + ".log"),
                        StandardCopyOption.REPLACE_EXISTING);
                try (var s = Files.list(dir)) {
                    var logs = s.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().toList();
                    for (int i = 0; i < Math.max(0, logs.size() - 20); i++) {
                        Files.deleteIfExists(logs.get(i));
                    }
                }
            }
            fileReady = true;
            info("日志文件已创建：" + logFile);
        } catch (Exception e) {
            fileReady = false;
            System.err.println("日志文件创建失败，仅控制台输出: " + e);
        }
    }

    public static void debug(String msg) { write(Level.DEBUG, msg, null); }
    public static void info(String msg)  { write(Level.INFO, msg, null); }
    public static void warn(String msg)  { write(Level.WARN, msg, null); }
    public static void error(String msg) { write(Level.ERROR, msg, null); }
    public static void error(String msg, Throwable t) { write(Level.ERROR, msg, t); }

    private static synchronized void write(Level level, String msg, Throwable t) {
        String line = "[" + TIME.format(LocalDateTime.now()) + "] [" + level + "] " + msg;
        if (t != null) {
            var sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            line += "\n" + sw;
        }
        if (consoleEnabled) System.out.println(line);
        if (fileReady && logFile != null) {
            try {
                Files.writeString(logFile, line + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }
    }
}
