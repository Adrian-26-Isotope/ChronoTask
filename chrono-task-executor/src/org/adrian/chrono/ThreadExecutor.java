package org.adrian.chrono;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * Implementation of {@link AbstractExecutor} that uses a configurable
 * {@link ThreadFactory} to execute timed tasks on individual threads.
 * <p>
 * By default, this executor uses virtual threads for task execution, but can be
 * configured to use a custom thread factory via
 * {@link #setThreadFactory(ThreadFactory)}.
 * </p>
 */
public class ThreadExecutor extends AbstractExecutor {

    private volatile ThreadFactory threadFactory = Thread.ofVirtual().factory();

    /**
     * Sets the thread factory to be used for creating threads to execute tasks.
     *
     * @param threadFactory the thread factory to use for task execution; must
     *                     not be {@code null}
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public void setThreadFactory(final ThreadFactory threadFactory) {
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    /**
     * Executes the given runnable on a new thread created by the configured thread
     * factory.
     * The thread is started immediately.
     *
     * @param runnable the task to execute
     */
    @Override
    protected void run(final Runnable runnable) {
        Thread taskThread = this.threadFactory.newThread(runnable);
        taskThread.start();
    }

    /**
     * Executes the given runnable on a new thread created by the configured thread
     * factory, assigning the specified name to the thread. The thread is started
     * immediately.
     *
     * @param runnable the task to execute
     * @param name the name to assign to the thread executing the task
     */
    @Override
    protected void run(final Runnable runnable, final String name) {
        Thread taskThread = this.threadFactory.newThread(runnable);
        taskThread.setName(name);
        taskThread.start();
    }

}
