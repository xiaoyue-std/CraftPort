package pcl.net;

import pcl.base.Log;
import pcl.base.Os;
import pcl.base.Task;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 下载引擎，移植自 ModNet.vb 的下载部分与 ModDownload.vb。
 *  - 下载源：官方与 BMCLAPI 镜像自动切换（ToolDownloadSource：0 镜像优先 / 1 自动 / 2 官方优先）
 *  - 大文件多线程分片（>1MB 分片，每片至少 256KB，对应 PCL2 规则）
 *  - SHA1 / 大小校验，失败自动重试
 */
public final class Downloader {

    /** 下载源策略，对应设置 ToolDownloadSource。 */
    public static int sourcePolicy() {
        return pcl.base.Config.getInt(pcl.base.Config.TOOL_DOWNLOAD_SOURCE, 1);
    }

    public static int threadLimit() {
        return Math.max(1, Math.min(128, pcl.base.Config.getInt(pcl.base.Config.TOOL_DOWNLOAD_THREAD,
                Os.IS_WINDOWS ? 63 : 32)));
    }

    public record DownloadItem(String url, Path dest, String sha1, long size, String name) {}

    /**
     * 全局下载限速令牌桶（设置 DownloadSpeedLimit，KB/s，0 = 不限）。
     * 桶容量为一个周期的额度；所有下载线程共享，按实际读取量扣减。
     */
    final class SpeedLimiter {
        private static final Object LOCK = new Object();
        private static double tokens;
        private static long lastRefill;

        public static long limitBytesPerSec() {
            return pcl.base.Config.getInt(pcl.base.Config.DOWNLOAD_SPEED_LIMIT, 0) * 1024L;
        }

        public static void take(int bytes) throws IOException {
            long limit = limitBytesPerSec();
            if (limit <= 0) return;
            synchronized (LOCK) {
                long now = System.nanoTime();
                if (lastRefill == 0) lastRefill = now;
                tokens += (now - lastRefill) / 1e9 * limit;
                lastRefill = now;
                if (tokens > limit) tokens = limit;
                if (tokens >= bytes) {
                    tokens -= bytes;
                    return;
                }
                long waitNanos = (long) ((bytes - tokens) / limit * 1e9);
                tokens = 0;
                if (waitNanos > 0) {
                    try {
                        java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载被中断", e);
                    }
                }
            }
        }
    }

    private Downloader() {}

    /** 下载单个文件（含镜像切换与重试），sha1/size 传 null 则跳过对应校验。 */
    public static void download(DownloadItem item, BiConsumer<Double, String> progress) throws IOException {
        Path dest = item.dest();
        Files.createDirectories(dest.getParent());
        // 已存在且校验通过则跳过（对应 PCL2 的重复下载检查）
        if (Files.exists(dest) && verify(dest, item.sha1(), item.size())) {
            if (progress != null) progress.accept(1.0, "已存在");
            return;
        }
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<String> candidates = new ArrayList<>();
            String mirror = Net.mirrorUrl(item.url());
            int policy = sourcePolicy();
            if (policy == 0 && mirror != null) { candidates.add(mirror); candidates.add(item.url()); }
            else if (policy == 2 || mirror == null) { candidates.add(item.url()); }
            else { candidates.add(item.url()); candidates.add(mirror); }
            for (String url : candidates) {
                try {
                    downloadSingle(url, dest, item.size(), progress);
                    if (verify(dest, item.sha1(), item.size())) {
                        if (progress != null) progress.accept(1.0, url);
                        return;
                    }
                    Log.warn("校验失败，删除重下: " + dest.getFileName());
                    Files.deleteIfExists(dest);
                    lastError = new IOException("SHA1 校验失败: " + url);
                } catch (pcl.base.Task.CanceledException e) {
                    throw e;
                } catch (IOException e) {
                    lastError = e;
                    Log.warn("下载失败 " + url + ": " + e.getMessage());
                    try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
                }
            }
        }
        throw lastError != null ? lastError : new IOException("下载失败: " + item.url());
    }

    /** 单次下载；大文件走多线程分片。 */
    private static void downloadSingle(String url, Path dest, long size,
                                       BiConsumer<Double, String> progress) throws IOException {
        try {
            var head = Net.getRaw(url, Duration.ofSeconds(15));
            if (head.statusCode() != 200) throw new Net.NetException("HTTP " + head.statusCode(), head.statusCode());
            long total = size > 0 ? size : head.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (total > 1_000_000 && acceptsRange(head)) {
                downloadSegmented(url, dest, total, progress);
            } else {
                downloadSimple(url, dest, total, progress);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
    }

    private static boolean acceptsRange(java.net.http.HttpResponse<byte[]> resp) {
        return resp.headers().firstValue("Accept-Ranges").map(v -> v.equalsIgnoreCase("bytes")).orElse(false);
    }

    private static void downloadSimple(String url, Path dest, long total,
                                       BiConsumer<Double, String> progress) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".pcltmp");
        try (InputStream in = Net.client().send(request(url).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream()).body();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            long lastData = System.nanoTime();
            while ((n = in.read(buf)) != -1) {
                SpeedLimiter.take(n);
                out.write(buf, 0, n);
                done += n;
                lastData = System.nanoTime();
                if (progress != null && total > 0) progress.accept((double) done / total, url);
            }
            if (done == 0 && total != 0) {
                throw new IOException("Connection closed with no data: " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static java.net.http.HttpRequest.Builder request(String url) {
        return java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("User-Agent", "CraftPort/1.0")
                // 响应头必须在 60s 内返回（防 CDN 挂起连接）
                .timeout(Duration.ofSeconds(60))
                .GET();
    }

    private static void downloadSegmented(String url, Path dest, long total,
                                          BiConsumer<Double, String> progress) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".pcltmp");
        int segments = Math.max(2, Math.min(8, (int) (total / (1024 * 1024))));
        long chunk = total / segments;
        AtomicLong downloaded = new AtomicLong();
        // 专用线程池，避免与批量下载的桶线程争用 Task.POOL 造成饥饿
        java.util.concurrent.ExecutorService segmentPool =
                java.util.concurrent.Executors.newFixedThreadPool(segments);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int idx = 0; idx < segments; idx++) {
                final int segIdx = idx;
                final long segStart = idx * chunk;
                final long segEnd = (idx == segments - 1) ? total - 1 : (segStart + chunk - 1);
                futures.add(segmentPool.submit(() -> {
                    // 流式写入限速下的分片（也避免整个分片驻留内存）
                    try (InputStream in = Net.client().send(
                            java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                                    .header("Range", "bytes=" + segStart + "-" + segEnd)
                                    .header("User-Agent", "CraftPort/1.0")
                                    .timeout(Duration.ofSeconds(60))
                                    .GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofInputStream()).body();
                         var ch = java.nio.channels.FileChannel.open(tmp,
                                 StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        byte[] buf = new byte[64 * 1024];
                        long pos = segStart;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            SpeedLimiter.take(n);
                            synchronized (tmp) {
                                ch.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos);
                            }
                            pos += n;
                            long doneNow = downloaded.addAndGet(n);
                            if (progress != null) progress.accept((double) doneNow / total, url);
                        }
                        if (pos != segEnd + 1) {
                            throw new IOException("分片长度不符: " + segIdx + " (" + pos + "/" + (segEnd + 1) + ")");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (var f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    throw c instanceof IOException ioe ? ioe : new IOException(c);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载被中断", e);
                }
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        } finally {
            segmentPool.shutdownNow();
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 批量并行下载（对应 LoaderDownload）。重复目标文件会先去重（LWJGL 等多平台条目会重复出现）。 */
    public static void downloadBatch(List<DownloadItem> items, java.util.function.IntConsumer onProgress)
            throws IOException {
        // 按目标路径去重，保留第一条
        java.util.Map<Path, DownloadItem> unique = new java.util.LinkedHashMap<>();
        for (DownloadItem item : items) unique.putIfAbsent(item.dest().toAbsolutePath(), item);
        List<DownloadItem> list = new ArrayList<>(unique.values());
        int threads = Math.min(list.size(), threadLimit());
        AtomicInteger finished = new AtomicInteger();
        List<IOException> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<List<DownloadItem>> buckets = new ArrayList<>();
        for (int i = 0; i < threads; i++) buckets.add(new ArrayList<>());
        for (int i = 0; i < list.size(); i++) buckets.get(i % threads).add(list.get(i));

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (List<DownloadItem> bucket : buckets) {
            if (bucket.isEmpty()) continue;
            futures.add(Task.POOL.submit(() -> {
                for (DownloadItem item : bucket) {
                    try {
                        download(item, null);
                    } catch (pcl.base.Task.CanceledException e) {
                        return;
                    } catch (IOException e) {
                        errors.add(e);
                    }
                    if (onProgress != null) onProgress.accept(finished.incrementAndGet());
                }
            }));
        }
        for (var f : futures) {
            try { f.get(); } catch (Exception e) {
                if (e.getCause() instanceof IOException ioe) errors.add(ioe);
            }
        }
        if (!errors.isEmpty()) {
            IOException ex = new IOException("有 " + errors.size() + " 个文件下载失败：" + errors.get(0).getMessage());
            throw ex;
        }
    }

    public static boolean verify(Path file, String sha1, long size) {
        try {
            if (size > 0 && Files.size(file) != size) return false;
            if (sha1 != null && !sha1.isBlank()) return sha1(file).equalsIgnoreCase(sha1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return String.format("%040x", new BigInteger(1, md.digest()));
        } catch (Exception e) {
            throw new IOException("SHA1 计算失败", e);
        }
    }
}
