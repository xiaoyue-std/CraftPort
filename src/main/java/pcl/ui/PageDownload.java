package pcl.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import pcl.base.Log;
import pcl.base.Task;
import pcl.minecraft.CurseForge;
import pcl.minecraft.InstallService;
import pcl.minecraft.McFolder;
import pcl.minecraft.McVersion;
import pcl.minecraft.ModLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * 下载页（对照 PCL2 的 PageDownloadInst 布局）：
 *  - 主页为大图标磁贴网格（游戏本体分类 + 三个模组加载器），对应 PCL2 下载页的入口卡片
 *  - 点击磁贴进入详情页（列表 + 安装按钮），左上角可返回
 *  - 底部全局进度条与状态栏
 */
public class PageDownload {

    /** 下载分类。 */
    private enum Cat {
        RELEASE("正式版", "release"),
        FABRIC("Fabric", "fabric"),
        FORGE("Forge", "forge"),
        CURSEFORGE("CurseForge", "curseforge");

        final String title, icon;
        Cat(String title, String icon) { this.title = title; this.icon = icon; }
    }

    private final MainApp main;
    private final VBox root = new VBox(12);

    private final VBox contentBox = new VBox();       // 磁贴主页 / 详情页容器
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("就绪");

    // 共享控件
    private final ListView<InstallService.Release> releaseList = new ListView<>();
    private final ComboBox<String> mcCombo = new ComboBox<>();
    private final ListView<ModLoader.LoaderEntry> loaderList = new ListView<>();
    private final Button actionButton = new Button("安装");

    private List<InstallService.Release> manifest = List.of();
    private Cat current;
    private volatile boolean busy;

    public PageDownload(MainApp main) {
        this.main = main;
        root.setPadding(new Insets(16));
        root.getChildren().addAll(contentBox, bottomStrip());
        VBox.setVgrow(contentBox, Priority.ALWAYS);
        showHome();
    }

    public VBox root() { return root; }

    /** 快照测试入口：按分类序号打开详情页（Cat.values()[i]）；CurseForge 自动触发一次搜索。 */
    void openDetailForTest(int catIndex) {
        Cat c = Cat.values()[catIndex];
        openDetail(c);
        if (c == Cat.CURSEFORGE) {
            searchField.setText("JEI");
            doSearch();
        }
    }

    private VBox bottomStrip() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setPrefHeight(6);
        statusLabel.getStyleClass().add("hint");
        statusLabel.setWrapText(true);
        return new VBox(6, progressBar, statusLabel);
    }

    // ==================== 磁贴主页（对应 PCL2 下载页入口） ====================

    private void showHome() {
        current = null;
        VBox page = Ui.card("下载");
        page.getChildren().add(Ui.hint("选择要下载的内容；模组加载器会安装到已下载的游戏版本上。"));

        TilePane tiles = new TilePane(12, 12);
        tiles.setPrefColumns(3);
        tiles.setPadding(new Insets(8, 0, 4, 0));
        for (Cat c : Cat.values()) tiles.getChildren().add(tile(c));

        VBox tilesCard = new VBox(tiles);
        tilesCard.getStyleClass().add("card");
        page.getChildren().add(tilesCard);
        swapContent(page);
    }

    private Button tile(Cat c) {
        Label label = new Label(c.title);
        label.getStyleClass().add("tile-label");
        VBox content = new VBox(8, Icons.categoryIcon(c.icon, 44), label);
        content.setAlignment(Pos.CENTER);
        Button btn = new Button();
        btn.setGraphic(content);
        btn.getStyleClass().add("tile");
        btn.setPrefSize(158, 118);
        btn.setOnAction(e -> openDetail(c));
        // 悬停动画：轻微放大 + 发光增强（对应 PCL2 下载页磁贴的悬停效果）
        Animate.attachScale(btn, 1.03);
        Animate.attachGlow(btn, javafx.scene.paint.Color.web("#3f8be8", 0.30), 8, 18);
        return btn;
    }

    // ==================== 详情页 ====================

    private void openDetail(Cat c) {
        current = c;
        VBox page = new VBox(10);

        HBox titleRow = new HBox(10, backButton(), Ui.header(c.title));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox panelCard = new VBox(10);
        panelCard.getStyleClass().add("card");
        VBox.setVgrow(panelCard, Priority.ALWAYS);
        actionButton.setOnAction(e -> {
            switch (c) {
                case RELEASE -> installRelease();
                case FABRIC -> installLoader(ModLoader.Kind.FABRIC);
                case FORGE -> installLoader(ModLoader.Kind.FORGE);
                case CURSEFORGE -> installCurseFile();
            }
        });
        switch (c) {
            case RELEASE -> {
                actionButton.setText("安装所选版本");
                panelCard.getChildren().addAll(actionButtonRow("刷新列表"), gameList());
                if (manifest.isEmpty()) refresh();
                else applyFilter();
            }
            case FABRIC -> {
                actionButton.setText("安装 Fabric");
                panelCard.getChildren().addAll(actionButtonRow("刷新列表"),
                        loaderList(ModLoader.Kind.FABRIC, Ui.hint(
                                "Fabric 安装为独立版本（{版本}-fabric），依赖库将在首次启动时自动补全。")));
            }
            case FORGE -> {
                actionButton.setText("安装 Forge");
                panelCard.getChildren().addAll(actionButtonRow("刷新列表"),
                        loaderList(ModLoader.Kind.FORGE, Ui.hint(
                                "支持 1.13+（调用官方安装器，无头完成补丁处理）；1.12 及更早版本暂不支持。")));
            }
            case CURSEFORGE -> {
                actionButton.setText("下载安装所选文件");
                panelCard.getChildren().add(buildCursePanel());
            }
        }

        page.getChildren().addAll(titleRow, panelCard);
        VBox.setVgrow(page, Priority.ALWAYS);
        swapContent(page);
    }

    private Button backButton() {
        Button back = new Button("< 返回");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(e -> showHome());
        return back;
    }

    private HBox actionButtonRow(String refreshText) {
        actionButton.getStyleClass().add("accent-button");
        Button refresh = new Button(refreshText);
        Button finalRefresh = refresh;
        refresh.setOnAction(e -> {
            if (current == Cat.RELEASE) refresh();
            else loadLoaders(kindOf(current));
        });
        HBox row = new HBox(10, actionButton, finalRefresh);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private ModLoader.Kind kindOf(Cat c) {
        return c == Cat.FABRIC ? ModLoader.Kind.FABRIC : ModLoader.Kind.FORGE;
    }

    private VBox gameList() {
        releaseList.setPrefHeight(360);
        releaseList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(InstallService.Release item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                String date = item.releaseTime().length() >= 10 ? item.releaseTime().substring(0, 10) : "";
                setText(item.id() + "   [" + typeLabel(item.type()) + "]  " + date);
            }
        });
        VBox panel = new VBox(releaseList);
        VBox.setVgrow(releaseList, Priority.ALWAYS);
        return panel;
    }

    private VBox loaderList(ModLoader.Kind kind, Label hint) {
        mcCombo.getItems().clear();
        for (McVersion v : McFolder.scanVersions(McFolder.selectedRoot())) mcCombo.getItems().add(v.name());
        if (!mcCombo.getItems().isEmpty()) mcCombo.getSelectionModel().selectFirst();

        loaderList.getItems().clear();
        loaderList.setPrefHeight(300);
        mcCombo.setOnAction(e -> { if (!busy) loadLoaders(kind); });

        HBox versionRow = new HBox(10, new Label("游戏版本"), mcCombo);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(10, versionRow, loaderList, hint);
        VBox.setVgrow(loaderList, Priority.ALWAYS);
        if (!mcCombo.getItems().isEmpty()) loadLoaders(kind);
        return panel;
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "release" -> "正式版";
            case "snapshot" -> "快照";
            case "old_alpha" -> "Alpha";
            case "old_beta" -> "Beta";
            default -> type;
        };
    }

    // ==================== 内容切换（带淡入） ====================

    private void swapContent(javafx.scene.Parent node) {
        contentBox.getChildren().setAll(node);
        Animate.riseIn(node);
    }

    // ==================== CurseForge 模组浏览 ====================

    private final TextField searchField = new TextField();
    private final ListView<CurseForge.ModInfo> modList = new ListView<>();
    private final ListView<CurseForge.FileInfo> fileList = new ListView<>();

    private VBox buildCursePanel() {
        searchField.setPromptText("输入模组名称，如 JEI / Sodium / Iris");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnAction(e -> doSearch());
        Button searchButton = new Button("搜索");
        searchButton.getStyleClass().add("accent-button");
        searchButton.setOnAction(e -> doSearch());

        // 游戏版本下拉（过滤模组文件版本）
        mcCombo.getItems().clear();
        for (McVersion v : McFolder.scanVersions(McFolder.selectedRoot())) mcCombo.getItems().add(v.name());
        if (!mcCombo.getItems().isEmpty()) mcCombo.getSelectionModel().selectFirst();
        mcCombo.setOnAction(e -> { if (modList.getSelectionModel().getSelectedItem() != null) loadFiles(); });

        HBox searchRow = new HBox(10, searchField, new Label("游戏版本"), mcCombo, searchButton);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        modList.setPrefHeight(300);
        modList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(CurseForge.ModInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setTooltip(null); return; }
                setText(item.toString());
                setTooltip(new Tooltip(item.summary()));
            }
        });
        modList.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) loadFiles();
        });

        fileList.setPrefHeight(300);
        fileList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(CurseForge.FileInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });

        VBox leftBox = new VBox(6, new Label("搜索结果"), modList);
        VBox.setVgrow(modList, Priority.ALWAYS);
        VBox rightBox = new VBox(6, new Label("兼容文件（下载到所选版本的 mods 文件夹）"), fileList);
        VBox.setVgrow(fileList, Priority.ALWAYS);
        HBox lists = new HBox(12, leftBox, rightBox);
        HBox.setHgrow(leftBox, Priority.ALWAYS);
        HBox.setHgrow(rightBox, Priority.ALWAYS);
        VBox.setVgrow(lists, Priority.ALWAYS);

        VBox panel = new VBox(10, searchRow, lists,
                Ui.hint("数据来自 CurseForge（MCIMirror 镜像）；文件将安装到所选版本的 mods 文件夹（版本隔离生效范围）。"));
        return panel;
    }

    private void doSearch() {
        if (busy) return;
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            Theme.warn("搜索", "请输入模组名称");
            return;
        }
        runQuiet("搜索 " + query, () -> {
            List<CurseForge.ModInfo> mods = CurseForge.search(query);
            Platform.runLater(() -> {
                modList.getItems().setAll(mods);
                fileList.getItems().clear();
                if (!mods.isEmpty()) modList.getSelectionModel().selectFirst();
            });
            return "搜索到 " + mods.size() + " 个模组";
        });
    }

    private void loadFiles() {
        // 注意：不检查 busy —— 搜索完成后的自动选中会在 runQuiet 收尾前触发这里
        CurseForge.ModInfo mod = modList.getSelectionModel().getSelectedItem();
        if (mod == null) return;
        String mc = mcCombo.getValue();
        runQuiet("获取 " + mod.name() + " 的文件列表", () -> {
            List<CurseForge.FileInfo> files = CurseForge.files(mod.id(), mc);
            Platform.runLater(() -> {
                fileList.getItems().setAll(files);
                if (!files.isEmpty()) fileList.getSelectionModel().selectFirst();
            });
            return mc == null || mc.isBlank()
                    ? "共 " + files.size() + " 个文件"
                    : "共 " + files.size() + " 个兼容 " + mc + " 的文件";
        });
    }

    private void installCurseFile() {
        if (busy) return;
        CurseForge.FileInfo file = fileList.getSelectionModel().getSelectedItem();
        String version = mcCombo.getValue();
        if (file == null) {
            Theme.warn("安装", "请先搜索模组并选择文件");
            return;
        }
        if (version == null || version.isBlank()) {
            Theme.warn("安装", "请先选择要安装到的游戏版本");
            return;
        }
        runInstall("下载 " + file.displayName(), () -> {
            Path modsDir = McFolder.selectedRoot().resolve("versions").resolve(version).resolve("mods");
            java.nio.file.Files.createDirectories(modsDir);
            CurseForge.download(file, modsDir.resolve(file.name()));
            return "已安装到 " + version + " 的 mods 文件夹：" + file.displayName();
        });
    }

    // ==================== 动作 ====================

    public void onShown() {
        // 静默预取版本清单（进入正式版分类时立即可用）
        if (manifest.isEmpty()) {
            Task.POOL.submit(() -> {
                try {
                    List<InstallService.Release> fetched = InstallService.fetchManifest();
                    Platform.runLater(() -> {
                    manifest = fetched;
                    if (current == Cat.RELEASE && releaseList.getItems().isEmpty()) applyFilter();
                    });
                } catch (Exception ignored) {}
            });
        }
    }

    private void refresh() {
        statusLabel.setText("正在获取版本清单…");
        actionButton.setDisable(true);
        Task.POOL.submit(() -> {
            try {
                manifest = InstallService.fetchManifest();
                Platform.runLater(() -> {
                    statusLabel.setText("共 " + manifest.size() + " 个版本");
                    applyFilter();
                    actionButton.setDisable(false);
                });
            } catch (Exception e) {
                Log.error("获取版本清单失败", e);
                Platform.runLater(() -> {
                    statusLabel.setText("获取失败：" + e.getMessage());
                    actionButton.setDisable(false);
                });
            }
        });
    }

    private void applyFilter() {
        releaseList.getItems().clear();
        for (InstallService.Release r : manifest) {
            if (r.isRelease()) releaseList.getItems().add(r);
        }
        if (statusLabel.getText().startsWith("就绪")) {
            statusLabel.setText("共 " + releaseList.getItems().size() + " 个版本");
        }
    }

    private void loadLoaders(ModLoader.Kind kind) {
        String mc = mcCombo.getValue();
        if (mc == null || mc.isBlank()) return;
        statusLabel.setText("正在获取 " + kind + " 版本列表…");
        loaderList.getItems().clear();
        actionButton.setDisable(true);
        Task.POOL.submit(() -> {
            try {
                List<ModLoader.LoaderEntry> loaders = ModLoader.fetchLoaders(kind, mc);
                Platform.runLater(() -> {
                    loaderList.getItems().setAll(loaders);
                    if (!loaders.isEmpty()) loaderList.getSelectionModel().selectFirst();
                    statusLabel.setText("共 " + loaders.size() + " 个 " + kind + " 版本");
                    actionButton.setDisable(false);
                });
            } catch (Exception e) {
                Log.error("获取加载器列表失败", e);
                Platform.runLater(() -> {
                    statusLabel.setText("获取失败：" + e.getMessage());
                    actionButton.setDisable(false);
                });
            }
        });
    }

    private void installRelease() {
        if (busy) return;
        InstallService.Release selected = releaseList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Theme.warn("安装", "请先在列表中选择一个版本");
            return;
        }
        runInstall("下载 " + selected.id(), () -> {
            Path mcRoot = McFolder.selectedRoot();
            McFolder.ensureProfile(mcRoot);
            InstallService.install(selected.id(), mcRoot);
            return "安装完成：" + selected.id() + "（依赖库与资源将在首次启动时自动补全）";
        });
    }

    private void installLoader(ModLoader.Kind kind) {
        if (busy) return;
        String mc = mcCombo.getValue();
        ModLoader.LoaderEntry loader = loaderList.getSelectionModel().getSelectedItem();
        if (mc == null || loader == null) {
            Theme.warn("安装", "请先选择游戏版本与 " + kind + " 版本");
            return;
        }
        runInstall("安装 " + kind + " " + loader.display(), () -> {
            String name = ModLoader.install(kind, mc, loader.version(), McFolder.selectedRoot());
            return "安装完成：" + name + "，可在启动页选择该版本";
        });
    }

    /** 带完成弹窗的安装执行框架：禁用 UI → 后台执行 → 汇报结果。 */
    private void runInstall(String startText, Task.Func.ThrowingFunction<String> work) {
        runQuiet(startText, work, true);
    }

    /** 静默执行（仅更新状态栏，不弹窗；搜索/浏览文件列表用）。 */
    private void runQuiet(String startText, Task.Func.ThrowingFunction<String> work) {
        runQuiet(startText, work, false);
    }

    private void runQuiet(String startText, Task.Func.ThrowingFunction<String> work, boolean withDialog) {
        busy = true;
        actionButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText(startText + "…");
        Task.POOL.submit(() -> {
            try {
                String message = work.apply();
                Platform.runLater(() -> {
                    statusLabel.setText(message);
                    if (withDialog) Theme.info("安装完成", message);
                });
            } catch (Exception e) {
                Log.error("操作失败", e);
                Platform.runLater(() -> {
                    statusLabel.setText("失败：" + e.getMessage());
                    Theme.error("操作失败", e.getMessage() == null ? e.toString() : e.getMessage());
                });
            } finally {
                busy = false;
                Platform.runLater(() -> {
                    actionButton.setDisable(false);
                    progressBar.setVisible(false);
                });
            }
        });
    }
}
