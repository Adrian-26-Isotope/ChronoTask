package adrian.os.java.timer;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * builder pattern for scheduling a {@link TimedTask}.
 */
public class TimedTaskBuilder {

    // mandatory args
    private final Consumer<TimedTask> task;
    private final AbstractTimedTaskExecutor executor;

    // optional args
    private String name;
    private Duration periodicDelay = null;
    private Duration repetitiveDelay = null;
    private Duration initialDelay = Duration.ZERO;

    /**
     * @param task     the task to be executed by the timer.
     * @param executor the executor to run the timer and task.
     * @warning Avoid using strong references to external objects within the task.
     *          Strong references will keep objects in scope for the entire lifetime
     *          of this {@link TimedTask}, preventing garbage collection and
     *          potentially causing memory leaks. Consider using weak references or
     *          ensuring proper cleanup when the {@link TimedTask} is no longer
     *          needed.
     */
    protected TimedTaskBuilder(final Consumer<TimedTask> task, final AbstractTimedTaskExecutor executor) {
        this.task = task;
        this.executor = executor;
    }

    /**
     * Add an initial delay to the scheduler. The duration is decoupled from the
     * input to prevent strong references to the external {@link Duration}.
     *
     * @param initialDelay the initial delay before the first execution
     * @return this builder instance for method chaining
     */
    public TimedTaskBuilder setInitialDelay(final Duration initialDelay) {
        this.initialDelay = Duration.ofNanos(initialDelay.toNanos());
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
    public TimedTaskBuilder setPeriodicDelay(final Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.periodicDelay = Duration.ofNanos(delay.toNanos());
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
    public TimedTaskBuilder setRepetitiveDelay(final Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("a negative duration is not allowed.");
        }
        this.repetitiveDelay = Duration.ofNanos(delay.toNanos());
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
    public TimedTaskBuilder setName(final String name) {
        this.name = String.valueOf(name);
        return this;
    }

    /**
     * build the timer with configured settings. The task needs to be started
     * separately!
     *
     * @return the TimedTask instance.
     */
    public TimedTask build() {
        TimedTask timer = new TimedTask(this.task, this.executor);
        if ((this.name != null) && !this.name.isBlank()) {
            timer.setName(this.name);
        }
        timer.setInitialDelay(this.initialDelay);
        timer.setPeriodicDelay(this.periodicDelay);
        timer.setRepetitiveDelay(this.repetitiveDelay);
        return timer;
    }

}
