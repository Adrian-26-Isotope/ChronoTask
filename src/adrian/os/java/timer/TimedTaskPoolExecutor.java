package adrian.os.java.timer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import adrian.os.java.threadpool.CustomThreadPool;


/**
 * Implementation of {@link AbstractTimedTaskExecutor} that uses a thread pool
 * to execute timed tasks.
 * <p>
 * This executor maintains a {@link CustomThreadPool} with configurable
 * settings. By default, it creates a pool with a minimum of 0 threads and an
 * idle time of 60 seconds, allowing threads to be created on demand and
 * released when idle.
 * </p>
 */
public class TimedTaskPoolExecutor extends AbstractTimedTaskExecutor {

    private final AbstractExecutorService threadPool;


    /**
     * Constructs a new {@code TimedTaskPoolExecutor}.
     * <p>
     * Initializes a {@link CustomThreadPool} with minimum threads set to 0 and an
     * idle time of 60 seconds.
     * </p>
     */
    public TimedTaskPoolExecutor() {
        this.threadPool = CustomThreadPool.builder().setMinThreads(0).setIdleTime(Duration.ofSeconds(60)).build();
    }

    /**
     * Constructs a new {@code TimedTaskPoolExecutor} with the specified name.
     * <p>
     * Initializes a {@link CustomThreadPool} with minimum threads set to 0 and an
     * idle time of 60 seconds.
     * </p>
     *
     * @param name the name of this executor
     */
    public TimedTaskPoolExecutor(final String name) {
        this.threadPool = CustomThreadPool.builder().setMinThreads(0).setIdleTime(Duration.ofSeconds(60)).setName(name)
                .build();
    }

    /**
     * Constructs a new {@link TimedTaskPoolExecutor} with a custom thread pool to
     * be used for executing tasks.
     * <p>
     * This allows replacing the default thread pool with a custom configured one.
     * </p>
     *
     * @param threadPool the executor service to use for task execution
     */
    public TimedTaskPoolExecutor(final AbstractExecutorService threadPool) {
        this.threadPool = Objects.requireNonNull(threadPool);
    }

    /**
     * Executes the given runnable using the configured thread pool.
     * <p>
     * Note: The provided name parameter is currently discarded and not used for
     * thread naming. The task is delegated to {@link #run(Runnable)}.
     * </p>
     *
     * @param runnable the task to execute
     * @param name     the intended name for the task (currently unused)
     */
    @Override
    void run(final Runnable runnable, final String name) {
        run(runnable);
    }

    /**
     * Executes the given runnable using the configured thread pool.
     * <p>
     * The task is submitted to the thread pool for execution. The thread pool
     * manages thread allocation and lifecycle.
     * </p>
     *
     * @param runnable the task to execute
     */
    @Override
    void run(final Runnable runnable) {
        this.threadPool.submit(runnable);
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
