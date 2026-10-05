package pcl.minecraft;

import com.google.gson.JsonObject;
import pcl.base.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 面板 Mods 下载与自动依赖补全（逻辑层，Web 面板只做展示）。
 *
 * 依赖解析规则：
 *  - 主来源是 **jar 内声明**（fabric.mod.json 的 depends / mods.toml 的 dependencies 块）——
 *    MCIMirror 代理的 CurseForge 元数据 dependencies 为空，jar 自带声明最可靠；
 *    声明的 modId 通过 Modrinth（slug 通常一致，404 再退回搜索）解析出最新兼容文件。
 *  - CurseForge 元数据 dependencies（relationType=3，引用 fileId）在镜像有数据时作为补充；
 *    Modrinth 元数据 dependencies（dependency_type=required，引用 projectId）正常可用。
 *  - 已存在同名文件跳过；optional/embedded 依赖不装；队列去重防循环，总量有上限。
 */
public final class ModsService {

    /** 单文件安装上限（依赖闭包防护；fabric-api 闭包一般 ≤5 个）。 */
    private static final int MAX_FILES = 50;

    private ModsService() {}

    /** 统一搜索结果条目（id 为 CurseForge modId 或 Modrinth projectId；icon/wikiUrl 可为空）。 */
    public record Item(String source, String id, String name, String summary, long downloads,
                       String icon, String wikiUrl) {}

    /** 安装报告：installed/skipped/failed 均为文件名。 */
    public record InstallReport(List<String> installed, List<String> skipped, List<String> failed) {}

    /** 面板支持的内容类型（与 CLI 的 --type 一致）。 */
    public static boolean validType(String type) {
        return java.util.Set.of("mod", "resourcepack", "shader", "datapack").contains(type);
    }

    /**
     * 按类型与来源搜索;query 为空时返回当前类型的热门榜单（按下载量），
     * 含中文时经 mc百科 桥接（searchChinese）。MC 版本/加载器只在安装阶段起作用。
     */
    public static List<Item> search(String source, String type, String query) throws IOException {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty() && hasCJK(q)) {
            return searchChinese(source, type, q);
        }
        return searchDirect(source, type, q, q.isEmpty() ? 15 : 30);
    }

    /** 是否含中日韩表意文字。 */
    private static boolean hasCJK(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)) return true;
        }
        return false;
    }

    /** 指定 pageSize 的普通搜索(热门榜单 pageSize 降到 15 缓解镜像大响应限速)。 */
    private static List<Item> searchDirect(String source, String type, String q, int pageSize) throws IOException {
        List<Item> list = new ArrayList<>();
        if ("modrinth".equalsIgnoreCase(source)) {
            for (var m : Modrinth.search(q, type)) {
                list.add(new Item("modrinth", m.projectId(), m.title(), m.description(), m.downloads(),
                        m.icon(), mcmodSearchUrl(m.title())));
            }
        } else {
            for (var m : CurseForge.search(q, cfCategory(type), pageSize)) {
                list.add(new Item("curseforge", String.valueOf(m.id()), m.name(), m.summary(), m.downloads(),
                        m.icon(), mcmodSearchUrl(m.name())));
            }
        }
        return list;
    }

    /**
     * 中文搜索桥:CurseForge/Modrinth 不收录中文别名,先把关键词交给 mc百科 搜索
     * (标题形如「钠 (Sodium)」),提取英文名再到目标源检索、按相关度融合;
     * 结果的 mc百科 链接直接使用词条直链。
     */
    private static List<Item> searchChinese(String source, String type, String query) throws IOException {
        String html = pcl.net.Net.get("https://search.mcmod.cn/s?key="
                + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
        var m = java.util.regex.Pattern.compile(
                "<a[^>]*href=\"(https://www\\.mcmod\\.cn/(?:class|modpack)/\\d+\\.html)\"[^>]*>(.*?)</a>")
                .matcher(html);
        List<String[]> entries = new ArrayList<>(); // [词条直链前缀, 标题]
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && entries.size() < 8) {
            String title = m.group(2).replaceAll("<[^>]+>", "").trim();
            if (title.startsWith("www.")) continue; // 地址行
            if (seen.add(title)) {
                entries.add(new String[]{m.group(1), title});
            }
        }
        List<Item> merged = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        // 镜像限速下每次源搜索要数秒,只取前 3 个英文名、每个 8 条,控制总时长
        int used = 0;
        for (String[] e : entries) {
            if (used >= 3 || merged.size() >= 15) break;
            if (merged.size() >= 15) break;
            String english = extractEnglish(e[1]);
            if (english == null) continue;
            used++;
            for (Item it : searchDirect(source, type, english, 8)) {
                if (seenIds.add(it.source() + ":" + it.id())) {
                    merged.add(new Item(it.source(), it.id(), it.name(), it.summary(),
                            it.downloads(), it.icon(), e[0]));
                }
                if (merged.size() >= 15) break;
            }
        }
        if (merged.isEmpty()) return searchDirect(source, type, query, 10); // 兜底:目标源自带(几乎不会命中)
        return merged;
    }

    /** 「钠 (Sodium)」→ Sodium;纯英文标题原样返回;纯中文无英文名 → null。 */
    static String extractEnglish(String title) {
        var m = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(title);
        if (m.find()) return m.group(1).trim();
        if (title.length() >= 2 && title.chars().allMatch(c -> c < 128)) return title;
        return null;
    }

    /** mc百科（mcmod.cn）站内搜索链接——无公开的 id 映射 API，跳转到搜索页最可靠。 */
    public static String mcmodSearchUrl(String name) {
        if (name == null || name.isBlank()) return "";
        return "https://search.mcmod.cn/s?key="
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 词条直链缓存（name → 直达 URL；解析失败不入缓存，允许下次重试）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> MCMOD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** mcmod 搜索结果里的词条链接（class=模组词条, modpack=整合包词条）。 */
    private static final java.util.regex.Pattern MCMOD_LINK =
            java.util.regex.Pattern.compile("https?://[a-z.]*mcmod\\.cn/((?:class|modpack))/\\d+\\.html");

    /**
     * 模组名 → mc百科词条直达 URL：抓取 mcmod 站内搜索页（服务端渲染），
     * 取第一条词条链接（Sodium → class/2785）。失败返回 null，前端退回搜索页。
     */
    public static String resolveMcmod(String name) throws IOException {
        if (name == null || name.isBlank()) return null;
        String cached = MCMOD_CACHE.get(name);
        if (cached != null) return cached;
        String html = pcl.net.Net.get("https://search.mcmod.cn/s?key="
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8));
        var m = MCMOD_LINK.matcher(html);
        if (!m.find()) return null;
        String url = m.group(0).replaceFirst("^https?://[a-z.]*mcmod\\.cn/", "https://www.mcmod.cn/");
        MCMOD_CACHE.put(name, url);
        return url;
    }

    /** 指定 mod 的兼容文件列表（供面板版本选择；新在前）。fileId 可回传给 installWithDeps。 */
    public record FileEntry(String fileId, String name, String date, long size, List<String> versions) {}

    public static List<FileEntry> files(String source, String type, String id,
                                        String mc, String loader) throws IOException {
        List<FileEntry> list = new ArrayList<>();
        if ("modrinth".equalsIgnoreCase(source)) {
            for (var f : Modrinth.files(id, mc, "mod".equals(type) ? loader : null)) {
                list.add(new FileEntry(f.id(), f.versionNumber(), f.date(), f.size(), List.of()));
            }
        } else {
            for (var f : CurseForge.files(Long.parseLong(id), mc)) {
                list.add(new FileEntry(String.valueOf(f.id()), f.displayName(), f.date(), f.size(), f.gameVersions()));
            }
        }
        return list;
    }

    /** 安装最新兼容版(面板默认)。 */
    public static InstallReport installWithDeps(String source, String type, String id,
                                                String mc, String loader, Path destDir,
                                                BiConsumer<Integer, Integer> progress) throws IOException {
        return installWithDeps(source, type, id, null, mc, loader, destDir, progress);
    }

    /**
     * 安装主条目及其全部 required 依赖到 destDir，返回逐文件报告。
     * fileId 非空时安装指定版本（来自 files() 列表），否则装最新兼容版。
     * progress 回调 (done, total)：total 随依赖展开动态增长。
     */
    public static InstallReport installWithDeps(String source, String type, String id, String fileId,
                                                String mc, String loader, Path destDir,
                                                BiConsumer<Integer, Integer> progress) throws IOException {
        Files.createDirectories(destDir);
        List<String> installed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 待处理队列：CF 元素为 CurseForge.FileInfo，MR 元素为 Modrinth.FileInfo
        Deque<Object> queue = new ArrayDeque<>();
        if ("modrinth".equalsIgnoreCase(source)) {
            List<Modrinth.FileInfo> files = Modrinth.files(id, mc, "mod".equals(type) ? loader : null);
            if (files.isEmpty()) throw new IOException("No compatible files for " + mc);
            Modrinth.FileInfo f = files.get(0);
            if (fileId != null) {
                for (var c : files) if (c.id().equals(fileId)) { f = c; break; }
            }
            seen.add("mr:" + f.id());
            queue.add(f);
        } else {
            long modId = Long.parseLong(id);
            List<CurseForge.FileInfo> files = CurseForge.files(modId, mc);
            if (files.isEmpty()) throw new IOException("No compatible files for " + mc);
            CurseForge.FileInfo f = pickCf(files, loader);
            if (fileId != null) {
                for (var c : files) if (String.valueOf(c.id()).equals(fileId)) { f = c; break; }
            }
            seen.add("cf:" + f.id());
            queue.add(f);
        }

        int total = 1, done = 0;
        while (!queue.isEmpty()) {
            if (installed.size() + skipped.size() + failed.size() >= MAX_FILES) {
                failed.add("(limit reached - " + MAX_FILES + " files max)");
                break;
            }
            Object task = queue.poll();
            try {
                if (task instanceof CurseForge.FileInfo cf) {
                    processCf(cf, mc, loader, destDir, installed, skipped, failed, seen, queue);
                } else if (task instanceof Modrinth.FileInfo mr) {
                    processMr(mr, mc, loader, destDir, installed, skipped, failed, seen, queue);
                }
            } catch (IOException e) {
                failed.add(displayOf(task) + " (" + e.getMessage() + ")");
            }
            done++;
            progress.accept(done, Math.max(total, done + queue.size()));
        }
        return new InstallReport(List.copyOf(installed), List.copyOf(skipped), List.copyOf(failed));
    }

    // ==================== CurseForge ====================

    private static void processCf(CurseForge.FileInfo f, String mc, String loader, Path destDir,
                                  List<String> installed, List<String> skipped, List<String> failed,
                                  Set<String> seen, Deque<Object> queue) throws IOException {
        Path dest = destDir.resolve(f.name());
        if (!Files.exists(dest)) {
            CurseForge.download(f, dest, null);
            installed.add(f.displayName());
        } else {
            skipped.add(f.displayName());
        }
        // jar 内声明(主来源,镜像元数据经常缺 dependencies)
        enqueueJarDeps(dest, mc, loader, seen, queue, failed);
        // 元数据声明(镜像有数据时作为补充)
        for (long depFileId : f.requiredFileIds()) {
            String key = "cf:" + depFileId;
            if (!seen.add(key)) continue;
            var batch = CurseForge.filesBatch(List.of(depFileId));
            if (batch.isEmpty()) { failed.add("(dependency file " + depFileId + " not found)"); continue; }
            CurseForge.FileInfo dep = batch.get(0);
            if (!dep.gameVersions().contains(mc)) {
                // 依赖文件钉在别的版本上:回退到该 mod 的最新兼容文件
                List<CurseForge.FileInfo> candidates = dep.modId() > 0
                        ? CurseForge.files(dep.modId(), mc) : List.of();
                if (candidates.isEmpty()) { failed.add(dep.displayName() + " (no " + mc + " build)"); continue; }
                dep = pickCf(candidates, loader);
            }
            queue.add(dep);
        }
    }

    /** 优先选带加载器标记（Fabric/Forge）的文件，没有则取最新。 */
    private static CurseForge.FileInfo pickCf(List<CurseForge.FileInfo> files, String loader) {
        if (loader == null || loader.isBlank()) return files.get(0);
        String marker = loader.toLowerCase(Locale.ROOT);
        for (CurseForge.FileInfo f : files) {
            for (String v : f.gameVersions()) {
                if (v.toLowerCase(Locale.ROOT).contains(marker)) return f;
            }
        }
        return files.get(0);
    }

    // ==================== Modrinth ====================

    private static void processMr(Modrinth.FileInfo f, String mc, String loader, Path destDir,
                                  List<String> installed, List<String> skipped, List<String> failed,
                                  Set<String> seen, Deque<Object> queue) throws IOException {
        Path dest = destDir.resolve(f.name());
        if (!Files.exists(dest)) {
            Modrinth.download(f, dest);
            installed.add(f.name());
        } else {
            skipped.add(f.name());
        }
        enqueueJarDeps(dest, mc, loader, seen, queue, failed);
        for (String depProject : f.requiredProjectIds()) {
            String key = "mr:" + depProject;
            if (!seen.add(key)) continue;
            List<Modrinth.FileInfo> candidates = Modrinth.files(depProject, mc, loader);
            if (candidates.isEmpty()) {
                failed.add("(dependency " + depProject + " has no " + mc + " build)");
                continue;
            }
            queue.add(candidates.get(0));
        }
    }

    // ==================== jar 声明依赖 ====================

    /** 加载器/游戏本体等非模组依赖,不参与下载解析。 */
    private static final java.util.Set<String> NON_MOD_DEPS = java.util.Set.of(
            "minecraft", "java", "fabricloader", "fabric", "quilt_loader", "forge", "neoforge",
            "rift", "liteloader", "mixinextras");

    /**
     * 扫描 jar 的依赖声明并入队(fabric.mod.json 的 depends + mods.toml 的 required 块)。
     * 主条目被跳过时也扫描——手动放进去的主文件同样能补全依赖。解析失败静默忽略(尽力而为)。
     */
    private static void enqueueJarDeps(Path jar, String mc, String loader,
                                       Set<String> seen, Deque<Object> queue, List<String> failed) {
        for (String modId : jarRequiredModIds(jar)) {
            String key = "mrs:" + modId.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) continue;
            try {
                Modrinth.FileInfo dep = resolveModrinthLatest(modId, mc, loader);
                if (dep == null) { failed.add(modId + " (not resolvable via Modrinth)"); continue; }
                if (!seen.add("mr:" + dep.id())) continue;
                queue.add(dep);
            } catch (IOException e) {
                failed.add(modId + " (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * modId → Modrinth 最新兼容文件(slug 命中即用;404 时退回搜索取第一项)。
     * fabric-* 模块 id(fabric-api 的内部 capability,如 fabric-rendering-v1)不是独立项目,
     * 统一回落到提供者 fabric-api——其 jar 声明 provides 全部 fabric-* 模块。
     */
    private static Modrinth.FileInfo resolveModrinthLatest(String modId, String mc, String loader)
            throws IOException {
        Modrinth.FileInfo f = resolveModrinthLatest0(modId, mc, loader);
        if (f == null && modId.toLowerCase(Locale.ROOT).startsWith("fabric-")) {
            f = resolveModrinthLatest0("fabric-api", mc, loader);
        }
        return f;
    }

    private static Modrinth.FileInfo resolveModrinthLatest0(String modId, String mc, String loader)
            throws IOException {
        String projectId;
        try {
            var o = pcl.net.Net.getJson(Modrinth.API + "/project/" + modId);
            projectId = Json.str(o, "id", "");
        } catch (pcl.net.Net.NetException e) {
            if (e.statusCode != 404) throw e;
            List<Modrinth.ModInfo> hits = Modrinth.search(modId, "mod");
            if (hits.isEmpty()) return null;
            projectId = hits.get(0).projectId();
        }
        if (projectId == null || projectId.isBlank()) return null;
        List<Modrinth.FileInfo> files = Modrinth.files(projectId, mc, loader);
        return files.isEmpty() ? null : files.get(0);
    }

    /**
     * 提取 jar 声明的 required 依赖 modId 列表(非 mod 类型或解析失败返回空)。
     * fabric.mod.json: {"depends": {"fabric-api": "*", ...}};mods.toml: [[dependencies.x]] modId="..."。
     */
    static List<String> jarRequiredModIds(Path jar) {
        List<String> out = new ArrayList<>();
        if (!jar.toString().endsWith(".jar") || !Files.isRegularFile(jar)) return out;
        try (var zf = new java.util.zip.ZipFile(jar.toFile())) {
            var fabricJson = zf.getEntry("fabric.mod.json");
            if (fabricJson != null) {
                var o = Json.parse(new String(zf.getInputStream(fabricJson).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                if (o instanceof com.google.gson.JsonObject obj && obj.has("depends")
                        && obj.get("depends").isJsonObject()) {
                    for (String id : obj.getAsJsonObject("depends").keySet()) {
                        if (!NON_MOD_DEPS.contains(id.toLowerCase(Locale.ROOT))) out.add(id);
                    }
                }
            }
            for (String entryName : List.of("META-INF/mods.toml", "mods.toml")) {
                var toml = zf.getEntry(entryName);
                if (toml == null) continue;
                String text = new String(zf.getInputStream(toml).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String section : text.split("\\[\\[")) {
                    if (!section.startsWith("dependencies.")) continue;
                    int end = section.indexOf("]]");
                    String body = end >= 0 ? section.substring(0, end) : section;
                    if (body.contains("\"optional\"") || body.contains("\"embedded\"")
                            || body.contains("\"incompatible\"")) continue;
                    var m = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(body);
                    if (m.find()) {
                        String id = m.group(1);
                        if (!NON_MOD_DEPS.contains(id.toLowerCase(Locale.ROOT))) out.add(id);
                    }
                }
            }
        } catch (Exception e) {
            pcl.base.Log.debug("jar 依赖声明扫描失败(忽略): " + jar + ": " + e);
        }
        return out;
    }

    private static String displayOf(Object task) {
        if (task instanceof CurseForge.FileInfo cf) return cf.displayName();
        if (task instanceof Modrinth.FileInfo mr) return mr.name();
        return String.valueOf(task);
    }

    /** CF --type → 分类。 */
    static CurseForge.Category cfCategory(String type) {
        return switch (type) {
            case "resourcepack" -> CurseForge.Category.RESOURCE_PACK;
            case "shader" -> CurseForge.Category.SHADER;
            default -> CurseForge.Category.MOD;
        };
    }
}
