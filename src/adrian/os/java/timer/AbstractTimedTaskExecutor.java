package adrian.os.java.timer;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract base class for creating and running {@link TimedTask}s.
 * <p>
 * Provides a common implementation for creating timed tasks while allowing
 * subclasses to define their own execution strategies.
 * </p>
 */
public abstract class AbstractTimedTaskExecutor {

    /**
     * Creates a new {@link TimedTaskBuilder} for the specified task.
     *
     * @param task the task to execute as part of the timed task
     * @return a new {@link TimedTaskBuilder} instance for configuring the timed
     *         task
     */
    public TimedTaskBuilder createTask(final Consumer<TimedTask> task) {
        return new TimedTaskBuilder(task, this);
    }

    /**
     * Creates a new {@link FutureTimedTaskBuilder} for the specified task.
     *
     * @param <T>  the result type of the callable
     * @param task the callable to execute as part of the timed task
     * @return a new {@link FutureTimedTaskBuilder} instance for configuring the
     *         callable timed task
     */
    public <T> FutureTimedTaskBuilder<T> createFutureTask(final Function<FutureTimedTask<T>, T> task) {
        return new FutureTimedTaskBuilder<>(task, this);
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
