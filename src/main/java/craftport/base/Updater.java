package craftport.base;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 自更新：从 GitHub 拉源码 → 本地用 JDK 编译 → 产出新的可执行 jar。
 *
 * 只依赖 JDK（javac + jar），不需要 Maven；编译期 classpath 指向当前运行的 jar 取 Gson，
 * 因此离线环境下只要 Gson 类在运行时可用就能编译（见 {@link #compile()}）。
 *
 * 用户数据不受影响：配置、日志、游戏目录、服务端目录都在数据目录 / 游戏目录下，
 * 本类只在临时工作目录和 jar 所在目录内操作。
 */
public final class Updater {

    public static final String REPO = "xiaoyue-std/CraftPort";
    public static final String DEFAULT_REF = "main";

    /** 下载源码 zip（.zip 小、解压用 JDK 自带能力；.tar.gz 需要额外依赖）。 */
    private static final String ARCHIVE = "https://github.com/" + REPO + "/archive/refs/heads/%s.zip";
    /** 指定提交（atom feed 给出的 sha，可精确到某个版本）。 */
    private static final String ARCHIVE_SHA = "https://github.com/" + REPO + "/archive/%s.zip";
    /** 提交 Atom feed：不限流（GitHub API 未认证仅 60 次/小时）。 */
    private static final String FEED = "https://github.com/" + REPO + "/commits/%s.atom";

    private Updater() {}

    /** 远端最新提交。 */
    public record RemoteCommit(String sha, String title) {
        public String shortSha() { return sha.length() > 7 ? sha.substring(0, 7) : sha; }
    }

    /** 更新检查结果。 */
    public record UpdateCheck(boolean updateAvailable, RemoteCommit remote, String localCommit) {}

    // ==================== 检查 ====================

    /** 查询远端最新提交；网络失败抛 IOException。 */
    public static RemoteCommit latestCommit(String ref) throws IOException {
        String feed = craftport.net.Net.get(FEED.formatted(ref));
        String sha = firstGroup(feed, "Grit::Commit/([0-9a-f]{40})");
        if (sha == null) throw new IOException("Unexpected commit feed format for ref: " + ref);
        String title = firstGroup(feed, "<entry>.*?<title>\\s*(.*?)\\s*</title>");
        if (title != null) title = unescape(title);
        else title = "";
        return new RemoteCommit(sha, title);
    }

    /**
     * 检查是否有更新：本地无提交信息（源码构建）视为有更新；
     * 否则比对最新提交与本地提交。先用 Atom feed（不限流、带提交标题），
     * 网络不可达时退回 codeload 的 Last-Modified 头（不含 sha，仅判断是否有新提交）。
     */
    public static UpdateCheck check(String ref) throws IOException {
        String local = BuildInfo.commit();
        try {
            RemoteCommit remote = latestCommit(ref);
            boolean same = !local.isEmpty() && (remote.sha().equals(local) || remote.sha().startsWith(local));
            return new UpdateCheck(!same, remote, local);
        } catch (IOException feedFailure) {
            if (local.isEmpty()) throw feedFailure;
            java.time.Instant last = remoteLastModified(ref);
            if (last == null) throw feedFailure;
            long localBuilt = localBuildTime();
            boolean same = localBuilt > 0 && last.getEpochSecond() / 60 <= localBuilt / 60 + 1;
            return new UpdateCheck(!same, new RemoteCommit("", "modified " + last), local);
        }
    }

    /** 远端最新提交的 Last-Modified（HEAD 请求 codeload 归档）。 */
    private static java.time.Instant remoteLastModified(String ref) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(ARCHIVE.formatted(ref)))
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .timeout(java.time.Duration.ofSeconds(20))
                    .build();
            var resp = craftport.net.Net.client().send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            return resp.headers().firstValue("last-modified")
                    .map(v -> java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static long localBuildTime() {
        try {
            return java.time.Instant.parse(BuildInfo.built()).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 下载与解压 ====================

    /** 下载源码 zip 并解压到 dest（返回源码根目录，自动识别顶层目录）；sha 为空时按 ref 下载。 */
    public static Path downloadAndExtract(Path workDir, String ref, String sha,
                                          java.util.function.BiConsumer<Double, String> progress) throws IOException {
        Files.createDirectories(workDir);
        Path zip = workDir.resolve("source.zip");
        String url = (sha != null && !sha.isBlank())
                ? ARCHIVE_SHA.formatted(sha)
                : ARCHIVE.formatted(ref);
        craftport.net.Downloader.download(
                new craftport.net.Downloader.DownloadItem(url, zip, "", 0, "CraftPort source"), progress);
        Path srcRoot = workDir.resolve("src");
        deleteRecursively(srcRoot);
        unzip(zip, srcRoot);
        // GitHub 的 zip 里有一个顶层目录（如 CraftPort-main/），编译时用它作为项目根
        try (var s = Files.list(srcRoot)) {
            return s.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new IOException("Source archive has no top-level directory"));
        }
    }

    /** JDK 自带的 ZipInputStream 解压（防 zip-slip），不引入外部依赖。 */
    public static void unzip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (var in = Files.newInputStream(zip); var zis = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest)) throw new IOException("Zip entry escapes target: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    // ==================== 编译 ====================

    /** 编译结果：新 jar 路径与耗时。 */
    public record BuildResult(Path jar, long elapsedMs) {}

    /**
     * 用 javac + jar 编译源码目录，产出带 Main-Class、build.properties 与依赖类的可执行 jar。
     *
     * @param projectRoot  解压出的项目根（含 src/main/java）
     * @param outJar       输出 jar
     * @param runtimeCp    运行时依赖（取 Gson 等类并入新 jar；javac 的 -cp 只用于解析符号）
     * @param version      写入 build.properties 的版本
     * @param commit       写入 build.properties 的提交 sha
     */
    public static BuildResult compile(Path projectRoot, Path outJar, String runtimeCp,
                                      String version, String commit) throws IOException {
        long t0 = System.currentTimeMillis();
        Path srcDir = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(srcDir)) throw new IOException("Not a CraftPort source tree: " + srcDir);
        Path resDir = projectRoot.resolve("src/main/resources");
        Path classes = projectRoot.resolve("build/classes");
        deleteRecursively(classes);
        Files.createDirectories(classes);

        List<String> sources = new ArrayList<>();
        Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) sources.add(file.toAbsolutePath().toString());
                return FileVisitResult.CONTINUE;
            }
        });
        if (sources.isEmpty()) throw new IOException("No Java sources found under " + srcDir);

        String javac = tool("javac");
        List<String> cmd = new ArrayList<>(List.of(javac, "--release", "21", "-encoding", "UTF-8",
                "-nowarn", "-d", classes.toAbsolutePath().toString()));
        if (runtimeCp != null && !runtimeCp.isBlank()) {
            cmd.add("-cp");
            cmd.add(runtimeCp);
        }
        cmd.addAll(sources);
        run(cmd, projectRoot, "javac");

        // 资源（面板 web.html 等）
        if (Files.isDirectory(resDir)) copyTree(resDir, classes);
        // 运行时依赖：javac 的 -cp 不会打包，必须显式并入，否则新 jar 缺 Gson
        List<String> runtimeJars = splitClasspath(runtimeCp);
        for (String entry : runtimeJars) {
            if (!entry.endsWith(".jar")) continue;
            try (var in = Files.newInputStream(Path.of(entry)); var zis = new java.util.zip.ZipInputStream(in)) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
                    Path target = classes.resolve(name).normalize();
                    if (!target.startsWith(classes)) continue;
                    if (e.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zis.closeEntry();
                }
            }
        }

        // 构建信息（更新流程写入，供下次检查比对提交）
        Properties b = new Properties();
        b.setProperty("version", version == null ? BuildInfo.DEV : version);
        b.setProperty("commit", commit == null ? "" : commit);
        b.setProperty("built", java.time.Instant.now().toString());
        Files.createDirectories(classes.resolve("craftport"));
        try (var out = Files.newOutputStream(classes.resolve("craftport/build.properties"))) {
            b.store(out, "CraftPort build info");
        }

        Path manifest = projectRoot.resolve("build/MANIFEST.MF");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "Manifest-Version: 1.0\nMain-Class: craftport.cli.CliMain\n",
                StandardCharsets.UTF_8);
        run(List.of(tool("jar"), "cfm", outJar.toAbsolutePath().toString(),
                manifest.toAbsolutePath().toString(), "-C", classes.toAbsolutePath().toString(), "."),
                projectRoot, "jar");
        if (!Files.isRegularFile(outJar) || Files.size(outJar) < 100_000) {
            throw new IOException("Built jar looks invalid: " + outJar);
        }
        return new BuildResult(outJar, System.currentTimeMillis() - t0);
    }

    /** 缓存与工作目录（数据目录下，与用户数据分开，不参与更新）。 */
    public static Path workDir() {
        return Os.dataDir().resolve("update");
    }

    /**
     * 处理「新 jar 已由 run 脚本替换」的收尾：
     * journal 记录本次更新的目标提交；当前构建的提交与之相符说明替换已发生
     * （源码构建的 label 是 dev，无法靠 label 区分，因此用提交比对）。
     */
    public static void consumePendingMarker() {
        Path journal = Os.dataDir().resolve("update-journal.txt");
        if (!Files.isRegularFile(journal)) return;
        try {
            String newSha = journalValue(journal, "newSha");
            String oldLabel = journalValue(journal, "oldLabel");
            String current = BuildInfo.commit();
            if (newSha == null || newSha.isBlank() || current.isEmpty()
                    || !(current.equals(newSha) || newSha.startsWith(current))) {
                return; // 还没替换，等 run 脚本
            }
            System.out.println("Update applied: running " + BuildInfo.label()
                    + (oldLabel.isBlank() ? "" : " (previously " + oldLabel + ")") + ".");
            Files.deleteIfExists(currentJar().resolveSibling("CraftPort.jar.bak"));
            deleteRecursively(workDir());
            Files.deleteIfExists(journal);
            // 更新检查缓存里的「本地版本」已过期，删掉以便下次重新比对
            Files.deleteIfExists(Os.dataDir().resolve("update-check.txt"));
        } catch (Exception e) {
            Log.debug("清理更新残留失败: " + e.getMessage());
        }
    }

    /** 写入更新日志（供下次启动确认替换结果）。 */
    public static void writeJournal(String oldLabel, String newSha) {
        Path journal = Os.dataDir().resolve("update-journal.txt");
        try {
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, "oldLabel=" + oldLabel + "\n"
                    + "newSha=" + (newSha == null ? "" : newSha) + "\n");
        } catch (IOException e) {
            Log.warn("写入更新日志失败: " + e.getMessage());
        }
    }

    private static String journalValue(Path journal, String key) {
        try {
            for (String line : Files.readAllLines(journal)) {
                if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** 从 pom.xml 读取项目版本（更新构建时写入 build.properties）。 */
    public static String versionFromPom(Path projectRoot) {
        try {
            String pom = Files.readString(projectRoot.resolve("pom.xml"));
            var m = java.util.regex.Pattern.compile("(?s)<artifactId>craftport</artifactId>\\s*<version>([^<]+)</version>")
                    .matcher(pom);
            return m.find() ? m.group(1).trim() : BuildInfo.DEV;
        } catch (Exception e) {
            return BuildInfo.DEV;
        }
    }

    /** 编译期依赖：当前 jar 即可提供 Gson（shade 后的 jar 内含全部依赖）。 */
    public static String compileClasspath(Path currentJar) {
        String self = currentJar.toAbsolutePath().toString();
        String sep = Os.classpathSeparator();
        // 开发/测试场景下 jdk 模块路径之外的手工 classpath 也带上
        String existing = System.getProperty("java.class.path", "");
        return existing.isBlank() || existing.contains(self) ? self : self + sep + existing;
    }

    // ==================== 落位（新 jar → .new） ====================

    /** 计划：把 built 安装到 installDir 下的 CraftPort.jar.new，保留备份。 */
    public record InstallPlan(Path newJar, Path currentJar, Path backupJar) {}

    public static InstallPlan planInstall(Path builtJar, Path installDir) {
        Path dir = installDir.toAbsolutePath().normalize();
        return new InstallPlan(dir.resolve("CraftPort.jar.new"), dir.resolve("CraftPort.jar"),
                dir.resolve("CraftPort.jar.bak"));
    }

    /**
     * 落位：新 jar 写为 CraftPort.jar.new；当前 jar 在 run 脚本替换前先备份为 .bak。
     * 运行中的 jar 在 Windows 上无法覆盖，因此统一走「下次启动由 run 脚本替换」。
     */
    public static void install(BuildResult result, InstallPlan plan) throws IOException {
        Files.createDirectories(plan.newJar().getParent());
        Files.copy(result.jar(), plan.newJar(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ==================== 工具 ====================

    /** 按平台分隔符拆分 classpath。 */
    private static List<String> splitClasspath(String cp) {
        List<String> out = new ArrayList<>();
        if (cp == null || cp.isBlank()) return out;
        for (String s : cp.split(java.util.regex.Pattern.quote(Os.classpathSeparator()))) {
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** 定位 JDK 工具：优先与当前 JVM 同源的安装位置（兼容 JDK8 的 jre/bin 布局），其次 PATH。 */
    public static String tool(String name) {
        String exe = Os.exeName(name);
        String home = System.getProperty("java.home", "");
        for (Path candidate : new Path[]{
                Path.of(home, "bin", exe),               // JDK 9+ / JRE 布局下的 bin
                Path.of(home, "..", "bin", exe)}) {      // JDK 8 的 jre/bin -> ../bin
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return exe;
    }

    /** 当前运行的 jar 路径；从 jar 外运行（IDE / 测试）时抛异常。 */
    public static Path currentJar() throws IOException {
        try {
            var source = Updater.class.getProtectionDomain().getCodeSource();
            if (source == null) throw new IOException("unknown code source");
            return Path.of(source.getLocation().toURI()).toAbsolutePath();
        } catch (Exception e) {
            throw new IOException("Cannot determine the running jar location - update is only available from the packaged jar", e);
        }
    }

    /** JDK 是否可用：优先用与当前 JVM 同源的 javac（java.home 支持 JRE 布局 jre/bin），
     *  javac 不在时再看 PATH。 */
    public static boolean hasJdk() {
        return isJdkTool(tool("javac")) && isJdkTool(tool("jar"));
    }

    private static boolean isJdkTool(String path) {
        try {
            // jar 只认 --version（-version 是非法选项），javac 两者都接受
            Process p = new ProcessBuilder(path, "--version").redirectErrorStream(true).start();
            return p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(List<String> cmd, Path workDir, String what) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (var in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException(what + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " interrupted", e);
        }
        if (p.exitValue() != 0) {
            String tail = output.length() > 4000 ? output.substring(output.length() - 4000) : output;
            throw new IOException(what + " failed (exit " + p.exitValue() + "):\n" + tail);
        }
    }

    public static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 递归删除（不跟随符号链接）。 */
    public static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String firstGroup(String text, String regex) {
        var m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }
}
