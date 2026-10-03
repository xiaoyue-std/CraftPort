package pcl.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** 共享 UI 构建工具：PCL2 风格的卡片与强调条标题。 */
public final class Ui {

    private Ui() {}

    /** PCL2 风格卡片：白底圆角 + 投影 + 带强调条的标题。 */
    public static VBox card(String title) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        if (title != null && !title.isBlank()) card.getChildren().add(header(title));
        return card;
    }

    /** 卡片标题：左侧竖向强调条 + 加粗标题（对应 PCL2 卡片标题样式）。 */
    public static HBox header(String title) {
        Region bar = new Region();
        bar.getStyleClass().add("accent-bar");
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        HBox h = new HBox(8, bar, t);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }
}
