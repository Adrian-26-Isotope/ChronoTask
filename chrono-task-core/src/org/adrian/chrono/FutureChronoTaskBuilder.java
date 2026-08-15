package org.adrian.chrono;

import java.util.function.Function;

/**
 * builder pattern for scheduling a {@link FutureChronoTask}.
 *
 * @param <T> the result type of the task
 */
public class FutureChronoTaskBuilder<T> extends AbstractTaskBuilder<FutureChronoTaskBuilder<T>> {

    // mandatory args
    private final Function<FutureChronoTask<T>, T> task;


    /**
     * Creates a builder for a {@link FutureChronoTask} executed by the given
     * executor.
     * <p>
     * <b>Avoid using strong references to external objects within the task. Strong
     * references will keep objects in scope for the entire lifetime of this
     * {@link FutureChronoTask}, preventing garbage collection and potentially
     * causing memory leaks. Consider using weak references or ensuring proper
     * cleanup when the {@link FutureChronoTask} is no longer needed.</b>
     *
     * @param task the task to be executed by the timer.
     * @param executor the executor to run the timer and task.
     */
    protected FutureChronoTaskBuilder(final Function<FutureChronoTask<T>, T> task, final AbstractExecutor executor) {
        super(executor);
        this.task = task;
    }


    /**
     * build the {@link FutureChronoTask} with configured settings. The task needs to be started
     * separately!
     *
     * @return the {@link FutureChronoTask} instance.
     */
    public FutureChronoTask<T> build() {
        FutureChronoTask<T> chronoTask = new FutureChronoTask<>(this.task, getExecutor());
        applyConfig(chronoTask);
        return chronoTask;
    }

}
