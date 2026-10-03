import pcl.minecraft.*;
import pcl.base.*;

import java.nio.file.Path;

/** 端到端启动测试（手动运行）：java -cp target/PCLJ.jar LaunchTest <游戏目录> <版本名> */
public final class LaunchTest {

    public static void main(String[] args) throws Exception {
        Path tmp = Path.of(args[0]);
        String versionName = args.length > 1 ? args[1] : "1.20.6";
        System.out.println("TESTDIR: " + tmp);

        McVersion v = McVersion.load(tmp.resolve("versions").resolve(versionName), versionName);
        String uuid = LoginService.offlineUuid("PCLJTest");
        LoginService.LoginResult login = new LoginService.LoginResult("PCLJTest", uuid, uuid, LoginService.Type.OFFLINE);
        ArgsBuilder.LaunchOptions opts = new ArgsBuilder.LaunchOptions(v, tmp, null, login,
                v.gameDirectory(tmp, true), v.nativesDir(), "", 854, 480, false, null);

        long t0 = System.currentTimeMillis();
        try {
            LaunchPipeline.LaunchResult result = LaunchPipeline.launch(opts, (p, s) ->
                    System.out.printf("[PROGRESS %.0f%%] %s%n", p * 100, s));
            System.out.println("LAUNCH-OK elapsed=" + (System.currentTimeMillis() - t0) / 1000 + "s");
            for (int i = 0; i < 6; i++) {
                Thread.sleep(10000);
                System.out.println("t+" + ((i + 1) * 10) + "s alive=" + result.process().isAlive());
            }
            result.process().destroy();
        } catch (Exception e) {
            System.out.println("LAUNCH-FAIL: " + e);
            e.printStackTrace();
        }
        System.exit(0);
    }
}
