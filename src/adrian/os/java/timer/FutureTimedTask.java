package adrian.os.java.timer;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A timed task that exposes execution results via {@link CompletableFuture}.
 * <p>
 * Each execution atomically captures the pending future, executes the task, and
 * completes that future with the result. A fresh future is installed for the
 * following execution so callers can chain results without a race.
 * </p>
 * <p>
 * Instances are created via {@link AbstractTimedTaskExecutor#createFutureTask(Function)}.
 * </p>
 *
 * @param <T> the type of the result produced by the task.
 */
public class FutureTimedTask<T> {

    private final TimedTask timedTask;
    private final AtomicReference<CompletableFuture<T>> nextResult;
    private volatile T lastResult;

    /**
     * @param task the function to execute on each trigger; receives this task
     *            instance as argument.
     * @param executor the executor that runs the underlying timer and task threads.
     */
    FutureTimedTask(final Function<FutureTimedTask<T>, T> task, final AbstractTimedTaskExecutor executor) {
        Objects.requireNonNull(task);
        this.nextResult = new AtomicReference<>(new CompletableFuture<>());

        Consumer<TimedTask> consumer = _ -> {
            CompletableFuture<T> currentFuture = this.nextResult.getAndSet(new CompletableFuture<>());
            try {
                T result = task.apply(FutureTimedTask.this);
                this.lastResult = result;
                currentFuture.complete(result);
            }
            catch (final Exception e) {
                currentFuture.completeExceptionally(e);
            }
        };

        this.timedTask = executor.createTask(consumer).build();
    }

    /**
     * Starts the task and returns the {@link CompletableFuture} for the next
     * upcoming execution. If the task was previously stopped before its prior
     * {@code start()}'s execution ever fired, that same future instance is
     * reused rather than replaced, since it was never completed; it will be
     * completed by whichever execution runs next.
     *
     * @return the future that will be completed by the next execution, or
     *         {@code null} if the task is already running
     */
    public synchronized CompletableFuture<T> start() {
        if (isRunning()) {
            return null;
        }
        CompletableFuture<T> next = getNextResult();
        this.timedTask.start();
        return next;
    }

    /**
     * Stops any recurring executions and terminates this task gracefully. Once
     * stopped the task can be started again via {@link #start()}.
     */
    public void stop() {
        this.timedTask.stop();
    }

    /**
     * @return {@code true} if this task is currently running, {@code false}
     *         otherwise
     */
    public boolean isRunning() {
        return this.timedTask.isRunning();
    }

    /**
     * Returns the result of the last completed execution. Empty until the first
     * execution finishes successfully.
     *
     * @return an {@link Optional} containing the last result, or
     *         {@link Optional#empty()} if no execution has completed yet
     */
    public Optional<T> getLastResult() {
        return Optional.ofNullable(this.lastResult);
    }

    /**
     * Returns the {@link CompletableFuture} for the next upcoming execution.
     * <p>
     * For recurring tasks, call this method after waiting on the previous future to
     * obtain the future for the following execution.
     * </p>
     *
     * @return the future that will be completed by the next execution
     */
    public CompletableFuture<T> getNextResult() {
        return this.nextResult.get();
    }

    /**
     * @param delay the initial delay before the first execution; ignored when
     *            negative
     * @return false if the task is currently running
     */
    public boolean setInitialDelay(final Duration delay) {
        return this.timedTask.setInitialDelay(delay);
    }

    /**
     * Periodic delay means the task executes at fixed intervals from the start
     * time. Clears any repetitive delay.
     *
     * @param delay the fixed delay between task executions
     * @return false if the task is currently running
     */
    public boolean setPeriodicDelay(final Duration delay) {
        return this.timedTask.setPeriodicDelay(delay);
    }

    /**
     * Repetitive delay means the task executes with a fixed delay after the
     * previous execution completes. Clears any periodic delay.
     *
     * @param delay the delay between consecutive task executions
     * @return false if the task is currently running
     */
    public boolean setRepetitiveDelay(final Duration delay) {
        return this.timedTask.setRepetitiveDelay(delay);
    }

    /**
     * @param name the name of the task
     * @return false if the task is currently running
     */
    public boolean setName(final String name) {
        return this.timedTask.setName(name);
    }
}
