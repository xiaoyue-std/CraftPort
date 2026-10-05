package craftport.minecraft;

import craftport.base.Log;
import craftport.base.Os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java 运行时搜索 / 检测 / 选择，移植自 Modules/Minecraft/ModJava.vb。
 *  - 搜索：PATH、JAVA_HOME/JDK_HOME、常见安装目录、各启动器 runtime 目录
 *  - 检测：运行 java -XshowSettings:properties -version 解析版本
 *  - 版本要求：GetJavaRequirement 的规则（1.20.5+→21、1.18+→17、1.17+→16、1.12+→8、1.5.2-→≤8）
 *  - 选择：自动模式选主版本号最接近 21 且满足要求的
 */
public final class JavaManager {

    private static final List<JavaRuntime> cache = new ArrayList<>();
    private static volatile boolean searched = false;

    private JavaManager() {}

    /** 清空缓存以便重新搜索（对应 PCL2 的"重新全盘搜索"）。 */
    public static synchronized void invalidate() {
        searched = false;
        cache.clear();
    }

    // ==================== 搜索 ====================

    /** 全盘搜索 Java（对应 ModJava 的并行搜索，此处为顺序搜索 + 去重）。 */
    public static synchronized List<JavaRuntime> searchAll() {
        if (searched) return new ArrayList<>(cache);
        cache.clear();
        Set<Path> candidates = new LinkedHashSet<>();

        // 1. PATH 中的 java（Windows 用 where，Linux/macOS 用 which）
        try {
            Process p = new ProcessBuilder(Os.IS_WINDOWS ? "where" : "which", Os.exeName("java"))
                    .redirectErrorStream(true).start();
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                try (var lines = new String(p.getInputStream().readAllBytes()).lines()) {
                    lines.map(String::trim).filter(s -> !s.isEmpty())
                            .forEach(s -> candidates.add(Path.of(s)));
                }
            }
        } catch (Exception ignored) {}

        // 2. JAVA_HOME / JDK_HOME
        for (String envKey : new String[]{"JAVA_HOME", "JDK_HOME"}) {
            String v = System.getenv(envKey);
            if (v != null && !v.isBlank()) candidates.add(Path.of(v.trim(), "bin", Os.exeName("java")));
        }

        // 3. 常见安装目录（合并 PCL2 的 CandidateFolders 与 Linux 习惯位置）
        Path home = Path.of(System.getProperty("user.home"));
        // 启动器自动下载的 Mojang 运行时（各平台统一扫描当前 .minecraft 的 runtime 目录）
        try {
            scanDirectory(McFolder.selectedRoot().resolve("runtime"), 3, candidates);
        } catch (Exception e) {
            Log.warn("扫描 runtime 目录失败: " + e.getMessage());
        }
        if (Os.IS_WINDOWS) {
            String pf = Os.env("ProgramFiles", "C:\\Program Files");
            String pf86 = Os.env("ProgramFiles(x86)", "C:\\Program Files (x86)");
            for (Path base : new Path[]{Path.of(pf), Path.of(pf86)}) {
                for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Amazon Corretto",
                        "Zulu", "Microsoft", "BellSoft", "AdoptOpenJDK"}) {
                    scanDirectory(base.resolve(vendor), 2, candidates);
                }
            }
            String appdata = Os.env("APPDATA", "");
            if (!appdata.isBlank()) {
                scanDirectory(Path.of(appdata, ".minecraft", "runtime"), 3, candidates);
                scanDirectory(Path.of(appdata, ".hmcl", "java"), 2, candidates);
                scanDirectory(Path.of(appdata, ".jdks"), 1, candidates);
                scanDirectory(Path.of(appdata, "ModrinthApp", "meta", "java_versions"), 2, candidates);
                scanDirectory(Path.of(appdata, "PrismLauncher", "java"), 2, candidates);
            }
            scanDirectory(Path.of(home.toString(), ".jdks"), 1, candidates);
        } else {
            // Linux/macOS 的 JDK 目录
            for (Path base : new Path[]{
                    Path.of("/usr/lib/jvm"),                      // Debian/Ubuntu/Fedora 等
                    Path.of("/opt"),                              // 手动解压 / 商业发行版
                    Path.of("/usr/java"),                         // RHEL 系
                    Path.of("/Library", "Java", "JavaVirtualMachines"), // macOS
                    Path.of(home.toString(), ".jdks"),            // JetBrains
                    Path.of(home.toString(), ".sdkman", "candidates", "java"), // SDKMAN!
                    Path.of(home.toString(), ".hmcl", "java"),    // HMCL 下载的 runtime
                    Path.of(home.toString(), ".local", "share", "PrismLauncher", "java"),
                    Path.of(home.toString(), ".local", "share", "jdks"),
                    Path.of("/app", "jdk"),                       // Flatpak 部分应用
                    Path.of("/snap")}) {
                scanDirectory(base, 2, candidates);
            }
            // asdf
            scanDirectory(Path.of(home.toString(), ".asdf", "installs", "java"), 2, candidates);
        }

        // 4. 逐个检测
        for (Path exe : candidates) {
            JavaRuntime rt = detect(exe);
            if (rt != null && !containsSame(cache, rt)) cache.add(rt);
        }
        // 排序：精确的 Java 21 最优先（对应"优先使用 Java 21"），
        // 其次按与 21 的距离，距离相同取更高版本
        cache.sort(Comparator
                .comparingInt((JavaRuntime rt) -> rt.major() == 21 ? 0 : 1)
                .thenComparingInt(rt -> Math.abs(rt.major() - 21))
                .thenComparing(Comparator.comparingInt(JavaRuntime::major).reversed()));
        searched = true;
        Log.info("共找到 " + cache.size() + " 个 Java 运行时");
        return new ArrayList<>(cache);
    }

    private static boolean containsSame(List<JavaRuntime> list, JavaRuntime rt) {        try {
            Path norm = rt.executable().toRealPath();
            for (JavaRuntime existing : list) {
                if (existing.executable().toRealPath().equals(norm)) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    private static void scanDirectory(Path dir, int maxDepth, Set<Path> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.find(dir, maxDepth,
                (p, attr) -> p.getFileName() != null
                        && p.getFileName().toString().equals(Os.exeName("java"))
                        && attr.isRegularFile())) {
            stream.forEach(out::add);
        } catch (IOException ignored) {}
    }

    /**
     * 检测单个 java 可执行文件，对应 ModJava 的 JRE 检测：
     * 运行 java -XshowSettings:properties -version，正则解析 java.version / file.encoding / native.encoding。
     */
    public static JavaRuntime detect(Path executable) {
        if (!Files.isRegularFile(executable)) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(executable.toString(),
                    "-XshowSettings:properties", "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (var in = p.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (output.contains("fatal error")) return null;
            Matcher version = Pattern.compile("java\\.version = ([^\\r\\n]+)").matcher(output);
            if (!version.find()) return null;
            String full = version.group(1).trim();
            // 1.x 格式转换（对应 PCL2 的 "1." 前缀处理）
            String major = full.startsWith("1.") ? full.substring(2, full.indexOf('.', 2)) : full.split("[.\"_]")[0];
            String fileEnc = group(output, "file\\.encoding = ([^\\r\\n]+)");
            String nativeEnc = group(output, "native\\.encoding = ([^\\r\\n]+)");
            return new JavaRuntime(major, full, executable, fileEnc, nativeEnc);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    // ==================== 版本要求规则 ====================

    /**
     * 计算 MC 版本所需的 Java 版本范围（对应 GetJavaRequirement）。
     * 返回 [min, max]，max 为 Integer.MAX_VALUE 表示无上限。
     */
    public static int[] requirement(McVersion version) {
        int javaMajor = version.requiredJavaMajor();
        if (javaMajor >= 22) return new int[]{javaMajor, Integer.MAX_VALUE};
        try {
            String date = version.releaseTime.length() > 10 ? version.releaseTime.substring(0, 10) : "2020-01-01";
            if (version.mcVersion.matches("1\\.20\\.[5-9].*") || version.mcVersion.matches("1\\.2[1-9].*")
                    || date.compareTo("2024-04-02") >= 0) {
                return new int[]{21, Integer.MAX_VALUE};
            }
            if (version.mcVersion.matches("1\\.(18|19|20)(\\.\\d+)*.*") || date.compareTo("2021-11-30") >= 0) {
                return new int[]{17, Integer.MAX_VALUE};
            }
            if (version.mcVersion.matches("1\\.17(\\.\\d+)*.*") || date.compareTo("2021-05-11") >= 0) {
                return new int[]{16, Integer.MAX_VALUE};
            }
            if (date.compareTo("2017-01-01") >= 0) {
                return new int[]{8, Integer.MAX_VALUE};
            }
            // 1.5.2 及更早
            return new int[]{5, 8};
        } catch (Exception e) {
            return new int[]{8, Integer.MAX_VALUE};
        }
    }

    /** 该版本是否满足要求。 */
    public static boolean satisfies(JavaRuntime rt, int[] req) {
        return rt != null && rt.major() >= req[0] && rt.major() <= req[1];
    }

    // ==================== 选择 ====================

    /**
     * 选择用于启动的 Java（对应 VersionArgumentJavaV2 的策略 0=自动 / 3=强制指定）。
     * versionJavaSetting：版本级设置的 Java 路径，为空则自动选择。
     */
    public static JavaRuntime pick(McVersion version, String versionJavaSetting) throws IOException {
        // 策略 3：强制指定
        if (versionJavaSetting != null && !versionJavaSetting.isBlank()) {
            Path exe = Path.of(versionJavaSetting);
            JavaRuntime rt = detect(exe);
            if (rt != null) return rt;
            throw new IOException("指定的 Java 无法运行: " + versionJavaSetting);
        }
        // 策略 0：自动 —— 本机搜索（Java 21 优先），找不到则自动从 Mojang 镜像下载对应运行时
        searchAll();
        int[] req = requirement(version);
        JavaRuntime best = firstSatisfying(req);
        if (best != null) return best;

        // 未找到 → 自动下载（对应 PCL2 SelectOrDownloadJava 的下载分支）
        Log.info("未找到满足要求的 Java（" + req[0] + "+），自动下载 Mojang 运行时…");
        try {
            JavaRuntimeDownloader.ensureRuntime(version);
        } catch (IOException e) {
            throw new IOException("自动下载 Java 运行时失败: " + e.getMessage()
                    + "。请在系统安装 Java " + req[0] + (req[1] == Integer.MAX_VALUE ? "+" : "~" + req[1])
                    + "，或在设置中手动指定 Java 路径。", e);
        }
        invalidate();
        searchAll();
        best = firstSatisfying(req);
        if (best != null) return best;
        throw new IOException("自动下载后仍未找到满足要求的 Java（需要 " + req[0]
                + (req[1] == Integer.MAX_VALUE ? "+" : "~" + req[1]) + "）。");
    }

    /** 返回排序后第一个满足要求的运行时（列表已按 Java 21 优先排序）。 */
    private static JavaRuntime firstSatisfying(int[] req) {
        for (JavaRuntime rt : cache) {
            if (satisfies(rt, req)) return rt;
        }
        return null;
    }

    public static List<JavaRuntime> cached() {
        return new ArrayList<>(cache);
    }
}
