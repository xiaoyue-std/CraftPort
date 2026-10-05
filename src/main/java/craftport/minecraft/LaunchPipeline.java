package craftport.minecraft;

import craftport.base.Log;
import craftport.base.Os;
import craftport.base.Task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动流水线，移植自 ModLaunch.vb 的 McLaunchSteps（LoaderCombo 编排）：
 * 预检测 → 获取 Java → 补全文件 → 生成参数 → 解压 Natives → 版本特判(语言) → 启动进程。
 */
public final class LaunchPipeline {

    /** 启动结果。 */
    public record LaunchResult(Process process, List<String> command) {}

    private LaunchPipeline() {}

    /**
     * 执行启动流程（后台线程调用）。
     *
     * @param options   启动选项（版本/目录/Java/登录信息等）
     * @param onLog     日志回调
     * @return 启动结果
     */
    public static LaunchResult launch(ArgsBuilder.LaunchOptions options,
                                      java.util.function.BiConsumer<Double, String> onProgress) throws Exception {
        McVersion version = options.version();
        Path mcRoot = options.mcRoot();

        // ---- 步骤 0：预检测（对应 McLaunchPreCheck）----
        report(onProgress, 0.02, "预检测");
        if (Os.IS_WINDOWS) {
            // `!` 与 `;` 在 Windows classpath 中有特殊含义（对应 PCL2 的检查）；Linux 上无此问题
            String root = mcRoot.toString();
            if (root.contains("!") || root.contains(";")) {
                throw new IOException("游戏路径包含特殊字符 ! 或 ;，请移动 .minecraft 后重试");
            }
        }
        McFolder.ensureProfile(mcRoot);

        // ---- 步骤 1：获取 Java（权重 4）----
        report(onProgress, 0.08, "获取 Java");
        String javaSetting = craftport.base.Config.vStr(version.name(), "VersionArgumentJava",
                craftport.base.Config.get("CacheJavaPath", ""));
        JavaRuntime java = JavaManager.pick(version, javaSetting);
        Log.info("使用 " + java);
        options = new ArgsBuilder.LaunchOptions(version, mcRoot, java, options.login(),
                options.gameDir(), options.nativesDir(), options.extraGameArgs(),
                options.windowWidth(), options.windowHeight(), options.fullscreen(),
                options.serverAddress());

        // ---- 步骤 2：登录校验（登录已在 UI 层完成，这里仅校验）----
        report(onProgress, 0.15, "校验登录信息");
        if (options.login() == null) throw new IOException("尚未登录，请先完成登录");

        // ---- 步骤 3：补全文件（权重 15，对应 DlClientFix）----
        report(onProgress, 0.20, "检查客户端文件");
        FileCompleter.completeClient(version, mcRoot, null);
        report(onProgress, 0.30, "下载依赖库");
        FileCompleter.completeLibraries(version, mcRoot);
        report(onProgress, 0.55, "下载资源文件");
        FileCompleter.completeAssets(version, mcRoot, options.gameDir());
        report(onProgress, 0.70, "文件补全完成");

        // ---- 步骤 4：解压 Natives（权重 2）----
        report(onProgress, 0.74, "解压 Natives");
        FileCompleter.extractNatives(version, mcRoot);

        // ---- 步骤 5：老版本语言修复（对应 <=1.10 的 lang 大小写修正）----
        fixLegacyLang(version, options.gameDir());

        // ---- 步骤 6：生成启动参数（权重 2）----
        report(onProgress, 0.80, "生成启动参数");
        List<String> jvmArgs = ArgsBuilder.buildJvmArgs(options);
        List<String> gameArgs = ArgsBuilder.buildGameArgs(options);
        String mainClass = version.mainClass();

        // ---- 步骤 7：启动进程（权重 2）----
        report(onProgress, 0.90, "启动游戏");
        List<String> command = new ArrayList<>();
        command.add(java.executable().toString());
        command.addAll(jvmArgs);
        command.add(mainClass);
        command.addAll(gameArgs);
        Log.info("启动命令: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(options.gameDir().toFile());
        Process process = pb.start();
        GameProcess.attach(process);

        report(onProgress, 1.0, "游戏已启动");
        return new LaunchResult(process, command);
    }

    /** <=1.10 版本 options.txt 的 lang 需要后两位大写（否则 NPE）。 */
    private static void fixLegacyLang(McVersion version, Path gameDir) {
        try {
            String v = version.mcVersion();
            if (!v.matches("1\\.\\d+(\\.\\d+)?")) return; // 快照与远古版本跳过
            int minor = Integer.parseInt(v.split("\\.")[1]);
            if (minor > 10) return;
            Path options = gameDir.resolve("options.txt");
            if (!Files.exists(options)) return;
            String content = Files.readString(options, StandardCharsets.UTF_8);
            String fixed = java.util.regex.Pattern.compile("(?m)^lang:([a-z]{2})_([a-z]{2})$")
                    .matcher(content)
                    .replaceAll(m -> "lang:" + m.group(1) + "_" + m.group(2).toUpperCase());
            if (!fixed.equals(content)) {
                Files.writeString(options, fixed, StandardCharsets.UTF_8);
                Log.info("已修复老版本语言设置大小写");
            }
        } catch (Exception ignored) {
            // 版本号解析失败则跳过
        }
    }

    private static void report(java.util.function.BiConsumer<Double, String> onProgress,
                               double progress, String stage) {
        if (onProgress != null) onProgress.accept(progress, stage);
    }
}
