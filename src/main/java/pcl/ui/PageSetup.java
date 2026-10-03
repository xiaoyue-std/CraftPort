package pcl.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import pcl.base.Config;
import pcl.base.Os;
import pcl.minecraft.JavaManager;
import pcl.minecraft.JavaRuntime;
import pcl.minecraft.McFolder;
import pcl.base.Task;

import java.nio.file.Path;
import java.util.List;

/**
 * 设置页，移植自 Pages/PageSetup/PageSetupLaunch.xaml 等设置页。
 * 下载源 / 线程 / 内存 / GC / 版本隔离 / JVM 参数 / 游戏目录 / Java 管理。
 */
public class PageSetup {

    private final VBox root = new VBox(12);
    private final TextField dirField = new TextField();
    private final TextField jvmField = new TextField();
    private final TextField javaField = new TextField();
    private final ListView<JavaRuntime> javaList = new ListView<>();
    private final ComboBox<String> sourceBox = new ComboBox<>();
    private final ComboBox<String> gcBox = new ComboBox<>();
    private final ComboBox<String> indieBox = new ComboBox<>();
    private final CheckBox autoRam = new CheckBox("自动分配内存");
    private final Spinner<Integer> ramSpinner = new Spinner<>(512, 32768, 4096, 256);
    private final Spinner<Integer> threadSpinner = new Spinner<>(1, 128, 32);

    public PageSetup(MainApp main) {
        root.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");

        VBox dirCard = card("游戏目录");
        dirField.setText(McFolder.selectedRoot().toString());
        HBox dirRow = new HBox(10, dirField, browseButton(), useDefaultButton());
        HBox.setHgrow(dirField, Priority.ALWAYS);
        dirCard.getChildren().addAll(dirRow, hint("支持多目录：修改后启动器将在此目录查找 versions 下的版本。"));

        VBox downloadCard = card("下载设置");
        sourceBox.getItems().addAll("镜像优先（BMCLAPI）", "自动（官方优先）", "官方优先");
        int source = Config.getInt(Config.TOOL_DOWNLOAD_SOURCE, 1);
        sourceBox.getSelectionModel().select(source == 0 ? 0 : source == 2 ? 2 : 1);
        sourceBox.setOnAction(e -> Config.setInt(Config.TOOL_DOWNLOAD_SOURCE,
                sourceBox.getSelectionModel().getSelectedIndex() == 0 ? 0
                        : sourceBox.getSelectionModel().getSelectedIndex() == 2 ? 2 : 1));
        HBox threadRow = new HBox(10, new Label("下载线程数"), threadSpinner);
        threadSpinner.valueProperty().addListener((o, ov, nv) -> Config.setInt(Config.TOOL_DOWNLOAD_THREAD, nv));
        threadSpinner.getValueFactory().setValue(Config.getInt(Config.TOOL_DOWNLOAD_THREAD, 32));
        downloadCard.getChildren().addAll(new HBox(10, new Label("下载源"), sourceBox), threadRow,
                hint("BMCLAPI 镜像可加速国内访问；下载策略与 PCL2 的 ToolDownloadSource 一致。"));

        VBox memoryCard = card("内存");
        autoRam.setSelected(Config.getInt(Config.LAUNCH_RAM_TYPE, 0) == 0);
        ramSpinner.getValueFactory().setValue(
                Config.getInt(Config.LAUNCH_RAM_CUSTOM, defaultRam()));
        ramSpinner.disableProperty().bind(autoRam.selectedProperty());
        autoRam.setOnAction(e -> Config.setInt(Config.LAUNCH_RAM_TYPE, autoRam.isSelected() ? 0 : 1));
        ramSpinner.valueProperty().addListener((o, ov, nv) -> Config.setInt(Config.LAUNCH_RAM_CUSTOM, nv));
        memoryCard.getChildren().addAll(autoRam, new HBox(10, new Label("自定义内存 (MB)"), ramSpinner),
                hint("自动模式按物理内存的 40% 分配（限 1~8GB）。"));

        VBox javaCard = card("Java 运行时");
        Button detectButton = new Button("重新检测");
        detectButton.getStyleClass().add("accent-button");
        detectButton.setOnAction(e -> detectJavas());
        javaField.setPromptText("（可选）指定 java 可执行文件路径，留空为自动选择");
        javaField.setText(Config.get("CacheJavaPath", ""));
        javaField.textProperty().addListener((o, ov, nv) -> Config.set("CacheJavaPath", nv == null ? "" : nv.trim()));
        javaList.setPrefHeight(140);
        javaList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(JavaRuntime item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
        javaCard.getChildren().addAll(new HBox(10, detectButton), javaField, javaList,
                hint("自动模式优先使用 Java 21；没有合适版本时自动从 Mojang 镜像下载运行时。已自动扫描 PATH、"
                        + "JAVA_HOME、" + (Os.IS_LINUX ? "/usr/lib/jvm、~/.sdkman 等" : "Program Files 等") + "常见位置。"));

        VBox advanceCard = card("高级启动设置");
        gcBox.getItems().addAll("不优化", "G1GC", "优化 G1GC（推荐）", "ZGC");
        int gc = Config.getInt(Config.LAUNCH_ADVANCE_GC, 4);
        gcBox.getSelectionModel().select(gc == 0 ? 0 : gc == 1 ? 1 : gc == 5 ? 3 : 2);
        gcBox.setOnAction(e -> {
            int idx = gcBox.getSelectionModel().getSelectedIndex();
            Config.setInt(Config.LAUNCH_ADVANCE_GC, idx == 0 ? 0 : idx == 1 ? 1 : idx == 3 ? 5 : 4);
        });
        indieBox.getItems().addAll("全部隔离（推荐）", "仅可装 Mod 的版本", "仅非正式版", "两者", "关闭");
        int indie = Config.getInt(Config.LAUNCH_ARGUMENT_INDIE, 4);
        indieBox.getSelectionModel().select(indie == 0 ? 4 : indie == 1 ? 1 : indie == 2 ? 2 : indie == 3 ? 3 : 0);
        indieBox.setOnAction(e -> {
            int idx = indieBox.getSelectionModel().getSelectedIndex();
            Config.setInt(Config.LAUNCH_ARGUMENT_INDIE, idx == 4 ? 0 : idx == 1 ? 1 : idx == 2 ? 2 : idx == 3 ? 3 : 4);
        });
        jvmField.setText(Config.get(Config.LAUNCH_ADVANCE_JVM, Config.DEFAULT_JVM_ARGS));
        jvmField.textProperty().addListener((o, ov, nv) -> Config.set(Config.LAUNCH_ADVANCE_JVM, nv));
        advanceCard.getChildren().addAll(
                new HBox(10, new Label("GC 优化"), gcBox),
                new HBox(10, new Label("版本隔离"), indieBox),
                new HBox(10, new Label("JVM 参数"), jvmField),
                hint("版本隔离开启时，每个版本拥有独立的 saves/mods/config 目录（对应 PCL2 的版本隔离）。"));

        scroll.setContent(new VBox(12, dirCard, downloadCard, memoryCard, javaCard, advanceCard));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);
        VBox.setVgrow(root, Priority.ALWAYS);

        detectJavas();
    }

    public VBox root() { return root; }

    private int defaultRam() {
        return pcl.minecraft.ArgsBuilder.autoRamMb();
    }

    private VBox card(String title) {
        return Ui.card(title);
    }

    private Label hint(String text) {
        return Ui.hint(text);
    }

    private Button browseButton() {
        Button btn = new Button("浏览…");
        btn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择 .minecraft 目录");
            java.io.File dir = chooser.showDialog(root.getScene().getWindow());
            if (dir != null) {
                dirField.setText(dir.getAbsolutePath());
                McFolder.setRoot(Path.of(dir.getAbsolutePath()));
            }
        });
        return btn;
    }

    private Button useDefaultButton() {
        Button btn = new Button("恢复默认");
        btn.setOnAction(e -> {
            dirField.setText(Os.defaultMinecraftDir().toString());
            McFolder.setRoot(Os.defaultMinecraftDir());
        });
        return btn;
    }

    private void detectJavas() {
        javaList.getItems().clear();
        Task.POOL.submit(() -> {
            JavaManager.invalidate();
            List<JavaRuntime> found = JavaManager.searchAll();
            javafx.application.Platform.runLater(() -> javaList.getItems().setAll(found));
        });
    }
}
