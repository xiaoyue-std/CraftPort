package craftport.cli;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.util.List;

/**
 * REPL 会话句柄（P1 交互降级的总闸）。
 * 一次性命令模式下 session 为 null：调用方必须先用 {@link #interactive()} 判断，
 * 非交互时保持原报错路径（红线：一次性命令路径零改动）。
 */
final class CliUi {

    private static volatile Repl session;

    static void attach(Repl repl) { session = repl; }
    static void detach() { session = null; }

    /** 当前是否处于交互 REPL 上下文（一次性命令为 false）。 */
    static boolean interactive() { return session != null; }

    /** 箭头键选择列表，返回下标；Esc/取消返回 -1。仅交互模式可用。 */
    static int select(String title, List<String> options) {
        return session.select(title, options, false);
    }

    /** 同 select，键入字符实时前缀过滤（releases 挑选用）。仅交互模式可用。 */
    static int selectFiltered(String title, List<String> options) {
        return session.select(title, options, true);
    }

    /** 读一行输入（Ctrl+C/Ctrl+D 返回 null）。仅交互模式可用。 */
    static String prompt(String prompt) { return session.prompt(prompt); }

    /** y/N 确认（默认 N）。仅交互模式可用。 */
    static boolean confirm(String prompt) { return session.confirm(prompt); }

    static Terminal terminal() { return session.terminal(); }
    static LineReader lineReader() { return session.reader(); }

    private CliUi() {}
}
