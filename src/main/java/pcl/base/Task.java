package pcl.base;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 异步任务体系，移植自 Modules/Base/ModLoader.vb 的 LoaderBase / LoaderCombo。
 *  - Task：单个异步步骤，带状态机、进度、权重、取消
 *  - TaskChain：按序编排多个子任务，按权重汇总总进度（对应 LoaderCombo + ProgressWeight）
 */
public abstract class Task<T> {

    public enum State { WAITING, LOADING, FINISHED, FAILED, CANCELED }

    private volatile State state = State.WAITING;
    private volatile double progress; // 0~1
    private final double progressWeight;
    private volatile boolean cancelRequested;
    protected final List<Task<?>> children = new ArrayList<>();
    protected Task<?> parent;
    private volatile Thread workerThread;
    private final List<Consumer<Task<?>>> stateListeners = new CopyOnWriteArrayList<>();

    protected Task(double progressWeight) {
        this.progressWeight = progressWeight;
    }

    public State state() { return state; }
    public double progress() { return progress; }
    public double progressWeight() { return progressWeight; }
    public Task<?> parent() { return parent; }
    public List<Task<?>> children() { return children; }
    public void addStateListener(Consumer<Task<?>> l) { stateListeners.add(l); }

    public Task<T> onStateChanged(Consumer<Task<?>> l) { addStateListener(l); return this; }

    protected void setProgress(double p) {
        progress = Math.max(0, Math.min(1, p));
        Task<?> par = parent;
        while (par != null) {
            par.progress = par.computeProgress();
            par = par.parent;
        }
    }

    private double computeProgress() {
        double total = 0, done = 0;
        for (Task<?> c : children) {
            total += c.progressWeight;
            done += c.progressWeight * c.progress;
        }
        return total == 0 ? progress : done / total;
    }

    public void cancel() {
        cancelRequested = true;
        if (state == State.WAITING || state == State.LOADING) {
            for (Task<?> c : children) c.cancel();
            Thread w = workerThread;
            if (w != null) w.interrupt();
        }
    }

    public boolean isCancelRequested() { return cancelRequested; }

    /** 每个步骤中应周期调用以响应取消（对应 PCL 的 LoopCheck）。 */
    protected void throwIfCanceled() {
        if (cancelRequested) throw new CanceledException();
    }

    public static class CanceledException extends RuntimeException {
        public CanceledException() { super("任务已取消"); }
    }

    /** 失败时展示给用户的提示（对应 LoaderBase 的 ErrorMessage）。 */
    public String errorMessage() { return null; }

    public T run() {
        state = State.LOADING;
        notifyState();
        workerThread = Thread.currentThread();
        try {
            T result = execute();
            progress = 1;
            state = State.FINISHED;
            notifyState();
            return result;
        } catch (CanceledException e) {
            state = State.CANCELED;
            notifyState();
            throw e;
        } catch (Throwable e) {
            state = State.FAILED;
            notifyState();
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    protected abstract T execute() throws Exception;

    private void notifyState() {
        for (Consumer<Task<?>> l : stateListeners) l.accept(this);
    }

    /** 按序执行的组合任务。 */
    public static class Chain<T> extends Task<T> {
        private final List<Task<?>> steps = new ArrayList<>();
        private final List<Boolean> blocks = new ArrayList<>();
        private final T result;

        public Chain(T result) {
            super(1);
            this.result = result;
        }

        /** 添加步骤；block=false 的步骤失败不阻断后续（对应 LoaderCombo 的 Block 参数）。 */
        public Chain<T> then(Task<?> task, boolean block) {
            steps.add(task);
            blocks.add(block);
            children.add(task);
            task.parent = this;
            return this;
        }

        public Chain<T> then(Task<?> task) { return then(task, true); }

        @SuppressWarnings("unchecked")
        @Override
        protected T execute() {
            for (int i = 0; i < steps.size(); i++) {
                throwIfCanceled();
                Task<?> step = steps.get(i);
                try {
                    step.run();
                } catch (CanceledException e) {
                    throw e;
                } catch (Throwable e) {
                    if (blocks.get(i)) throw e;
                    Log.warn("非阻塞步骤失败: " + step.getClass().getSimpleName() + " - " + e);
                }
            }
            return result;
        }
    }

    /** 函数包装任务（对应 LoaderTask）。 */
    public static class Func<T> extends Task<T> {
        private final ThrowingFunction<T> fn;
        private final String name;

        public interface ThrowingFunction<R> { R apply() throws Exception; }

        public Func(String name, double weight, ThrowingFunction<T> fn) {
            super(weight);
            this.name = name;
            this.fn = fn;
        }

        public String name() { return name; }

        @Override
        protected T execute() throws Exception {
            return fn.apply();
        }

        @Override
        public String errorMessage() {
            return "步骤「" + name + "」失败";
        }
    }

    /** 线程池：对应 PCL 的多线程下载管理（NetManager），核心线程数取 CPU 核数。 */
    public static final ExecutorService POOL =
            Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    });

    /** 在后台线程执行任务，返回 Future。 */
    public static <T> Future<T> runAsync(Task<T> task) {
        return POOL.submit(task::run);
    }

    /** 简单并行执行 n 个单元工作（对应 LoaderDownload 的多线程分片）。 */
    public static void parallel(int units, java.util.function.IntConsumer unit) throws InterruptedException {
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = POOL;
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < units; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    unit.accept(idx);
                } finally {
                    done.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                if (e.getCause() instanceof Error er) throw er;
                throw new RuntimeException(e.getCause());
            }
        }
    }
}
