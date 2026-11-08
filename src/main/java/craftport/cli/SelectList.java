package craftport.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JLine 箭头键选择列表（P1）：↑/↓ 移动、Home/End 跳转、回车确认、Esc 取消；
 * filterable 时键入 ASCII 字符实时前缀过滤、退格删除（版本列表挑选用）。
 * 哑终端（管道/CI）退化为数字输入，永远不发网络请求、不做阻塞之外的动作。
 * 终端属性在 finally 里恢复，后续 LineReader.readLine 不受影响。
 */
final class SelectList {

    /** 列表可视行数（滚动窗口）；再加一行输入提示构成整个控件高度。 */
    private static final int WINDOW = 10;
    private static final int HEIGHT = WINDOW + 1;

    private SelectList() {}

    /**
     * 选择一项，返回下标；取消（Esc/Ctrl+C/Ctrl+D/空列表）返回 -1。
     *
     * @param terminal   当前会话终端（dumb 时自动退化为数字输入）
     * @param reader     哑终端退化时的行输入
     * @param filterable true = 键入字符实时前缀过滤
     */
    static int pick(Terminal terminal, LineReader reader, String title, List<String> options, boolean filterable) {
        if (options == null || options.isEmpty()) return -1;
        if (Repl.isDumb(terminal)) return pickNumeric(reader, title, options);
        try {
            return pickRaw(terminal, title, options, filterable);
        } catch (IOException | EndOfFileException | UserInterruptException e) {
            return -1;   // 终端异常按取消处理
        }
    }

    // ==================== 哑终端退化：数字输入 ====================

    private static int pickNumeric(LineReader reader, String title, List<String> options) {
        System.out.println(title);
        for (int i = 0; i < options.size(); i++) System.out.printf("  [%d] %s%n", i, options.get(i));
        while (true) {
            String line;
            try {
                line = reader.readLine("number (empty = cancel): ");
            } catch (EndOfFileException | UserInterruptException e) {
                return -1;
            }
            if (line == null) return -1;
            line = line.trim();
            if (line.isEmpty()) return -1;
            try {
                int idx = Integer.parseInt(line);
                if (idx >= 0 && idx < options.size()) return idx;
            } catch (NumberFormatException ignored) {}
            System.out.println("Invalid number: " + line);
        }
    }

    // ==================== 原始模式：箭头键 + 过滤 ====================

    private static int pickRaw(Terminal terminal, String title, List<String> options,
                               boolean filterable) throws IOException {
        Attributes saved = terminal.getAttributes();
        PrintWriter out = terminal.writer();
        out.write(title + "\r\n");
        out.flush();
        NonBlockingReader in = terminal.reader();
        terminal.enterRawMode();

        boolean drawn = false;
        int sel = 0;
        StringBuilder filter = new StringBuilder();
        int[] view = view(options, filter.toString());
        try {
            while (true) {
                if (sel >= view.length) sel = Math.max(0, view.length - 1);
                if (drawn) out.write("\033[" + HEIGHT + "A");   // 回到控件块顶重绘
                draw(out, options, view, sel, filter, filterable);
                out.flush();
                drawn = true;

                int c = in.read();
                if (c == -1 || c == 3 || c == 4) {              // EOF / Ctrl+C / Ctrl+D → 取消
                    erase(out);
                    return -1;
                }
                if (c == '\r' || c == '\n') {
                    if (view.length > 0) { erase(out); return view[sel]; }
                    continue;   // 过滤无结果时不允许回车
                }
                if (c == 27) {                                  // ESC：箭头序列前缀或单独取消
                    int next = in.peek(50);
                    if (next == '[' || next == 'O') {
                        in.read();
                        int k = in.read();
                        if (k == 'A') sel = Math.max(0, sel - 1);
                        else if (k == 'B') sel = Math.min(Math.max(0, view.length - 1), sel + 1);
                        else if (k == 'H') sel = 0;
                        else if (k == 'F') sel = Math.max(0, view.length - 1);
                    } else {
                        erase(out);
                        return -1;
                    }
                } else if (c == 127 || c == 8) {                // 退格：删一个过滤字符
                    if (filter.length() > 0) {
                        filter.setLength(filter.length() - 1);
                        view = view(options, filter.toString());
                        sel = 0;
                    }
                } else if (filterable && c >= 32 && c < 127) {  // 可打印 ASCII → 追加过滤
                    filter.append((char) c);
                    view = view(options, filter.toString());
                    sel = 0;
                }
                // 其余按键忽略并重绘
            }
        } finally {
            terminal.setAttributes(saved);
        }
    }

    /** 画控件（WINDOW 行列表 + 1 行状态/过滤行，总高恒定便于整块覆盖重绘）。 */
    private static void draw(PrintWriter out, List<String> options, int[] view, int sel,
                             StringBuilder filter, boolean filterable) {
        int winStart = Math.max(0, Math.min(sel - WINDOW / 2, Math.max(0, view.length - WINDOW)));
        for (int row = 0; row < WINDOW; row++) {
            out.write("\033[2K");
            int vi = winStart + row;
            if (vi < view.length) {
                if (vi == sel) out.write("\033[7m > " + options.get(view[vi]) + "\033[0m");
                else out.write("   " + options.get(view[vi]));
                if (row == 0 && winStart > 0) out.write("  ^");
                if (row == WINDOW - 1 && winStart + WINDOW < view.length) out.write("  v");
            }
            out.write("\r\n");
        }
        out.write("\033[2K");
        if (view.length == 0) out.write("(no match - backspace to undo filter, Esc cancel)");
        else if (filterable) out.write("filter: '" + filter + "'  (Enter select, Esc cancel)");
        else out.write("(Enter select, Esc cancel)");
        out.write("\r\n");
    }

    /** 清除控件块并把光标停在块顶（供调用方打印结果）。 */
    private static void erase(PrintWriter out) {
        out.write("\033[" + HEIGHT + "A");
        for (int i = 0; i < HEIGHT; i++) out.write("\033[2K\r\n");
        out.write("\033[" + HEIGHT + "A");
        out.flush();
    }

    /** 前缀过滤（大小写不敏感）后的下标视图。 */
    private static int[] view(List<String> options, String filter) {
        String f = filter.toLowerCase(Locale.ROOT);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (f.isEmpty() || options.get(i).toLowerCase(Locale.ROOT).startsWith(f)) idx.add(i);
        }
        return idx.stream().mapToInt(Integer::intValue).toArray();
    }
}
