package craftport.cli;

import craftport.minecraft.McFolder;
import craftport.minecraft.McVersion;
import craftport.minecraft.ServerDeployer;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.completer.FileNameCompleter;

import java.util.List;
import java.util.Map;

/**
 * REPL 静态补全表（P0）：命令名 / server·mods·modpack 子子命令 / 按命令的 --flag /
 * 位置参数（launch·fabric·forge 补已装版本，server start·console 补已部署目录）。
 * 数据源只有磁盘：首次 Tab 惰性扫描并缓存，绝不做网络请求；
 * 每条命令执行后由 Repl 调 invalidate() 失效，下次 Tab 重扫磁盘。
 */
final class CraftPortCompleter implements Completer {

    private static final List<String> COMMANDS = List.of(
            "versions", "releases", "install", "fabric", "forge", "mods", "modpack",
            "launch", "server", "login", "dir", "runtimes", "status", "update",
            "help", "exit", "quit");

    private static final Map<String, String> CMD_DESC = Map.ofEntries(
            Map.entry("versions", "List installed versions"),
            Map.entry("releases", "List available vanilla versions"),
            Map.entry("install", "Install a vanilla version"),
            Map.entry("fabric", "Install Fabric on a version"),
            Map.entry("forge", "Install Forge on a version"),
            Map.entry("mods", "Search & install content"),
            Map.entry("modpack", "Search & install modpacks"),
            Map.entry("launch", "Launch a version"),
            Map.entry("server", "Deploy/start/manage servers"),
            Map.entry("login", "Save login credentials"),
            Map.entry("dir", "Show or set the game directory"),
            Map.entry("runtimes", "List detected Java runtimes"),
            Map.entry("status", "Show current configuration"),
            Map.entry("update", "Check GitHub for updates"),
            Map.entry("help", "Show command help"),
            Map.entry("exit", "Leave the console"),
            Map.entry("quit", "Leave the console"));

    private static final List<String> SERVER_SUBS =
            List.of("deploy", "start", "console", "list", "delete", "web", "service");
    private static final List<String> DEPLOY_FLAGS =
            List.of("--type", "--dir", "--accept-eula", "--no-auto-java");
    private static final List<String> START_FLAGS = List.of("--dir", "--memory", "--java");
    private static final List<String> WEB_FLAGS = List.of("--port", "--host");
    private static final List<String> DIRONLY_FLAGS = List.of("--dir");
    private static final List<String> MODS_SUBS = List.of("search", "install");
    private static final List<String> MODS_FLAGS =
            List.of("--mc", "--source", "--type", "--loader", "--pick", "--file", "--dir");
    private static final List<String> MODPACK_SUBS = List.of("search", "install", "deploy");
    private static final List<String> MODPACK_FLAGS =
            List.of("--mc", "--pick", "--file", "--server", "--dir", "--accept-eula", "--start");
    private static final List<String> LAUNCH_FLAGS =
            List.of("--username", "--server", "--offline", "--microsoft", "--no-isolation", "--no-wait");
    private static final List<String> UPDATE_FLAGS =
            List.of("--check", "--ref", "--install-dir", "--no-apply");

    private List<String> versions;   // 已装版本名（惰性缓存）
    private List<String> servers;    // 已部署服务器目录（惰性缓存）

    /** 每条命令执行后调用：版本/服务器列表可能已变化。 */
    void invalidate() {
        versions = null;
        servers = null;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> out) {
        List<String> words = line.words();
        int wi = line.wordIndex();
        String cur = line.word();
        if (wi <= 0) { commands(out); return; }
        String cmd = words.get(0);

        // 前一个词是值型 flag → 补全它的取值（--pick/--file 等自由输入的返回 null 落空）
        if (wi >= 2) {
            String prev = words.get(wi - 1);
            if (prev.startsWith("--")) {
                List<String> vals = flagValues(cmd, prev.substring(2));
                if (vals != null) { add(out, vals, "values"); return; }
            }
        }

        switch (cmd) {
            case "server" -> {
                if (wi == 1) { add(out, SERVER_SUBS, "subcommand"); return; }
                String sub = words.get(1);
                if (cur.startsWith("--")) {
                    add(out, switch (sub) {
                        case "deploy" -> DEPLOY_FLAGS;
                        case "start", "console" -> START_FLAGS;
                        case "web" -> WEB_FLAGS;
                        case "delete", "service" -> DIRONLY_FLAGS;
                        default -> List.of();
                    }, "flag");
                    return;
                }
                if (wi == 2 && ("start".equals(sub) || "console".equals(sub) || "delete".equals(sub))) {
                    add(out, serverDirs(), "server dir");
                }
            }
            case "mods" -> {
                if (wi == 1) { add(out, MODS_SUBS, "subcommand"); return; }
                if (cur.startsWith("--")) add(out, MODS_FLAGS, "flag");
            }
            case "modpack" -> {
                if (wi == 1) { add(out, MODPACK_SUBS, "subcommand"); return; }
                if (cur.startsWith("--")) add(out, MODPACK_FLAGS, "flag");
            }
            case "launch" -> {
                if (wi == 1 && !cur.startsWith("--")) add(out, installedVersions(), "version");
                else if (cur.startsWith("--")) add(out, LAUNCH_FLAGS, "flag");
            }
            case "fabric", "forge" -> {
                if (wi == 1 && !cur.startsWith("--")) add(out, installedVersions(), "version");
            }
            case "update" -> {
                if (cur.startsWith("--")) add(out, UPDATE_FLAGS, "flag");
            }
            case "dir" -> {
                if (wi == 1) new FileNameCompleter().complete(reader, line, out);
            }
            default -> {}
        }
    }

    /** 值型 flag 的候选；null = 自由输入不补全。--mc 用已装版本作离线提示源。 */
    private List<String> flagValues(String cmd, String flag) {
        return switch (flag) {
            case "mc" -> installedVersions();
            case "source" -> List.of("curseforge", "modrinth");
            case "type" -> "mods".equals(cmd)
                    ? List.of("mod", "resourcepack", "shader", "datapack")
                    : List.of("vanilla", "fabric", "forge", "neoforge", "paper");
            case "loader" -> List.of("fabric", "forge");
            case "dir" -> "server".equals(cmd) ? serverDirs() : null;
            default -> null;
        };
    }

    private List<String> installedVersions() {
        if (versions == null) {
            try {
                versions = McFolder.scanVersions(McFolder.selectedRoot()).stream()
                        .map(McVersion::name).toList();
            } catch (Exception e) {
                versions = List.of();
            }
        }
        return versions;
    }

    private List<String> serverDirs() {
        if (servers == null) {
            try {
                servers = ServerDeployer.listServers().stream().map(Object::toString).toList();
            } catch (Exception e) {
                servers = List.of();
            }
        }
        return servers;
    }

    private void add(List<Candidate> out, List<String> items, String group) {
        for (String s : items) out.add(new Candidate(s, s, group, null, null, null, false));
    }

    private void commands(List<Candidate> out) {
        for (String c : COMMANDS) {
            out.add(new Candidate(c, c, "commands", CMD_DESC.get(c), null, null, false));
        }
    }
}
