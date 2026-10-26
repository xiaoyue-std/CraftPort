package craftport.base;

import java.io.InputStream;
import java.util.Properties;

/**
 * 构建信息：版本号、提交与构建时间。
 * 由更新流程写入 jar 内的 craftport/build.properties；源码构建（mvn package）没有该文件，
 * 此时版本显示为 dev，更新检查会把远端视为可升级。
 */
public final class BuildInfo {

    public static final String DEV = "dev";

    private static final String VERSION;
    private static final String COMMIT;
    private static final String BUILT;

    static {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/craftport/build.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
        }
        VERSION = p.getProperty("version", DEV).trim();
        COMMIT = p.getProperty("commit", "").trim();
        BUILT = p.getProperty("built", "").trim();
    }

    private BuildInfo() {}

    public static String version() { return VERSION; }
    /** 完整提交 sha，未知时为空串。 */
    public static String commit() { return COMMIT; }
    public static String built() { return BUILT; }

    /** 是否为源码构建（无法判断是否落后于远端）。 */
    public static boolean isDev() { return COMMIT.isEmpty(); }

    /** 展示用标识，如 "1.0.0 (db014c3)" 或 "dev"。 */
    public static String label() {
        if (COMMIT.isEmpty()) return DEV;
        String shortSha = COMMIT.length() > 7 ? COMMIT.substring(0, 7) : COMMIT;
        return (VERSION.equals(DEV) ? "" : VERSION + " ") + "(" + shortSha + ")";
    }
}
