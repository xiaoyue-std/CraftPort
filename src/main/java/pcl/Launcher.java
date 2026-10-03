package pcl;

import javafx.application.Application;

/**
 * 启动入口（不继承 Application）。
 * 从 classpath（非模块路径）运行 JavaFX 时，主类必须是普通类，
 * 否则会报"缺少 JavaFX 运行时组件"，因此由这里转交给 pcl.ui.MainApp。
 */
public final class Launcher {

    public static void main(String[] args) {
        // 子命令分发：cli 进入纯英文命令行模式（不启动 JavaFX）
        if (args.length > 0 && "cli".equals(args[0])) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            pcl.cli.CliMain.main(rest);
            return;
        }
        Application.launch(pcl.ui.MainApp.class, args);
    }

    private Launcher() {}
}
