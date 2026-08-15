
package org.adrian.chrono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.adrian.chrono.util.MonotonicClock;

/**
 * A timer with the ability to run a single task. This task can be schedules
 * periodically, repetitively or once with a initial delay.
 */
public class ChronoTask extends AbstractChronoTask {

    private static final MonotonicClock MONOTONIC_CLOCK = new MonotonicClock(ZoneId.systemDefault());

    @SuppressWarnings("javadoc")
    protected enum State {
                          RUNNING,
                          SHUTDOWN,
                          STOPPED;
    }

    /* mandatory fields */
    private final Consumer<ChronoTask> task;
    private final AbstractExecutor executor;

    /* optional fields */
    private volatile String name = "";
    private volatile Duration initialDelay;
    private final AtomicReference<Schedule> schedule = new AtomicReference<>(new Schedule.OneShot());
    private final AtomicReference<Semaphore> executionThrottle = new AtomicReference<>(new Semaphore(1000));

    /* internal fields */
    private long count = 0;
    private final Timer timer = new Timer();
    private volatile Instant nextExecution;
    private volatile State state = State.STOPPED;
    private final Object executionLock = new Object();
    private volatile Runnable onTermination;


    /**
     * @param task the task to be executed by this timer.
     */
    protected ChronoTask(final Consumer<ChronoTask> task, final AbstractExecutor exec) {
        this.task = Objects.requireNonNull(task);
        this.executor = Objects.requireNonNull(exec);
    }

    /**
     * start the timer thread.
     */
    public boolean start() {
        synchronized (this) {
            while (getState() == State.SHUTDOWN) {
                try {
                    wait();
                }
                catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (getState() != State.STOPPED) {
                return false; // task is still running --> cannot be started again.
            }
            setState(State.RUNNING);
            setNextExecutionTime(Instant.now(MONOTONIC_CLOCK).plus(this.initialDelay));
        }

        try {
            if ((this.name == null) || this.name.isBlank()) {
                this.executor.run(this.timer::runTimer);
            }
            else {
                String timerName = "[" + this.name + "]Timer";
                this.executor.run(this.timer::runTimer, timerName);
            }
        }
        catch (RejectedExecutionException _) {
            // submission was dropped/rejected: roll back so the task cannot get stuck.
            synchronized (this) {
                setState(State.STOPPED);
                setNextExecutionTime(null);
                notifyAll();
            }
            fireTermination();
            return false;
        }
        return true;
    }

    /**
     * stop any reoccurring executions and terminate this timer gracefully. Once
     * stopped it can be started again.
     */
    public synchronized void stop() {
        if (isRunning()) {
            setState(State.SHUTDOWN);
            // notify waiting timer thread
            setNextExecutionTime(null);
            // interrupt sleeping timer thread
            this.timer.interruptTimerThread();
        }
    }

    /**
     * Applies a configuration mutation only while the task is not running.
     *
     * @param setter the configuration mutation to apply
     * @return {@code true} if applied, {@code false} if the task is currently
     *         running
     */
    private synchronized boolean applyIfStopped(final Runnable setter) {
        if (!isRunning()) {
            setter.run();
            return true;
        }
        return false;
    }

    @Override
    protected boolean setName(final String name) {
        return applyIfStopped(() -> this.name = name);
    }

    /**
     * @return the state
     */
    protected State getState() {
        return this.state;
    }

    /**
     * set a new state.
     * <p>
     * Callers that transition to {@link State#STOPPED} must invoke
     * {@link #fireTermination()} <em>after</em> releasing the instance
     * monitor so that registered callbacks run outside this lock.
     * </p>
     */
    protected void setState(final State state) {
        this.state = state;
    }

    /**
     * Registers a callback invoked when this task transitions to the
     * {@link State#STOPPED} state. The callback runs once per transition, on the thread
     * performing the transition, <em>outside</em> the instance monitor. It is
     * therefore safe for the callback to acquire other monitors.
     *
     * @param listener the callback, or {@code null} to clear it
     */
    void setOnTermination(final Runnable listener) {
        this.onTermination = listener;
    }

    /**
     * Invokes the registered termination callback, if any. Must be called
     * <em>after</em> the {@code synchronized} block that performed the
     * {@link State#STOPPED} transition, i.e. outside the instance monitor.
     */
    private void fireTermination() {
        final Runnable listener = this.onTermination;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * @return true if this task is still running, false otherwise.
     */
    public boolean isRunning() {
        return getState() == State.RUNNING;
    }

    /**
     * @return the time the next execution shall be triggered.
     */
    protected Instant getNextExecution() {
        return this.nextExecution;
    }

    /**
     * set the new next execution time.
     */
    protected void setNextExecutionTime(final Instant time) {
        synchronized (this.executionLock) {
            this.nextExecution = time;
            this.executionLock.notifyAll();
        }
    }

    @Override
    protected boolean setInitialDelay(final Duration delay) {
        return applyIfStopped(() -> this.initialDelay = delay);
    }

    @Override
    protected boolean setSchedule(final Schedule schedule) {
        return applyIfStopped(() -> this.schedule.set(Objects.requireNonNull(schedule)));
    }

    @Override
    protected boolean setMaxConcurrentExecutions(final int max) {
        return applyIfStopped(() -> this.executionThrottle.set(new Semaphore(max)));
    }

    /**
     * encapsulate timer thread code
     */
    private class Timer {

        private Thread timerThread;

        private void runTimer() {
            this.timerThread = Thread.currentThread();
            try {
                if (!isRunning()) {
                    return;
                }
                loopTimer();
            }
            finally {
                // deliberately clear the interrupt state. in case of a pool thread, the thread can safely be reused.
                Thread.interrupted();

                synchronized (ChronoTask.this) {
                    setState(State.STOPPED);
                    // notify potential waiting restart.
                    ChronoTask.this.notifyAll();
                }
                fireTermination();
            }
        }

        private void loopTimer() {
            try {
                Instant next;
                while (isAlive() && ((next = getNextExecution()) != null)) {
                    if (next.compareTo(Instant.now(MONOTONIC_CLOCK)) <= 0) {
                        Semaphore throttle = ChronoTask.this.executionThrottle.get();
                        throttle.acquire();
                        Instant deferredNext = setNextPeriodicExecutionTime(next);
                        executeTask(throttle, deferredNext == null);
                    }
                    waitTillNextExecution();
                }
            }
            catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean isAlive() {
            return isRunning() && !Thread.currentThread().isInterrupted();
        }

        private void executeTask(final Semaphore throttle, final boolean deferred) {
            Runnable runnable = () -> {
                try {
                    ChronoTask.this.task.accept(ChronoTask.this);
                }
                catch (final Exception e) {
                    Thread current = Thread.currentThread();
                    Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
                    if (handler != null) {
                        handler.uncaughtException(current, e);
                    }
                }
                finally {
                    if (deferred) {
                        setNextRepetetiveExecutionTime();
                    }
                    throttle.release();
                }
            };

            if ((ChronoTask.this.name == null) || ChronoTask.this.name.isBlank()) {
                ChronoTask.this.executor.run(runnable);
            }
            else {
                String taskName = "[" + ChronoTask.this.name + "]Task#" + (++ChronoTask.this.count);
                ChronoTask.this.executor.run(runnable, taskName);
            }

        }

        private Instant setNextPeriodicExecutionTime(final Instant next) {
            Instant deferredNext = ChronoTask.this.schedule.get().afterDispatch(next);
            setNextExecutionTime(deferredNext);
            return deferredNext;
        }

        private void setNextRepetetiveExecutionTime() {
            Instant next = ChronoTask.this.schedule.get().afterCompletion(Instant.now(MONOTONIC_CLOCK));
            if (next == null) {
                stop();
            }
            else {
                setNextExecutionTime(next);
            }
        }

        private void waitTillNextExecution() throws InterruptedException {
            if (getNextExecution() == null) {
                // REPETITIVE DELAY SCENARIO: wait till next execution time is set by the task
                // thread.
                synchronized (ChronoTask.this.executionLock) {
                    while (isAlive() && (getNextExecution() == null)) {
                        ChronoTask.this.executionLock.wait();
                    }
                }
            }
            else {
                Duration duration = Duration.between(Instant.now(MONOTONIC_CLOCK), getNextExecution());
                if (!duration.isNegative()) {
                    Thread.sleep(duration);
                }
                // timer thread sleeps
                // task thread terminates once finished
            }
        }

        private synchronized void interruptTimerThread() {
            if (this.timerThread != null) {
                this.timerThread.interrupt();
            }
        }

    }
}
