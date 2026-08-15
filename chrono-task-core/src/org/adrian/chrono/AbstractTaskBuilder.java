package org.adrian.chrono;

import java.time.Duration;
import java.util.Objects;

import org.adrian.chrono.util.DurationCopier;
import org.adrian.chrono.util.ThreadNames;

/**
 * Common builder pattern for task configuration.
 *
 * @param <B> self-referential builder type for method chaining.
 */
abstract class AbstractTaskBuilder<B extends AbstractTaskBuilder<B>> {

    private String name;
    private Schedule schedule = new Schedule.OneShot();
    private Duration initialDelay = Duration.ZERO;
    private int maxConcurrentExecutions = 1000;

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
        Objects.requireNonNull(initialDelay, "initialDelay");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.initialDelay = DurationCopier.copyOf(initialDelay);
        return self();
    }

    /**
     * Sets the schedule that determines when the next execution fires. This is
     * the primary API for configuring recurrence; it replaces any previous
     * schedule.
     *
     * @param schedule the schedule to use; must not be {@code null}
     * @return this builder instance for method chaining
     * @throws NullPointerException if {@code schedule} is {@code null}
     */
    public B setSchedule(final Schedule schedule) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        return self();
    }

    /**
     * Periodic delay means the task executes at fixed intervals from the start
     * time. Clears any previous schedule.
     * <p>
     * The duration is decoupled from the input to prevent strong references to
     * the external {@link Duration}.
     *
     * @param delay the fixed delay between task executions
     * @return this builder instance for method chaining
     * @throws NullPointerException if {@code delay} is {@code null}
     * @throws IllegalArgumentException if the given duration is negative
     * @deprecated use {@link #setSchedule(Schedule)} with {@link Schedule.Periodic}
     */
    @Deprecated
    public B setPeriodicDelay(final Duration delay) {
        this.schedule = new Schedule.Periodic(delay);
        return self();
    }

    /**
     * Repetitive delay means the task executes with a fixed delay after the
     * previous execution completes. Clears any previous schedule.
     * <p>
     * The duration is decoupled from the input to prevent strong references to
     * the external {@link Duration}.
     *
     * @param delay the delay between consecutive task executions
     * @return this builder instance for method chaining
     * @throws NullPointerException if {@code delay} is {@code null}
     * @throws IllegalArgumentException if the given duration is negative
     * @deprecated use {@link #setSchedule(Schedule)} with {@link Schedule.Repetitive}
     */
    @Deprecated
    public B setRepetitiveDelay(final Duration delay) {
        this.schedule = new Schedule.Repetitive(delay);
        return self();
    }

    /**
     * Sets the name of the task for identification purposes. The name is
     * validated via {@link ThreadNames#sanitize(String)} to reject characters
     * outside the allow-list and names exceeding the length cap. Check
     * {@link ThreadNames#ALLOWED} for the allow-list regex.
     *
     * @param name the name of the task.
     * @return this builder instance for method chaining.
     * @throws NullPointerException if {@code name} is {@code null}.
     * @throws IllegalArgumentException if {@code name} does not match allowed pattern.
     */
    public B setName(final String name) {
        this.name = ThreadNames.sanitize(name);
        return self();
    }

    /**
     * Bounds how many executions of this task may run concurrently. Only relevant
     * in periodic mode, where a slow task can otherwise overlap with subsequent
     * firings. Defaults to 1000.
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
        task.setSchedule(this.schedule);
        task.setMaxConcurrentExecutions(this.maxConcurrentExecutions);
    }
}
