package org.adrian.chrono;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract base class for creating and running {@link ChronoTask}s.
 * <p>
 * Provides a common implementation for creating timed tasks while allowing
 * subclasses to define their own execution strategies.
 * </p>
 */
public abstract class AbstractExecutor {

    /**
     * Creates a new {@link ChronoTaskBuilder} for the specified task.
     *
     * @param task the task to execute as part of the timed task
     * @return a new {@link ChronoTaskBuilder} instance for configuring the timed
     *         task
     */
    public ChronoTaskBuilder createTask(final Consumer<ChronoTask> task) {
        return new ChronoTaskBuilder(task, this);
    }

    /**
     * Creates a new {@link FutureChronoTaskBuilder} for the specified task.
     *
     * @param <T>  the result type of the callable
     * @param task the callable to execute as part of the timed task
     * @return a new {@link FutureChronoTaskBuilder} instance for configuring the
     *         callable timed task
     */
    public <T> FutureChronoTaskBuilder<T> createFutureTask(final Function<FutureChronoTask<T>, T> task) {
        return new FutureChronoTaskBuilder<>(task, this);
    }

    /**
     * Executes the given task using the executor's execution strategy.
     *
     * @param task to execute
     */
    protected abstract void run(final Runnable task);

    /**
     * Executes the given task using the executor's execution strategy, with an
     * associated name for the task.
     *
     * @param task to execute
     * @param name the name to associate with the task
     */
    protected abstract void run(final Runnable task, String name);
}
