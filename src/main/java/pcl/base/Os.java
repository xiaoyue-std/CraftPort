package pcl.base;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台适配层，移植自 ModBase.vb 的路径常量与平台判断（IsWinVistaOrHigher / PathTemp / PathPure 等）。
 * 这是 Linux 适配的核心：
 *  - 数据目录：Windows 用 %APPDATA%\PCLJ，Linux/macOS 用 ~/.local/share/PCLJ（遵循 XDG）
 *  - 临时目录：Windows 用 %TEMP%\PCL，Linux 用 /tmp/PCL 或 $XDG_RUNTIME_DIR
 *  - 类路径分隔符、可执行文件名、native 后缀全部按平台区分
 */
public final class Os {
    public enum Type { WINDOWS, LINUX, MACOS }

    public static final Type TYPE;
    public static final boolean IS_WINDOWS;
    public static final boolean IS_LINUX;
    public static final boolean IS_MACOS;
    public static final String OS_NAME;
    public static final String OS_ARCH;
    public static final int ARCH_BITS;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) TYPE = Type.WINDOWS;
        else if (os.contains("mac") || os.contains("darwin")) TYPE = Type.MACOS;
        else TYPE = Type.LINUX;
        IS_WINDOWS = TYPE == Type.WINDOWS;
        IS_LINUX = TYPE == Type.LINUX;
        IS_MACOS = TYPE == Type.MACOS;
        OS_NAME = IS_WINDOWS ? "windows" : IS_MACOS ? "osx" : "linux";
        String arch = System.getProperty("os.arch", "x86_64").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            OS_ARCH = "arm64";
            ARCH_BITS = 64;
        } else if (arch.contains("arm")) {
            OS_ARCH = "arm32";
            ARCH_BITS = 32;
        } else if (arch.contains("64")) {
            OS_ARCH = "x86_64";
            ARCH_BITS = 64;
        } else {
            OS_ARCH = "x86";
            ARCH_BITS = 32;
        }
    }

    private Os() {}

    /** 启动器数据目录（替代 PCL 程序目录存储 Setup.ini / 日志）。 */
    public static Path dataDir() {
        if (IS_WINDOWS) {
            String appdata = env("APPDATA", System.getProperty("user.home"));
            return Path.of(appdata, "PCLJ");
        }
        // Linux/macOS 遵循 XDG Base Directory 规范
        String xdg = env("XDG_DATA_HOME", "");
        if (!xdg.isBlank()) return Path.of(xdg, "PCLJ");
        return Path.of(System.getProperty("user.home"), ".local", "share", "PCLJ");
    }

    /** 临时目录，对应 PCL 的 PathTemp = %TEMP%\PCL\。 */
    public static Path tempDir() {
        if (IS_WINDOWS) return Path.of(env("TEMP", System.getProperty("java.io.tmpdir")), "PCL");
        String xdg = env("XDG_RUNTIME_DIR", "");
        if (!xdg.isBlank()) return Path.of(xdg, "PCL");
        return Path.of("/tmp", "PCL");
    }

    /** 存放 authlib-injector 等辅助文件的纯路径，对应 PathPure。Linux 下无特殊字符限制，直接用数据目录。 */
    public static Path pureDir() {
        return dataDir();
    }

    /** 按平台正确的可执行文件名。 */
    public static String exeName(String base) {
        return IS_WINDOWS ? base + ".exe" : base;
    }

    /** classpath 分隔符：Windows 为 ;，Linux/macOS 为 :。 */
    public static String classpathSeparator() {
        return IS_WINDOWS ? ";" : ":";
    }

    /** 当前平台的 natives classifier 名（natives-windows / natives-linux / natives-macos）。 */
    public static String nativesClassifier() {
        return IS_WINDOWS ? "natives-windows"
                : IS_MACOS ? "natives-macos"
                : OS_ARCH.equals("arm64") ? "natives-linux-arm64" : "natives-linux";
    }

    /** native 库文件后缀。 */
    public static String nativeLibSuffix() {
        return IS_WINDOWS ? ".dll" : IS_MACOS ? ".dylib" : ".so";
    }

    public static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 默认的 .minecraft 目录：Windows 官方启动器位置 / Linux 为 ~/...，可被设置覆盖。 */
    public static Path defaultMinecraftDir() {
        if (IS_WINDOWS) {
            return Path.of(env("APPDATA", System.getProperty("user.home")), ".minecraft");
        }
        if (IS_MACOS) {
            return Path.of(System.getProperty("user.home"),
                    "Library", "Application Support", "minecraft");
        }
        // Linux：官方启动器与多数社区启动器使用 ~/...，与 PCL2 一样支持多目录，这里给默认值
        return Path.of(System.getProperty("user.home"), ".minecraft");
    }

    /**
     * 探测系统上可能的 .minecraft 目录列表（对应 ModMinecraft 的目录发现逻辑）。
     * PCL2 扫描启动器所在目录 / 官启目录 / 自定义目录；Java 版额外适配 Linux 常见位置。
     */
    public static List<Path> discoverMinecraftDirs() {
        List<Path> list = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        addIfValid(list, Path.of(System.getProperty("user.dir"), ".minecraft"));
        addIfValid(list, Path.of(System.getProperty("user.dir"), "versions"));
        addIfValid(list, defaultMinecraftDir());
        if (!IS_WINDOWS) {
            // Linux 上各种启动器的常用目录（对应 PCL2 扫描 HMCL/Prism/Modrinth 的思路）
            addIfValid(list, Path.of(home.toString(), "curseforge", "minecraft", "Install", ".minecraft"));
            addIfValid(list, Path.of(home.toString(), ".local", "share", "PrismLauncher", ".minecraft"));
            addIfValid(list, Path.of(home.toString(), ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher", ".minecraft"));
            addIfValid(list, Path.of(home.toString(), ".local", "share", "modrinth", "instances"));
            addIfValid(list, Path.of(home.toString(), ".modrinth"));
            addIfValid(list, Path.of(home.toString(), "snap", "prismlauncher", "current", ".minecraft"));
        } else {
            addIfValid(list, Path.of(env("APPDATA", ""), ".minecraft"));
            addIfValid(list, Path.of(env("APPDATA", ""), ".minecraft", "versions"));
        }
        return list;
    }

    private static void addIfValid(List<Path> list, Path p) {
        if (p == null) return;
        Path versions = p.getFileName() != null && p.getFileName().toString().equals("versions")
                ? p.getParent() : p;
        if (versions == null) return;
        if (Files.isDirectory(versions.resolve("versions")) && !list.contains(versions)) list.add(versions);
    }
}
