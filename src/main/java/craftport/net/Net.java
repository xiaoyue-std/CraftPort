package craftport.net;

import craftport.base.Json;
import craftport.base.Log;
import craftport.base.Os;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网络请求模块，移植自 Modules/Base/ModNet.vb 的 NetRequestByClientRetry。
 * 重试策略与 PCL2 一致：共 3 次，超时 10s → 30s → 4s；403/404 不重试；429 先等待 10 秒。
 * 同时实现 BMCLAPI 镜像 URL 换算规则。
 */
public final class Net {
    /** BMCLAPI 镜像源（对应 PCL2 的下载镜像规则）。 */
    public static final String BMCLAPI = "https://bmclapi2.bangbang93.com";
    public static final String MOJANG_META = "https://launchermeta.mojang.com";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static class NetException extends IOException {
        public final int statusCode;
        public NetException(String msg, int code) { super(msg); this.statusCode = code; }
    }

    private Net() {}

    public static HttpClient client() { return CLIENT; }

    /** GET 文本，按 PCL2 重试策略。 */
    public static String get(String url) throws IOException {
        return get(url, java.util.Map.of());
    }

    /** GET 文本（带请求头，如 Minecraft API 的 Authorization）。 */
    public static String get(String url, Map<String, String> headers) throws IOException {
        IOException last = null;
        int[] timeouts = {10, 30, 4};
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest.Builder builder = request(url).timeout(Duration.ofSeconds(timeouts[attempt]));
                for (var h : headers.entrySet()) builder.header(h.getKey(), h.getValue());
                HttpRequest req = builder.build();
                HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 429) {
                    Log.warn("请求 429，等待 10 秒后重试: " + url);
                    Thread.sleep(10_000);
                    continue;
                }
                if (resp.statusCode() != 200) {
                    throw new NetException("HTTP " + resp.statusCode() + " - " + url, resp.statusCode());
                }
                return new String(resp.body(), StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("请求被中断", e);
            } catch (NetException e) {
                // 403/404 直接失败不重试（对应 PCL2 规则）
                if (e.statusCode == 403 || e.statusCode == 404) throw e;
                last = e;
                Log.warn("请求失败(第" + (attempt + 1) + "次) " + url + ": " + e.getMessage());
            } catch (IOException e) {
                last = e;
                Log.warn("请求失败(第" + (attempt + 1) + "次) " + url + ": " + e.getMessage());
            }
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("请求被中断", ie);
            }
        }
        throw last != null ? last : new IOException("请求失败: " + url);
    }

    /** GET 并要求返回完整 JSON（对应 RequireJson 校验）。 */
    public static com.google.gson.JsonObject getJson(String url) throws IOException {
        String text = get(url);
        com.google.gson.JsonObject o = Json.parseObject(text);
        if (o == null) throw new IOException("返回内容不是有效 JSON: " + url);
        return o;
    }

    /** POST 表单数据（微软登录等用）。 */
    public static String postForm(String url, Map<String, String> form) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return post(url, sb.toString(), "application/x-www-form-urlencoded");
    }

    public static String postJson(String url, String json) throws IOException {
        return post(url, json, "application/json");
    }

    /** POST JSON 并把响应解析为 JsonObject（CurseForge 批量文件接口等）。 */
    public static com.google.gson.JsonObject getJsonViaPost(String url, String json) throws IOException {
        com.google.gson.JsonObject o = Json.parseObject(postJson(url, json));
        if (o == null) throw new IOException("Response is not valid JSON: " + url);
        return o;
    }

    public static String post(String url, String body, String contentType) throws IOException {
        HttpRequest req = request(url)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
        if (resp.statusCode() != 200) {
            throw new NetException("HTTP " + resp.statusCode() + " - " + url + " - " + resp.body(), resp.statusCode());
        }
        return resp.body();
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "CraftPort/1.0 (Java " + System.getProperty("java.version") + "; " + Os.OS_NAME + ")")
                // 所有请求统一 60s 响应头超时，防镜像挂起连接（正文流另有重试与兜底）
                .timeout(Duration.ofSeconds(60));
    }

    /** 快速 POST，不带重试（下载器分片用）。 */
    public static HttpResponse<byte[]> getRaw(String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest req = request(url).timeout(timeout).build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    // ==================== BMCLAPI 镜像规则 ====================

    /**
     * 将官方 URL 换算为 BMCLAPI 镜像 URL，规则与 ModNet.vb 一致；无法换算时返回 null。
     *  - version manifest: launchermeta.mojang.com/mc/game/... → bmclapi/mc/game/...
     *  - assets: resources.download.minecraft.net/{x}/{y} → bmclapi/assets/{x}/{y}
     *  - libraries: libraries.minecraft.net/... → bmclapi/maven/...
     *  - 版本 json/jar（launcher.mojang.com / piston-meta / piston-data）→ bmclapi 根路径
     */
    public static String mirrorUrl(String url) {
        if (url == null) return null;
        if (url.startsWith(BMCLAPI)) return url;
        if (url.startsWith("https://launchermeta.mojang.com/mc/game/")
                || url.startsWith("https://piston-meta.mojang.com/mc/game/")) {
            return BMCLAPI + url.substring(url.indexOf("/mc/game/"));
        }
        if (url.startsWith("https://resources.download.minecraft.net/")) {
            return BMCLAPI + url.substring("https://resources.download.minecraft.net".length());
        }
        if (url.startsWith("https://libraries.minecraft.net/")) {
            return BMCLAPI + "/maven" + url.substring("https://libraries.minecraft.net".length());
        }
        // Forge / 其他 maven 工件（version.json 里大量 maven.minecraftforge.net 库）
        if (url.startsWith("https://maven.minecraftforge.net/")) {
            return BMCLAPI + "/maven" + url.substring("https://maven.minecraftforge.net".length());
        }
        for (String host : new String[]{
                "https://launchermeta.mojang.com/", "https://launcher.mojang.com/",
                "https://piston-meta.mojang.com/", "https://piston-data.mojang.com/"}) {
            if (url.startsWith(host)) {
                return BMCLAPI + url.substring(host.length() - 1);
            }
        }
        return null;
    }

    /** 构造 Map 的简便方法。 */
    public static Map<String, String> form(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
