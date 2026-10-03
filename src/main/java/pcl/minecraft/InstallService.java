package pcl.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import pcl.base.Json;
import pcl.net.Downloader;
import pcl.net.Net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 版本安装服务，移植自 ModDownload.vb 的版本列表获取与安装（DlClient）。
 * 版本清单按设置在官方源与 BMCLAPI 镜像间选择。
 */
public final class InstallService {

    public record Release(String id, String type, String url, String releaseTime) {
        public boolean isRelease() { return "release".equals(type); }
        public boolean isSnapshot() { return "snapshot".equals(type); }
        public boolean isOld() { return "old_alpha".equals(type) || "old_beta".equals(type); }
    }

    private InstallService() {}

    /** 获取版本清单（对应 ModDownload 的版本列表，支持镜像）。 */
    public static List<Release> fetchManifest() throws IOException {
        String official = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
        int policy = pcl.base.Config.getInt(pcl.base.Config.TOOL_DOWNLOAD_VERSION, 1);
        JsonObject manifest;
        if (policy == 0) {
            try {
                manifest = Net.getJson(Net.BMCLAPI + "/mc/game/version_manifest.json");
            } catch (IOException e) {
                manifest = Net.getJson(official);
            }
        } else if (policy == 2) {
            try {
                manifest = Net.getJson(official);
            } catch (IOException e) {
                manifest = Net.getJson(Net.BMCLAPI + "/mc/game/version_manifest.json");
            }
        } else {
            // 自动：先镜像（对国内更快），失败转官方
            try {
                manifest = Net.getJson(Net.BMCLAPI + "/mc/game/version_manifest.json");
            } catch (IOException e) {
                manifest = Net.getJson(official);
            }
        }
        List<Release> list = new ArrayList<>();
        if (manifest.has("versions") && manifest.get("versions").isJsonArray()) {
            JsonArray arr = manifest.getAsJsonArray("versions");
            for (var e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                list.add(new Release(Json.str(v, "id", ""), Json.str(v, "type", ""),
                        Json.str(v, "url", ""), Json.str(v, "releaseTime", "")));
            }
        }
        return list;
    }

    /** 安装指定版本：下载版本 json（jar 交给启动前的文件补全）。 */
    public static void install(String versionId, Path mcRoot) throws IOException {
        List<Release> manifest = fetchManifest();
        Release target = manifest.stream().filter(r -> r.id().equals(versionId)).findFirst()
                .orElseThrow(() -> new IOException("版本清单中没有 " + versionId));
        Path folder = mcRoot.resolve("versions").resolve(versionId);
        Path jsonFile = folder.resolve(versionId + ".json");
        Files.createDirectories(folder);
        // 版本 json 优先镜像（对应 launcher/mojang meta 的镜像规则）
        String url = target.url();
        String mirror = Net.mirrorUrl(url);
        if (mirror != null && pcl.base.Config.getInt(pcl.base.Config.TOOL_DOWNLOAD_SOURCE, 1) != 2) {
            try {
                Files.writeString(jsonFile, Net.get(mirror), StandardCharsets.UTF_8);
                return;
            } catch (IOException ignored) {
            }
        }
        Files.writeString(jsonFile, Net.get(url), StandardCharsets.UTF_8);
        // 校验 json 可解析
        if (Json.parseObject(Files.readString(jsonFile)) == null) {
            Files.deleteIfExists(jsonFile);
            throw new IOException("版本 json 下载损坏");
        }
    }

    /** 批量下载辅助暴露给 UI。 */
    public static void download(Downloader.DownloadItem item) throws IOException {
        Downloader.download(item, null);
    }
}
