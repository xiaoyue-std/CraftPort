package pcl.base;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置系统，移植自 Pages/PageSetup/Setup.vb。
 * PCL2 按 Normal/Registry/Instance 三种存储分别写 ini 与注册表；
 * Java 重构版统一改为 JSON 文件（跨平台无注册表）：
 *  - 全局设置：数据目录 config.json
 *  - 版本级设置：versions/{版本名}/CraftPort/Setup.json（对应 Instance 存储与 PCL\Setup.ini）
 */
public final class Config {
    private static final Path GLOBAL_FILE = Os.dataDir().resolve("config.json");
    private static JsonObject global = new JsonObject();

    // 登录缓存（PCL2 以 DES 加密存注册表；此处存于用户目录 config.json，权限跟随操作系统）
    public static String cacheUsername = "";
    public static String cacheUuid = "";
    public static String cacheAccessToken = "";
    public static String cacheRefreshToken = ""; // 微软登录刷新令牌
    public static String cacheClientId = "";     // 微软登录应用 ID，可用环境变量 PCL_MS_CLIENT_ID 覆盖

    /** 最近一次选择的版本（对应 PCL.ini 的 Version 缓存）。 */
    public static String cacheVersion = "";

    /** 自定义游戏目录，格式 名称>路径|名称>路径（对应 LaunchFolders）。 */
    public static String launchFolders = "";

    static { load(); }

    public static synchronized void load() {
        try {
            if (Files.exists(GLOBAL_FILE)) {
                JsonObject o = Json.parseObject(Files.readString(GLOBAL_FILE));
                if (o != null) global = o;
            }
        } catch (Exception e) {
            Log.warn("读取配置失败，使用默认值: " + e);
        }
        cacheUsername = Json.str(global, "CacheUsername", "");
        cacheUuid = Json.str(global, "CacheUuid", "");
        cacheAccessToken = Json.str(global, "CacheAccessToken", "");
        cacheRefreshToken = Json.str(global, "CacheRefreshToken", "");
        cacheClientId = Json.str(global, "CacheClientId",
                Os.env("PCL_MS_CLIENT_ID", ""));
        cacheVersion = Json.str(global, "CacheVersion", "");
        launchFolders = Json.str(global, "LaunchFolders", "");
    }

    public static synchronized void save() {
        global.addProperty("CacheUsername", cacheUsername);
        global.addProperty("CacheUuid", cacheUuid);
        global.addProperty("CacheAccessToken", cacheAccessToken);
        global.addProperty("CacheRefreshToken", cacheRefreshToken);
        global.addProperty("CacheClientId", cacheClientId);
        global.addProperty("CacheVersion", cacheVersion);
        global.addProperty("LaunchFolders", launchFolders);
        try {
            Files.createDirectories(GLOBAL_FILE.getParent());
            Files.writeString(GLOBAL_FILE, Json.GSON.toJson(global), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("保存配置失败", e);
        }
    }

    // ---- 全局设置项读写（对应 Setup.Get/Set）----
    public static synchronized String get(String key, String def) { return Json.str(global, key, def); }
    public static synchronized int getInt(String key, int def) { return Json.intOf(global, key, def); }
    public static synchronized boolean getBool(String key, boolean def) { return Json.bool(global, key, def); }
    public static synchronized void set(String key, String value) { global.addProperty(key, value); save(); }
    public static synchronized void setInt(String key, int value) { global.addProperty(key, value); save(); }
    public static synchronized void setBool(String key, boolean value) { global.addProperty(key, value); save(); }

    // ---- 版本级设置 ----
    private static final Map<String, JsonObject> versionSetups = new LinkedHashMap<>();

    public static synchronized JsonObject versionSetup(String versionName) {
        return versionSetups.computeIfAbsent(versionName, n -> {
            Path f = versionSetupFile(n);
            try {
                if (Files.exists(f)) {
                    JsonObject o = Json.parseObject(Files.readString(f));
                    if (o != null) return o;
                }
            } catch (Exception ignored) {}
            return new JsonObject();
        });
    }

    public static synchronized void saveVersionSetup(String versionName) {
        JsonObject o = versionSetup(versionName);
        Path f = versionSetupFile(versionName);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("保存版本设置失败: " + versionName, e);
        }
    }

    private static Path versionSetupFile(String versionName) {
        return Path.of(get("CacheMinecraftDir", Os.defaultMinecraftDir().toString()),
                "versions", versionName, "CraftPort", "Setup.json");
    }

    /** 版本级设置辅助（String/Int/Bool）。 */
    public static String vStr(String ver, String key, String def) { return Json.str(versionSetup(ver), key, def); }
    public static int vInt(String ver, String key, int def) { return Json.intOf(versionSetup(ver), key, def); }
    public static boolean vBool(String ver, String key, boolean def) { return Json.bool(versionSetup(ver), key, def); }
    public static void vSet(String ver, String key, String value) {
        versionSetup(ver).addProperty(key, value); saveVersionSetup(ver);
    }
    public static void vSetInt(String ver, String key, int value) {
        versionSetup(ver).addProperty(key, value); saveVersionSetup(ver);
    }
    public static void vSetBool(String ver, String key, boolean value) {
        versionSetup(ver).addProperty(key, value); saveVersionSetup(ver);
    }

    // ---- 设置键名常量（与 PCL2 设置项一一对应）----
    /** 下载源：0 镜像优先 / 1 自动 / 2 官方优先。 */
    public static final String TOOL_DOWNLOAD_SOURCE = "ToolDownloadSource";
    /** 版本清单源：0 镜像优先 / 1 自动 / 2 官方优先。 */
    public static final String TOOL_DOWNLOAD_VERSION = "ToolDownloadVersion";
    /** 下载线程数（PCL2 默认 63，Linux 文件系统上 32 已足够）。 */
    public static final String TOOL_DOWNLOAD_THREAD = "ToolDownloadThread";
    /** GC 优化：0 无 / 1 G1 / 4 优化 G1 / 5 ZGC。 */
    public static final String LAUNCH_ADVANCE_GC = "LaunchAdvanceGC";
    /** 版本隔离策略：0 关闭 / 1 仅可装 Mod 版本 / 2 仅非正式版 / 3 两者 / 4 全部。 */
    public static final String LAUNCH_ARGUMENT_INDIE = "LaunchArgumentIndieV2";
    /** JVM 参数。 */
    public static final String LAUNCH_ADVANCE_JVM = "LaunchAdvanceJvm";
    public static final String LAUNCH_ADVANCE_GAME = "LaunchAdvanceGame";
    /** 内存：0 自动 / 1 手动（MB）。 */
    public static final String LAUNCH_RAM_TYPE = "LaunchRamType";
    public static final String LAUNCH_RAM_CUSTOM = "LaunchRamCustom";
    /** 窗口：0 全屏 / 1 固定大小 / 2 随窗口缩放（默认）。 */
    public static final String LAUNCH_ARGUMENT_WINDOW_TYPE = "LaunchArgumentWindowType";
    public static final String LAUNCH_ARGUMENT_WIDTH = "LaunchArgumentWidth";
    public static final String LAUNCH_ARGUMENT_HEIGHT = "LaunchArgumentHeight";
    /** 启动器可见性：0 关闭启动器 / 2 隐藏 / 4 最小化 / 5 不变。 */
    public static final String LAUNCH_ARGUMENT_VISIBLE = "LaunchArgumentVisible";
    /** 选择的 .minecraft 目录。 */
    public static final String CACHE_MINECRAFT_DIR = "CacheMinecraftDir";

    /** PCL2 默认 JVM 参数（去掉仅 Windows 有意义的 allowAmbiguousCommands）。 */
    public static final String DEFAULT_JVM_ARGS =
            "-XX:-OmitStackTraceInFastThrow -Dfml.ignoreInvalidMinecraftCertificates=true -Dfml.ignorePatchDiscrepancies=true";
}
