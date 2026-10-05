package craftport.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import craftport.base.Json;
import craftport.base.Log;
import craftport.base.Os;
import craftport.net.Downloader;
import craftport.net.Net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Mojang 官方 Java 运行时自动下载，移植自 ModJava.vb 的 SelectOrDownloadJava 下载分支。
 *  - 运行时清单：piston-meta 的 products/java-runtime/all.json（BMCLAPI 镜像同路径）
 *  - 组件选择：优先版本 json 声明的 javaVersion.component（1.20.5+ 为 java-runtime-delta/Java 21），
 *    老版本回退 jre-legacy（Java 8）
 *  - 安装位置：{.minecraft}/runtime/{component}/，Linux/macOS 上自动为可执行文件加执行权限
 */
public final class JavaRuntimeDownloader {

    private static final String ALL_JSON =
            "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private JavaRuntimeDownloader() {}

    /**
     * 确保指定版本所需的 Java 运行时已安装，返回安装目录。
     * 已存在则直接返回，否则下载（按下载源设置走官方/镜像）。
     */
    public static Path ensureRuntime(McVersion version) throws IOException {
        return ensureComponent(componentFor(version));
    }

    /** 确保指定组件（如 java-runtime-delta / jre-legacy）已安装，返回安装目录（服务端部署等场景复用）。 */
    public static Path ensureComponent(String component) throws IOException {
        Path target = McFolder.selectedRoot().resolve("runtime").resolve(component);
        Path exe = target.resolve("bin").resolve(Os.exeName("java"));
        if (Files.isRegularFile(exe)) {
            Log.info("Java 运行时已存在: " + component);
            return target;
        }
        download(component, target);
        return target;
    }

    /** 该版本应使用的运行时组件名。 */
    static String componentFor(McVersion version) {
        String declared = version.javaComponent();
        if (!declared.isBlank()) return declared;
        int major = version.requiredJavaMajor();
        if (major >= 21) return "java-runtime-delta"; // Java 21
        if (major >= 17) return "java-runtime-gamma"; // Java 17
        return "jre-legacy";                          // Java 8（老版本）
    }

    /** 下载指定组件到目标目录。all.json 结构：{OS键: {组件名: [条目]}}，条目内含 manifest 指向文件清单。 */
    static void download(String component, Path target) throws IOException {
        Log.info("开始下载 Java 运行时 " + component + " …");
        JsonObject all = fetchAllJson();

        // OS 键（与 Mojang 清单一致）：windows-x64/-x86/-arm64、linux/-i386、mac-os/-arm64
        String osKey;
        if (Os.IS_WINDOWS) {
            osKey = Os.OS_ARCH.equals("arm64") ? "windows-arm64"
                    : Os.ARCH_BITS == 64 ? "windows-x64" : "windows-x86";
        } else if (Os.IS_MACOS) {
            osKey = Os.OS_ARCH.equals("arm64") ? "mac-os-arm64" : "mac-os";
        } else {
            if (Os.OS_ARCH.equals("arm64") || Os.OS_ARCH.equals("arm32")) {
                throw new IOException("Mojang 未提供 " + Os.OS_ARCH + " Linux 的官方 Java 运行时，"
                        + "请自行安装（如 openjdk-21-jdk）");
            }
            osKey = Os.ARCH_BITS == 64 ? "linux" : "linux-i386";
        }

        JsonObject osNode = all.has(osKey) && all.get(osKey).isJsonObject()
                ? all.getAsJsonObject(osKey) : null;
        if (osNode == null) throw new IOException("运行时清单中没有平台 " + osKey);

        JsonArray candidates = osNode.has(component) && osNode.get(component).isJsonArray()
                ? osNode.getAsJsonArray(component) : new JsonArray();
        if (candidates.size() == 0) {
            throw new IOException("运行时清单中没有组件 " + component + "（平台 " + osKey + "）");
        }
        JsonObject entry = candidates.get(0).getAsJsonObject();
        if (!entry.has("manifest") || !entry.get("manifest").isJsonObject()) {
            throw new IOException("运行时条目缺少 manifest: " + component);
        }

        // 第二步：拉取该组件的文件清单（piston-meta 的 manifest json，files 为 {相对路径: 描述符} 映射）
        JsonObject manifestInfo = entry.getAsJsonObject("manifest");
        String manifestUrl = Json.str(manifestInfo, "url", "");
        if (manifestUrl.isBlank()) throw new IOException("manifest 下载地址为空");
        JsonObject manifest = fetchJson(manifestUrl);
        if (!manifest.has("files") || !manifest.get("files").isJsonObject()) {
            throw new IOException("运行时 " + component + " 的 manifest 缺少 files");
        }

        // 下载全部文件（逐文件 SHA1 校验；镜像规则自动把 piston-data 换成 BMCLAPI）
        List<Downloader.DownloadItem> items = new ArrayList<>();
        List<String> executables = new ArrayList<>();
        for (var fe : manifest.getAsJsonObject("files").entrySet()) {
            String rel = fe.getKey();
            if (!fe.getValue().isJsonObject()) continue;
            JsonObject f = fe.getValue().getAsJsonObject();
            String type = Json.str(f, "type", "file");
            switch (type) {
                case "directory" -> Files.createDirectories(target.resolve(rel));
                case "link" -> { /* 符号链接极少出现，跳过 */ }
                default -> {
                    if (!f.has("downloads") || !f.getAsJsonObject("downloads").has("raw")) continue;
                    JsonObject raw = f.getAsJsonObject("downloads").getAsJsonObject("raw");
                    items.add(new Downloader.DownloadItem(
                            Json.str(raw, "url", ""),
                            target.resolve(rel),
                            Json.str(raw, "sha1", ""),
                            Json.longOf(raw, "size", 0),
                            component + "/" + rel));
                    if (Json.bool(f, "executable", false)) executables.add(rel);
                }
            }
        }
        if (items.isEmpty()) throw new IOException("运行时 " + component + " 的文件清单为空");
        Downloader.downloadBatch(items, done -> {
            if (done % 50 == 0) Log.info("Java 运行时下载进度 " + done + "/" + items.size());
        });

        // Linux/macOS：为声明的可执行文件加执行权限
        for (String rel : executables) {
            markExecutable(target.resolve(rel));
        }
        // bin/java 本体也确保可执行（清单遗漏时的兜底）
        markExecutable(target.resolve("bin").resolve(Os.exeName("java")));
        Log.info("Java 运行时下载完成: " + component);
    }

    private static void markExecutable(Path file) {
        try {
            if (!Files.isRegularFile(file)) return;
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 无 POSIX 权限，忽略
        }
    }

    /** 获取运行时清单（自动优先镜像，失败回退官方）。 */
    private static JsonObject fetchAllJson() throws IOException {
        return fetchJson(ALL_JSON);
    }

    /** 获取 JSON：镜像优先（可按设置切换），失败回退官方。 */
    private static JsonObject fetchJson(String officialUrl) throws IOException {
        String mirror = Net.mirrorUrl(officialUrl);
        IOException last = null;
        String[] urls = mirror != null
                ? (Downloader.sourcePolicy() == 2 ? new String[]{officialUrl, mirror} : new String[]{mirror, officialUrl})
                : new String[]{officialUrl};
        for (String url : urls) {
            try {
                return Net.getJson(url);
            } catch (IOException e) {
                last = e;
                Log.warn("清单获取失败 " + url + ": " + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("清单获取失败");
    }
}
