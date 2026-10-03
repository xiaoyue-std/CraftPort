package pcl.minecraft;

import java.io.File;
import java.lang.reflect.Method;

/**
 * OptiFine 官方安装器的无头调用入口。
 * 在独立子 JVM 中运行（java -cp PCLJ.jar:OptiFine安装器.jar pcl.minecraft.OptiFineInstallerRunner <.minecraft目录>），
 * 反射调用 optifine.Installer#doInstall(File)，由 OptiFine 自己完成安装
 * （包括新版安装器的 xdelta 客户端补丁），避免把补丁算法搬进启动器。
 */
public final class OptiFineInstallerRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: OptiFineInstallerRunner <.minecraft目录>");
            System.exit(2);
        }
        File mcDir = new File(args[0]);
        if (!mcDir.isDirectory()) {
            System.err.println("目录不存在: " + mcDir);
            System.exit(2);
        }
        Class<?> installer = Class.forName("optifine.Installer");
        Method doInstall = installer.getMethod("doInstall", File.class);
        doInstall.invoke(null, mcDir);
        System.out.println("OptiFine 官方安装完成");
        System.exit(0);
    }

    private OptiFineInstallerRunner() {}
}
