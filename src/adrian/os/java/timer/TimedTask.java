package adrian.os.java.timer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A timer with the ability to run a single task. This task can be schedules
 * periodically, repetitively or once with a initial delay.
 */
public class TimedTask {

    @SuppressWarnings("javadoc")
    protected enum State {
                          RUNNING,
                          SHUTDOWN,
                          STOPPED;
    }

    /* mandatory fields */
    private final Consumer<TimedTask> task;
    private final AbstractTimedTaskExecutor executor;

    /* optional fields */
    private String name = "";
    private Duration initialDelay;
    private Duration periodicDelay;
    private Duration repetitiveDelay;

    /* internal fields */
    private long count = 0;
    private final Timer timer = new Timer();
    private volatile LocalDateTime nextExecution;
    private volatile State state = State.STOPPED;
    private final Object executionLock = new Object();


    /**
     * @param task the task to be executed by this timer.
     */
    protected TimedTask(final Consumer<TimedTask> task, final AbstractTimedTaskExecutor exec) {
        this.task = Objects.requireNonNull(task);
        this.executor = Objects.requireNonNull(exec);
    }

    /**
     * start the timer thread.
     */
    public synchronized boolean start() {
        while (getState() == State.SHUTDOWN) {
            try {
                wait();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        if (getState() == State.STOPPED) {
            setState(State.RUNNING);
            setNextExecutionTime(LocalDateTime.now().plus(this.initialDelay));
            if ((this.name == null) || this.name.isBlank()) {
                this.executor.run(this.timer::runTimer);
            }
            else {
                String timerName = "[" + this.name + "]Timer";
                this.executor.run(this.timer::runTimer, timerName);
            }
            return true;
        }

        return false; // task is still running --> cannot be started again.
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
     * @param name optional name for tasks
     * @return false, if called in RUNNING state.
     */
    protected boolean setName(final String name) {
        if (!isRunning()) {
            this.name = name;
            return true;
        }
        return false;
    }

    /**
     * @return the state
     */
    protected State getState() {
        return this.state;
    }

    /**
     * set a new state.
     */
    protected void setState(final State state) {
        this.state = state;
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
    protected LocalDateTime getNextExecution() {
        return this.nextExecution;
    }

    /**
     * set the new next execution time.
     */
    protected void setNextExecutionTime(final LocalDateTime time) {
        synchronized (this.executionLock) {
            this.nextExecution = time;
            this.executionLock.notifyAll();
        }
    }

    /**
     * @param delay the initial delay to set
     * @return false, if called in RUNNING state.
     */
    protected boolean setInitialDelay(final Duration delay) {
        if (!isRunning()) {
            this.initialDelay = delay;
            return true;
        }
        return false;
    }

    /**
     * @param repeatDelay the repetitive delay to set
     * @return false, if called in RUNNING state.
     */
    protected boolean setRepetitiveDelay(final Duration repeatDelay) {
        if (!isRunning()) {
            this.repetitiveDelay = repeatDelay;
            return true;
        }
        return false;
    }

    /**
     * @param periodDelay the periodic delay to set
     * @return false, if called in RUNNING state.
     */
    protected boolean setPeriodicDelay(final Duration periodDelay) {
        if (!isRunning()) {
            this.periodicDelay = periodDelay;
            return true;
        }
        return false;
    }

    /**
     * encapsulate timer thread code
     */
    private class Timer {

        private volatile Thread timerThread;

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

                synchronized (TimedTask.this) {
                    setState(State.STOPPED);
                    // notify potential waiting restart.
                    TimedTask.this.notifyAll();
                }
            }
        }

        private void loopTimer() {
            try {
                LocalDateTime next;
                while (isAlive() && ((next = getNextExecution()) != null)) {
                    if (next.compareTo(LocalDateTime.now()) <= 0) {
                        calculatePeriodicExecutionTime(next);
                        executeTask();
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

        private void executeTask() {
            Runnable task = () -> {
                try {
                    TimedTask.this.task.accept(TimedTask.this);
                }
                catch (final Exception e) {
                    Thread current = Thread.currentThread();
                    current.getUncaughtExceptionHandler().uncaughtException(current, e);
                }
                finally {
                    calculateRepetitiveExecutionTime();
                }
            };

            if ((TimedTask.this.name == null) || TimedTask.this.name.isBlank()) {
                TimedTask.this.executor.run(task);
            }
            else {
                String taskName = "[" + TimedTask.this.name + "]Task#" + (++TimedTask.this.count);
                TimedTask.this.executor.run(task, taskName);
            }

        }

        /**
         * only with periodic scenario: calculate the next execution time.
         */
        private void calculatePeriodicExecutionTime(final LocalDateTime currentExecutionTime) {
            if (TimedTask.this.periodicDelay != null) {
                setNextExecutionTime(currentExecutionTime.plus(TimedTask.this.periodicDelay));
            }
            else {
                // set next execution to null temporarily.
                // once the task finishes it will set the next execution with the repetitive
                // delay.
                setNextExecutionTime(null);
            }
        }

        /**
         * only for repetitive scenario: set the next execution time.
         */
        private void calculateRepetitiveExecutionTime() {
            if (TimedTask.this.repetitiveDelay != null) {
                setNextExecutionTime(LocalDateTime.now().plus(TimedTask.this.repetitiveDelay));
            }
            else if (TimedTask.this.periodicDelay == null) {
                // SINGLE TASK EXECUTION SCENARIO
                stop(); // stop TimedTask!
            }
        }

        private void waitTillNextExecution() throws InterruptedException {
            if (getNextExecution() == null) {
                // REPETITIVE DELAY SCENARIO: wait till next execution time is set by the task
                // thread.
                synchronized (TimedTask.this.executionLock) {
                    while (isAlive() && (getNextExecution() == null)) {
                        TimedTask.this.executionLock.wait();
                    }
                }
            }
            else {
                Duration duration = Duration.between(LocalDateTime.now(), getNextExecution());
                if (!duration.isNegative()) {
                    Thread.sleep(duration);
                }
                // timer thread sleeps
                // task thread terminates once finished
            }
        }

        private void interruptTimerThread() {
            if (this.timerThread != null) {
                this.timerThread.interrupt();
            }
        }

    }
}
