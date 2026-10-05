package craftport.minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * server.properties 的读取与写回（Web 面板属性编辑用）。
 * 与 Minecraft 的 java.util.Properties 存取保持往返兼容：
 * 值按 Properties 规则转义（反斜杠、\n \t \r、unicode 转义），文件本身为 UTF-8（1.18+ 服务端读取编码）。
 */
public final class ServerProperties {

    private ServerProperties() {}

    /** 按文件顺序读取键值对（跳过注释与空行）。 */
    public static LinkedHashMap<String, String> load(Path dir) throws IOException {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(dir.resolve("server.properties"), StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            map.put(unescape(t.substring(0, eq)), unescape(t.substring(eq + 1)));
        }
        return map;
    }

    /** 覆写 server.properties（Minecraft 同款两行注释头 + key=value）。 */
    public static void save(Path dir, Map<String, String> props) throws IOException {
        StringBuilder sb = new StringBuilder("#Minecraft server properties\n#")
                .append(java.time.LocalDateTime.now()).append('\n');
        for (var e : props.entrySet()) {
            sb.append(escape(e.getKey())).append('=').append(escape(e.getValue())).append('\n');
        }
        Files.writeString(dir.resolve("server.properties"), sb.toString(), StandardCharsets.UTF_8);
    }

    /** Properties 写出规则：控制字符与非 ASCII 转 unicode 转义，保持 Properties.load 可无损读回。 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        if (s.startsWith(" ")) sb.append('\\');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Properties 读取规则：\n \t \r 与 unicode 转义还原，未知转义丢弃反斜杠（与 java.util.Properties 一致）。 */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            if (++i >= s.length()) break;
            char n = s.charAt(i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (NumberFormatException e) {
                        sb.append('u');
                    }
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }
}
