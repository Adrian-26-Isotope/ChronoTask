package org.adrian.chrono;

import java.time.Duration;

abstract class AbstractChronoTask {

    /**
     * @param delay the initial delay before the first execution; ignored when
     *            negative
     * @return false if the task is currently running
     */
    protected abstract boolean setInitialDelay(final Duration delay);

    /**
     * Bounds how many executions of this task may run concurrently. Only relevant
     * in periodic mode, where a slow task can otherwise overlap with subsequent
     * firings; defaults to unbounded ({@link Integer#MAX_VALUE}).
     *
     * @param max the maximum number of concurrent executions to allow
     * @return false if the task is currently running
     */
    protected abstract boolean setMaxConcurrentExecutions(final int max);

    /**
     * @param name the name of the task
     * @return false if the task is currently running
     */
    protected abstract boolean setName(final String name);

    /**
     * Periodic delay means the task executes at fixed intervals from the start
     * time. Clears any repetitive delay.
     *
     * @param delay the fixed delay between task executions
     * @return false if the task is currently running
     */
    protected abstract boolean setPeriodicDelay(final Duration delay);

    /**
     * Repetitive delay means the task executes with a fixed delay after the
     * previous execution completes. Clears any periodic delay.
     *
     * @param delay the delay between consecutive task executions
     * @return false if the task is currently running
     */
    protected abstract boolean setRepetitiveDelay(final Duration delay);

}
