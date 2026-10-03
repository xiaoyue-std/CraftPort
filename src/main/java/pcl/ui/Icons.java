package pcl.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Random;

/**
 * 程序化绘制的 PCL2 风格图标与侧栏雪山背景。
 * 全部用 Canvas 绘制而非图片资源：跨平台渲染一致，且避免使用原版受版权保护的素材。
 */
public final class Icons {

    private Icons() {}

    // ==================== 侧栏雪山背景（对应 PCL2 侧栏的雪景图） ====================

    public static void paintMountain(Canvas c) {
        double w = c.getWidth(), h = c.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = c.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        // 天空渐变
        var sky = new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, Color.web("#5D97D8")),
                new javafx.scene.paint.Stop(0.5, Color.web("#9CC6EE")),
                new javafx.scene.paint.Stop(1, Color.web("#E9F4FE")));
        g.setFill(sky);
        g.fillRect(0, 0, w, h);
        // 三层山（远→近，逐层变深）
        range(g, w, h, 0.30, 0.14, Color.web("#BCD8F3"), 7741);
        range(g, w, h, 0.50, 0.18, Color.web("#8FB7E4"), 23123);
        range(g, w, h, 0.70, 0.16, Color.web("#5F8FCB"), 424242);
        // 云由 paintClouds 单独绘制（支持动画漂移）
    }

    /** 漂浮的云（由主窗口的动画循环驱动 t，缓慢横向漂移；在透明覆盖画布上绘制）。 */
    public static void paintClouds(GraphicsContext g, double w, double h, double t) {
        if (w <= 0 || h <= 0) return;
        g.clearRect(0, 0, w, h);
        cloud(g, wrap(w * 0.55 + t * 6, w), h * 0.13, 14);
        cloud(g, wrap(w * 0.22 + t * 9, w), h * 0.22, 10);
        cloud(g, wrap(w * 0.72 + t * 7, w), h * 0.30, 8);
    }

    /** 横向循环：x 超出边界后从另一侧飘入（留出云宽度的余量）。 */
    private static double wrap(double x, double w) {
        double margin = 40;
        double span = w + margin * 2;
        return ((x + margin) % span + span) % span - margin;
    }

    /** 一条山脉剪影：余弦调制主峰形 + 随机锯齿。 */
    private static void range(GraphicsContext g, double w, double h,
                              double topFrac, double variance, Color color, long seed) {
        Random r = new Random(seed);
        int n = 26;
        double[] ys = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double shape = 0.65 + 0.6 * Math.abs(Math.cos(Math.PI * i / n * 2.4 + seed % 7));
            double y = h * topFrac * shape + (r.nextDouble() - 0.5) * h * variance * 0.4;
            ys[i] = Math.max(2, y);
        }
        g.setFill(color);
        g.beginPath();
        g.moveTo(0, h);
        for (int i = 0; i <= n; i++) g.lineTo(w * i / n, ys[i]);
        g.lineTo(w, h);
        g.closePath();
        g.fill();
    }

    private static void cloud(GraphicsContext g, double cx, double cy, double r) {
        g.setFill(Color.rgb(255, 255, 255, 0.85));
        g.fillOval(cx - r, cy - r * 0.5, r * 2, r);
        g.fillOval(cx - r * 1.4, cy, r * 1.5, r * 0.8);
        g.fillOval(cx + r * 0.3, cy + r * 0.05, r * 1.4, r * 0.75);
    }

    // ==================== 导航图标（白色，16px） ====================

    public static Canvas playIcon() {
        Canvas c = icon(16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.WHITE);
        g.fillPolygon(new double[]{4, 4, 13.5}, new double[]{2.5, 13.5, 8}, 3);
        return c;
    }

    public static Canvas downloadIcon() {
        Canvas c = icon(16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.WHITE);
        g.fillRect(6.5, 1.5, 3, 7);
        g.fillPolygon(new double[]{3, 13, 8}, new double[]{7.5, 7.5, 12.5}, 3);
        g.fillRect(2.5, 13, 11, 2);
        return c;
    }

    public static Canvas gearIcon() {
        Canvas c = icon(16);
        GraphicsContext g = c.getGraphicsContext2D();
        double cx = 8, cy = 8;
        g.setFill(Color.WHITE);
        // 齿
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            g.fillRect(cx + Math.cos(a) * 5.2 - 1.5, cy + Math.sin(a) * 5.2 - 1.5, 3, 3);
        }
        // 环
        g.setLineWidth(3);
        g.setStroke(Color.WHITE);
        g.strokeOval(cx - 3.4, cy - 3.4, 6.8, 6.8);
        return c;
    }

    private static Canvas icon(int s) {
        Canvas c = new Canvas(s, s);
        c.setCache(true);
        return c;
    }

    // ==================== 版本/分类图标（像素草方块与加载器标志） ====================

    /** 按版本名选择图标：加载器版本显示对应标志，原版显示草方块。 */
    public static Canvas forVersion(String versionName, int s) {
        String n = versionName == null ? "" : versionName.toLowerCase();
        if (n.contains("-fabric")) return categoryIcon("fabric", s);
        if (n.contains("-forge")) return categoryIcon("forge", s);
        if (n.contains("optifine")) return categoryIcon("optifine", s);
        if (n.matches(".*\\d\\d\\dw\\d\\da.*") || n.contains("snapshot")) return categoryIcon("snapshot", s);
        return grassBlock(s, versionName);
    }

    /** 像素风草方块（MC 标志性图标；颜色带确定性随机变化）。 */
    public static Canvas grassBlock(int s, String seed) {
        Canvas c = new Canvas(s, s);
        GraphicsContext g = c.getGraphicsContext2D();
        Random r = new Random(seed == null || seed.isBlank() ? 0 : seed.hashCode());
        int top = Math.max(3, (int) (s * 0.30));
        for (int y = 0; y < top; y++) {
            for (int x = 0; x < s; x++) {
                int v = r.nextInt(28);
                g.setFill(Color.rgb(96 + v, 168 + v, 58 + v / 2));
                g.fillRect(x, y, 1, 1);
            }
        }
        for (int y = top; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int v = r.nextInt(30);
                g.setFill(Color.rgb(122 + v, 84 + v / 2, 48 + v / 3));
                g.fillRect(x, y, 1, 1);
            }
        }
        g.setStroke(Color.rgb(0, 0, 0, 0.30));
        g.setLineWidth(1);
        g.strokeRect(0.5, 0.5, s - 1, s - 1);
        return c;
    }

    /** 分类磁贴图标（下载页大按钮用）。 */
    public static Canvas categoryIcon(String key, int s) {
        Canvas c = icon(s);
        GraphicsContext g = c.getGraphicsContext2D();
        switch (key) {
            case "release" -> {
                g = null; // 直接复用草方块画布
                Canvas gb = grassBlock(s, "release");
                return gb;
            }
            case "snapshot" -> {
                Canvas gb = grassBlock(s, "snapshot");
                GraphicsContext gg = gb.getGraphicsContext2D();
                gg.setFill(Color.rgb(150, 90, 200, 0.40)); // 紫色染色
                gg.fillRect(0, 0, s, s * 0.30);
                return gb;
            }
            case "old" -> {
                Canvas gb = grassBlock(s, "old");
                GraphicsContext gg = gb.getGraphicsContext2D();
                gg.setFill(Color.rgb(180, 160, 120, 0.45)); // 泛黄做旧
                gg.fillRect(0, 0, s, s);
                return gb;
            }
            case "fabric" -> {
                g.setFill(Color.web("#DBE1E8"));
                g.fillRoundRect(1, 1, s - 2, s - 2, s * 0.18, s * 0.18);
                g.setFill(Color.web("#4C5866"));
                g.setFont(Font.font("SansSerif", FontWeight.BOLD, s * 0.62));
                g.fillText("F", s * 0.30, s * 0.72);
            }
            case "forge" -> {
                // 铁砧剪影
                g.setFill(Color.web("#4A4A52"));
                g.fillRect(s * 0.12, s * 0.22, s * 0.76, s * 0.16);       // 砧面
                g.fillRect(s * 0.38, s * 0.38, s * 0.24, s * 0.26);       // 颈
                g.fillRect(s * 0.26, s * 0.64, s * 0.48, s * 0.10);       // 座
                g.fillRect(s * 0.16, s * 0.74, s * 0.68, s * 0.10);       // 底
            }
            case "optifine" -> {
                // 橙色太阳
                g.setFill(Color.web("#F5A623"));
                double cx = s / 2.0, cy = s / 2.0, r = s * 0.22;
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    g.fillRect(cx + Math.cos(a) * s * 0.32 - s * 0.05,
                            cy + Math.sin(a) * s * 0.32 - s * 0.05, s * 0.10, s * 0.10);
                }
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
            }
            case "curseforge" -> {
                // 橙红底白 C（CurseForge 品牌色意象）
                g.setFill(Color.web("#F16436"));
                g.fillRoundRect(1, 1, s - 2, s - 2, s * 0.18, s * 0.18);
                g.setFill(Color.WHITE);
                g.setFont(Font.font("SansSerif", FontWeight.BOLD, s * 0.62));
                g.fillText("C", s * 0.28, s * 0.74);
            }
            default -> {
                return grassBlock(s, key);
            }
        }
        g.setStroke(Color.rgb(0, 0, 0, 0.20));
        g.setLineWidth(1);
        g.strokeRect(0.5, 0.5, s - 1, s - 1);
        return c;
    }
}
