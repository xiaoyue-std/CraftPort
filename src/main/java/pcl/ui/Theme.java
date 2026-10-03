package pcl.ui;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/**
 * 主题与通用 UI 工具，移植自 Application.xaml / 主题系统（My Project 的资源与样式）。
 * JavaFX CSS 实现与 WPF 等价的圆角卡片风格。
 */
public final class Theme {
    /** 主题色（对应 PCL2 的默认蓝色主题）。 */
    public static final String ACCENT = "#3f8be8";
    public static final String ACCENT_DARK = "#2f6fbd";
    public static final String BG = "#f4f6f9";
    public static final String CARD = "#ffffff";
    public static final String TEXT = "#262626";
    public static final String TEXT_SUB = "#7a7a7a";

    private Theme() {}

    public static void apply(Scene scene) {
        var css = Theme.class.getResource("/pcl/style.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
    }

    public static void info(String title, String message) {
        alert(Alert.AlertType.INFORMATION, title, message);
    }

    public static void warn(String title, String message) {
        alert(Alert.AlertType.WARNING, title, message);
    }

    public static void error(String title, String message) {
        alert(Alert.AlertType.ERROR, title, message);
    }

    private static void alert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setHeaderText(title);
        alert.showAndWait();
    }
}
