package org.adrian.chrono;

import java.util.function.Consumer;

/**
 * builder pattern for scheduling a {@link ChronoTask}.
 */
public class ChronoTaskBuilder extends AbstractTaskBuilder<ChronoTaskBuilder> {

    // mandatory args
    private final Consumer<ChronoTask> task;


    /**
     * Creates a builder for a {@link ChronoTask} executed by the given executor.
     * <p>
     * <b>Avoid using strong references to external objects within the task. Strong
     * references will keep objects in scope for the entire lifetime of this
     * {@link ChronoTask}, preventing garbage collection and potentially causing
     * memory leaks. Consider using weak references or ensuring proper cleanup
     * when the {@link ChronoTask} is no longer needed.</b>
     *
     * @param task the task to be executed by the timer.
     * @param executor the executor to run the timer and task.
     */
    protected ChronoTaskBuilder(final Consumer<ChronoTask> task, final AbstractExecutor executor) {
        super(executor);
        this.task = task;
    }


    /**
     * build the {@link ChronoTask} with configured settings. The task needs to be started
     * separately!
     *
     * @return the ChronoTask instance.
     */
    public ChronoTask build() {
        ChronoTask chronoTask = new ChronoTask(this.task, getExecutor());
        applyConfig(chronoTask);
        return chronoTask;
    }

}
