package craftport.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import craftport.base.Config;
import craftport.base.Json;
import craftport.base.Os;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动参数构建，移植自 ModLaunch.vb 的 VersionArgumentJvm / VersionArgumentGame / 占位符替换。
 */
public final class ArgsBuilder {

    public record LaunchOptions(
            McVersion version,
            Path mcRoot,
            JavaRuntime java,
            LoginService.LoginResult login,
            Path gameDir,          // 版本隔离后的游戏目录
            Path nativesDir,
            String extraGameArgs,  // 自定义游戏参数
            int windowWidth,
            int windowHeight,
            boolean fullscreen,
            String serverAddress   // 自动进服，可空
    ) {}

    private ArgsBuilder() {}

    // ==================== 内存 ====================

    /** 自动内存分配（对应 GetRam 的简化实现）：物理内存的 40%，限 1~8GB。 */
    public static int autoRamMb() {
        long totalMb = ((com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize()
                / 1024 / 1024;
        return (int) Math.max(1024, Math.min(8192, totalMb * 40 / 100));
    }

    public static int ramMb(McVersion version) {
        int globalType = Config.getInt(Config.LAUNCH_RAM_TYPE, 0);
        int type = Config.vInt(version.name(), "VersionRamType", globalType);
        if (type == 1) {
            return Math.max(512, Config.vInt(version.name(), "VersionRamCustom",
                    Config.getInt(Config.LAUNCH_RAM_CUSTOM, autoRamMb())));
        }
        return autoRamMb();
    }

    // ==================== JVM 参数 ====================

    /** 构建 JVM 参数（对应 VersionArgumentJvmV2）。 */
    public static List<String> buildJvmArgs(LaunchOptions o) {
        List<String> args = new ArrayList<>();
        McVersion version = o.version();
        int javaMajor = o.java().major();
        int ram = ramMb(version);
        args.add("-Xmx" + ram + "m");

        // GC 优化（对应 LaunchAdvanceGC：0 无 / 1 G1 / 4 优化 G1 / 5 ZGC）
        int gc = Config.getInt(Config.LAUNCH_ADVANCE_GC, 4);
        if (gc == 1) args.add("-XX:+UseG1GC");
        else if (gc == 4) {
            args.add("-XX:+UseG1GC");
            // 以下 G1 细分参数为实验性选项，需先解锁（Java 8+ 均要求）
            args.add("-XX:+UnlockExperimentalVMOptions");
            args.add("-XX:G1NewSizePercent=20");
            args.add("-XX:G1ReservePercent=20");
            args.add("-XX:G1HeapRegionSize=32M");
            args.add("-XX:MaxGCPauseMillis=50");
            if (javaMajor <= 7) args.add("-XX:MaxPermSize=512m");
        } else if (gc == 5 && javaMajor >= 15) {
            args.add("-XX:+UseZGC");
            if (javaMajor >= 21 && javaMajor <= 22) args.add("-XX:+ZGenerational");
            if (javaMajor >= 24) args.add("-XX:+UseCompactObjectHeaders");
        }

        // Log4j 防御（对应 ModLaunch 的固定注入）
        args.add("-Dlog4j2.formatMsgNoLookups=true");

        // 编码参数（Linux 默认 UTF-8，但保留对老 JVM 的兼容逻辑）
        String encoding = "UTF-8";
        if (javaMajor >= 21) {
            // Java 21+ 默认 UTF-8，无需处理
        } else if (javaMajor >= 18) {
            args.add("-Dfile.encoding=COMPAT");
        } else {
            args.add("-Dfile.encoding=" + encoding);
        }
        if (javaMajor < 19) {
            args.add("-Dsun.stdout.encoding=" + encoding);
            args.add("-Dsun.stderr.encoding=" + encoding);
        } else {
            args.add("-Dstdout.encoding=" + encoding);
            args.add("-Dstderr.encoding=" + encoding);
        }

        // 用户自定义 JVM 参数（对应 LaunchAdvanceJvm）
        String userJvm = Config.vStr(version.name(), "VersionAdvanceJvm",
                Config.get(Config.LAUNCH_ADVANCE_JVM, Config.DEFAULT_JVM_ARGS));
        for (String arg : userJvm.trim().split("\\s+")) {
            if (!arg.isBlank()) args.add(arg);
        }

        // 版本 json 声明的 JVM 参数
        JsonArray jvmArgs = version.newJvmArguments();
        if (jvmArgs != null) {
            appendRuleArguments(args, jvmArgs, o);
        } else {
            // 旧版固定参数（对应旧版 JVM 参数；HeapDumpPath 的 Windows 特技在 Linux 上无意义，改为常规路径）
            args.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
            args.add("-Djava.library.path=" + o.nativesDir());
            args.add("-cp");
            args.add(classpath(o));
        }

        return dedupJvm(replacePlaceholders(args, o));
    }

    // ==================== 游戏参数 ====================

    /** 构建游戏参数（对应 VersionArgumentGameV2）。 */
    public static List<String> buildGameArgs(LaunchOptions o) {
        List<String> args = new ArrayList<>();
        McVersion version = o.version();

        if (version.hasNewArguments()) {
            JsonArray gameArgs = version.newGameArguments();
            if (gameArgs != null) appendRuleArguments(args, gameArgs, o);
        } else {
            // 旧版 minecraftArguments
            for (String arg : version.oldArguments().split("\\s+")) {
                if (!arg.isBlank()) args.add(arg);
            }
            // 强制追加窗口尺寸（对应旧版追加 --height/--width）
            args.add("--height");
            args.add(String.valueOf(o.windowHeight()));
            args.add("--width");
            args.add(String.valueOf(o.windowWidth()));
        }

        // 窗口
        if (o.fullscreen()) {
            args.add("--fullscreen");
        } else if (!version.hasNewArguments()) {
            // 新版自带 --width/--height 占位符；旧版已在上面追加
        } else {
            args.add("--height");
            args.add(String.valueOf(o.windowHeight()));
            args.add("--width");
            args.add(String.valueOf(o.windowWidth()));
        }

        // 自动进服（quickPlayMultiplayer 规则：发布时间 > 2023-04-04）
        if (o.serverAddress() != null && !o.serverAddress().isBlank()) {
            boolean useQuickPlay = version.releaseTime().compareTo("2023-04-04") > 0;
            if (useQuickPlay) {
                args.add("--quickPlayMultiplayer");
                args.add(o.serverAddress());
            } else {
                args.add("--server");
                args.add(o.serverAddress());
                args.add("--port");
                args.add(o.serverAddress().contains(":")
                        ? o.serverAddress().substring(o.serverAddress().indexOf(':') + 1) : "25565");
            }
        }

        // 自定义版本信息（对应 --versionType）
        String versionType = Config.vStr(version.name(), "VersionArgumentInfo",
                "Minecraft " + version.mcVersion());
        if (versionType != null && !versionType.isBlank()) {
            args.add("--versionType");
            args.add(versionType);
        }

        // 用户自定义游戏参数
        if (o.extraGameArgs() != null && !o.extraGameArgs().isBlank()) {
            for (String arg : o.extraGameArgs().trim().split("\\s+")) {
                if (!arg.isBlank()) args.add(arg);
            }
        }

        // 占位符替换 + 键值对去重（新值覆盖旧值）
        return dedupGame(replacePlaceholders(args, o));
    }

    // ==================== 规则参数遍历（arguments.jvm / arguments.game） ====================

    private static void appendRuleArguments(List<String> out, JsonArray arr, LaunchOptions o) {
        for (JsonElement e : arr) {
            if (e.isJsonObject()) {
                JsonObject obj = e.getAsJsonObject();
                if (!ruleMatches(obj, o)) continue;
                JsonElement value = obj.get("value");
                if (value == null) continue;
                if (value.isJsonArray()) {
                    for (JsonElement v : value.getAsJsonArray()) out.add(v.getAsString());
                } else {
                    out.add(value.getAsString());
                }
            } else if (!e.isJsonNull()) {
                out.add(e.getAsString());
            }
        }
    }

    /** rules 过滤（os.name / os.arch / features；features 未命中视为通过）。 */
    private static boolean ruleMatches(JsonObject entry, LaunchOptions o) {
        if (!entry.has("rules") || !entry.get("rules").isJsonArray()) return true;
        boolean result = false;
        boolean hasNonFeatureRule = false;
        for (JsonElement re : entry.getAsJsonArray("rules")) {
            if (!re.isJsonObject()) continue;
            JsonObject rule = re.getAsJsonObject();
            String action = Json.str(rule, "action", "allow");
            boolean matched = true;
            if (rule.has("os") && rule.get("os").isJsonObject()) {
                hasNonFeatureRule = true;
                JsonObject os = rule.getAsJsonObject("os");
                String osName = Json.str(os, "name", "");
                if (!osName.isEmpty() && !osName.equals(Os.OS_NAME)) matched = false;
                if (matched && os.has("arch") && !Json.str(os, "arch", "").equals(Os.OS_ARCH)) matched = false;
            }
            if (rule.has("features") && rule.get("features").isJsonObject()) {
                hasNonFeatureRule = true;
                // 启动器未开启任何 feature（is_demo_user 等按需添加），全视为 false
                matched = false;
            }
            if (matched) result = action.equals("allow");
        }
        return result || !hasNonFeatureRule;
    }

    // ==================== 占位符替换 ====================

    private static List<String> replacePlaceholders(List<String> args, LaunchOptions o) {
        McVersion version = o.version();
        Path mcRoot = o.mcRoot();
        String classpath = classpath(o);
        var map = new java.util.LinkedHashMap<String, String>();
        // 注意顺序：${classpath} 是 ${classpath_separator} 的子串，必须先替换后者
        map.put("${classpath_separator}", Os.classpathSeparator());
        map.put("${classpath}", classpath);
        map.put("${natives_directory}", o.nativesDir().toString());
        map.put("${library_directory}", mcRoot.resolve("libraries").toString());
        map.put("${libraries_directory}", mcRoot.resolve("libraries").toString());
        map.put("${pure_directory}", Os.pureDir().toString());
        map.put("${launcher_name}", "CraftPort");
        map.put("${launcher_version}", "1.0.0");
        map.put("${version_name}", version.name());
        map.put("${game_directory}", o.gameDir().toString());
        map.put("${assets_root}", mcRoot.resolve("assets").toString());
        map.put("${assets_index_name}", version.assetsId());
        map.put("${auth_player_name}", o.login().username());
        map.put("${auth_uuid}", o.login().uuid());
        map.put("${auth_access_token}", o.login().accessToken());
        map.put("${access_token}", o.login().accessToken());
        map.put("${auth_session}", "token:" + o.login().accessToken() + ":" + o.login().uuid());
        map.put("${user_type}", o.login().type() == LoginService.Type.MICROSOFT ? "msa" : "mojang");
        map.put("${user_properties}", "{}");
        map.put("${clientid}", "0");
        map.put("${client_token}", o.login().accessToken());
        map.put("${auth_xuid}", "0");
        map.put("${primary_jar}", version.clientJar().toString());
        map.put("${version_type}", Json.str(version.info, "type", "release"));
        map.put("${resolution_width}", String.valueOf(o.windowWidth()));
        map.put("${resolution_height}", String.valueOf(o.windowHeight()));
        // pre-1.6 的 legacy 资源目录
        map.put("${game_assets}", mcRoot.resolve("assets").resolve("virtual").resolve("legacy").toString());

        List<String> out = new ArrayList<>();
        for (String arg : args) {
            for (var e : map.entrySet()) {
                if (arg.contains(e.getKey())) arg = arg.replace(e.getKey(), e.getValue());
            }
            out.add(arg);
        }
        return out;
    }

    /** classpath：全部库 + 客户端 jar + Forge 二进制补丁（如有）；OptiFine 应位于倒数第二位，此处保持 libraries 顺序即可。 */
    private static String classpath(LaunchOptions o) {
        StringBuilder sb = new StringBuilder();
        String sep = Os.classpathSeparator();
        for (Library lib : o.version().libraries()) {
            Path jar = lib.localPath(o.mcRoot().resolve("libraries"));
            if (Files.exists(jar)) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(jar);
            }
        }
        // Forge 1.13+ 的二进制补丁：安装在版本文件夹内，需在 classpath 上供 bootstrap 运行时应用
        for (String patch : new String[]{"binpatches.pack.lzma", "data/client.lzma"}) {
            Path p = o.version().folder().resolve(patch);
            if (Files.exists(p)) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(p);
            }
        }
        if (sb.length() > 0) sb.append(sep);
        sb.append(o.version().clientJar());
        return sb.toString();
    }

    // ==================== 去重 ====================

    /** JVM 参数去重：键值对参数新值覆盖旧值，其余保留（对应 PCL2 的去重规则）。 */
    private static List<String> dedupJvm(List<String> args) {
        List<String> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        // 倒序遍历，保留最新的键值对
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            String key = jvmKey(arg);
            if (key != null) {
                if (seenKeys.contains(key)) continue;
                seenKeys.add(key);
            }
            result.add(0, arg);
        }
        return result;
    }

    private static String jvmKey(String arg) {
        if (arg.startsWith("-D")) {
            int eq = arg.indexOf('=');
            return eq > 0 ? arg.substring(0, eq) : arg;
        }
        if (arg.startsWith("-Xmx") || arg.startsWith("-Xms") || arg.startsWith("-Xmn")) return arg.substring(0, 4);
        return null;
    }

    /** 游戏参数去重：键值对（--xxx 后跟值）新值覆盖旧值。 */
    private static List<String> dedupGame(List<String> args) {
        List<String> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            if (arg.startsWith("--")) {
                if (seenKeys.contains(arg)) {
                    // 已有更新的同名参数：若是键值对，连同旧值一起跳过
                    if (i > 0 && !args.get(i - 1).startsWith("--")) i--;
                    continue;
                }
                seenKeys.add(arg);
            }
            result.add(0, arg);
        }
        return result;
    }
}
