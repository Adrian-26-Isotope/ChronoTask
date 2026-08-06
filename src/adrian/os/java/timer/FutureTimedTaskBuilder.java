package adrian.os.java.timer;

import java.time.Duration;
import java.util.function.Function;

/**
 * builder pattern for scheduling a {@link FutureTimedTask}.
 *
 * @param <T> the result type of the callable
 */
public class FutureTimedTaskBuilder<T> {

    // mandatory args
    private final Function<FutureTimedTask<T>, T> callable;
    private final AbstractTimedTaskExecutor executor;

    // optional args
    private String name;
    private Duration periodicDelay = null;
    private Duration repetitiveDelay = null;
    private Duration initialDelay = Duration.ZERO;
    private int maxConcurrentExecutions = Integer.MAX_VALUE;

    /**
     * @param callable the callable to be executed by the timer.
     * @param executor the executor to run the timer and task.
     * @warning Avoid using strong references to external objects within the
     *          callable. Strong references will keep objects in scope for the
     *          entire lifetime of this {@link FutureTimedTask}, preventing garbage
     *          collection and potentially causing memory leaks. Consider using weak
     *          references or ensuring proper cleanup when the
     *          {@link FutureTimedTask} is no longer needed.
     */
    protected FutureTimedTaskBuilder(final Function<FutureTimedTask<T>, T> callable,
            final AbstractTimedTaskExecutor executor) {
        this.callable = callable;
        this.executor = executor;
    }

    /**
     * Add an initial delay to the scheduler. The duration is decoupled from the
     * input to prevent strong references to the external {@link Duration}.
     *
     * @param initialDelay the initial delay before the first execution
     * @return this builder instance for method chaining
     * @throws IllegalArgumentException if the given duration is negative
     */
    public FutureTimedTaskBuilder<T> setInitialDelay(final Duration initialDelay) {
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.initialDelay = DurationCopier.copyOf(initialDelay);
        return this;
    }

    /**
     * Periodic delay means the task executes at fixed intervals from the start
     * time.<br>
     * Add a periodic delay to the scheduler. Clears the repetitive delay. The
     * duration is decoupled from the input to prevent strong references to the
     * external {@link Duration}.
     *
     * @param delay the fixed delay between task executions
     * @return this builder instance for method chaining
     */
    public FutureTimedTaskBuilder<T> setPeriodicDelay(final Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.periodicDelay = DurationCopier.copyOf(delay);
        this.repetitiveDelay = null;
        return this;
    }

    /**
     * Repetitive delay means the task executes with a fixed delay after the
     * previous execution completes.<br>
     * Add a repetitive delay to the scheduler. Clears the periodic delay. The
     * duration is decoupled from the input to prevent strong references to the
     * external {@link Duration}.
     *
     * @param delay the delay between consecutive task executions
     * @return this builder instance for method chaining
     */
    public FutureTimedTaskBuilder<T> setRepetitiveDelay(final Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.repetitiveDelay = DurationCopier.copyOf(delay);
        this.periodicDelay = null;
        return this;
    }

    /**
     * Sets the name of the task for identification purposes. The string is
     * decoupled from the input to not store a strong reference.
     *
     * @param name the name of the task.
     * @return this builder instance for method chaining.
     */
    public FutureTimedTaskBuilder<T> setName(final String name) {
        this.name = new String(name);
        return this;
    }

    /**
     * Bounds how many executions of this task may run concurrently. Only relevant
     * in periodic mode, where a slow task can otherwise overlap with subsequent
     * firings. Defaults to unbounded ({@link Integer#MAX_VALUE}).
     *
     * @param max the maximum number of concurrent executions to allow
     * @return this builder instance for method chaining
     * @throws IllegalArgumentException if {@code max} is less than 1
     */
    public FutureTimedTaskBuilder<T> setMaxConcurrentExecutions(final int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max concurrent executions must be at least 1.");
        }
        this.maxConcurrentExecutions = max;
        return this;
    }

    /**
     * build the timer with configured settings. The task needs to be started
     * separately!
     *
     * @return the {@link FutureTimedTask} instance.
     */
    public FutureTimedTask<T> build() {
        FutureTimedTask<T> task = new FutureTimedTask<>(this.callable, this.executor);
        if ((this.name != null) && !this.name.isBlank()) {
            task.setName(this.name);
        }
        task.setInitialDelay(this.initialDelay);
        task.setPeriodicDelay(this.periodicDelay);
        task.setRepetitiveDelay(this.repetitiveDelay);
        task.setMaxConcurrentExecutions(this.maxConcurrentExecutions);
        return task;
    }
}
