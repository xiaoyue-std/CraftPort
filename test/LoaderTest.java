import pcl.minecraft.*;
import pcl.base.*;

import java.nio.file.Files;
import java.nio.file.Path;

/** 加载器安装全流程测试：java -cp target/PCLJ.jar LoaderTest <游戏目录> <MC版本> <fabric|optifine|forge|all> */
public final class LoaderTest {

    public static void main(String[] args) throws Exception {
        Path mcRoot = Path.of(args[0]);
        String mc = args.length > 1 ? args[1] : "1.20.6";
        String what = args.length > 2 ? args[2] : "all";

        McFolder.setRoot(mcRoot);
        McFolder.ensureProfile(mcRoot);

        // 原版 json（仅元数据，正式文件在启动时补全）
        System.out.println("== [1] 安装原版 " + mc + " json ==");
        InstallService.install(mc, mcRoot);
        System.out.println("OK: " + Files.exists(mcRoot.resolve("versions/" + mc + "/" + mc + ".json")));

        String fabricName = null, forgeName = null;
        if (what.equals("all") || what.equals("fabric")) {
            System.out.println("== [2] Fabric 版本列表 ==");
            var loaders = ModLoader.fetchLoaders(ModLoader.Kind.FABRIC, mc);
            System.out.println("数量: " + loaders.size() + "，最新: " + (loaders.isEmpty() ? "-" : loaders.get(0)));
            System.out.println("== [3] 安装 Fabric ==");
            fabricName = ModLoader.install(ModLoader.Kind.FABRIC, mc, loaders.get(0).version(), mcRoot);
            System.out.println("OK: " + fabricName);
            System.out.println("== [4] 启动 Fabric 版本（同时补全原版客户端/库/资源）==");
            launchAndWatch(mcRoot, fabricName, 75);
        }
        if (what.equals("all") || what.equals("optifine")) {
            System.out.println("== [5] OptiFine 安装（官方无头安装器，需原版客户端 jar）==");
            try {
                var loaders = ModLoader.fetchLoaders(ModLoader.Kind.OPTIFINE, mc);
                // 优先选正式版
                var pick = loaders.stream().filter(l -> l.detail().equals("正式")).findFirst()
                        .orElse(loaders.isEmpty() ? null : loaders.get(0));
                if (pick == null) throw new IllegalStateException("没有可用的 OptiFine 版本");
                System.out.println("选择: " + pick);
                String name = ModLoader.install(ModLoader.Kind.OPTIFINE, mc, pick.version(), mcRoot);
                System.out.println("OK: " + name);
            } catch (Exception e) {
                System.out.println("OPTIFINE-FAIL: " + e.getMessage());
            }
        }
        if (what.equals("all") || what.equals("forge")) {
            System.out.println("== [6] Forge 版本列表 ==");
            var loaders = ModLoader.fetchLoaders(ModLoader.Kind.FORGE, mc);
            System.out.println("数量: " + loaders.size() + "，最新: " + (loaders.isEmpty() ? "-" : loaders.get(0)));
            System.out.println("== [7] 安装 Forge ==");
            forgeName = ModLoader.install(ModLoader.Kind.FORGE, mc, loaders.get(0).version(), mcRoot);
            System.out.println("OK: " + forgeName);
            System.out.println("== [8] 启动 Forge 版本（首次启动含补丁与 FML 引导，较慢）==");
            launchAndWatch(mcRoot, forgeName, 150);
        }
        System.out.println("ALL-DONE");
        System.exit(0);
    }

    private static void launchAndWatch(Path mcRoot, String versionName, int watchSeconds) throws Exception {
        McVersion v = McVersion.load(mcRoot.resolve("versions").resolve(versionName), versionName);
        if (v == null) throw new IllegalStateException("版本加载失败: " + versionName);
        String uuid = LoginService.offlineUuid("PCLJTest");
        var login = new LoginService.LoginResult("PCLJTest", uuid, uuid, LoginService.Type.OFFLINE);
        var opts = new ArgsBuilder.LaunchOptions(v, mcRoot, null, login,
                v.gameDirectory(mcRoot, true), v.nativesDir(), "", 854, 480, false, null);
        long t0 = System.currentTimeMillis();
        try {
            var result = LaunchPipeline.launch(opts, (p, s) ->
                    System.out.printf("[%.0f%%] %s%n", p * 100, s));
            System.out.println("LAUNCH-OK " + versionName + " 用时 " + (System.currentTimeMillis() - t0) / 1000 + "s");
            // 输出游戏早期日志关键行
            GameProcess.addLogListener(line -> {
                if (line.contains("ERROR") || line.contains("Exception") || line.contains("Backend")
                        || line.contains("Render") || line.contains("Fabric") || line.contains("Forge")
                        || line.contains("Loading") || line.contains("Transformation"))
                    System.out.println("  [游戏] " + line);
            });
            for (int i = 0; i < watchSeconds / 5; i++) {
                Thread.sleep(5000);
                boolean alive = result.process().isAlive();
                System.out.println("t+" + ((i + 1) * 5) + "s alive=" + alive);
                if (!alive) {
                    System.out.println("EXIT-CODE=" + result.process().exitValue());
                    break;
                }
            }
            if (result.process().isAlive()) {
                System.out.println("游戏保持运行中，结束测试进程。");
                result.process().destroy();
            }
        } catch (Exception e) {
            System.out.println("LAUNCH-FAIL: " + e);
        }
    }
}
