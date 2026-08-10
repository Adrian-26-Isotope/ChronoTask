package org.adrian.chrono;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.adrian.chrono.util.DurationCopier;

/**
 * Represents the recurrence policy of a {@link ChronoTask}. A schedule
 * determines when the next execution fires: immediately after dispatch (before
 * the task runs) via {@link #afterDispatch(Instant)}, or after the task
 * completes via {@link #afterCompletion(Instant)}. Exactly one {@code Schedule}
 * instance is held by a task, so the three modes — periodic, repetitive, and
 * one-shot — are mutually exclusive by construction rather than by ad-hoc
 * clearing of nullable fields.
 *
 * @see Periodic
 * @see Repetitive
 * @see OneShot
 */
public sealed interface Schedule {

    /**
     * Computes the next execution time immediately after a task is dispatched
     * (submitted for execution), or {@code null} if the next execution should be
     * deferred until the current execution completes.
     *
     * @param dispatchTime the instant at which the current execution was
     *            dispatched
     * @return the next execution instant, or {@code null} to defer to
     *         {@link #afterCompletion(Instant)}
     */
    Instant afterDispatch(Instant dispatchTime);

    /**
     * Computes the next execution time immediately after a task completes, or
     * {@code null} if the task should stop (one-shot semantics). Only consulted
     * when {@link #afterDispatch(Instant)} returned {@code null} (i.e. the
     * schedule deferred to completion time).
     *
     * @param completionTime the instant at which the current execution completed
     * @return the next execution instant, or {@code null} to stop the task
     */
    Instant afterCompletion(Instant completionTime);

    /**
     * Periodic schedule: the task executes at fixed intervals measured from the
     * dispatch time of each execution, regardless of how long the task takes.
     * Similar to
     * {@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate}.
     * <p>
     * The next execution is scheduled at dispatch time via
     * {@link #afterDispatch(Instant)}; {@link #afterCompletion(Instant)} is not
     * consulted.
     * </p>
     *
     * @param delay the fixed interval between dispatch times
     */
    record Periodic(Duration delay) implements Schedule {

        /**
         * Compact constructor.
         *
         * @param delay the fixed interval between dispatch times
         * @throws NullPointerException if {@code delay} is {@code null}
         * @throws IllegalArgumentException if {@code delay} is negative
         */
        public Periodic {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("a negative duration is not allowed.");
            }
            delay = DurationCopier.copyOf(delay);
        }

        @Override
        public Instant afterDispatch(final Instant dispatchTime) {
            return dispatchTime.plus(this.delay);
        }

        @Override
        public Instant afterCompletion(final Instant completionTime) {
            return null;
        }
    }

    /**
     * Repetitive schedule: the task executes with a fixed delay measured from
     * the completion of the previous execution. Similar to
     * {@link java.util.concurrent.ScheduledExecutorService#scheduleWithFixedDelay}.
     * <p>
     * The next execution is deferred to completion time; {@link #afterDispatch}
     * returns {@code null} and {@link #afterCompletion(Instant)} schedules the
     * next firing.
     * </p>
     *
     * @param delay the delay between the completion of one execution and the
     *            dispatch of the next
     */
    record Repetitive(Duration delay) implements Schedule {

        /**
         * Compact constructor.
         *
         * @param delay the delay between completions and subsequent dispatches
         * @throws NullPointerException if {@code delay} is {@code null}
         * @throws IllegalArgumentException if {@code delay} is negative
         */
        public Repetitive {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("a negative duration is not allowed.");
            }
            delay = DurationCopier.copyOf(delay);
        }

        @Override
        public Instant afterDispatch(final Instant dispatchTime) {
            return null;
        }

        @Override
        public Instant afterCompletion(final Instant completionTime) {
            return completionTime.plus(this.delay);
        }
    }

    /**
     * One-shot schedule: the task executes exactly once and then stops.
     * <p>
     * Both {@link #afterDispatch} and {@link #afterCompletion} return
     * {@code null}: dispatch defers to completion, and completion signals stop.
     * </p>
     */
    record OneShot() implements Schedule {

        @Override
        public Instant afterDispatch(final Instant dispatchTime) {
            return null;
        }

        @Override
        public Instant afterCompletion(final Instant completionTime) {
            return null;
        }
    }
}
