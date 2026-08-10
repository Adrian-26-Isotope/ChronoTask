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
     * Sets the schedule that determines when the next execution fires. Replaces
     * any previous schedule.
     *
     * @param schedule the schedule to use
     * @return false if the task is currently running
     */
    protected abstract boolean setSchedule(final Schedule schedule);

}
