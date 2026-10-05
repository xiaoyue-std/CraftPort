package craftport.minecraft;

import com.google.gson.JsonArray;
import craftport.base.Json;
import craftport.net.Downloader;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Modrinth 模组源（官方 API，免密钥）。
 * 与 CurseForge 源（CurseForge.java）并列，覆盖两大主流模组站。
 */
public final class Modrinth {

    static final String API = "https://api.modrinth.com/v2";

    /** 搜索结果条目（icon 为图标 URL，可能为空）。 */
    public record ModInfo(String projectId, String slug, String title, long downloads, String description,
                          String icon) {
        @Override
        public String toString() { return title + "   [↓ " + String.format("%,d", downloads) + "]"; }
    }

    /** 模组文件条目（primary 文件；projectId 与 requiredProjectIds 供依赖补全用）。 */
    public record FileInfo(String id, String name, String versionNumber, String date, long size, String url,
                           String projectId, List<String> requiredProjectIds) {
        @Override
        public String toString() {
            String d = date.length() >= 10 ? date.substring(0, 10) : date;
            String mb = size > 0 ? String.format("   %.1fMB", size / 1048576.0) : "";
            return name + "   " + d + mb;
        }
    }

    private Modrinth() {}

    /** 搜索 mod 类项目（按下载量，最多 20 条）。 */
    public static List<ModInfo> search(String query) throws IOException {
        return search(query, "mod");
    }

    /**
     * 按项目类型搜索（Modrinth project_type：mod / resourcepack / shader / datapack / modpack）。
     * 对应 PCL2 下载页的分类（Mod / 资源包 / 光影 / 数据包 / 整合包）。
     */
    public static List<ModInfo> search(String query, String projectType) throws IOException {
        String facets = "%5B%5B%22project_type%3A" + URLEncoder.encode(projectType, StandardCharsets.UTF_8)
                .replace("+", "%20") + "%22%5D%5D";
        // 空关键词 = 热门榜单,按下载量排序;有关键词时用默认相关度
        String index = (query == null || query.isBlank()) ? "&index=downloads" : "";
        String url = API + "/search?query=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8)
                + "&limit=20" + index + "&facets=" + facets;
        var root = Json.parse(craftport.net.Net.get(url));
        List<ModInfo> list = new ArrayList<>();
        if (root instanceof com.google.gson.JsonObject o && o.has("hits")) {
            for (var e : o.getAsJsonArray("hits")) {
                if (!e.isJsonObject()) continue;
                var hit = e.getAsJsonObject();
                list.add(new ModInfo(Json.str(hit, "project_id", ""),
                        Json.str(hit, "slug", ""), Json.str(hit, "title", ""),
                        Json.longOf(hit, "downloads", 0),
                        Json.str(hit, "description", ""),
                        Json.str(hit, "icon_url", "")));
            }
        }
        return list;
    }

    /**
     * 获取模组版本文件（新在前，最多 30 条），按 MC 版本与加载器客户端过滤。
     * Modrinth 的服务端过滤参数可用，但为与 CurseForge 源保持一致的简单实现，统一客户端过滤。
     */
    public static List<FileInfo> files(String projectIdOrSlug, String mcVersion, String loader) throws IOException {
        var arr = Json.parse(craftport.net.Net.get(API + "/project/" + projectIdOrSlug + "/version"));
        List<FileInfo> list = new ArrayList<>();
        if (arr instanceof JsonArray versions) {
            for (var e : versions) {
                if (!e.isJsonObject()) continue;
                var v = e.getAsJsonObject();
                List<String> gameVersions = stringList(v, "game_versions");
                if (mcVersion != null && !mcVersion.isBlank() && !gameVersions.contains(mcVersion)) continue;
                if (loader != null && !loader.isBlank() && !stringList(v, "loaders").contains(loader)) continue;
                String fileUrl = "";
                String fileName = "";
                long size = 0;
                if (v.has("files") && v.get("files").isJsonArray() && v.getAsJsonArray("files").size() > 0) {
                    // 优先 primary 文件
                    var files = v.getAsJsonArray("files");
                    var chosen = files.get(0).getAsJsonObject();
                    for (var fe : files) {
                        if (Json.bool(fe.getAsJsonObject(), "primary", false)) { chosen = fe.getAsJsonObject(); break; }
                    }
                    fileUrl = Json.str(chosen, "url", "");
                    fileName = Json.str(chosen, "filename", "");
                    size = Json.longOf(chosen, "size", 0);
                }
                if (fileUrl.isBlank()) continue;
                List<String> required = new ArrayList<>();
                if (v.has("dependencies") && v.get("dependencies").isJsonArray()) {
                    for (var de : v.getAsJsonArray("dependencies")) {
                        if (!de.isJsonObject()) continue;
                        var d = de.getAsJsonObject();
                        if ("required".equals(Json.str(d, "dependency_type", ""))
                                && !Json.str(d, "project_id", "").isBlank()) {
                            required.add(Json.str(d, "project_id", ""));
                        }
                    }
                }
                list.add(new FileInfo(Json.str(v, "id", ""), fileName,
                        Json.str(v, "version_number", ""),
                        Json.str(v, "date_published", ""), size, fileUrl,
                        Json.str(v, "project_id", ""), List.copyOf(required)));
            }
        }
        return list.subList(0, Math.min(30, list.size()));
    }

    /** 下载文件到目标路径（Modrinth CDN 直链）。 */
    public static void download(FileInfo f, Path dest) throws IOException {
        Downloader.download(new Downloader.DownloadItem(f.url(), dest, "", f.size(), f.name()), null);
    }

    private static List<String> stringList(com.google.gson.JsonObject o, String key) {
        List<String> list = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (var e : o.getAsJsonArray(key)) list.add(e.getAsString());
        }
        return list;
    }
}
