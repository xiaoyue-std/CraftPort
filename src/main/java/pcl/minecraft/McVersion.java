package pcl.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pcl.base.Json;
import pcl.base.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minecraft 版本对象，移植自 ModMinecraft.vb 的 McVersion。
 *  - 读取 versions/{名}/{名}.json，递归合并 inheritsFrom（子版本字段的优先，libraries 子版本在前）
 *  - 提供版本识别（正式版/快照/远古版）、版本隔离后的游戏目录、客户端 jar 路径
 */
public final class McVersion {

    public final String name;          // 版本文件夹名（即 version_name）
    public final Path folder;          // versions/{名}/
    public final Path jsonFile;
    public JsonObject info;            // 合并 inheritsFrom 后的完整 json
    public String releaseTime = "";
    public String mcVersion = "";      // 对应 clientVersion / id 识别结果
    public String infoSource = "";     // 识别来源（与 PCL2 的 InstanceInfoSource 对应）
    public boolean isOld;              // 远古版本（releaseTime < 2013）
    public boolean isSnapshot;         // 快照/非正式版
    public String rootName;            // 继承链最底层的原版版本名（Fabric/Forge 的客户端 jar 所在）

    private McVersion(String name, Path folder) {
        this.name = name;
        this.folder = folder;
        this.jsonFile = folder.resolve(name + ".json");
    }

    /** 加载并解析版本；json 损坏返回 null。 */
    public static McVersion load(Path folder, String name) {
        McVersion v = new McVersion(name, folder);
        JsonObject raw = readJson(v.jsonFile);
        if (raw == null) return null;
        v.info = mergeInherits(raw, folder, name, 0);
        v.rootName = resolveRootName(raw, folder, name);
        v.identify();
        return v;
    }

    /** 沿 inheritsFrom 链找出最底层的原版版本名（客户端 jar 属于该版本文件夹）。 */
    private static String resolveRootName(JsonObject raw, Path folder, String name) {
        String root = name;
        JsonObject cur = raw;
        for (int depth = 0; depth < 5; depth++) {
            String parentName = Json.str(cur, "inheritsFrom", "");
            if (parentName.isBlank() || parentName.equals(name)) break;
            root = parentName;
            Path versionsDir = folder.getParent();
            Path parentFile = versionsDir != null
                    ? versionsDir.resolve(parentName).resolve(parentName + ".json")
                    : folder.resolve(parentName).resolve(parentName + ".json");
            JsonObject parent = readJson(parentFile);
            if (parent == null) break;
            cur = parent;
        }
        return root;
    }

    private static JsonObject readJson(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            return Json.parseObject(Files.readString(file));
        } catch (Exception e) {
            Log.warn("版本 json 读取失败: " + file + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 递归合并 inheritsFrom（对应 JsonObject 的合并逻辑）：
     *  防自引用与过深嵌套；libraries：子版本在前、父版本在后（classpath 顺序敏感）；
     *  其余对象/数组：子版本优先。
     */
    private static JsonObject mergeInherits(JsonObject child, Path folder, String name, int depth) {
        if (depth > 5) return child;
        String parentName = Json.str(child, "inheritsFrom", "");
        if (parentName.isBlank() || parentName.equals(name)) return child;
        // 父版本与子版本同级，都位于 versions/ 目录下
        Path versionsDir = folder.getParent();
        Path parentFile = versionsDir != null
                ? versionsDir.resolve(parentName).resolve(parentName + ".json")
                : folder.resolve(parentName).resolve(parentName + ".json");
        JsonObject parent = readJson(parentFile);
        if (parent == null) {
            Log.warn("找不到父版本 " + parentName + "，版本 " + name + " 可能无法启动");
            return child;
        }
        parent = mergeInherits(parent, folder, parentName, depth + 1);
        JsonObject merged = parent.deepCopy();
        for (var entry : child.entrySet()) {
            String key = entry.getKey();
            if ("inheritsFrom".equals(key)) continue;
            JsonElement cv = entry.getValue();
            if ("libraries".equals(key) && cv.isJsonArray()) {
                // 子版本库在前，父版本库在后（与 PCL2 一致）
                JsonArray arr = new JsonArray();
                cv.getAsJsonArray().forEach(arr::add);
                if (merged.has(key) && merged.get(key).isJsonArray()) {
                    merged.getAsJsonArray(key).forEach(arr::add);
                }
                merged.add(key, arr);
            } else if ("arguments".equals(key) && cv.isJsonObject()) {
                JsonObject childArgs = cv.getAsJsonObject();
                JsonObject parentArgs = merged.has("arguments") && merged.get("arguments").isJsonObject()
                        ? merged.getAsJsonObject("arguments") : new JsonObject();
                JsonObject args = parentArgs.deepCopy();
                for (String argKey : new String[]{"game", "jvm"}) {
                    JsonArray arr = new JsonArray();
                    if (childArgs.has(argKey) && childArgs.get(argKey).isJsonArray()) {
                        childArgs.getAsJsonArray(argKey).forEach(arr::add);
                    }
                    if (args.has(argKey) && args.get(argKey).isJsonArray()) {
                        args.getAsJsonArray(argKey).forEach(arr::add);
                    }
                    if (arr.size() > 0) args.add(argKey, arr);
                }
                merged.add("arguments", args);
            } else {
                merged.add(key, cv);
            }
        }
        return merged;
    }

    /** 版本识别（对应 ModMinecraft 的识别优先级链，此处实现主要几级）。 */
    private void identify() {
        // 1. clientVersion / javaVersion.majorVersion 之外的 MC 版本
        mcVersion = Json.str(info, "clientVersion", "");
        if (mcVersion.isBlank()) mcVersion = Json.str(info, "id", "");
        // 2. --fml.mcVersion（Forge 1.13+ 的 jvm 参数）
        if (mcVersion.isBlank()) {
            String jvmArgs = argumentsJvmString();
            if (jvmArgs.contains("--fml.mcVersion")) {
                String[] parts = jvmArgs.split("--fml.mcVersion");
                if (parts.length > 1) {
                    String seg = parts[1].trim().split("\\s+")[0];
                    mcVersion = seg.replace("${version}", "").replace("\"", "");
                }
            }
        }
        if (mcVersion.isBlank()) mcVersion = name;
        // 3. releaseTime
        releaseTime = Json.str(info, "releaseTime",
                Json.str(info, "time", "2013-06-13T00:00:00+08:00"));
        try {
            LocalDate date = OffsetDateTime.parse(releaseTime).toLocalDate();
            isOld = date.isBefore(LocalDate.of(2013, 6, 13)); // 1.6 之前为远古版本
        } catch (Exception e) {
            isOld = false;
        }
        // 4. 正式版 / 快照
        isSnapshot = !isOld && mcVersion.matches(".*[a-zA-Z].*") && !mcVersion.matches("1\\.\\d+.*");
        infoSource = "releaseTime";
    }

    private String argumentsJvmString() {
        try {
            if (info.has("arguments") && info.getAsJsonObject("arguments").has("jvm")) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement e : info.getAsJsonObject("arguments").getAsJsonArray("jvm")) {
                    sb.append(e.isJsonObject() ? "" : e.getAsString()).append(' ');
                }
                return sb.toString();
            }
            return Json.str(info, "minecraftArguments", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 沿 inheritsFrom 链取最底层的 assetIndex（对应 DlClientAssetIndexGet）。 */
    public JsonObject assetIndex() {
        return info.has("assetIndex") && info.get("assetIndex").isJsonObject()
                ? info.getAsJsonObject("assetIndex") : null;
    }

    /** 资源索引 id，取不到时老版本回退 legacy。 */
    public String assetsId() {
        JsonObject ai = assetIndex();
        if (ai != null && !Json.str(ai, "id", "").isBlank()) return Json.str(ai, "id", "");
        return isOld ? "legacy" : "legacy";
    }

    /** 合并后的库列表（已按规则过滤当前平台）。 */
    public List<Library> libraries() {
        List<Library> list = new ArrayList<>();
        if (info.has("libraries") && info.get("libraries").isJsonArray()) {
            for (Library lib : Library.parseList(info.getAsJsonArray("libraries"), null)) {
                if (lib.allowed()) list.add(lib);
            }
        }
        return list;
    }

    /**
     * 主 jar 路径（${primary_jar}）。
     * 带 inheritsFrom 的加载器版本（Fabric/Forge 等）没有自己的 jar，
     * 客户端 jar 位于继承链最底层的原版版本文件夹中。
     */
    public Path clientJar() {
        String jar = Json.str(info, "jar", "");
        Path useFolder = folder;
        String useName = name;
        if (rootName != null && !rootName.equals(name)) {
            Path versionsDir = folder.getParent();
            if (versionsDir != null) {
                useFolder = versionsDir.resolve(rootName);
                useName = rootName;
            }
        }
        return useFolder.resolve((jar.isBlank() ? useName : jar) + ".jar");
    }

    /** 客户端 jar 的下载地址（downloads.client.url）。 */
    public String clientJarUrl() {
        if (info.has("downloads") && info.get("downloads").isJsonObject()
                && info.getAsJsonObject("downloads").has("client")
                && info.getAsJsonObject("downloads").get("client").isJsonObject()) {
            return Json.str(info.getAsJsonObject("downloads").getAsJsonObject("client"), "url", "");
        }
        return "";
    }

    public String clientJarSha1() {
        if (info.has("downloads") && info.get("downloads").isJsonObject()
                && info.getAsJsonObject("downloads").has("client")
                && info.getAsJsonObject("downloads").get("client").isJsonObject()) {
            return Json.str(info.getAsJsonObject("downloads").get("client").getAsJsonObject(), "sha1", "");
        }
        return "";
    }

    /** 主类（老版本缺省 net.minecraft.client.main.Main）。 */
    public String mainClass() {
        return Json.str(info, "mainClass", "net.minecraft.client.main.Main");
    }

    /** 新版 arguments（1.13+）。 */
    public boolean hasNewArguments() {
        return info.has("arguments") && info.get("arguments").isJsonObject()
                && info.getAsJsonObject("arguments").has("game");
    }

    /** 旧版 minecraftArguments。 */
    public String oldArguments() {
        return Json.str(info, "minecraftArguments", "");
    }

    /** JVM arguments.jvm 数组（新版）。 */
    public JsonArray newJvmArguments() {
        if (info.has("arguments") && info.getAsJsonObject("arguments").has("jvm")
                && info.getAsJsonObject("arguments").get("jvm").isJsonArray()) {
            return info.getAsJsonObject("arguments").getAsJsonArray("jvm");
        }
        return null;
    }

    public JsonArray newGameArguments() {
        if (info.has("arguments") && info.getAsJsonObject("arguments").has("game")
                && info.getAsJsonObject("arguments").get("game").isJsonArray()) {
            return info.getAsJsonObject("arguments").getAsJsonArray("game");
        }
        return null;
    }

    /** Mojang 声明的 Java 版本要求（javaVersion.majorVersion），无则 -1。 */
    public int requiredJavaMajor() {
        if (info.has("javaVersion") && info.get("javaVersion").isJsonObject()) {
            return Json.intOf(info.getAsJsonObject("javaVersion"), "majorVersion", -1);
        }
        return -1;
    }

    /** Mojang 声明的运行时组件名（javaVersion.component，如 java-runtime-delta），无则空串。 */
    public String javaComponent() {
        if (info.has("javaVersion") && info.get("javaVersion").isJsonObject()) {
            return Json.str(info.getAsJsonObject("javaVersion"), "component", "");
        }
        return "";
    }

    /** 版本隔离后的游戏目录（对应 PathIndie）。 */
    public Path gameDirectory(Path mcRoot, boolean isolation) {
        return isolation ? folder : mcRoot;
    }

    /** natives 解压目录：versions/{名}/{名}-natives（对应 McLaunchNatives）。 */
    public Path nativesDir() {
        return folder.resolve(name + "-natives");
    }

    @Override
    public String toString() { return name; }

    // ---- 访问器（字段为 public final，同时提供方法形式便于 lambda 调用）----
    public String name() { return name; }
    public boolean isSnapshot() { return isSnapshot; }
    public boolean isOld() { return isOld; }
    public String releaseTime() { return releaseTime; }
    public String mcVersion() { return mcVersion; }
    public Path folder() { return folder; }

    /** 排序：正式版在前，版本号新在前（对应列表排序逻辑的简化版）。 */
    public static void sort(List<McVersion> versions) {
        versions.sort((a, b) -> {
            if (a.isOld != b.isOld) return a.isOld ? 1 : -1;
            int t = b.releaseTime.compareTo(a.releaseTime);
            return t != 0 ? t : a.name.compareTo(b.name);
        });
    }

    /** 去重辅助。 */
    public static void distinct(List<McVersion> versions) {
        Set<String> seen = new LinkedHashSet<>();
        versions.removeIf(v -> !seen.add(v.name));
    }

    /** 格式化当前时间（版本发布时间比较用）。 */
    public static String nowIso() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
