package org.adrian.chrono;

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
 * Each execution runs the task first, then atomically claims whichever future is
 * currently exposed and completes it with the result, installing a fresh future for
 * the following execution. Because the claim happens at completion time rather than
 * at dispatch time, overlapping executions are published in the order they finish,
 * not the order they started.
 * </p>
 * <p>
 * Instances are created via {@link AbstractExecutor#createFutureTask(Function)}.
 * </p>
 *
 * @param <T> the type of the result produced by the task.
 */
public class FutureChronoTask<T> extends AbstractChronoTask {

    private final ChronoTask chronoTask;
    private final AtomicReference<CompletableFuture<T>> nextResult;
    private volatile T lastResult;

    /**
     * @param task the function to execute on each trigger; receives this task
     *            instance as argument.
     * @param executor the executor that runs the underlying timer and task threads.
     */
    FutureChronoTask(final Function<FutureChronoTask<T>, T> task, final AbstractExecutor executor) {
        Objects.requireNonNull(task);
        this.nextResult = new AtomicReference<>(new CompletableFuture<>());

        Consumer<ChronoTask> consumer = _ -> {
            T result = null;
            Exception failure = null;
            try {
                result = task.apply(FutureChronoTask.this);
            }
            catch (final Exception e) {
                failure = e;
            }
            synchronized (FutureChronoTask.this) {
                CompletableFuture<T> currentFuture = FutureChronoTask.this.nextResult.get();
                if (currentFuture.isDone()) {
                    return;
                }
                FutureChronoTask.this.nextResult.set(new CompletableFuture<>());
                if (failure == null) {
                    FutureChronoTask.this.lastResult = result;
                    currentFuture.complete(result);
                }
                else {
                    currentFuture.completeExceptionally(failure);
                }
            }
        };

        this.chronoTask = executor.createTask(consumer).build();
        this.chronoTask.setOnTermination(this::onTermination);
    }

    /**
     * Starts the task and returns the {@link CompletableFuture} for the next
     * upcoming execution. If the task was previously stopped before its prior
     * {@code start()}'s execution ever fired, the cancelled future is replaced
     * with a fresh one, since {@link #stop()} cancels any pending future.
     *
     * @return the future that will be completed by the next execution, or
     *         {@code null} if the task is already running or failed to start
     */
    public synchronized CompletableFuture<T> start() {
        if (isRunning()) {
            return null;
        }
        CompletableFuture<T> next = getNextResult();
        if (next.isDone()) {
            next = new CompletableFuture<>();
            this.nextResult.set(next);
        }
        boolean started = this.chronoTask.start();
        return started ? next : null;
    }

    /**
     * Stops any recurring executions and terminates this task gracefully. Once
     * stopped the task can be started again via {@link #start()}. Any pending
     * {@link CompletableFuture} is cancelled so that a caller waiting on
     * {@code get()} does not hang forever; the result of an in-flight execution
     * that has not yet claimed the future is discarded.
     */
    public synchronized void stop() {
        this.chronoTask.stop();
        this.nextResult.get().cancel(false);
    }

    /**
     * Called when the underlying {@link ChronoTask} terminates on its own
     * (e.g. a one-shot schedule completing) without an external {@link #stop()}.
     * Cancels any orphan {@link CompletableFuture} left by the last execution's
     * claim-and-replace so that {@link #getNextResult()} never hands out a
     * future that hangs forever after the task has stopped.
     * <p>
     * This callback is invoked by {@code ChronoTask.fireTermination()}
     * <em>outside</em> the {@code ChronoTask} monitor, so it is safe for this
     * method to be {@code synchronized}. The {@link #isRunning()} guard
     * ensures that if a new {@link #start()} has already transitioned the task
     * back to {@code RUNNING}, the fresh future is not cancelled.
     * </p>
     */
    private synchronized void onTermination() {
        if (!isRunning()) {
            this.nextResult.get().cancel(false);
        }
    }

    /**
     * @return {@code true} if this task is currently running, {@code false}
     *         otherwise
     */
    public boolean isRunning() {
        return this.chronoTask.isRunning();
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
     * Returns the {@link CompletableFuture} that will be completed by whichever
     * execution's result becomes available next, in completion order rather than
     * start order.
     * <p>
     * Under overlapping executions this may be an execution that was already
     * in-flight before this method was called, if that execution finishes first
     * among the currently in-flight ones. For recurring tasks, call this method
     * after waiting on the previous future to obtain the future for the following
     * result.
     * </p>
     *
     * @return the future that will be completed by the next result to become
     *         available
     */
    public CompletableFuture<T> getNextResult() {
        return this.nextResult.get();
    }

    @Override
    protected boolean setInitialDelay(final Duration delay) {
        return this.chronoTask.setInitialDelay(delay);
    }

    @Override
    protected boolean setSchedule(final Schedule schedule) {
        return this.chronoTask.setSchedule(schedule);
    }

    @Override
    protected boolean setName(final String name) {
        return this.chronoTask.setName(name);
    }

    @Override
    protected boolean setMaxConcurrentExecutions(final int max) {
        return this.chronoTask.setMaxConcurrentExecutions(max);
    }
}
