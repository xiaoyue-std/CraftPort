package pcl.minecraft;

import pcl.base.Json;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Server List Ping（Minecraft 无鉴权状态查询协议，手写 varint 帧实现，零依赖）。
 * 面板用它查询在线人数——对被外部（CLI/systemd）启动的服务器同样有效。
 */
public final class ServerPing {

    /** 查询结果（motd 可能为空；查询失败返回 null，由调用方决定展示）。 */
    public record Status(int online, int max, String motd, String version) {}

    private ServerPing() {}

    /** 向 127.0.0.1:port 发起一次 SLP；任何失败返回 null（服务器离线/非 MC 端口/超时）。 */
    public static Status pingLocal(int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            var out = new DataOutputStream(socket.getOutputStream());
            var in = socket.getInputStream();

            // handshake: 包 0x00 = 协议版本(-1) + 主机 + 端口 + 下一状态(1=状态)
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarint(hs, -1);
            byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            writeVarint(hs, host.length);
            hs.write(host);
            hs.write((port >>> 8) & 0xFF);  // 端口:大端序 short
            hs.write(port & 0xFF);
            writeVarint(hs, 1);
            writePacket(out, 0x00, hs.toByteArray());
            // status request: 空包 0x00
            writePacket(out, 0x00, new byte[0]);

            readVarint(in);            // 包长
            if (readVarint(in) != 0x00) return null;  // 包 id
            int strLen = readVarint(in);
            byte[] body = in.readNBytes(strLen);
            if (body.length != strLen) return null;
            return parse(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static Status parse(String json) {
        var o = Json.parseObject(json);
        if (o == null) return null;
        int online = 0, max = 0;
        if (o.has("players") && o.get("players").isJsonObject()) {
            var p = o.getAsJsonObject("players");
            online = Json.intOf(p, "online", 0);
            max = Json.intOf(p, "max", 0);
        }
        String motd = "";
        if (o.has("description")) {
            if (o.get("description").isJsonPrimitive()) motd = o.get("description").getAsString();
            else if (o.get("description").isJsonObject()) motd = Json.str(o.getAsJsonObject("description"), "text", "");
        }
        String version = o.has("version") && o.get("version").isJsonObject()
                ? Json.str(o.getAsJsonObject("version"), "name", "") : "";
        return new Status(online, max, motd, version);
    }

    private static void writePacket(DataOutputStream out, int packetId, byte[] body) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarint(buf, packetId);
        buf.write(body);
        byte[] packet = buf.toByteArray();
        writeVarint(out, packet.length);
        out.write(packet);
        out.flush();
    }

    private static void writeVarint(java.io.OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarint(InputStream in) throws IOException {
        int value = 0, position = 0;
        byte b;
        do {
            b = (byte) in.read();
            value |= (b & 0x7F) << position;
            position += 7;
            if (position > 35) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return value;
    }
}
