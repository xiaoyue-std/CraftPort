package pcl.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import pcl.base.Log;
import pcl.base.Os;

/**
 * 主窗口，移植自 FormMain.xaml / Application.xaml.vb。
 * 布局：左侧雪山背景导航栏（PCL2 标志性设计）+ 右侧页面栈（启动/下载/设置，带过渡动画）。
 */
public class MainApp extends Application {

    private StackPane pageStack;
    private PageLaunch pageLaunch;
    private PageDownload pageDownload;
    private PageSetup pageSetup;
    private final Button[] navButtons = new Button[3];
    /** 当前显示的页面索引（页面切换动画的方向依据）。 */
    private int shownIndex;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Log.initFile(Os.dataDir());
        Log.info("PCL Java Edition 启动，平台: " + Os.OS_NAME + "/" + Os.OS_ARCH
                + "，数据目录: " + Os.dataDir());

        // 无边框窗口：使用自定义标题栏（对应 PCL2 的自绘窗口边框）
        stage.initStyle(StageStyle.UNDECORATED);

        BorderPane root = new BorderPane();
        root.setTop(buildTitleBar(stage));

        // ---- 左侧栏：雪山背景 + 导航（程序化绘制替代 PCL2 的雪景图）----
        Canvas bg = new Canvas();
        VBox navBox = new VBox(6);
        navBox.setPadding(new Insets(18, 12, 12, 12));

        Text logo = new Text("PCL");
        logo.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, 30));
        logo.setFill(Color.WHITE);
        logo.setEffect(new DropShadow(8, Color.rgb(30, 60, 100, 0.5)));
        Text subtitle = new Text("Java Edition");
        subtitle.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, 12));
        subtitle.setFill(Color.rgb(255, 255, 255, 0.92));
        VBox logoBox = new VBox(0, logo, subtitle);
        logoBox.setAlignment(Pos.CENTER);
        logoBox.setPadding(new Insets(4, 0, 16, 0));

        Button navLaunch = nav("启动游戏", Icons.playIcon(), 0);
        Button navDownload = nav("下载游戏", Icons.downloadIcon(), 1);
        Button navSetup = nav("启动器设置", Icons.gearIcon(), 2);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        String javaVer = System.getProperty("java.version");
        Label info = new Label("PCLJ 1.0\nJava " + (javaVer.matches("\\d+.*") ? javaVer.split("\\.")[0] : javaVer));
        info.getStyleClass().add("sidebar-info");

        navBox.getChildren().addAll(logoBox, navLaunch, navDownload, navSetup, spacer, info);
        Animate.attachScale(navLaunch, 1.04);
        Animate.attachScale(navDownload, 1.04);
        Animate.attachScale(navSetup, 1.04);

        StackPane sidebar = new StackPane(bg, navBox);
        sidebar.setPrefWidth(172);
        bg.widthProperty().bind(sidebar.widthProperty());
        bg.heightProperty().bind(sidebar.heightProperty());
        Runnable paint = () -> Icons.paintMountain(bg);
        bg.widthProperty().addListener((o, ov, nv) -> paint.run());
        bg.heightProperty().addListener((o, ov, nv) -> paint.run());
        root.setLeft(sidebar);

        // ---- 云层动画：透明覆盖画布 + AnimationTimer 驱动缓慢漂移 ----
        Canvas clouds = new Canvas();
        clouds.setMouseTransparent(true);
        clouds.widthProperty().bind(sidebar.widthProperty());
        clouds.heightProperty().bind(sidebar.heightProperty());
        sidebar.getChildren().add(1, clouds);
        new AnimationTimer() {
            private long startNanos = -1;
            @Override
            public void handle(long now) {
                if (startNanos < 0) startNanos = now;
                double t = (now - startNanos) / 1e9;
                if (clouds.getWidth() > 0) {
                    Icons.paintClouds(clouds.getGraphicsContext2D(),
                            clouds.getWidth(), clouds.getHeight(), t);
                }
            }
        }.start();

        // ---- 页面 ----
        pageLaunch = new PageLaunch(this);
        pageDownload = new PageDownload(this);
        pageSetup = new PageSetup(this);
        pageStack = new StackPane(pageLaunch.root());
        root.setCenter(pageStack);

        attachResize(stage, root);

        Scene scene = new Scene(root, 1020, 700);
        Theme.apply(scene);
        stage.setTitle("Plain Craft Launcher Java Edition");
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(e -> {
            if (pcl.minecraft.GameProcess.isRunning()) {
                Log.info("启动器关闭，游戏进程保持运行");
            }
        });
        stage.show();
        paint.run();

        selectPage(0);
        pageLaunch.onShown();

        // 测试快照模式：-Dpclj.snapshot=<目录> 时自动截取各页面为 PNG（用于自动化验证 UI）
        String snapDir = System.getProperty("pclj.snapshot");
        if (snapDir != null) {
            runSnapshots(snapDir);
        }
    }

    private void runSnapshots(String dir) {
        java.util.function.BiConsumer<String, javafx.scene.Node> shot = (name, node) -> {
            try {
                javafx.scene.image.WritableImage img = node.snapshot(new javafx.scene.SnapshotParameters(), null);
                int w = (int) img.getWidth(), h = (int) img.getHeight();
                var bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var pr = img.getPixelReader();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
                }
                javax.imageio.ImageIO.write(bi, "png", new java.io.File(dir, name + ".png"));
                Log.info("快照已保存: " + name);
            } catch (Exception e) {
                Log.error("快照失败: " + name, e);
            }
        };
        // 顺序：启动页 → 下载主页 → CurseForge 详情(自动搜索) → 设置页 → 退出（截取整个场景，含侧栏与标题栏）
        var seq = new javafx.animation.SequentialTransition();
        Object[][] steps = {
                {400L, (Runnable) () -> selectPage(0)},
                {400L, (Runnable) () -> shot.accept("1-launch", pageStack.getScene().getRoot())},
                {400L, (Runnable) () -> selectPage(1)},
                {400L, (Runnable) () -> shot.accept("2-download-home", pageStack.getScene().getRoot())},
                {400L, (Runnable) () -> pageDownload.openDetailForTest(3)},
                {75000L, (Runnable) () -> shot.accept("3-download-curseforge", pageStack.getScene().getRoot())},
                {400L, (Runnable) () -> selectPage(2)},
                {400L, (Runnable) () -> shot.accept("4-setup", pageStack.getScene().getRoot())},
                {100L, (Runnable) Platform::exit},
        };
        for (Object[] step : steps) {
            var p = new javafx.animation.PauseTransition(Duration.millis((Long) step[0]));
            p.setOnFinished(e -> ((Runnable) step[1]).run());
            seq.getChildren().add(p);
        }
        seq.play();
    }

    private Button nav(String text, Canvas icon, int index) {
        Button btn = new Button(text, icon);
        btn.getStyleClass().add("nav-button");
        btn.setOnAction(e -> selectPage(index));
        navButtons[index] = btn;
        return btn;
    }

    // ==================== 自定义标题栏（无边框窗口） ====================

    private double dragX, dragY;
    private boolean dragging;

    /** 自绘标题栏：标题 + 最小化/最大化/关闭，支持拖动移动与双击最大化。 */
    private HBox buildTitleBar(Stage stage) {
        Label title = new Label("Plain Craft Launcher Java Edition");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button min = new Button("—"), max = new Button("□"), close = new Button("✕");
        min.getStyleClass().add("title-button");
        max.getStyleClass().add("title-button");
        close.getStyleClass().addAll("title-button", "close");
        min.setOnAction(e -> stage.setIconified(true));
        max.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        close.setOnAction(e -> Platform.exit());

        HBox bar = new HBox(4, title, spacer, min, max, close);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        bar.setOnMousePressed(e -> {
            if (stage.isMaximized() || e.getClickCount() > 1) return;
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
            dragging = true;
        });
        bar.setOnMouseDragged(e -> {
            if (dragging && !stage.isMaximized()) {
                stage.setX(e.getScreenX() - dragX);
                stage.setY(e.getScreenY() - dragY);
            }
        });
        bar.setOnMouseReleased(e -> dragging = false);
        bar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) stage.setMaximized(!stage.isMaximized());
        });
        return bar;
    }

    // ==================== 边缘拖拽缩放（无边框窗口的窗口缩放） ====================

    private static final double EDGE = 5;
    private String resizeDir = "NONE";
    private double pressX, pressY, initX, initY, initW, initH;

    private void attachResize(Stage stage, Region root) {
        root.setOnMouseMoved(e -> {
            double w = root.getWidth(), h = root.getHeight();
            boolean north = e.getY() < EDGE, south = e.getY() > h - EDGE;
            boolean west = e.getX() < EDGE, east = e.getX() > w - EDGE;
            resizeDir = north && west ? "NW" : north && east ? "NE" : south && west ? "SW"
                    : south && east ? "SE" : north ? "N" : south ? "S" : west ? "W" : east ? "E" : "NONE";
            root.setCursor(switch (resizeDir) {
                case "N", "S" -> Cursor.V_RESIZE;
                case "E", "W" -> Cursor.H_RESIZE;
                case "NW", "SE" -> Cursor.NW_RESIZE;
                case "NE", "SW" -> Cursor.NE_RESIZE;
                default -> Cursor.DEFAULT;
            });
        });
        root.setOnMousePressed(e -> {
            if ("NONE".equals(resizeDir) || stage.isMaximized()) return;
            pressX = e.getScreenX();
            pressY = e.getScreenY();
            initX = stage.getX();
            initY = stage.getY();
            initW = stage.getWidth();
            initH = stage.getHeight();
        });
        root.setOnMouseDragged(e -> {
            if ("NONE".equals(resizeDir) || stage.isMaximized()) return;
            double dx = e.getScreenX() - pressX, dy = e.getScreenY() - pressY;
            double minW = stage.getMinWidth(), minH = stage.getMinHeight();
            switch (resizeDir) {
                case "N" -> { stage.setY(initY + dy); stage.setHeight(Math.max(minH, initH - dy)); }
                case "S" -> stage.setHeight(Math.max(minH, initH + dy));
                case "W" -> { stage.setX(initX + dx); stage.setWidth(Math.max(minW, initW - dx)); }
                case "E" -> stage.setWidth(Math.max(minW, initW + dx));
                case "NW" -> {
                    stage.setY(initY + dy); stage.setHeight(Math.max(minH, initH - dy));
                    stage.setX(initX + dx); stage.setWidth(Math.max(minW, initW - dx));
                }
                case "NE" -> {
                    stage.setY(initY + dy); stage.setHeight(Math.max(minH, initH - dy));
                    stage.setWidth(Math.max(minW, initW + dx));
                }
                case "SW" -> {
                    stage.setX(initX + dx); stage.setWidth(Math.max(minW, initW - dx));
                    stage.setHeight(Math.max(minH, initH + dy));
                }
                case "SE" -> {
                    stage.setWidth(Math.max(minW, initW + dx));
                    stage.setHeight(Math.max(minH, initH + dy));
                }
            }
        });
    }

    public void selectPage(int index) {
        javafx.scene.Parent node = switch (index) {
            case 0 -> pageLaunch.root();
            case 1 -> pageDownload.root();
            default -> pageSetup.root();
        };
        // 页面切换：按方向滑入淡入 + 卡片错落浮现（对应 PCL2 的页面切换动画）
        if (pageStack.getChildren().size() != 1 || pageStack.getChildren().get(0) != node) {
            pageStack.getChildren().setAll(node);
            Animate.pageIn(node, index > shownIndex);
            if (node instanceof VBox page) Animate.staggerIn(page);
        }
        shownIndex = index;
        if (index == 0) pageLaunch.onShown();
        if (index == 1) pageDownload.onShown();
        for (int i = 0; i < navButtons.length; i++) {
            if (navButtons[i] != null) navButtons[i].getStyleClass().remove("selected");
        }
        if (navButtons[index] != null) navButtons[index].getStyleClass().add("selected");
    }
}
