package pcl.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pcl.base.Json;
import pcl.base.Log;
import pcl.base.Os;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个 Minecraft 库（对应 ModMinecraft.vb 中对 libraries 的解析）。
 * 记录坐标、下载 URL、natives 分类器与规则过滤结果。
 */
public final class Library {

    public final String group;
    public final String artifact;
    public final String version;
    public final String classifier; // 下载该库本体时使用的 classifier，一般为 null
    public final JsonObject raw;    // 原始 json 条目
    public final String repoUrl;    // 所属仓库根 URL

    public Library(JsonObject raw, String repoUrl) {
        this.raw = raw;
        this.repoUrl = repoUrl != null ? repoUrl : "https://libraries.minecraft.net/";
        String[] coords = Json.str(raw, "name", "").split(":");
        this.group = coords.length > 0 ? coords[0] : "";
        this.artifact = coords.length > 1 ? coords[1] : "";
        this.version = coords.length > 2 ? coords[2] : "";
        // 坐标第 4 段为 classifier（如 natives-windows，1.19+ 的平台 native 库以此形式列出）
        this.classifier = coords.length > 3 ? coords[3] : null;
    }

    /** 库文件相对路径（不含 classifier 时）。 */
    public String path() {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + (classifier == null || classifier.isBlank() ? "" : "-" + classifier) + ".jar";
    }

    /** 带指定 classifier 的相对路径。 */
    public String pathWithClassifier(String cls) {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + "-" + cls + ".jar";
    }

    public String name() {
        return group + ":" + artifact + ":" + version + (classifier == null || classifier.isBlank() ? "" : ":" + classifier);
    }

    /** 本体下载 URL。 */
    public String url() {
        JsonObject downloads = raw.has("downloads") && raw.get("downloads").isJsonObject()
                ? raw.getAsJsonObject("downloads") : null;
        if (downloads != null && downloads.has("artifact")
                && downloads.get("artifact").isJsonObject()) {
            String u = Json.str(downloads.getAsJsonObject("artifact"), "url", "");
            if (!u.isBlank()) return u;
        }
        String path = raw.has("url") && !Json.str(raw, "url", "").isBlank()
                ? Json.str(raw, "url", "") + path() : repoUrl + path();
        return path;
    }

    /**
     * downloads.artifact 中是否声明了显式下载地址。
     * 没有地址的库由加载器在运行时生成（如 Forge 的 :client 产物），不应尝试下载。
     */
    public boolean hasExplicitUrl() {
        if (!raw.has("downloads") || !raw.get("downloads").isJsonObject()) return false;
        JsonObject downloads = raw.getAsJsonObject("downloads");
        if (!downloads.has("artifact") || !downloads.get("artifact").isJsonObject()) return false;
        return !Json.str(downloads.getAsJsonObject("artifact"), "url", "").isBlank();
    }

    /** SHA1（downloads.artifact.sha1）。 */
    public String sha1() {
        JsonObject downloads = raw.has("downloads") && raw.get("downloads").isJsonObject()
                ? raw.getAsJsonObject("downloads") : null;
        return downloads != null && downloads.has("artifact")
                ? Json.str(downloads.getAsJsonObject("artifact"), "sha1", "") : "";
    }

    public long size() {
        JsonObject downloads = raw.has("downloads") && raw.get("downloads").isJsonObject()
                ? raw.getAsJsonObject("downloads") : null;
        return downloads != null && downloads.has("artifact")
                ? Json.longOf(downloads.getAsJsonObject("artifact"), "size", 0) : 0;
    }

    /**
     * 规则检查（rules 数组），返回该库在当前平台是否启用。
     * 与官方启动器语义一致：无规则默认启用；有规则时默认禁用，按顺序以最后一条命中的规则为准。
     * （例如同一 LWJGL 库会为 windows/linux/mac 各列一条 allow 规则，非目标平台必须排除）
     */
    public boolean allowed() {
        if (!raw.has("rules") || !raw.get("rules").isJsonArray()) return true;
        boolean result = false;
        for (JsonElement e : raw.getAsJsonArray("rules")) {
            if (!e.isJsonObject()) continue;
            JsonObject rule = e.getAsJsonObject();
            String action = Json.str(rule, "action", "allow");
            if (!rule.has("os") || !rule.get("os").isJsonObject()) {
                // 无 os 限定（仅 features）的规则：启动器未开启 feature，视为不命中
                continue;
            }
            JsonObject os = rule.getAsJsonObject("os");
            String osName = Json.str(os, "name", "");
            boolean osMatch = osName.isEmpty() || osName.equals(Os.OS_NAME);
            boolean archMatch = !os.has("arch") || Json.str(os, "arch", "").equals(Os.OS_ARCH);
            if (osMatch && archMatch) {
                result = action.equals("allow");
            }
        }
        return result;
    }

    /** 是否为 natives 库（有 natives-{os} 或 natives 字段）。 */
    public boolean hasNatives() {
        return raw.has("natives") && raw.get("natives").isJsonObject();
    }

    /** 当前平台 natives classifier（替换 ${arch}）。 */
    public String nativesClassifier() {
        if (!hasNatives()) return null;
        String cls = Json.str(raw.getAsJsonObject("natives"), Os.OS_NAME, null);
        if (cls == null) return null;
        return cls.replace("${arch}", String.valueOf(Os.ARCH_BITS));
    }

    /** natives jar 下载信息（downloads.classifiers）。 */
    public String nativesUrl(String cls) {
        JsonObject downloads = raw.has("downloads") && raw.get("downloads").isJsonObject()
                ? raw.getAsJsonObject("downloads") : null;
        if (downloads != null && downloads.has("classifiers")
                && downloads.get("classifiers").isJsonObject()
                && downloads.getAsJsonObject("classifiers").has(cls)
                && downloads.getAsJsonObject("classifiers").get(cls).isJsonObject()) {
            return Json.str(downloads.getAsJsonObject("classifiers").get(cls).getAsJsonObject(), "url", "");
        }
        // 老版本无 downloads，拼 URL
        return repoUrl + pathWithClassifier(cls);
    }

    public String nativesSha1(String cls) {
        JsonObject downloads = raw.has("downloads") && raw.get("downloads").isJsonObject()
                ? raw.getAsJsonObject("downloads") : null;
        if (downloads != null && downloads.has("classifiers")
                && downloads.get("classifiers").isJsonObject()
                && downloads.getAsJsonObject("classifiers").has(cls)
                && downloads.getAsJsonObject("classifiers").get(cls).isJsonObject()) {
            return Json.str(downloads.getAsJsonObject("classifiers").get(cls).getAsJsonObject(), "sha1", "");
        }
        return "";
    }

    /** Extract 排除规则（例如剔除 OSX 里的 .dylib 等干扰文件）。 */
    public List<String> extractExcludes() {
        List<String> list = new ArrayList<>();
        if (raw.has("extract") && raw.get("extract").isJsonObject()
                && raw.getAsJsonObject("extract").has("exclude")
                && raw.getAsJsonObject("extract").get("exclude").isJsonArray()) {
            for (JsonElement e : raw.getAsJsonObject("extract").getAsJsonArray("exclude")) {
                list.add(e.getAsString());
            }
        }
        return list;
    }

    /** 从 arguments.jvm / minecraftArguments 中解析出的库 URL 一般无需处理，此方法保留接口。 */
    public static Library parse(JsonElement element, String repoUrl) {
        if (element == null || !element.isJsonObject()) return null;
        try {
            return new Library(element.getAsJsonObject(), repoUrl);
        } catch (Exception e) {
            Log.warn("库解析失败: " + e.getMessage());
            return null;
        }
    }

    /** 工具：遍历 json 库数组。 */
    public static List<Library> parseList(JsonArray arr, String repoUrl) {
        List<Library> list = new ArrayList<>();
        if (arr == null) return list;
        for (JsonElement e : arr) {
            Library lib = parse(e, repoUrl);
            if (lib != null) list.add(lib);
        }
        return list;
    }

    /** 游戏目录里的库文件路径。 */
    public Path localPath(Path librariesDir) {
        return librariesDir.resolve(path());
    }
}
