package org.adrian.chrono;

import java.time.Duration;

/**
 * Common builder pattern for task configuration.
 *
 * @param <B> self-referential builder type for method chaining.
 */
abstract class AbstractTaskBuilder<B extends AbstractTaskBuilder<B>> {

    private String name;
    private Duration periodicDelay = null;
    private Duration repetitiveDelay = null;
    private Duration initialDelay = Duration.ZERO;
    private int maxConcurrentExecutions = Integer.MAX_VALUE;

    private final AbstractExecutor executor;


    /**
     * @param executor the responsible for execution any task created with this builder.
     */
    protected AbstractTaskBuilder(final AbstractExecutor executor) {
        this.executor = executor;
    }

    /**
     * @return the executor
     */
    protected AbstractExecutor getExecutor() {
        return this.executor;
    }


    /**
     * @return itself, casted.
     */
    @SuppressWarnings("unchecked")
    protected B self() {
        return (B) this;
    }

    /**
     * Add an initial delay to the scheduler. The duration is decoupled from the
     * input to prevent strong references to the external {@link Duration}.
     *
     * @param initialDelay the initial delay before the first execution
     * @return this builder instance for method chaining
     * @throws IllegalArgumentException if the given duration is negative
     */
    public B setInitialDelay(final Duration initialDelay) {
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.initialDelay = DurationCopier.copyOf(initialDelay);
        return self();
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
     * @throws IllegalArgumentException if the given duration is negative
     */
    public B setPeriodicDelay(final Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.periodicDelay = DurationCopier.copyOf(delay);
        this.repetitiveDelay = null;
        return self();
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
     * @throws IllegalArgumentException if the given duration is negative
     */
    public B setRepetitiveDelay(final Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.repetitiveDelay = DurationCopier.copyOf(delay);
        this.periodicDelay = null;
        return self();
    }

    /**
     * Sets the name of the task for identification purposes. The string is
     * decoupled from the input to not store a strong reference.
     *
     * @param name the name of the task.
     * @return this builder instance for method chaining.
     * @throws NullPointerException if name is null.
     */
    public B setName(final String name) {
        this.name = new String(name);
        return self();
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
    public B setMaxConcurrentExecutions(final int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max concurrent executions must be at least 1.");
        }
        this.maxConcurrentExecutions = max;
        return self();
    }

    /**
     * Applies the configured settings to the given task.
     *
     * @param task the task instance to configure.
     */
    protected void applyConfig(final AbstractChronoTask task) {
        if ((this.name != null) && !this.name.isBlank()) {
            task.setName(this.name);
        }
        task.setInitialDelay(this.initialDelay);
        task.setPeriodicDelay(this.periodicDelay);
        task.setRepetitiveDelay(this.repetitiveDelay);
        task.setMaxConcurrentExecutions(this.maxConcurrentExecutions);
    }
}
