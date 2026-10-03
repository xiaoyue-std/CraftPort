package pcl.base;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON 工具，替代 VB.NET 的 JsonObject（MeloongCore 提供）。
 */
public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final Gson GSON_COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    /** 解析为 JsonObject，失败返回 null（对应 PCL 的 JsonCheck）。 */
    public static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonElement e = JsonParser.parseString(text);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            Log.warn("JSON 解析失败: " + e.getMessage());
            return null;
        }
    }

    public static JsonElement parse(String text) {
        try { return JsonParser.parseString(text); } catch (Exception e) { return null; }
    }

    public static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsString();
    }

    public static int intOf(JsonObject o, String key, int def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return def; }
    }

    public static long longOf(JsonObject o, String key, long def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsLong(); } catch (Exception e) { return def; }
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsBoolean(); } catch (Exception e) { return def; }
    }
}
