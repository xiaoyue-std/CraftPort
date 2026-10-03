package pcl.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ProgressBar;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.WeakHashMap;

/**
 * PCL2 风格的轻量 UI 动画工具，全部基于 JavaFX 动画 API（纯 Java 实现）。
 * 统一使用 EASE_OUT 缓动；同一目标上的动画互不打架（单飞管理）。
 */
public final class Animate {

    /** 同一目标只保留最新一个 Timeline（弱引用，节点回收后自动清理）。 */
    private static final WeakHashMap<Object, Timeline> RUNNING = new WeakHashMap<>();

    private Animate() {}

    // ==================== 页面与容器 ====================

    /** 页面切换：整体按方向滑入 + 淡入（对应 PCL2 的页面切换动画）。 */
    public static void pageIn(Node node, boolean fromRight) {
        node.setOpacity(0);
        node.setTranslateX(fromRight ? 26 : -26);
        FadeTransition fade = new FadeTransition(Duration.millis(200), node);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(200), node);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition both = new ParallelTransition(node, fade, slide);
        both.play();
    }

    /** 内容切换：轻微上浮淡入。 */
    public static void riseIn(Node node) {
        node.setOpacity(0);
        node.setTranslateY(10);
        FadeTransition fade = new FadeTransition(Duration.millis(160), node);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(160), node);
        rise.setToY(0);
        rise.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition both = new ParallelTransition(node, fade, rise);
        both.play();
    }

    /** 卡片错落浮现：对容器直接子节点依次上浮淡入（对应 PCL2 进页的层次感）。 */
    public static void staggerIn(Parent container) {
        int i = 0;
        for (Node child : container.getChildrenUnmodifiable()) {
            final int delay = i++;
            child.setOpacity(0);
            child.setTranslateY(10);
            FadeTransition fade = new FadeTransition(Duration.millis(180), child);
            fade.setToValue(1);
            TranslateTransition rise = new TranslateTransition(Duration.millis(180), child);
            rise.setToY(0);
            rise.setInterpolator(Interpolator.EASE_OUT);
            SequentialTransition seq = new SequentialTransition(child,
                    new PauseTransition(Duration.millis(45L * delay)),
                    new ParallelTransition(fade, rise));
            seq.play();
        }
    }

    // ==================== 悬停效果 ====================

    /** 悬停轻微放大（离开自动还原；缩放围绕节点中心）。 */
    public static void attachScale(Node node, double hoverScale) {
        node.setOnMouseEntered(e -> scaleTo(node, hoverScale));
        node.setOnMouseExited(e -> scaleTo(node, 1.0));
    }

    private static void scaleTo(Node node, double value) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(node.scaleXProperty(), node.getScaleX()),
                        new KeyValue(node.scaleYProperty(), node.getScaleY())),
                new KeyFrame(Duration.millis(130),
                        new KeyValue(node.scaleXProperty(), value, Interpolator.EASE_OUT),
                        new KeyValue(node.scaleYProperty(), value, Interpolator.EASE_OUT)));
        t.setCycleCount(1);
        playOnce(node, t);
    }

    /**
     * 悬停光晕：用 Java 管理 DropShadow 半径（进入增强、离开回落）。
     * 注意：会替换节点上由 CSS 设置的 -fx-effect。
     */
    public static void attachGlow(Node node, Color color, double idleRadius, double hoverRadius) {
        DropShadow glow = new DropShadow(idleRadius, color);
        node.setEffect(glow);
        node.setOnMouseEntered(e -> glowTo(glow, hoverRadius));
        node.setOnMouseExited(e -> glowTo(glow, idleRadius));
    }

    private static void glowTo(DropShadow glow, double radius) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), glow.getRadius())),
                new KeyFrame(Duration.millis(150), new KeyValue(glow.radiusProperty(), radius, Interpolator.EASE_OUT)));
        t.setCycleCount(1);
        playOnce(glow, t);
    }

    // ==================== 进度条 ====================

    private static final WeakHashMap<ProgressBar, Timeline> PROGRESS = new WeakHashMap<>();

    /** 进度条平滑过渡到目标值（indeterminate 传 -1 直接生效）。 */
    public static void smoothProgress(ProgressBar bar, double to) {
        if (to < 0) {
            bar.setProgress(-1);
            return;
        }
        double from = Math.max(0, bar.getProgress());
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(bar.progressProperty(), from)),
                new KeyFrame(Duration.millis(300), new KeyValue(bar.progressProperty(), to, Interpolator.EASE_OUT)));
        t.setCycleCount(1);
        Timeline old = PROGRESS.remove(bar);
        if (old != null) old.stop();
        PROGRESS.put(bar, t);
        t.play();
    }

    private static void playOnce(Object key, Timeline t) {
        Timeline old = RUNNING.remove(key);
        if (old != null) old.stop();
        RUNNING.put(key, t);
        t.play();
    }
}
