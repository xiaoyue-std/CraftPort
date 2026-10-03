package pcl.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import pcl.base.Config;
import pcl.base.Log;
import pcl.base.Os;
import pcl.base.Task;
import pcl.minecraft.*;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 启动页（对照 PCL2 的 PageLaunchLeft 布局重构）：
 *  - 顶部居中的大版本选择框（PCL2 标志性设计）
 *  - 登录信息区：像素头像（由 UUID 生成的 Identicon）+ 用户名 + 登录按钮
 *  - 居中的大启动按钮，进度直接显示在按钮上（PCL2 的按钮三态）
 *  - 底部游戏日志区
 */
public class PageLaunch {

    private final MainApp main;
    private final VBox root = new VBox(12);
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final TextField usernameField = new TextField();
    private final Canvas avatar = new Canvas(44, 44);
    private final Label userLabel = new Label();
    private final Button launchButton = new Button("启动游戏");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("就绪");
    private final TextArea logArea = new TextArea();
    private final Consumer<String> logListener = line -> Platform.runLater(() -> {
        logArea.appendText(line + System.lineSeparator());
        logArea.setScrollTop(Double.MAX_VALUE);
    });

    private volatile boolean launching;

    public PageLaunch(MainApp main) {
        this.main = main;
        root.setPadding(new Insets(16, 16, 12, 16));

        // ---- 顶部：居中的大版本选择（对应 PCL2 启动页顶部的版本下拉框，带版本图标）----
        versionBox.setPromptText("选择要启动的版本");
        versionBox.getStyleClass().add("version-select");
        versionBox.setMaxWidth(460);
        versionBox.setPrefHeight(42);
        versionBox.setButtonCell(versionCell());
        versionBox.setCellFactory(v -> versionCell());
        Button refreshButton = new Button("刷新");
        refreshButton.setOnAction(e -> refreshVersions());
        HBox versionRow = new HBox(10, versionBox, refreshButton);
        versionRow.setAlignment(Pos.CENTER);

        // ---- 登录信息区（对应 PCL2 的头像 + 用户名）----
        StackPane avatarBox = new StackPane(avatar);
        avatarBox.getStyleClass().add("avatar-frame");
        usernameField.setPromptText("输入离线用户名");
        HBox.setHgrow(usernameField, Priority.ALWAYS);
        Button loginButton = new Button("离线登录");
        loginButton.getStyleClass().add("accent-button");
        loginButton.setOnAction(e -> onLogin());
        HBox loginRow = new HBox(10, avatarBox, usernameField, loginButton);
        loginRow.setAlignment(Pos.CENTER_LEFT);
        loginRow.setMaxWidth(560);
        VBox loginCard = Ui.card("登录信息");
        userLabel.getStyleClass().add("hint");
        loginCard.getChildren().addAll(loginRow, userLabel);
        loginCard.setMaxWidth(620);

        VBox topCard = Ui.card(null);
        topCard.getChildren().addAll(versionRow, loginCard);
        topCard.setAlignment(Pos.CENTER);
        VBox centerWrapper = new VBox(topCard);
        centerWrapper.setAlignment(Pos.CENTER);

        // ---- 大启动按钮（对应 PCL2 的巨大圆角启动按钮：悬停发光+微缩放由 Java 动画驱动）----
        launchButton.getStyleClass().add("launch-button");
        launchButton.setMaxWidth(620);
        launchButton.setPrefHeight(64);
        launchButton.setOnAction(e -> onLaunch());
        Animate.attachScale(launchButton, 1.015);
        Animate.attachGlow(launchButton, javafx.scene.paint.Color.web("#3f8be8", 0.45), 10, 24);
        progressBar.setMaxWidth(620);
        progressBar.setPrefHeight(6);
        progressBar.setVisible(false);
        statusLabel.getStyleClass().add("hint");
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        VBox launchBox = new VBox(8, launchButton, progressBar, statusLabel);
        launchBox.setAlignment(Pos.CENTER);
        launchBox.setMaxWidth(660);
        VBox launchCard = new VBox(launchBox);
        launchCard.getStyleClass().add("card");
        launchCard.setPadding(new Insets(18));
        launchCard.setAlignment(Pos.CENTER);

        // ---- 底部日志区 ----
        VBox logCard = Ui.card("游戏日志");
        HBox logBtns = new HBox(8, clearButton(), killButton(), openDirButton());
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);
        logCard.getChildren().addAll(logBtns, logArea);
        VBox.setVgrow(logCard, Priority.ALWAYS);

        root.getChildren().addAll(centerWrapper, launchCard, logCard);
        VBox.setVgrow(logCard, Priority.ALWAYS);
    }

    /** 版本下拉的单元格：草方块/加载器图标 + 版本名。 */
    private ListCell<String> versionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setGraphic(Icons.forVersion(item, 18));
                }
            }
        };
    }

    public VBox root() { return root; }

    /** 根据 UUID 生成 8×8 像素风 Identicon 头像（离线无皮肤服务时的替代展示）。 */
    private void drawAvatar(String uuid) {
        GraphicsContext g = avatar.getGraphicsContext2D();
        g.clearRect(0, 0, 44, 44);
        String seed = (uuid == null || uuid.isBlank()) ? "PCLJ" : uuid.replace("-", "");
        // 颜色由 UUID 前几位决定
        int hue = Integer.parseUnsignedInt(seed.substring(0, Math.min(4, seed.length())), 16) % 360;
        Color main = Color.hsb(hue, 0.55, 0.75);
        Color bg = Color.hsb(hue, 0.12, 0.94);
        g.setFill(bg);
        g.fillRect(0, 0, 44, 44);
        g.setFill(main);
        int cell = 5; // 8 格 x 5px ≈ 40px
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 8; y++) {
                int idx = (y * 4 + x) % seed.length();
                char c = seed.charAt(idx);
                if ((c + y * 7 + x * 13) % 3 == 0) {
                    g.fillRect(x * cell + 2, y * cell + 2, cell - 1, cell - 1);
                    g.fillRect((7 - x) * cell + 2, y * cell + 2, cell - 1, cell - 1); // 镜像
                }
            }
        }
    }

    private void onLogin() {
        String name = usernameField.getText().trim();
        if (name.isEmpty()) {
            Theme.warn("登录", "请输入用户名");
            return;
        }
        LoginService.LoginResult r = LoginService.offlineLogin(name);
        Config.cacheUsername = r.username();
        Config.cacheUuid = r.uuid();
        Config.cacheAccessToken = r.accessToken();
        Config.save();
        refreshUserLabel();
        Theme.info("登录成功", "欢迎，" + r.username() + "\nUUID: " + r.uuid());
    }

    private Button clearButton() {
        Button btn = new Button("清空日志");
        btn.setOnAction(e -> logArea.clear());
        return btn;
    }

    private Button killButton() {
        Button btn = new Button("结束游戏");
        btn.setOnAction(e -> {
            if (GameProcess.isRunning()) {
                GameProcess.kill();
            } else {
                Theme.warn("结束游戏", "游戏未在运行");
            }
        });
        return btn;
    }

    private Button openDirButton() {
        Button btn = new Button("打开游戏目录");
        btn.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().open(McFolder.selectedRoot().toFile());
            } catch (Exception ex) {
                Theme.warn("打开目录", "无法打开: " + ex.getMessage());
            }
        });
        return btn;
    }

    /** 页面显示时刷新版本与用户。 */
    public void onShown() {
        refreshVersions();
        usernameField.setText(Config.cacheUsername);
        refreshUserLabel();
        GameProcess.removeLogListener(logListener);
        GameProcess.addLogListener(logListener);
    }

    private void refreshUserLabel() {
        String name = Config.cacheUsername;
        userLabel.setText(name == null || name.isBlank() ? "尚未登录（离线模式）" : "当前用户：" + name);
        drawAvatar(Config.cacheUuid);
    }

    private void refreshVersions() {
        versionBox.getItems().clear();
        Path root = McFolder.selectedRoot();
        List<McVersion> versions = McFolder.scanVersions(root);
        if (versions.isEmpty()) {
            statusLabel.setText(McFolder.emptyHint());
            versionBox.setPromptText("无可用版本，请先到「下载游戏」页安装");
        } else {
            for (McVersion v : versions) versionBox.getItems().add(v.name());
            String last = Config.cacheVersion;
            if (last != null && versionBox.getItems().contains(last)) {
                versionBox.setValue(last);
            } else {
                versionBox.getSelectionModel().selectFirst();
            }
        }
    }

    private void onLaunch() {
        if (launching) return;
        String versionName = versionBox.getValue();
        if (versionName == null || versionName.isBlank()) {
            Theme.warn("启动", "请先选择一个版本；没有版本请先到「下载游戏」页安装。");
            return;
        }
        if (Config.cacheUsername == null || Config.cacheUsername.isBlank()) {
            Theme.warn("启动", "请先登录（当前支持离线登录）。");
            return;
        }
        launching = true;
        launchButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("正在启动…");

        Task.POOL.submit(() -> {
            try {
                Path mcRoot = McFolder.selectedRoot();
                McVersion version = McVersion.load(mcRoot.resolve("versions").resolve(versionName), versionName);
                if (version == null) throw new java.io.IOException("版本 " + versionName + " 的 json 缺失或损坏");
                boolean isolation = isolationFor(version);
                Path gameDir = version.gameDirectory(mcRoot, isolation);

                ArgsBuilder.LaunchOptions options = new ArgsBuilder.LaunchOptions(
                        version, mcRoot, null,
                        new LoginService.LoginResult(Config.cacheUsername, Config.cacheUuid,
                                Config.cacheAccessToken, LoginService.Type.OFFLINE),
                        gameDir, version.nativesDir(),
                        Config.get(Config.LAUNCH_ADVANCE_GAME, ""),
                        Config.getInt(Config.LAUNCH_ARGUMENT_WIDTH, 854),
                        Config.getInt(Config.LAUNCH_ARGUMENT_HEIGHT, 480),
                        Config.getInt(Config.LAUNCH_ARGUMENT_WINDOW_TYPE, 2) == 0,
                        null);

                LaunchPipeline.launch(options, (progress, stage) ->
                        Platform.runLater(() -> {
                            Animate.smoothProgress(progressBar, progress);
                            statusLabel.setText(stage);
                            launchButton.setText(progress >= 1 ? "游戏运行中" : "启动中 " + (int) (progress * 100) + "%");
                        }));

                Config.cacheVersion = versionName;
                Config.save();
                Platform.runLater(() -> {
                    statusLabel.setText("游戏已启动");
                    applyLauncherVisibility();
                });
            } catch (Exception e) {
                Log.error("启动失败", e);
                Platform.runLater(() -> {
                    statusLabel.setText("启动失败");
                    launchButton.setText("启动游戏");
                    Theme.error("启动失败", e.getMessage() == null ? e.toString() : e.getMessage());
                });
            } finally {
                launching = false;
                Platform.runLater(() -> {
                    launchButton.setDisable(false);
                    progressBar.setVisible(false);
                });
            }
        });
    }

    /** 版本隔离判定（对应 VersionArgumentIndieV2 的全局策略）。 */
    private boolean isolationFor(McVersion version) {
        int policy = Config.getInt(Config.LAUNCH_ARGUMENT_INDIE, 4);
        return switch (policy) {
            case 0 -> false;
            case 1 -> version.isSnapshot() || hasModFolder(version);
            case 2 -> version.isSnapshot() || version.isOld();
            case 3 -> version.isSnapshot() || version.isOld() || hasModFolder(version);
            default -> true; // 4：全部隔离（PCL2 默认）
        };
    }

    private boolean hasModFolder(McVersion version) {
        return java.nio.file.Files.isDirectory(version.folder.resolve("mods"));
    }

    /** 启动器可见性（对应 LaunchArgumentVisible）。 */
    private void applyLauncherVisibility() {
        int visible = Config.getInt(Config.LAUNCH_ARGUMENT_VISIBLE, 5);
        Stage stage = (Stage) root.getScene().getWindow();
        switch (visible) {
            case 0 -> Platform.runLater(stage::close);
            case 2 -> stage.hide();
            case 4 -> stage.setIconified(true);
            default -> {} // 5 不变
        }
    }
}
