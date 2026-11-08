package craftport.cli;

import craftport.base.BuildInfo;
import craftport.base.Os;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JLine 3 驱动的交互 REPL（P0）。纯展示层：命令执行直接复用 CliMain.dispatch，
 * 不复制任何 cmdXxx 逻辑；哑终端（管道/CI）不走这里，由 CliMain.interactive()
 * 退回原数字菜单（红线：非交互环境不挂起）。
 * 历史持久化在 Os.dataDir()/repl-history；Ctrl+C 回到提示符，Ctrl+D 视为 exit。
 */
final class Repl {

    private final Terminal terminal;
    private final LineReader reader;
    private final CraftPortCompleter completer = new CraftPortCompleter();

    Repl(Terminal terminal) throws IOException {
        this.terminal = terminal;
        Files.createDirectories(Os.dataDir());   // repl-history 的父目录
        this.reader = LineReaderBuilder.builder()
                .appName("CraftPort")
                .terminal(terminal)
                .completer(completer)
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, Os.dataDir().resolve("repl-history").toString())
                .variable(LineReader.HISTORY_SIZE, 500)
                .build();
    }

    /** 系统终端探测：拿不到真实 TTY 时落 dumb（dumb(true) 保证不抛异常）。 */
    static Terminal createTerminal() throws IOException {
        return TerminalBuilder.builder().name("CraftPort").dumb(true).build();
    }

    /** dumb 终端判断（类型 dumb / dumb-color...）。 */
    static boolean isDumb(Terminal terminal) {
        return terminal.getType().toLowerCase(Locale.ROOT).startsWith("dumb");
    }

    Terminal terminal() { return terminal; }
    LineReader reader() { return reader; }

    int run() {
        CliUi.attach(this);
        try {
            System.out.println("CraftPort " + BuildInfo.label() + " - interactive console");
            System.out.println("Type 'help' for commands; Tab completes; 'exit' or Ctrl+D quits.");
            while (true) {
                String line;
                try {
                    line = reader.readLine("CraftPort> ");
                } catch (UserInterruptException e) {   // Ctrl+C：丢弃当前行，回到提示符
                    continue;
                } catch (EndOfFileException e) {       // Ctrl+D：视为 exit
                    break;
                }
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                if ("exit".equals(line) || "quit".equals(line)) break;
                execute(line);
            }
        } finally {
            CliUi.detach();
        }
        System.out.println("Bye!");
        return 0;
    }

    private void execute(String line) {
        String[] tokens = tokenize(line);
        try {
            if ("server".equals(tokens[0]) && tokens.length > 1 && "web".equals(tokens[1])) {
                // REPL 里面板不阻塞 REPL 主循环（一次性命令的 CountDownLatch 保活语义
                // 只在一次性格局需要；REPL 进程存活期间面板一直在）
                Map<String, String> flags = CliMain.parseFlags(tokens, 2);
                WebPanel.start(Integer.parseInt(flags.getOrDefault("port", "8765")),
                        flags.getOrDefault("host", "127.0.0.1"));
                System.out.println("(Panel keeps running while this session is open; it stops on 'exit'.)");
                return;
            }
            CliMain.dispatch(tokens);
        } catch (Exception e) {
            // dispatch 内部已捕获业务异常；这里兜底，保证 REPL 永不因单条命令挂掉
            System.out.println("Unexpected error: " + e);
        }
        completer.invalidate();   // 命令可能新增了版本/服务器
    }

    /**
     * 单双引号 + 反斜杠转义的分词（REPL 里手敲带空格的查询词用）。
     * 双引号内 \" 与 \\ 转义，其它反斜杠原样保留；未闭合引号宽容收尾。
     */
    static String[] tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean token = false;   // 当前是否在累积一个词（保留空串参数 ""）
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\' && i + 1 < line.length()) {
                    char n = line.charAt(++i);
                    if (n == '"' || n == '\\') cur.append(n);
                    else cur.append(c).append(n);
                } else if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                token = true;
            } else if (c == ' ' || c == '\t') {
                if (token) { out.add(cur.toString()); cur.setLength(0); token = false; }
            } else {
                cur.append(c);
                token = true;
            }
        }
        if (token || quote != 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // ==================== P1：交互原语（经 CliUi 供 CliMain 使用） ====================

    int select(String title, List<String> options, boolean filterable) {
        return SelectList.pick(terminal, reader, title, options, filterable);
    }

    String prompt(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (EndOfFileException | UserInterruptException e) {
            return null;
        }
    }

    boolean confirm(String prompt) {
        String s = prompt(prompt + " [y/N]: ");
        return s != null && s.trim().equalsIgnoreCase("y");
    }
}
