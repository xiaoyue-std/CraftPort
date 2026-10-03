package pcl.minecraft;

import com.google.gson.JsonObject;
import pcl.base.Config;
import pcl.base.Json;
import pcl.base.Log;
import pcl.base.Os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * .minecraft 目录管理，移植自 ModMinecraft.vb 的目录发现 / 版本扫描 / launcher_profiles.json 维护。
 */
public final class McFolder {

    /** 当前选择的 .minecraft 根目录（全局设置 CacheMinecraftDir）。 */
    public static Path selectedRoot() {
        String dir = Config.get(Config.CACHE_MINECRAFT_DIR, "");
        if (!dir.isBlank() && Files.isDirectory(Path.of(dir))) return Path.of(dir);
        Path def = Os.defaultMinecraftDir();
        if (Files.isDirectory(def.resolve("versions"))) return def;
        List<Path> found = Os.discoverMinecraftDirs();
        if (!found.isEmpty()) return found.get(0);
        // 全无则自动创建（与 PCL2 一致）
        return def;
    }

    public static void setRoot(Path p) {
        Config.set(Config.CACHE_MINECRAFT_DIR, p.toString());
    }

    /** 可用的 .minecraft 目录列表（对应 ModMinecraft 的目录发现）。 */
    public static List<Path> availableRoots() {
        List<Path> list = new ArrayList<>();
        Path cur = selectedRoot();
        if (Files.isDirectory(cur)) list.add(cur);
        for (Path p : Os.discoverMinecraftDirs()) {
            if (!list.contains(p)) list.add(p);
        }
        String custom = Config.launchFolders;
        if (custom != null && !custom.isBlank()) {
            for (String seg : custom.split("\\|")) {
                if (seg.isBlank()) continue;
                String path = seg.contains(">") ? seg.substring(seg.indexOf('>') + 1) : seg;
                if (Files.isDirectory(Path.of(path)) && !list.contains(Path.of(path))) {
                    list.add(Path.of(path));
                }
            }
        }
        return list;
    }

    /** 确保目录结构存在，并维护 launcher_profiles.json（clientToken 与 PCL2 一致）。 */
    public static void ensureProfile(Path root) {
        try {
            Files.createDirectories(root.resolve("versions"));
            Files.createDirectories(root.resolve("assets"));
            Files.createDirectories(root.resolve("libraries"));
            Path profiles = root.resolve("launcher_profiles.json");
            if (!Files.exists(profiles)) {
                JsonObject o = new JsonObject();
                o.addProperty("clientToken", "23323323323323323323323323323333");
                JsonObject profilesObj = new JsonObject();
                JsonObject pclProfile = new JsonObject();
                pclProfile.addProperty("name", "CraftPort");
                pclProfile.addProperty("type", "custom");
                profilesObj.add("pclj", pclProfile);
                o.add("profiles", profilesObj);
                Files.writeString(profiles, Json.GSON.toJson(o), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.error("初始化 .minecraft 目录失败: " + root, e);
        }
    }

    /** 扫描 versions 下的全部版本（对应 ModMinecraft 的版本扫描）。 */
    public static List<McVersion> scanVersions(Path root) {
        List<McVersion> versions = new ArrayList<>();
        Path versionsDir = root.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return versions;
        try (var stream = Files.list(versionsDir)) {
            for (Path folder : stream.filter(Files::isDirectory).toList()) {
                String name = folder.getFileName().toString();
                // 跳过 cache 等无 json 目录（对应 PCL2 的跳过逻辑）
                if (!Files.isRegularFile(folder.resolve(name + ".json"))) continue;
                if (name.equals("cache") || name.equals("BLClient") || name.equals("PCL")) continue;
                McVersion v = McVersion.load(folder, name);
                if (v != null) versions.add(v);
            }
        } catch (IOException e) {
            Log.error("版本扫描失败: " + versionsDir, e);
        }
        McVersion.distinct(versions);
        McVersion.sort(versions);
        return versions;
    }

    /** 列表为空时的友好提示。 */
    public static String emptyHint() {
        return "未找到任何版本，请先在「下载」页安装游戏，或调整设置中的游戏目录。";
    }

    private McFolder() {}
}
