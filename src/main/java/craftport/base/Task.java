package craftport.base;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局执行器：下载与并行任务共用的守护线程池，以及取消异常类型。
 */
public final class Task {

    private Task() {}

    /** 全局线程池（守护线程，核心线程数取 CPU 核数的两倍、至少 4）。 */
    public static final ExecutorService POOL =
            Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    });

    /** 任务被取消时抛出，供下载等长流程提前退出。 */
    public static class CanceledException extends RuntimeException {
        public CanceledException() { super("任务已取消"); }
    }
}
