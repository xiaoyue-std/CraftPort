package pcl.minecraft;

import com.google.gson.JsonObject;
import pcl.base.Config;
import pcl.base.Json;
import pcl.base.Log;
import pcl.base.Os;
import pcl.net.Net;

import java.io.IOException;
import java.util.Map;

/**
 * 登录模块，移植自 ModLaunch.vb 的登录部分（McLoginServer / GetLegacyUuid / 微软六步登录）。
 * 支持：离线登录、微软登录（Device Code 流，client_id 需自行注册或用环境变量 PCL_MS_CLIENT_ID）。
 */
public final class LoginService {

    public enum Type { OFFLINE, MICROSOFT }

    /** 登录结果（对应 McLoginData）。 */
    public record LoginResult(String username, String uuid, String accessToken, Type type) {}

    private LoginService() {}

    // ==================== 离线登录 ====================

    /**
     * PCL2 的离线 UUID 算法（McLoginLegacyUuid）：
     * 32 位 = hex(用户名长度) 补 0 至 16 位 + hex(稳定 hashCode) 补 0 至 16 位，
     * 然后把第 12 位替换为 "3"、第 16 位替换为 "9"（原字符被丢弃），最后按 8-4-4-4-12 加连字符。
     * hashCode 为稳定哈希（h*31+c），避免依赖 JVM 的 String.hashCode 实现。
     */
    public static String offlineUuid(String username) {
        String lenHex = String.format("%016x", (long) username.length());
        String hashHex = String.format("%016x", stableHash(username) & 0xFFFFFFFFL);
        String full = lenHex + hashHex; // 32 位
        String fixed = full.substring(0, 12) + "3" + full.substring(13, 16)
                + "9" + full.substring(17);
        return fixed.substring(0, 8) + "-" + fixed.substring(8, 12) + "-" + fixed.substring(12, 16)
                + "-" + fixed.substring(16, 20) + "-" + fixed.substring(20);
    }

    /** 稳定哈希（对应 MeloongCore 的 GetStableHashCode，Java 风格 h*31+c，32 位溢出语义）。 */
    private static long stableHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = h * 31 + s.charAt(i);
        }
        return h;
    }

    public static LoginResult offlineLogin(String username) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("用户名不能为空");
        String uuid = offlineUuid(username);
        return new LoginResult(username, uuid, uuid, Type.OFFLINE);
    }

    // ==================== 微软登录（Device Code 流，六步） ====================

    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.live.com/oauth20_token.srf";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_AUTH_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_ENTITLEMENTS = "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    /** 用已缓存的刷新令牌直接换取登录信息；失败返回 null。 */
    public static LoginResult tryRefresh() {
        String refreshToken = Config.cacheRefreshToken;
        if (refreshToken == null || refreshToken.isBlank()) return null;
        try {
            JsonObject token = Json.parseObject(Net.postForm(TOKEN_URL, Net.form(
                    "client_id", clientId(),
                    "refresh_token", refreshToken,
                    "grant_type", "refresh_token")));
            if (token == null || !token.has("access_token")) return null;
            if (token.has("refresh_token")) Config.cacheRefreshToken = token.get("refresh_token").getAsString();
            String accessToken = token.get("access_token").getAsString();
            return finishMicrosoft(accessToken);
        } catch (Exception e) {
            Log.warn("刷新微软登录失败: " + e.getMessage());
            return null;
        }
    }

    /** 完整的设备码登录流程，onDeviceCode 回调展示 verification_url 与 user_code。 */
    public static LoginResult microsoftLogin(java.util.function.BiConsumer<String, String> onDeviceCode)
            throws IOException, InterruptedException {
        String clientId = clientId();
        if (clientId.isBlank()) {
            throw new IOException("未配置微软登录应用 ID。请先在 Azure 注册应用并设置环境变量 PCL_MS_CLIENT_ID，"
                    + "或使用离线登录。");
        }
        // 第 1 步：获取设备码
        JsonObject deviceCode = Json.parseObject(Net.postForm(DEVICE_CODE_URL, Net.form(
                "client_id", clientId, "scope", SCOPE)));
        if (deviceCode == null || !deviceCode.has("user_code")) {
            throw new IOException("获取设备码失败");
        }
        String userCode = deviceCode.get("user_code").getAsString();
        String verificationUrl = Json.str(deviceCode, "verification_uri", "https://www.microsoft.com/link");
        int interval = Json.intOf(deviceCode, "interval", 5);
        long expiresIn = Json.longOf(deviceCode, "expires_in", 900);
        if (onDeviceCode != null) onDeviceCode.accept(verificationUrl, userCode);

        // 轮询令牌
        long deadline = System.currentTimeMillis() + expiresIn * 1000;
        JsonObject token = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JsonObject resp = Json.parseObject(Net.postForm(TOKEN_URL, Net.form(
                    "client_id", clientId,
                    "device_code", deviceCode.get("device_code").getAsString(),
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code")));
            if (resp != null && resp.has("access_token")) { token = resp; break; }
            if (resp != null && resp.has("error")) {
                String err = resp.get("error").getAsString();
                if (err.equals("authorization_pending")) continue;
                throw new IOException("微软登录失败: " + Json.str(resp, "error_description", err));
            }
        }
        if (token == null) throw new IOException("设备码已过期，请重试");
        if (token.has("refresh_token")) {
            Config.cacheRefreshToken = token.get("refresh_token").getAsString();
            Config.save();
        }
        return finishMicrosoft(token.get("access_token").getAsString());
    }

    /** 第 2~6 步：XBL → XSTS → Minecraft → 验证持有 → 拉取档案。 */
    private static LoginResult finishMicrosoft(String accessToken) throws IOException {
        // 第 2 步：XBL
        JsonObject xbl = Json.parseObject(Net.postJson(XBL_URL, Json.GSON_COMPACT.toJson(Map.of(
                "Properties", Map.of("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + accessToken),
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT"))));
        if (xbl == null || !xbl.has("Token")) throw new IOException("Xbox Live 登录失败");
        String xblToken = xbl.get("Token").getAsString();
        String uhs = "";
        if (xbl.has("DisplayClaims") && xbl.get("DisplayClaims").isJsonObject()) {
            var claims = xbl.getAsJsonObject("DisplayClaims");
            if (claims.has("xui") && claims.get("xui").isJsonArray()
                    && claims.getAsJsonArray("xui").size() > 0) {
                uhs = Json.str(claims.getAsJsonArray("xui").get(0).getAsJsonObject(), "uhs", "");
            }
        }

        // 第 3 步：XSTS
        JsonObject xsts = Json.parseObject(Net.postJson(XSTS_URL, Json.GSON_COMPACT.toJson(Map.of(
                "Properties", Map.of("SandboxId", "RETAIL", "UserTokens", java.util.List.of(xblToken)),
                "RelyingParty", "rp://api.minecraftservices.com/",
                "TokenType", "JWT"))));
        if (xsts == null || !xsts.has("Token")) {
            long code = xsts != null && xsts.has("XErr") ? Json.longOf(xsts, "XErr", 0) : 0;
            String hint;
            if (code == 2148916233L) hint = "该微软账号没有 Xbox 档案";
            else if (code >= 2148916235L && code <= 2148916238L) hint = "Xbox 服务拒绝登录（地区限制或未成年账号）";
            else hint = "Xbox 安全令牌获取失败 (XErr=" + code + ")";
            throw new IOException(hint);
        }
        String xstsToken = xsts.get("Token").getAsString();

        // 第 4 步：换取 Minecraft 访问令牌
        JsonObject mcAuth = Json.parseObject(Net.postJson(MC_AUTH_URL, Json.GSON_COMPACT.toJson(Map.of(
                "identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken))));
        if (mcAuth == null || !mcAuth.has("access_token")) throw new IOException("Minecraft 令牌换取失败");
        String mcToken = mcAuth.get("access_token").getAsString();

        // 第 5 步：验证游戏持有
        JsonObject entitlement = Json.parseObject(Net.get(MC_ENTITLEMENTS + "?access_token=" + mcToken));
        if (entitlement == null || !entitlement.has("items")
                || entitlement.getAsJsonArray("items").size() == 0) {
            throw new IOException("该账号未购买 Minecraft: Java Edition");
        }

        // 第 6 步：档案
        JsonObject profile = Json.parseObject(Net.get(MC_PROFILE,
                java.util.Map.of("Authorization", "Bearer " + mcToken)));
        if (profile == null || !profile.has("id")) throw new IOException("获取档案失败");
        return new LoginResult(profile.get("name").getAsString(), profile.get("id").getAsString(),
                mcToken, Type.MICROSOFT);
    }

    private static String clientId() {
        return Config.cacheClientId != null ? Config.cacheClientId : "";
    }
}
