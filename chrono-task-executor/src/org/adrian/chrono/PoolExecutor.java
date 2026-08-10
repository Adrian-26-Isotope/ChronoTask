package org.adrian.chrono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.adrian.threadpool.ElasticThreadPool;


/**
 * Implementation of {@link AbstractExecutor} that uses a thread pool
 * to execute timed tasks.
 * <p>
 * This executor maintains a {@link ElasticThreadPool} with configurable
 * settings. By default, it creates a pool with a minimum of 0 threads and an
 * idle time of 60 seconds, allowing threads to be created on demand and
 * released when idle.
 * </p>
 */
public class PoolExecutor extends AbstractExecutor {

    private final String name;
    private final AbstractExecutorService threadPool;


    /**
     * Constructs a new {@code TimedTaskPoolExecutor}.
     * <p>
     * Initializes a {@link ElasticThreadPool} with minimum threads set to 0 and an
     * idle time of 60 seconds.
     * </p>
     */
    public PoolExecutor() {
        this.name = null;
        this.threadPool = ElasticThreadPool.builder().setMinThreads(0).setIdleTime(Duration.ofSeconds(60)).start();
    }

    /**
     * Constructs a new {@code TimedTaskPoolExecutor} with the specified name.
     * <p>
     * Initializes a {@link ElasticThreadPool} with minimum threads set to 0 and an
     * idle time of 60 seconds.
     * </p>
     *
     * @param name the name of this executor
     */
    public PoolExecutor(final String name) {
        this.name = name;
        this.threadPool =
                ElasticThreadPool.builder().setMinThreads(0).setIdleTime(Duration.ofSeconds(60)).setName(name).start();
    }

    /**
     * Constructs a new {@link PoolExecutor} with a custom thread pool to
     * be used for executing tasks.
     * <p>
     * This allows replacing the default thread pool with a custom configured one.
     * </p>
     *
     * @param threadPool the executor service to use for task execution
     */
    public PoolExecutor(final AbstractExecutorService threadPool) {
        this.name = null;
        this.threadPool = Objects.requireNonNull(threadPool);
    }

    /**
     * Executes the given runnable using the configured thread pool, temporarily
     * renaming the pool thread during execution.
     * <p>
     * If this executor has a name, the thread is renamed to
     * {@code <poolName>/<name>} during execution; otherwise it is renamed to
     * {@code <name>}. The original thread name is restored after the runnable
     * completes.
     * </p>
     * <p>
     * The runnable is dispatched via
     * {@link AbstractExecutorService#execute(Runnable)}, so any {@code Throwable}
     * thrown from within the runnable propagates uncaught
     * out of the pool thread and reaches that thread's
     * {@link Thread#getUncaughtExceptionHandler() UncaughtExceptionHandler}.
     * </p>
     *
     * @param runnable the task to execute
     * @param name the name to assign to the thread during execution
     */
    @Override
    protected void run(final Runnable runnable, final String name) {
        this.threadPool.execute(() -> {
            Thread current = Thread.currentThread();
            String oldName = current.getName();
            current.setName((this.name != null) ? this.name + "/" + name : name);
            try {
                runnable.run();
            }
            finally {
                current.setName(oldName);
            }
        });
    }

    /**
     * Executes the given runnable using the configured thread pool.
     * <p>
     * The task is dispatched to the thread pool via
     * {@link AbstractExecutorService#execute(Runnable)} for execution. The
     * thread pool manages thread allocation and lifecycle.
     * </p>
     * <p>
     * The runnable is dispatched via
     * {@link AbstractExecutorService#execute(Runnable)}, so any {@code Throwable}
     * thrown from within the runnable propagates uncaught
     * out of the pool thread and reaches that thread's
     * {@link Thread#getUncaughtExceptionHandler() UncaughtExceptionHandler}.
     * </p>
     *
     * @param runnable the task to execute
     */
    @Override
    protected void run(final Runnable runnable) {
        this.threadPool.execute(runnable);
    }

    /**
     * shutdown this pool executor. See {@link AbstractExecutorService#shutdown()}
     * for details.
     */
    public void shutdown() {
        this.threadPool.shutdown();
    }

    /**
     * shutdown this pool executor immediately. See
     * {@link AbstractExecutorService#shutdownNow()} for details.
     */
    public List<Runnable> shutdownNow() {
        return this.threadPool.shutdownNow();
    }

    /**
     * See {@link AbstractExecutorService#isShutdown()} for details.
     */
    public boolean isShutdown() {
        return this.threadPool.isShutdown();
    }

    /**
     * See {@link AbstractExecutorService#isTerminated()} for details.
     */
    public boolean isTerminated() {
        return this.threadPool.isTerminated();
    }

    /**
     * See {@link AbstractExecutorService#awaitTermination(long, TimeUnit)} for
     * details.
     */
    public boolean awaitTermination(final Duration duration) throws InterruptedException {
        return this.threadPool.awaitTermination(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

}
