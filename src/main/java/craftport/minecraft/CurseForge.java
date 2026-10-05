package craftport.minecraft;

import com.google.gson.JsonObject;
import craftport.base.Json;
import craftport.base.Log;
import craftport.base.Task;
import craftport.net.Downloader;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CurseForge 模组搜索与下载（对应 ModDownload.vb / ResourceSearcher.vb 的 CurseForge 部分）。
 *
 * 访问策略与 PCL2 的镜像规则一致：
 *  - API：api.curseforge.com → mod.mcimirror.top/curseforge 代理（无需 API key）
 *  - 文件：forgecdn 直链（mediafilez.forgecdn.net）优先，mcimirror 文件代理兜底
 */
public final class CurseForge {

    private static final String API = "https://mod.mcimirror.top/curseforge";
    private static final long GAME_ID = 432; // Minecraft: Java Edition
    private static final long CLASS_ID = 6;  // Mods 分类

    /** 搜索结果条目（icon 为缩略图 URL，可能为空）。 */
    public record ModInfo(long id, String name, String slug, String summary, long downloads, String icon) {
        @Override
        public String toString() { return name + "   [↓ " + String.format("%,d", downloads) + "]"; }
    }

    /** 模组文件条目（modId 与 requiredFileIds 供依赖补全用；relationType=3 才是 required）。 */
    public record FileInfo(long id, long modId, String name, String displayName, String date, long size,
                           List<String> gameVersions, List<Long> requiredFileIds) {
        @Override
        public String toString() {
            String d = date.length() >= 10 ? date.substring(0, 10) : date;
            String mb = size > 0 ? String.format("   %.1fMB", size / 1048576.0) : "";
            return displayName + "   " + d + mb;
        }
    }

    private CurseForge() {}

    /** 统一解析单个文件 JSON（含 modId 与 required 依赖 fileId 列表）。 */
    private static FileInfo parseFile(JsonObject o) {
        List<String> versions = new ArrayList<>();
        if (o.has("gameVersions") && o.get("gameVersions").isJsonArray()) {
            for (var v : o.getAsJsonArray("gameVersions")) versions.add(v.getAsString());
        }
        List<Long> required = new ArrayList<>();
        if (o.has("dependencies") && o.get("dependencies").isJsonArray()) {
            for (var d : o.getAsJsonArray("dependencies")) {
                if (!d.isJsonObject()) continue;
                var dep = d.getAsJsonObject();
                if (Json.longOf(dep, "relationType", 0) == 3) required.add(Json.longOf(dep, "id", 0L));
            }
        }
        return new FileInfo(Json.longOf(o, "id", 0), Json.longOf(o, "modId", 0),
                Json.str(o, "fileName", ""),
                Json.str(o, "displayName", Json.str(o, "fileName", "")),
                Json.str(o, "fileDate", ""),
                Json.longOf(o, "fileLength", 0),
                List.copyOf(versions), List.copyOf(required));
    }

    /** CurseForge 内容分类（参考 PCL2 下载页：Mod/整合包/资源包/光影）。 */
    public enum Category {
        MOD(6L), MODPACK(4471L), RESOURCE_PACK(12L), SHADER(6552L);
        public final long classId;
        Category(long classId) { this.classId = classId; }
    }

    /** 搜索模组（Mods 分类，按下载量降序，最多 30 条）。 */
    public static List<ModInfo> search(String query) throws IOException {
        return search(query, Category.MOD);
    }

    /** 搜索整合包（Modpacks 分类 classId=4471）。 */
    public static List<ModInfo> searchModpacks(String query) throws IOException {
        return search(query, Category.MODPACK);
    }

    /** 按分类搜索（默认 30 条）。 */
    public static List<ModInfo> search(String query, Category category) throws IOException {
        return search(query, category, 30);
    }

    /**
     * 按分类搜索。空关键词 = 热门榜单（sortField=2 下载量降序），此时调用方通常给更小的 pageSize
     * ——镜像对未缓存的大响应限速（~30KB/s），30 条约 770KB 要 25 秒，15 条可减半等待。
     */
    public static List<ModInfo> search(String query, Category category, int pageSize) throws IOException {
        String url = API + "/v1/mods/search?gameId=" + GAME_ID + "&classId=" + category.classId
                + "&searchFilter=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8)
                + "&sortField=2&sortOrder=desc&pageSize=" + pageSize;
        JsonObject resp = craftport.net.Net.getJson(url);
        List<ModInfo> list = new ArrayList<>();
        if (resp.has("data") && resp.get("data").isJsonArray()) {
            for (var e : resp.getAsJsonArray("data")) {
                if (!e.isJsonObject()) continue;
                var o = e.getAsJsonObject();
                list.add(new ModInfo(Json.longOf(o, "id", 0), Json.str(o, "name", ""),
                        Json.str(o, "slug", ""), Json.str(o, "summary", ""),
                        Json.longOf(o, "downloadCount", 0), logoOf(o)));
            }
        }
        return list;
    }

    /** logo.thumbnailUrl 优先，退回 logo.url（面板结果列表显示图标用）。 */
    private static String logoOf(JsonObject o) {
        if (o.has("logo") && o.get("logo").isJsonObject()) {
            var logo = o.getAsJsonObject("logo");
            String t = Json.str(logo, "thumbnailUrl", "");
            return !t.isBlank() ? t : Json.str(logo, "url", "");
        }
        return "";
    }

    /**
     * 批量获取文件信息（对应 PCL2 的 POST /v1/mods/files，每批 100 个）。
     * 整合包安装用它一次性拿到全部成员 mod 的文件名与大小。
     */
    public static List<FileInfo> filesBatch(List<Long> fileIds) throws IOException {
        List<FileInfo> out = new ArrayList<>();
        for (int from = 0; from < fileIds.size(); from += 100) {
            var chunk = fileIds.subList(from, Math.min(fileIds.size(), from + 100));
            StringBuilder json = new StringBuilder("{\"fileIds\":[");
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) json.append(',');
                json.append(chunk.get(i));
            }
            json.append("]}");
            JsonObject resp = craftport.net.Net.getJsonViaPost(API + "/v1/mods/files", json.toString());
            if (resp.has("data") && resp.get("data").isJsonArray()) {
                for (var e : resp.getAsJsonArray("data")) {
                    if (!e.isJsonObject()) continue;
                    var o = e.getAsJsonObject();
                    if (!Json.bool(o, "isAvailable", true)) continue;
                    out.add(parseFile(o));
                }
            }
        }
        return out;
    }

    /** forgecdn 直链（按 fileId 与文件名构造；文件名按 RFC 3986 路径段编码——+ 编为 %2B、空格编为 %20，否则 CDN 403/URI 报错）。 */
    public static String cdnUrl(long fileId, String fileName) {
        return "https://mediafilez.forgecdn.net/files/" + (fileId / 1000) + "/" + (fileId % 1000) + "/"
                + encodePathSegment(fileName);
    }

    private static String encodePathSegment(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    /** 文件全量列表缓存（按 modId；大模组一次拉取可达数 MB，切换版本过滤时复用）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, List<FileInfo>> FILE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 获取模组文件列表（新在前，最多 50 条）。
     * 镜像的 gameVersion 服务端过滤无效，改为拉取全量后按 gameVersions 数组客户端过滤；
     * 全量结果按 modId 缓存，同一模组重复过滤不再请求网络。
     */
    public static List<FileInfo> files(long modId, String mcVersion) throws IOException {
        List<FileInfo> all = FILE_CACHE.computeIfAbsent(modId, id -> {
            try {
                return fetchAllFiles(id);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        List<FileInfo> list = new ArrayList<>();
        for (FileInfo f : all) {
            if (mcVersion != null && !mcVersion.isBlank() && !f.gameVersions().contains(mcVersion)) continue;
            list.add(f);
            if (list.size() >= 50) break;
        }
        return list;
    }

    /** 拉取并缓存模组的全部可用文件（按 id 倒序 = 新在前）。 */
    private static List<FileInfo> fetchAllFiles(long modId) throws IOException {
        JsonObject resp = craftport.net.Net.getJson(API + "/v1/mods/" + modId + "/files?pageSize=999");
        List<FileInfo> list = new ArrayList<>();
        if (resp.has("data") && resp.get("data").isJsonArray()) {
            for (var e : resp.getAsJsonArray("data")) {
                if (!e.isJsonObject()) continue;
                var o = e.getAsJsonObject();
                if (!Json.bool(o, "isAvailable", true)) continue;
                list.add(parseFile(o));
            }
        }
        list.sort(Comparator.comparingLong(FileInfo::id).reversed());
        return list;
    }

    /** 下载模组文件到目标路径（forgecdn 直链优先，mcimirror 文件代理兜底）。 */
    public static void download(FileInfo f, Path dest) throws IOException {
        download(f, dest, null);
    }

    /** 下载模组文件到目标路径（可带进度回调）。 */
    public static void download(FileInfo f, Path dest,
                                java.util.function.BiConsumer<Double, String> progress) throws IOException {
        if (f.id() <= 0 || f.name().isBlank()) throw new IOException("文件信息不完整");
        // 两级 URL 都必须用编码后的路径（+ → %2B、空格 → %20，否则 CDN 403 / URI 非法）
        String cdnPath = (f.id() / 1000) + "/" + (f.id() % 1000) + "/" + encodePathSegment(f.name());
        List<String> urls = List.of(
                "https://mediafilez.forgecdn.net/files/" + cdnPath,
                "https://mod.mcimirror.top/files/" + cdnPath);
        IOException last = null;
        for (String url : urls) {
            try {
                Downloader.download(new Downloader.DownloadItem(url, dest, "", f.size(), f.name()), progress);
                return;
            } catch (Task.CanceledException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                Log.warn("模组下载源失败 " + url + ": " + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("模组下载失败");
    }
}
