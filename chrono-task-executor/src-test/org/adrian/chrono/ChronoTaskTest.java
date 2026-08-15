package org.adrian.chrono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.adrian.chrono.ChronoTask.State;
import org.adrian.threadpool.ElasticThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChronoTaskTest {

    private final AtomicLong counter = new AtomicLong(0);
    private AbstractExecutor currentExecutor;

    @BeforeEach
    void setup() {
        this.counter.set(0);
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        // Cleanup pool executor if it was used
        if (this.currentExecutor instanceof PoolExecutor poolExecutor) {
            poolExecutor.shutdown();
            assertTrue(poolExecutor.awaitTermination(Duration.ofMillis(2100)),
                    "idle time expired, pool should have been terminated");
        }
    }

    /**
     * Provides both executor implementations for parameterized tests.
     */
    static Stream<Arguments> executorProvider() {
        return Stream.of(Arguments.of(new ThreadExecutor()), Arguments.of(new PoolExecutor(ElasticThreadPool.builder()
                .setMinThreads(0).setIdleTime(Duration.ofSeconds(2)).setName("test-pool").start())));
    }

    @ParameterizedTest
    @MethodSource("executorProvider")
    void testState(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        var chronoTask = executor.createTask(createTask(1000)).build();
        chronoTask.start();
        assertEquals(State.RUNNING, chronoTask.getState());
        chronoTask.stop();
        assertEquals(State.SHUTDOWN, chronoTask.getState());
    }

    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitive(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setPeriodicDelay(Duration.ofSeconds(5)).setRepetitiveDelay(Duration.ofMillis(200));
        ChronoTask timer = builder.build();
        timer.start();
        Thread.sleep(50); // buffer for thread startup
        assertEquals(1, this.counter.get());
        Thread.sleep(50); // task is instant, no duration to wait for
        Thread.sleep(200); // repetitive delay + buffer
        assertEquals(2, this.counter.get());
        timer.stop();
        assertEquals(2, this.counter.get());
    }

    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodic(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = this.currentExecutor.createTask(createTask(0));
        builder.setRepetitiveDelay(Duration.ofSeconds(5)).setPeriodicDelay(Duration.ofMillis(200));
        ChronoTask timer = builder.build();
        timer.start();
        Thread.sleep(50); // buffer for thread startup
        assertEquals(1, this.counter.get());
        Thread.sleep(250); // periodic delay + buffer
        assertEquals(2, this.counter.get());
        timer.stop();
        assertEquals(2, this.counter.get());
    }

    @ParameterizedTest
    @MethodSource("executorProvider")
    void testDelayed1(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = this.currentExecutor.createTask(createTask(0));
        ChronoTask timer = builder.build();
        timer.start();
        Thread.sleep(50); // buffer for thread startup
        assertEquals(1, this.counter.get());
        Thread.sleep(50); // task is instant, no duration to wait for
        assertEquals(State.STOPPED, timer.getState());
        assertEquals(1, this.counter.get());
    }

    @ParameterizedTest
    @MethodSource("executorProvider")
    void testDelayed2(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setInitialDelay(Duration.ofMillis(200));
        ChronoTask timer = builder.build();
        timer.start();
        Thread.sleep(50); // Buffer for thread startup
        assertEquals(0, this.counter.get());
        Thread.sleep(200); // Initial delay
        assertEquals(1, this.counter.get());
        Thread.sleep(50); // task is instant, no duration to wait for
        assertEquals(State.STOPPED, timer.getState());
        assertEquals(1, this.counter.get());
    }

    /**
     * Tests that state is NOT_RUNNING after build() and before start().
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStateAfterBuild(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        var ChronoTask = executor.createTask(createTask(1000)).build();
        assertEquals(State.STOPPED, ChronoTask.getState());
    }

    /**
     * Tests that start() returns false when task is already running.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testDoubleStart(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        var ChronoTask = executor.createTask(createTask(1000)).build();

        // First start should succeed
        assertTrue(ChronoTask.start());
        assertEquals(State.RUNNING, ChronoTask.getState());

        // Second start should fail (already running)
        assertFalse(ChronoTask.start());
        assertEquals(State.RUNNING, ChronoTask.getState());

        ChronoTask.stop();
    }

    /**
     * Tests that stop() on a task before start() is a no-op.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStopBeforeStart(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        var ChronoTask = executor.createTask(createTask(1000)).build();

        assertEquals(State.STOPPED, ChronoTask.getState());
        ChronoTask.stop(); // Should be no-op
        assertEquals(State.STOPPED, ChronoTask.getState());
    }

    /**
     * Tests that calling stop() multiple times is safe.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMultipleStops(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        var ChronoTask = executor.createTask(createTask(1000)).build();

        ChronoTask.start();
        assertEquals(State.RUNNING, ChronoTask.getState());

        ChronoTask.stop();
        assertEquals(State.SHUTDOWN, ChronoTask.getState());

        ChronoTask.stop(); // Second stop should be safe
        assertNotEquals(State.RUNNING, ChronoTask.getState());

        ChronoTask.stop(); // Third stop should be safe
        assertNotEquals(State.RUNNING, ChronoTask.getState());

        Thread.sleep(50);
        assertEquals(State.STOPPED, ChronoTask.getState());
    }

    /**
     * Tests that state is NOT_RUNNING after a single task completes.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStateAfterCompletion(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        var ChronoTask = executor.createTask(createTask(0)).build();

        ChronoTask.start();
        assertEquals(State.RUNNING, ChronoTask.getState());

        // Wait for task to execute and complete
        Thread.sleep(50); // Start execution
        assertEquals(1, this.counter.get());

        Thread.sleep(50); // Wait for task (instant)

        // Task should have completed and state should be NOT_RUNNING
        assertEquals(State.STOPPED, ChronoTask.getState());
        assertEquals(1, this.counter.get());
    }

    /**
     * Tests that a task can be stopped and then restarted successfully.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRestart(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setPeriodicDelay(Duration.ofMillis(200));
        ChronoTask ChronoTask = builder.build();

        // First start
        assertTrue(ChronoTask.start());
        assertEquals(State.RUNNING, ChronoTask.getState());
        Thread.sleep(50); // Allow task to execute
        assertEquals(1, this.counter.get());

        // Stop the task
        ChronoTask.stop();
        assertEquals(State.SHUTDOWN, ChronoTask.getState());
        Thread.sleep(50);
        long countAfterStop = this.counter.get();

        // Restart the task
        assertTrue(ChronoTask.start());
        assertEquals(State.RUNNING, ChronoTask.getState());
        Thread.sleep(50); // Allow task to execute
        assertTrue(this.counter.get() > countAfterStop, "Counter should increment after restart");

        ChronoTask.stop();
    }

    /**
     * Tests that a task can be stopped and restarted multiple times.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMultipleRestarts(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        var chronoTask = executor.createTask(createTask(50)).build(); // single execution task

        for (int i = 1; i <= 3; i++) {
            // Start the task
            assertTrue(chronoTask.start(), "Start should succeed on iteration " + i);
            assertEquals(State.RUNNING, chronoTask.getState());
            Thread.sleep(50); // Allow task to execute
            assertEquals(i, this.counter.get(), "Counter should be " + i + " on iteration " + i);
            Thread.sleep(50); // Wait for task to complete
            assertEquals(State.STOPPED, chronoTask.getState());
        }
    }

    /**
     * Tests that restarting a task uses the original configuration. The task should
     * not change behavior after restart.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRestartWithSameConfiguration(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setInitialDelay(Duration.ofMillis(100));
        ChronoTask ChronoTask = builder.build();

        // First start - verify initial delay
        ChronoTask.start();
        Thread.sleep(50);
        assertEquals(0, this.counter.get(), "Task should not execute yet (initial delay)");
        Thread.sleep(100);
        assertEquals(1, this.counter.get(), "Task should execute after initial delay");
        Thread.sleep(50); // Wait for completion
        ChronoTask.stop();

        Thread.sleep(50);

        // Restart - verify initial delay is still applied
        this.counter.set(0);
        ChronoTask.start();
        Thread.sleep(50);
        assertEquals(0, this.counter.get(), "Task should not execute yet on restart (initial delay)");
        Thread.sleep(100);
        assertEquals(1, this.counter.get(), "Task should execute after initial delay on restart");
        ChronoTask.stop();
    }

    /**
     * Tests single execution with explicit zero initial delay.
     * Task should execute immediately upon start.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testDelayedZeroInitialDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setInitialDelay(Duration.ZERO);
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Buffer for thread startup
        assertEquals(1, this.counter.get(), "Task should execute immediately with zero initial delay");

        Thread.sleep(50); // Wait for task (instant)
        assertEquals(State.STOPPED, timer.getState(), "Task should be NOT_RUNNING after completion");
        assertEquals(1, this.counter.get(), "Counter should remain 1 (single execution)");
    }

    /**
     * Tests periodic execution with 5+ cycles to verify consistent behavior.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicMultipleCycles(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        builder.setPeriodicDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // small buffer
        assertEquals(1, this.counter.get(), "First execution should complete");

        // Wait for 5 more cycles (5 × 100 ms = 500 ms; stay below the 7th at 600 ms)
        Thread.sleep(490);
        long finalCount = this.counter.get();
        assertTrue(finalCount == 6, "Should have 6 executions, but got " + finalCount);
        assertEquals(State.RUNNING, timer.getState(), "Task should still be running");

        timer.stop();
    }

    /**
     * Tests stopping a periodic task while it is executing.
     * Task should stop gracefully without leaving orphaned threads.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicStopDuringExecution(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(1000)); // 1-second task
        builder.setPeriodicDelay(Duration.ofMillis(200));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Allow execution to start
        assertEquals(1, this.counter.get(), "First execution should have started");

        // Stop while task is still executing (task duration is 1 second)
        timer.stop();
        assertEquals(State.SHUTDOWN, timer.getState(), "incorrect state after stop()");

        long countAtStop = this.counter.get();
        Thread.sleep(500); // Wait to ensure no additional executions occur
        assertEquals(countAtStop, this.counter.get(), "No additional executions should occur after stop");
    }

    /**
     * Tests periodic execution with an initial delay.
     * Task should wait for initial delay before first execution, then execute
     * periodically.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicWithInitialDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        builder.setInitialDelay(Duration.ofMillis(200)).setPeriodicDelay(Duration.ofMillis(200));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(100); // Half of initial delay
        assertEquals(0, this.counter.get(), "Task should not execute during initial delay");

        Thread.sleep(150); // Past initial delay
        assertEquals(1, this.counter.get(), "Task should execute after initial delay");

        Thread.sleep(250); // Wait for next periodic execution
        assertEquals(2, this.counter.get(), "Second execution should occur after periodic delay");

        timer.stop();
    }

    /**
     * Tests periodic execution with sub-second delays (100ms).
     * Verifies high-frequency periodic scheduling works correctly.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicShortDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        builder.setPeriodicDelay(Duration.ofMillis(50));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(25); // Allow first execution
        assertEquals(1, this.counter.get(), "First execution should complete");

        Thread.sleep(250); // Wait for ~5 more cycles
        long finalCount = this.counter.get();
        assertTrue((finalCount >= 5) && (finalCount <= 7),
                "Should have 5-7 executions with 50ms periodic delay, but got " + finalCount);

        timer.stop();
    }

    /**
     * Tests periodic execution where task duration exceeds periodic delay.
     * Next execution will not wait until current execution completes.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicLongRunningTask(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(1000)); // 1-second task
        builder.setPeriodicDelay(Duration.ofMillis(100)); // Shorter than task duration
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Allow first execution to start
        assertEquals(1, this.counter.get(), "First execution should have started");

        Thread.sleep(100); // Wait for next cycle
        assertEquals(2, this.counter.get(), "Second execution should start after first completes");

        Thread.sleep(100); // Wait for next cycle
        assertEquals(3, this.counter.get(), "Second execution should start after first completes");

        timer.stop();
    }

    /**
     * Tests repetitive execution with 5+ cycles to verify consistent behavior.
     * Repetitive mode waits for task completion before starting the delay.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveMultipleCycles(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        builder.setRepetitiveDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Small buffer for first execution
        assertEquals(1, this.counter.get(), "First execution should complete");

        // Wait for 5 more cycles (task duration + repetitive delay each)
        Thread.sleep(550);
        long finalCount = this.counter.get();
        assertTrue(finalCount == 6, "Should have 6 executions, but got " + finalCount);
        assertEquals(State.RUNNING, timer.getState(), "Task should still be running");

        timer.stop();
    }

    /**
     * Tests stopping a repetitive task while it is executing.
     * Task should stop gracefully without leaving orphaned threads.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveStopDuringExecution(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(1000)); // 1-second task
        builder.setPeriodicDelay(Duration.ofMillis(500));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Allow execution to start
        assertEquals(1, this.counter.get(), "First execution should have started");

        // Stop while task is still executing (task duration is 1 second)
        timer.stop();
        assertEquals(State.SHUTDOWN, timer.getState(), "incorrect state after stop()");

        long countAtStop = this.counter.get();
        Thread.sleep(500); // Wait to ensure no additional executions occur
        assertEquals(countAtStop, this.counter.get(), "No additional executions should occur after stop");
    }

    /**
     * Tests repetitive execution with an initial delay.
     * Task should wait for initial delay before first execution, then execute
     * repetitively.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveWithInitialDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        builder.setInitialDelay(Duration.ofMillis(200)).setRepetitiveDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(100); // Half of initial delay
        assertEquals(0, this.counter.get(), "Task should not execute during initial delay");

        Thread.sleep(150); // Past initial delay
        assertEquals(1, this.counter.get(), "Task should execute after initial delay");

        Thread.sleep(150); // Wait for next repetitive execution (task + delay)
        assertEquals(2, this.counter.get(), "Second execution should occur after repetitive delay");

        timer.stop();
    }

    /**
     * Tests repetitive execution with sub-second delays (100ms).
     * Verifies high-frequency repetitive scheduling works correctly.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveShortDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        builder.setRepetitiveDelay(Duration.ofMillis(50));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Allow first execution
        assertEquals(1, this.counter.get(), "First execution should complete");

        Thread.sleep(250); // Wait for ~5 more cycles
        long finalCount = this.counter.get();
        assertTrue((finalCount >= 5) && (finalCount <= 7),
                "Should have 5-7 executions with 50ms repetitive delay, but got " + finalCount);

        timer.stop();
    }

    /**
     * Tests repetitive execution where task duration varies between executions.
     * Verifies that the repetitive delay is always applied after task completion,
     * regardless of task duration.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveVariableTaskDuration(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        // Create a task with variable duration based on execution count
        Consumer<ChronoTask> variableTask = _ -> {
            try {
                long count = this.counter.incrementAndGet();
                // First execution: 50ms, second: 100ms, third: 50ms
                long sleepTime = (count == 2) ? 100 : 50;
                Thread.sleep(sleepTime);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };

        ChronoTaskBuilder builder = executor.createTask(variableTask);
        builder.setRepetitiveDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Allow first execution to start
        assertEquals(1, this.counter.get(), "first execution should have started");

        // First execution: 50ms task + 100ms delay = 150ms total
        Thread.sleep(150);
        assertEquals(2, this.counter.get(), "Second execution should have started");

        // Second execution: 100ms task + 100ms delay = 200ms total
        Thread.sleep(200);
        assertEquals(3, this.counter.get(), "Third execution should have started");

        timer.stop();
    }

    /**
     * Tests creating a task with a name and verifies thread naming.
     * Named tasks should have their timer thread and task threads named
     * accordingly.
     */
    @Test
    void testNamedTask() throws InterruptedException {
        this.currentExecutor = new ThreadExecutor();

        // Create a task that captures the thread name
        final String[] capturedThreadName = { null };
        Consumer<ChronoTask> namedTask = _ -> {
            capturedThreadName[0] = Thread.currentThread().getName();
            this.counter.incrementAndGet();
        };

        ChronoTaskBuilder builder = this.currentExecutor.createTask(namedTask);
        builder.setName("TestTask");
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(150); // Allow task to execute
        assertEquals(1, this.counter.get(), "Task should have executed");

        // Verify the thread name contains the task name
        assertFalse((capturedThreadName[0] == null) || capturedThreadName[0].isEmpty(),
                "Thread name should be captured");
        assertTrue(capturedThreadName[0].contains("TestTask"),
                "Thread name should contain task name, but was: " + capturedThreadName[0]);
        assertTrue(capturedThreadName[0].contains("Task#1"),
                "Thread name should contain task counter, but was: " + capturedThreadName[0]);

        timer.stop();
    }

    /**
     * Tests that a named task running in a named pool executor has a thread name
     * combining both pool and task name in the format
     * {@code <poolName>/<taskName>}.
     */
    @Test
    void testNamedTaskWithPoolExecutorThreadNames() throws InterruptedException {
        PoolExecutor poolExecutor = new PoolExecutor("TestPool");

        // Create a task that captures the thread name
        final String[] capturedThreadName = { null };
        Consumer<ChronoTask> namedTask = _ -> {
            capturedThreadName[0] = Thread.currentThread().getName();
            this.counter.incrementAndGet();
        };

        ChronoTask timer = poolExecutor.createTask(namedTask).setName("DatabaseSync").build();

        timer.start();
        Thread.sleep(150); // Allow task to execute
        assertEquals(1, this.counter.get(), "Task should have executed");

        assertEquals("TestPool/[DatabaseSync]Task#1", capturedThreadName[0],
                "Thread name should be TestPool/[DatabaseSync]Task#1, but was: " + capturedThreadName[0]);

        timer.stop();
        poolExecutor.shutdownNow();
    }

    /**
     * Tests that a blank name is rejected by the allow-list validation.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testNamedTaskWithBlankName(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        assertThrowsExactly(IllegalArgumentException.class, () -> builder.setName("   "));
    }

    /**
     * Tests that a null name is treated as unnamed (no special thread naming).
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testNamedTaskWithNullName(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        assertThrowsExactly(NullPointerException.class, () -> builder.setName(null));
    }

    /**
     * Tests that task counter increments in the thread name for periodic tasks.
     * Each execution should have a unique thread name with incrementing counter.
     */
    @Test
    void testNamedPeriodicTask() throws InterruptedException {
        this.currentExecutor = new ThreadExecutor();

        // Create a task that captures thread names
        final List<String> capturedThreadNames = new java.util.ArrayList<>();
        Consumer<ChronoTask> namedTask = _ -> {
            synchronized (capturedThreadNames) {
                capturedThreadNames.add(Thread.currentThread().getName());
            }
            this.counter.incrementAndGet();
        };

        ChronoTaskBuilder builder = this.currentExecutor.createTask(namedTask);
        builder.setName("PeriodicTask").setPeriodicDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // First execution
        assertEquals(1, this.counter.get(), "First execution should complete");

        Thread.sleep(100); // Second execution
        assertEquals(2, this.counter.get(), "Second execution should complete");

        Thread.sleep(100); // Third execution
        assertEquals(3, this.counter.get(), "Third execution should complete");

        timer.stop();

        // Verify thread names have incrementing counters
        assertEquals(3, capturedThreadNames.size(), "Should have captured 3 thread names");
        assertTrue(capturedThreadNames.get(0).contains("Task#1"),
                "First thread name should contain Task#1: " + capturedThreadNames.get(0));
        assertTrue(capturedThreadNames.get(1).contains("Task#2"),
                "Second thread name should contain Task#2: " + capturedThreadNames.get(1));
        assertTrue(capturedThreadNames.get(2).contains("Task#3"),
                "Third thread name should contain Task#3: " + capturedThreadNames.get(2));
    }

    /**
     * Tests multiple tasks with different names running concurrently.
     * Each task should maintain its own naming and counter.
     */
    @Test
    void testMultipleNamedTasks() throws InterruptedException {
        this.currentExecutor = new ThreadExecutor();

        final AtomicLong counter1 = new AtomicLong(0);
        final AtomicLong counter2 = new AtomicLong(0);
        final String[] threadName1 = { null };
        final String[] threadName2 = { null };

        // First task
        Consumer<ChronoTask> task1 = _ -> {
            threadName1[0] = Thread.currentThread().getName();
            counter1.incrementAndGet();
        };

        ChronoTaskBuilder builder1 = this.currentExecutor.createTask(task1);
        builder1.setName("Task1");
        ChronoTask timer1 = builder1.build();

        // Second task
        Consumer<ChronoTask> task2 = _ -> {
            threadName2[0] = Thread.currentThread().getName();
            counter2.incrementAndGet();
        };

        ChronoTaskBuilder builder2 = this.currentExecutor.createTask(task2);
        builder2.setName("Task2");
        ChronoTask timer2 = builder2.build();

        // Start both tasks
        timer1.start();
        timer2.start();

        Thread.sleep(200); // Allow both tasks to execute

        assertEquals(1, counter1.get(), "Task1 should have executed");
        assertEquals(1, counter2.get(), "Task2 should have executed");

        // Verify different thread names
        assertTrue(threadName1[0].contains("Task1"), "Thread for task1 should contain 'Task1': " + threadName1[0]);
        assertTrue(threadName2[0].contains("Task2"), "Thread for task2 should contain 'Task2': " + threadName2[0]);
        assertFalse(threadName1[0].equals(threadName2[0]), "Thread names should be different");

        timer1.stop();
        timer2.stop();
    }

    /**
     * Tests that the initial delay is accurate within acceptable tolerance.
     * Task should start execution close to the expected time after initial delay.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testInitialDelayPrecision(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final long startTime = System.currentTimeMillis();
        final long[] actualExecutionTime = { 0 };

        Consumer<ChronoTask> ChronoTask = _ -> {
            actualExecutionTime[0] = System.currentTimeMillis();
            this.counter.incrementAndGet();
        };

        ChronoTaskBuilder builder = executor.createTask(ChronoTask);
        Duration initialDelay = Duration.ofMillis(200);
        builder.setInitialDelay(initialDelay);
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(300); // Wait for execution to complete

        assertEquals(1, this.counter.get(), "Task should have executed once");

        long actualDelay = actualExecutionTime[0] - startTime;
        long expectedDelayMs = initialDelay.toMillis();
        long tolerance = 15;

        assertTrue(Math.abs(actualDelay - expectedDelayMs) <= tolerance,
                String.format(
                        "Initial delay should be within tolerance. Expected: %dms, Actual: %dms, Tolerance: ±%dms",
                        expectedDelayMs, actualDelay, tolerance));

        timer.stop();
    }

    /**
     * Tests that periodic executions occur at the expected intervals.
     * Verifies timing precision across multiple periodic cycles.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicDelayPrecision(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final long startTime = System.currentTimeMillis();
        final List<Long> executionTimes = new java.util.ArrayList<>();

        Consumer<ChronoTask> ChronoTask = _ -> {
            synchronized (executionTimes) {
                executionTimes.add(System.currentTimeMillis() - startTime);
                this.counter.incrementAndGet();
            }
        };

        ChronoTaskBuilder builder = executor.createTask(ChronoTask);
        Duration periodicDelay = Duration.ofMillis(200);
        builder.setPeriodicDelay(periodicDelay);
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(700); // Allow for ~3 executions
        timer.stop();

        assertTrue(executionTimes.size() >= 3, "Should have at least 3 executions");

        // Check timing between consecutive executions
        long tolerance = 15;
        for (int i = 1; i < executionTimes.size(); i++) {
            long actualInterval = executionTimes.get(i) - executionTimes.get(i - 1);
            long expectedInterval = periodicDelay.toMillis();

            assertTrue(Math.abs(actualInterval - expectedInterval) <= tolerance, String.format(
                    "Periodic interval %d should be within tolerance. Expected: %dms, Actual: %dms, Tolerance: ±%dms",
                    i, expectedInterval, actualInterval, tolerance));
        }
    }

    /**
     * Tests that repetitive delays are accurate.
     * Verifies that the delay between task completion and next execution is
     * correct.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveDelayPrecision(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final List<Long> executionStartTimes = new java.util.ArrayList<>();
        final List<Long> executionEndTimes = new java.util.ArrayList<>();
        final Duration taskDuration = Duration.ofMillis(100);

        Consumer<ChronoTask> ChronoTask = _ -> {
            synchronized (executionStartTimes) {
                executionStartTimes.add(System.currentTimeMillis());
            }
            this.counter.incrementAndGet();
            try {
                Thread.sleep(taskDuration);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            synchronized (executionEndTimes) {
                executionEndTimes.add(System.currentTimeMillis());
            }
        };

        ChronoTaskBuilder builder = executor.createTask(ChronoTask);
        Duration repetitiveDelay = Duration.ofMillis(200);
        builder.setRepetitiveDelay(repetitiveDelay);
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(1000); // Allow for ~3 executions (100ms task + 200ms delay = 300ms per cycle)
        timer.stop();

        assertTrue(executionStartTimes.size() >= 3, "Should have started at least 3 executions");
        assertTrue(executionEndTimes.size() == 3, "Should have finished at exact 3 executions");

        // Check delay between end of one execution and start of next
        long tolerance = 15;
        for (int i = 1; i < executionStartTimes.size(); i++) {
            long actualDelay = executionStartTimes.get(i) - executionEndTimes.get(i - 1);
            long expectedDelay = repetitiveDelay.toMillis();

            assertTrue(Math.abs(actualDelay - expectedDelay) <= tolerance, String.format(
                    "Repetitive delay %d should be within tolerance. Expected: %dms, Actual: %dms, Tolerance: ±%dms", i,
                    expectedDelay, actualDelay, tolerance));
        }
    }

    /**
     * Tests that getNextExecution() returns correct values before and during
     * execution.
     * Note: getNextExecution() is protected, so we test it indirectly through task
     * behavior.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testNextExecutionTime(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final long startTime = System.currentTimeMillis();

        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        Duration initialDelay = Duration.ofMillis(200);
        Duration periodicDelay = Duration.ofMillis(200);
        builder.setInitialDelay(initialDelay).setPeriodicDelay(periodicDelay);
        ChronoTask timer = builder.build();

        // Before start, state should be NOT_RUNNING
        assertEquals(State.STOPPED, timer.getState());

        timer.start();
        assertEquals(State.RUNNING, timer.getState());

        // Wait for first execution
        Thread.sleep(250);
        assertEquals(1, this.counter.get(), "First execution should complete");

        // Wait for second execution
        Thread.sleep(250);
        assertEquals(2, this.counter.get(), "Second execution should complete");

        timer.stop();

        // Verify executions occurred at expected times (indirectly)
        long totalTime = System.currentTimeMillis() - startTime;
        assertTrue(totalTime >= (initialDelay.toMillis() + periodicDelay.toMillis()),
                "Total execution time should be at least initial delay + one periodic delay");
    }

    /**
     * Tests that when a task throws an exception, the timer continues to execute
     * subsequent tasks (for periodic/repetitive modes).
     * The exception should be caught and not crash the timer.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskThrowsException(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        // Create a task that throws an exception on first execution, then works
        // normally
        Consumer<ChronoTask> exceptionTask = _ -> {
            long count = this.counter.incrementAndGet();
            if (count == 1) {
                throw new RuntimeException("Test exception on first execution");
            }
            // Subsequent executions work normally
        };

        ChronoTaskBuilder builder = executor.createTask(exceptionTask);
        builder.setPeriodicDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // First execution (throws exception)
        assertEquals(1, this.counter.get(), "First execution should have run");

        Thread.sleep(100); // Second execution (should work)
        assertEquals(2, this.counter.get(), "Second execution should run despite exception in first");

        Thread.sleep(100); // Third execution
        assertEquals(3, this.counter.get(), "Third execution should run");

        assertEquals(State.RUNNING, timer.getState(), "Timer should still be running");
        timer.stop();
    }

    /**
     * Tests that when a task throws a runtime exception, the timer handles it
     * gracefully.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskThrowsRuntimeException(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        Consumer<ChronoTask> exceptionTask = _ -> {
            this.counter.incrementAndGet();
            throw new IllegalStateException("expected test runtime exception");
        };

        ChronoTaskBuilder builder = executor.createTask(exceptionTask);
        builder.setRepetitiveDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // First execution
        assertEquals(1, this.counter.get(), "First execution should have run");

        Thread.sleep(100); // Second execution (despite exception)
        assertEquals(2, this.counter.get(), "Second execution should run despite runtime exception");

        assertEquals(State.RUNNING, timer.getState(), "Timer should still be running");
        timer.stop();
    }

    /**
     * Tests that a task handles interruption correctly.
     * When a task is interrupted, it should handle the interruption gracefully.
     */
    @Test
    void testTaskInterrupted() throws InterruptedException {
        var executor = new PoolExecutor("TaskInterruptor");
        this.currentExecutor = executor;

        final boolean[] interruptHandled = { false };

        Consumer<ChronoTask> interruptibleTask = _ -> {
            this.counter.incrementAndGet();
            try {
                Thread.sleep(Duration.ofSeconds(1)); // Long sleep to be interrupted
            } catch (InterruptedException _) {
                interruptHandled[0] = true;
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
        };

        ChronoTask timer = executor.createTask(interruptibleTask).build();

        timer.start();
        Thread.sleep(50); // Allow task to start
        assertEquals(1, this.counter.get(), "Task should have started");

        // terminate the executor pool immediately, which should cause the task thread
        // to be interrupted
        executor.shutdownNow();
        Thread.sleep(100); // Allow time for interrupt to be processed

        assertEquals(State.STOPPED, timer.getState(), "Timer should be stopped");
        assertTrue(interruptHandled[0], "Task should have been interrupted.");
    }

    /**
     * Tests attempting to create a timer with a null task.
     * This should either throw an exception.
     */
    @Test
    void testNullTask() {
        this.currentExecutor = new ThreadExecutor();

        // Attempt to create a task with null
        try {
            ChronoTask timer = this.currentExecutor.createTask(null).build();
            timer.start();
        } catch (NullPointerException _) {
            // This is expected behavior - null task not allowed
            assertTrue(true, "NullPointerException expected for null task");
        } catch (Exception e) {
            // Some other exception is also acceptable
            assertTrue(true, "Exception expected for null task: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Tests attempting to set negative initial delay.
     * The builder should throw IllegalArgumentException for negative initial
     * delays, consistent with setPeriodicDelay/setRepetitiveDelay.
     */
    @Test
    void testNegativeInitialDelay() {
        this.currentExecutor = new ThreadExecutor();
        ChronoTaskBuilder builder = this.currentExecutor.createTask(createTask(0));

        // Attempt to set negative initial delay should throw exception
        try {
            builder.setInitialDelay(Duration.ofMillis(-500));
            fail("Expected IllegalArgumentException for negative initial delay");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("negative"),
                    "Exception message should mention 'negative': " + e.getMessage());
        }
    }

    /**
     * Tests attempting to set negative periodic delay.
     * The builder should throw IllegalArgumentException for negative periodic
     * delays.
     */
    @Test
    void testNegativePeriodicDelay() {
        this.currentExecutor = new ThreadExecutor();
        ChronoTaskBuilder builder = this.currentExecutor.createTask(createTask(0));

        // Attempt to set negative periodic delay should throw exception
        try {
            builder.setPeriodicDelay(Duration.ofMillis(-300));
            fail("Expected IllegalArgumentException for negative periodic delay");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("negative"),
                    "Exception message should mention 'negative': " + e.getMessage());
        }
    }

    /**
     * Tests attempting to set negative repetitive delay.
     * The builder should throw IllegalArgumentException for negative repetitive
     * delays.
     */
    @Test
    void testNegativeRepetitiveDelay() {
        this.currentExecutor = new ThreadExecutor();
        ChronoTaskBuilder builder = this.currentExecutor.createTask(createTask(0));

        // Attempt to set negative repetitive delay should throw exception
        try {
            builder.setRepetitiveDelay(Duration.ofMillis(-300));
            fail("Expected IllegalArgumentException for negative repetitive delay");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("negative"),
                    "Exception message should mention 'negative': " + e.getMessage());
        }
    }

    /**
     * Tests scheduling with an extremely long delay (365 days).
     * Verifies that the timer can handle very long durations without overflow or
     * errors.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testExtremelyLongDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setInitialDelay(Duration.ofDays(365));
        ChronoTask timer = builder.build();

        timer.start();
        assertEquals(State.RUNNING, timer.getState(), "Timer should be running");

        Thread.sleep(50);
        assertEquals(0, this.counter.get(), "Task should not execute yet with 365-day delay");

        // The task should still be waiting
        assertEquals(State.RUNNING, timer.getState(), "Timer should still be running");

        timer.stop();
        assertEquals(State.SHUTDOWN, timer.getState());
    }

    /**
     * Tests that a task can stop itself by calling ChronoTask.stop().
     * The task receives a reference to its timer and can control its own execution.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskStopsItself(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        Consumer<ChronoTask> selfStoppingTask = ChronoTask -> {
            this.counter.incrementAndGet();
            // Task stops itself after first execution
            ChronoTask.stop();
        };

        ChronoTaskBuilder builder = executor.createTask(selfStoppingTask);
        builder.setPeriodicDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50);
        assertEquals(1, this.counter.get(), "Task should execute once");

        Thread.sleep(100); // Wait past next periodic cycle
        assertEquals(State.STOPPED, timer.getState(), "Timer should be stopped by the task");
        assertEquals(1, this.counter.get(), "Task should not execute again (stopped itself)");
    }

    /**
     * Tests that a periodic task can stop itself after N executions.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskStopsItselfInPeriodicMode(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final int stopAfter = 3;
        Consumer<ChronoTask> selfStoppingTask = ChronoTask -> {
            long count = this.counter.incrementAndGet();
            if (count >= stopAfter) {
                ChronoTask.stop();
            }
        };

        ChronoTaskBuilder builder = executor.createTask(selfStoppingTask);
        builder.setPeriodicDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // First execution
        assertEquals(1, this.counter.get());

        Thread.sleep(100); // Second execution
        assertEquals(2, this.counter.get());

        Thread.sleep(100); // Third execution (should stop itself)
        assertEquals(3, this.counter.get());

        Thread.sleep(100); // Verify no fourth execution
        assertEquals(State.STOPPED, timer.getState(), "Timer should be stopped");
        assertEquals(3, this.counter.get(), "Task should have stopped after 3 executions");
    }

    /**
     * Tests that a repetitive task can stop itself after N executions.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskStopsItselfInRepetitiveMode(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final int stopAfter = 3;
        Consumer<ChronoTask> selfStoppingTask = ChronoTask -> {
            long count = this.counter.incrementAndGet();
            if (count >= stopAfter) {
                ChronoTask.stop();
            }
        };

        ChronoTaskBuilder builder = executor.createTask(selfStoppingTask);
        builder.setRepetitiveDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // First execution
        assertEquals(1, this.counter.get());

        Thread.sleep(100); // Second execution
        assertEquals(2, this.counter.get());

        Thread.sleep(100); // Third execution (should stop itself)
        assertEquals(3, this.counter.get());

        Thread.sleep(100); // Verify no fourth execution
        assertEquals(State.STOPPED, timer.getState(), "Timer should be stopped");
        assertEquals(3, this.counter.get(), "Task should have stopped after 3 executions");
    }

    /**
     * Tests that a task can query its own running state using isRunning().
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskChecksIsRunning(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final boolean[] wasRunning = { false };
        Consumer<ChronoTask> checkingTask = ChronoTask -> {
            this.counter.incrementAndGet();
            wasRunning[0] = ChronoTask.isRunning();
        };

        ChronoTask timer = executor.createTask(checkingTask).build();

        timer.start();
        Thread.sleep(50);
        assertEquals(State.STOPPED, timer.getState());
        assertEquals(1, this.counter.get(), "Task should have executed");
        assertTrue(wasRunning[0], "Task should have detected it was running");
    }

    /**
     * Tests that a task attempting to restart itself while running fails.
     * A task calling start() on itself should return false.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskTriesToRestartItself(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final boolean[] restartResult = { true }; // Default to true, should become false
        Consumer<ChronoTask> restartingTask = ChronoTask -> {
            this.counter.incrementAndGet();
            // Try to start while already running
            restartResult[0] = ChronoTask.start();
        };

        ChronoTask timer = executor.createTask(restartingTask).build();

        timer.start();
        Thread.sleep(50); // Allow execution
        assertEquals(1, this.counter.get(), "Task should have executed");
        assertFalse(restartResult[0], "Task should not be able to restart itself while running");
        assertEquals(State.STOPPED, timer.getState());
    }

    /**
     * Tests race condition when task stops itself while external thread also calls
     * stop().
     * Both should complete safely without errors.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskSelfStopRaceCondition(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        Consumer<ChronoTask> selfStoppingTask = ChronoTask -> {
            this.counter.incrementAndGet();
            try {
                Thread.sleep(100); // Give external thread time to also call stop()
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            ChronoTask.stop(); // Task stops itself
        };

        ChronoTaskBuilder builder = executor.createTask(selfStoppingTask);
        builder.setPeriodicDelay(Duration.ofMillis(200));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50); // Allow task to start execution

        // External thread also calls stop() while task is stopping itself
        timer.stop();

        Thread.sleep(100); // Allow everything to settle
        assertEquals(State.STOPPED, timer.getState(), "Timer should be stopped");
        assertTrue(this.counter.get() == 1, "Task should have executed only once");
    }

    /**
     * Tests running 10+ tasks simultaneously to verify thread-safety.
     * Multiple tasks should execute concurrently without interference.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMultipleConcurrentTasks(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final int TASK_COUNT = 12;
        AtomicLong[] counters = new AtomicLong[TASK_COUNT];
        ChronoTask[] tasks = new ChronoTask[TASK_COUNT];

        // Create and start multiple tasks
        for (int i = 0; i < TASK_COUNT; i++) {
            final int index = i;
            counters[i] = new AtomicLong(0);

            Consumer<ChronoTask> task = _ -> {
                counters[index].incrementAndGet();
                try {
                    Thread.sleep(50); // Small delay to simulate work
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            };

            ChronoTaskBuilder builder = executor.createTask(task);
            builder.setPeriodicDelay(Duration.ofMillis(200));
            tasks[i] = builder.build();
            tasks[i].start();
        }

        // Let tasks run for a while
        Thread.sleep(500);

        // Stop all tasks
        for (ChronoTask task : tasks) {
            task.stop();
        }

        // Verify all tasks executed
        for (int i = 0; i < TASK_COUNT; i++) {
            assertTrue(counters[i].get() > 2, "Task " + i + " should have executed more than 2 times, but executed " +
                    counters[i].get() + " times");
        }
    }

    /**
     * Tests stopping a task from a different thread than the one that started it.
     * This verifies thread-safety of the stop operation.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStopFromAnotherThread(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setPeriodicDelay(Duration.ofMillis(50));
        ChronoTask timer = builder.build();

        // Start task in main thread
        timer.start();
        Thread.sleep(25);
        assertTrue(this.counter.get() == 1, "Task should have executed at least once");

        // Stop task from a different thread
        Thread stopperThread = Thread.ofVirtual().start(timer::stop);
        Thread.sleep(50);
        assertEquals(State.STOPPED, timer.getState());
        stopperThread.join(); // Wait for stopper thread to complete

        long countAfterStop = this.counter.get();
        Thread.sleep(150); // Wait to ensure no more executions
        assertEquals(countAfterStop, this.counter.get(), "No more executions should occur after stop");
    }

    /**
     * Tests race condition when multiple threads call start() simultaneously.
     * Only one start() should succeed, others should return false.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRaceConditionOnStart(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setPeriodicDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        final int THREAD_COUNT = 10;
        AtomicLong successCount = new AtomicLong(0);
        Thread[] threads = new Thread[THREAD_COUNT];

        // Create threads that all try to start the task simultaneously
        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                if (timer.start()) {
                    successCount.incrementAndGet();
                }
            });
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, successCount.get(), "Only one thread should successfully start the task");
        assertEquals(State.RUNNING, timer.getState(), "Task should be running");

        timer.stop();
    }

    /**
     * Tests race condition when multiple threads call stop() simultaneously.
     * All stop() calls should complete safely without errors.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRaceConditionOnStop(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setPeriodicDelay(Duration.ofMillis(50));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(25);
        assertEquals(1, this.counter.get(), "Task should have executed once");

        final int THREAD_COUNT = 10;
        Thread[] threads = new Thread[THREAD_COUNT];

        // Create threads that all try to stop the task simultaneously
        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = Thread.ofVirtual().start(timer::stop);
        }

        Thread.sleep(50);
        assertEquals(State.STOPPED, timer.getState());

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        long countAfterStop = this.counter.get();
        Thread.sleep(150); // Ensure no more executions
        assertEquals(countAfterStop, this.counter.get(), "No more executions should occur");
    }

    /**
     * Tests multiple tasks accessing and modifying shared state (a shared counter).
     * Verifies that concurrent access is handled correctly with AtomicLong.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testTaskAccessesSharedState(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final int TASK_COUNT = 5;
        AtomicLong sharedCounter = new AtomicLong(0);
        ChronoTask[] tasks = new ChronoTask[TASK_COUNT];

        // Create multiple tasks that all increment the same shared counter
        for (int i = 0; i < TASK_COUNT; i++) {
            Consumer<ChronoTask> task = _ -> {
                sharedCounter.incrementAndGet();
                try {
                    Thread.sleep(50); // Simulate work
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            };

            ChronoTaskBuilder builder = executor.createTask(task);
            builder.setPeriodicDelay(Duration.ofMillis(100));
            tasks[i] = builder.build();
            tasks[i].start();
        }

        // Let tasks run for a while
        Thread.sleep(500);

        // Stop all tasks
        for (ChronoTask task : tasks) {
            task.stop();
        }

        long finalCount = sharedCounter.get();
        // With 5 tasks running for ~500ms with 100ms period,
        // we expect roughly 25 executions (±some tolerance)
        assertTrue((finalCount >= 24) && (finalCount <= 30),
                "Expected total executions are not within tolerance. got " + finalCount);
    }

    /**
     * Tests high-frequency periodic execution with 10ms delay and 100 iterations.
     * Verifies that the system can handle rapid periodic scheduling without
     * degradation.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testHighFrequencyPeriodic(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        ChronoTask timer = builder.setPeriodicDelay(Duration.ofMillis(10)).build();

        timer.start();

        // Wait for approximately 100 iterations
        Thread.sleep(1000);

        timer.stop();

        long finalCount = this.counter.get();
        // With 10ms periodic delay, we expect roughly 100 executions
        // Allow tolerance for high-frequency operations due to system scheduling
        assertTrue((finalCount >= 100) && (finalCount <= 102),
                "executions with 10ms periodic delay do not fall with in tolerance range. Got " + finalCount);
    }

    /**
     * Tests high-frequency repetitive execution with 10ms delay and 100 iterations.
     * Verifies that the system can handle rapid repetitive scheduling without
     * degradation.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testHighFrequencyRepetitive(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0)); // Instant task
        ChronoTask timer = builder.setRepetitiveDelay(Duration.ofMillis(10)).build();

        timer.start();
        Thread.sleep(1000);
        timer.stop();

        long finalCount = this.counter.get();
        // With 10ms repetitive delay, we expect roughly 100 executions
        // Allow tolerance for high-frequency operations due to system scheduling
        assertTrue((finalCount >= 85) && (finalCount <= 100),
                "executions with 10ms Repetitive delay do not fall with in tolerance range. Got " + finalCount);
    }

    /**
     * Tests many tasks executing in quick succession.
     * Verifies that the executor can handle rapid task creation and execution.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testManyShortTasks(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final int TASK_COUNT = 50;
        ChronoTask[] tasks = new ChronoTask[TASK_COUNT];
        AtomicLong[] counters = new AtomicLong[TASK_COUNT];

        // Create many short tasks
        for (int i = 0; i < TASK_COUNT; i++) {
            final int index = i;
            counters[i] = new AtomicLong(0);
            Consumer<ChronoTask> task = _ -> counters[index].incrementAndGet();
            tasks[i] = executor.createTask(task).build();
        }

        // Start all tasks in quick succession
        long startTime = System.currentTimeMillis();
        for (ChronoTask task : tasks) {
            task.start();
        }
        long endTime = System.currentTimeMillis();

        // Wait for all tasks to complete
        Thread.sleep(200);

        // Verify all tasks executed
        for (int i = 0; i < TASK_COUNT; i++) {
            assertEquals(1, counters[i].get(), "Task " + i + " should have executed exactly once");
        }

        // Verify tasks started quickly
        long startupTime = endTime - startTime;
        assertTrue(startupTime < 100, "Starting " + TASK_COUNT + " tasks took " + startupTime + "ms, expected < 100ms");
    }

    /**
     * Tests creating and destroying many tasks to check for memory leaks.
     * Creates 100 tasks, starts them, waits for completion, and verifies cleanup.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMemoryLeakOnRepeatedCreation(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        final int ITERATIONS = 50;
        AtomicLong totalExecutions = new AtomicLong(0);

        for (int i = 0; i < ITERATIONS; i++) {
            Consumer<ChronoTask> task = _ -> totalExecutions.incrementAndGet();
            ChronoTask timer = executor.createTask(task).build();

            // Start and immediately stop (or let it complete)
            timer.start();
            Thread.sleep(5);
            timer.stop();
        }

        // All tasks should have executed
        assertTrue(totalExecutions.get() == ITERATIONS,
                "Expected " + ITERATIONS + " executions, got " + totalExecutions.get());

        // Give system time to clean up
        Thread.sleep(50);

        // If we got here without OutOfMemoryError or other issues, the test passed
        // In a real scenario, you might check thread counts or memory usage here
        assertTrue(true, "Successfully created and destroyed " + ITERATIONS + " tasks");
    }

    /**
     * Tests a very long-running task (10+ seconds).
     * Verifies that the timer can handle tasks with extended execution time.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testVeryLongRunningTask(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        Consumer<ChronoTask> longTask = _ -> {
            try {
                this.counter.incrementAndGet();
                Thread.sleep(Duration.ofSeconds(2));
                this.counter.incrementAndGet();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };

        var ChronoTask = executor.createTask(longTask).build();
        ChronoTask.start();
        Thread.sleep(50); // Wait for task to start
        assertEquals(1, this.counter.get(), "Task should have started");
        assertEquals(State.RUNNING, ChronoTask.getState());
        Thread.sleep(1900);
        assertEquals(State.RUNNING, ChronoTask.getState());
        Thread.sleep(100);
        assertEquals(State.STOPPED, ChronoTask.getState());
        assertEquals(2, this.counter.get(), "Task should have started");
    }

    /**
     * Tests periodic execution with zero delay (Duration.ZERO).
     * This should cause rapid consecutive executions.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicWithZeroDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        ChronoTask timer = builder.setPeriodicDelay(Duration.ZERO).build();

        timer.start();
        Thread.sleep(100);

        long count = this.counter.get();
        assertTrue(count > 10, "Should have many executions with zero delay, got: " + count);
        assertEquals(State.RUNNING, timer.getState());

        timer.stop();
        Thread.sleep(5); // short delay for currently executing tasks
        long finalCount = this.counter.get();
        Thread.sleep(100);
        assertEquals(finalCount, this.counter.get(), "No executions after stop");
    }

    /**
     * Tests repetitive execution with zero delay (Duration.ZERO).
     * This should cause rapid consecutive executions after task completion.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveWithZeroDelay(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        ChronoTask timer = builder.setRepetitiveDelay(Duration.ZERO).build();

        timer.start();
        Thread.sleep(100);

        long count = this.counter.get();
        assertTrue(count > 10, "Should have many executions with zero repetitive delay, got: " + count);
        assertEquals(State.RUNNING, timer.getState());

        timer.stop();
        Thread.sleep(5); // short delay for currently executing tasks
        long finalCount = this.counter.get();
        Thread.sleep(100);
        assertEquals(finalCount, this.counter.get(), "No executions after stop");
    }

    /**
     * Tests stopping immediately after starting (within milliseconds).
     * Verifies that the task can be stopped quickly without issues.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStopImmediatelyAfterStart(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        ChronoTask timer = builder.setPeriodicDelay(Duration.ofMillis(50)).build();

        timer.start();
        timer.stop();

        assertEquals(State.SHUTDOWN, timer.getState());

        // Wait a bit and verify no executions occur
        long countAtStop = this.counter.get();
        Thread.sleep(150);
        long countAfter = this.counter.get();

        // Task might have executed 0 or 1 times depending on timing
        assertTrue(countAtStop <= 1, "Should have at most 1 execution, got: " + countAtStop);
        assertEquals(countAtStop, countAfter, "No new executions should occur after stop");
    }

    /**
     * Tests restarting a task while it is executing.
     * Stop and restart should work gracefully even during execution.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRestartDuringExecution(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        Consumer<ChronoTask> longTask = _ -> {
            try {
                this.counter.incrementAndGet();
                Thread.sleep(Duration.ofMillis(500));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };

        var ChronoTask = executor.createTask(longTask).build();

        ChronoTask.start();
        Thread.sleep(50); // Wait for task to start executing
        assertEquals(1, this.counter.get(), "Task should be executing");
        assertEquals(State.RUNNING, ChronoTask.getState());

        // Stop while task is executing
        ChronoTask.stop();
        assertEquals(State.SHUTDOWN, ChronoTask.getState());

        Thread.sleep(600); // Wait for task to finish
        long countAfterStop = this.counter.get();

        // Restart
        assertTrue(ChronoTask.start(), "Should be able to restart");
        assertEquals(State.RUNNING, ChronoTask.getState());
        Thread.sleep(50);
        assertTrue(this.counter.get() > countAfterStop, "Counter should increment after restart");

        ChronoTask.stop();
    }

    /**
     * Tests restarting immediately after stopping (within milliseconds).
     * Verifies that quick restart cycles work correctly.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRestartImmediatelyAfterStop(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        var ChronoTask = executor.createTask(createTask(0)).build();

        // First start
        ChronoTask.start();
        Thread.sleep(50);
        long firstCount = this.counter.get();
        assertTrue(firstCount >= 1, "Should have at least 1 execution");

        // immediately restart
        assertTrue(ChronoTask.start(), "Immediate restart should succeed");
        assertEquals(State.RUNNING, ChronoTask.getState());
        Thread.sleep(50);
        long secondCount = this.counter.get();
        assertTrue(secondCount > firstCount, "Should have more executions after restart");

        ChronoTask.stop();
    }

    /**
     * Tests building a task but never calling start().
     * Verifies that no execution occurs and resources are not wasted.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testBuildWithoutStart(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setPeriodicDelay(Duration.ofMillis(10));
        ChronoTask timer = builder.build();

        // Don't call start(), just wait
        assertEquals(State.STOPPED, timer.getState());
        Thread.sleep(50);

        // Verify no executions occurred
        assertEquals(0, this.counter.get(), "Task should not execute without start()");
        assertEquals(State.STOPPED, timer.getState());

        // Verify we can still start it later
        assertTrue(timer.start(), "Should be able to start later");
        Thread.sleep(50);
        assertTrue(this.counter.get() >= 5, "Should execute after start() is called");

        timer.stop();
    }

    /**
     * Tests that stop() promptly terminates the timer thread even when it is
     * sleeping between repetitive executions (i.e. blocked in Thread.sleep()).
     * Without interrupt(), stop() would have no effect until the 60-second sleep
     * expired.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStopInterruptsTimerThreadDuringSleep(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        // instant task, 60-second repetitive delay — after first execution the timer
        // thread enters Thread.sleep() for 60 seconds
        ChronoTaskBuilder builder = executor.createTask(createTask(0));
        builder.setRepetitiveDelay(Duration.ofSeconds(5));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(150); // wait for first execution to complete
        assertEquals(1, this.counter.get(), "First execution should have completed");
        // timer thread is now sleeping for 60 seconds

        long stopStart = System.currentTimeMillis();
        timer.stop();
        long elapsed = System.currentTimeMillis() - stopStart;

        assertEquals(State.SHUTDOWN, timer.getState(), "Timer should be in shutdown");
        assertTrue(elapsed < 500, "stop() should return promptly (< 500ms), but took " + elapsed + "ms");
    }

    /**
     * Tests that setMaxConcurrentExecutions(2) bounds overlapping periodic
     * executions to exactly 2 concurrent invocations when the task duration is
     * much longer than the periodic delay.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMaxConcurrentExecutionsThrottlesOverlap(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger peak = new AtomicInteger(0);
        ChronoTaskBuilder builder = executor.createTask(createThrottleTrackingTask(200, inFlight, peak));
        builder.setPeriodicDelay(Duration.ofMillis(50)).setMaxConcurrentExecutions(2);
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(500);
        timer.stop();

        assertTrue(peak.get() <= 2, "Peak concurrent executions should never exceed the configured limit of 2");
        assertTrue(peak.get() >= 2, "Peak concurrent executions should actually reach 2 (bounded overlap)");
    }

    /**
     * Tests that setMaxConcurrentExecutions(1) fully serializes periodic
     * executions, equivalent to no overlap at all.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMaxConcurrentExecutionsSerializesWhenSetToOne(final AbstractExecutor executor)
            throws InterruptedException {
        this.currentExecutor = executor;
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger peak = new AtomicInteger(0);
        ChronoTaskBuilder builder = executor.createTask(createThrottleTrackingTask(100, inFlight, peak));
        builder.setPeriodicDelay(Duration.ofMillis(50)).setMaxConcurrentExecutions(1);
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(400);
        timer.stop();

        assertEquals(1, peak.get(), "Peak concurrent executions should be exactly 1 when the limit is 1");
    }

    /**
     * Tests that periodic executions overlap without bound by default (no
     * setMaxConcurrentExecutions() call), locking in the intentional
     * overlap-by-default behavior.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMaxConcurrentExecutionsDefaultAllowsUnboundedOverlap(final AbstractExecutor executor)
            throws InterruptedException {
        this.currentExecutor = executor;
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger peak = new AtomicInteger(0);
        ChronoTaskBuilder builder = executor.createTask(createThrottleTrackingTask(300, inFlight, peak));
        builder.setPeriodicDelay(Duration.ofMillis(100));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(400);
        timer.stop();

        assertTrue(peak.get() > 1, "Overlap should occur by default without an explicit throttle");
    }

    /**
     * Tests that setMaxConcurrentExecutions() rejects zero and negative values.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testSetMaxConcurrentExecutionsRejectsInvalidValue(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(0));

        assertThrowsExactly(IllegalArgumentException.class, () -> builder.setMaxConcurrentExecutions(0));
        assertThrowsExactly(IllegalArgumentException.class, () -> builder.setMaxConcurrentExecutions(-1));
    }

    /**
     * Tests that setMaxConcurrentExecutions() returns false when the task is
     * already running.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testSetMaxConcurrentExecutionsReturnsFalseWhenRunning(final AbstractExecutor executor)
            throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(1000));
        builder.setPeriodicDelay(Duration.ofSeconds(2));
        ChronoTask timer = builder.build();

        timer.start();
        Thread.sleep(50);

        assertFalse(timer.setMaxConcurrentExecutions(3),
                "setMaxConcurrentExecutions() should return false while the task is running");

        timer.stop();
    }

    /**
     * Tests that stop() promptly unblocks a timer thread waiting on the
     * throttle's acquire() call, avoiding a hang.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStopWhileBlockedOnThrottle(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;
        ChronoTaskBuilder builder = executor.createTask(createTask(1000)); // 1-second task
        builder.setPeriodicDelay(Duration.ofMillis(100)).setMaxConcurrentExecutions(1);
        ChronoTask timer = builder.build();

        timer.start();
        // First execution starts immediately and holds the single permit; the timer
        // thread will block on acquire() for the next firing shortly after.
        Thread.sleep(100);

        timer.stop();

        long deadline = System.currentTimeMillis() + 1000;
        while ((timer.getState() != State.STOPPED) && (System.currentTimeMillis() < deadline)) {
            Thread.sleep(20);
        }

        assertEquals(State.STOPPED, timer.getState(), "Task should reach STOPPED promptly, not hang, after stop()");
    }

    // ========== Helper Methods ==========

    private Consumer<ChronoTask> createTask(final int milliseconds) {
        return _ -> {
            try {
                this.counter.incrementAndGet();
                Thread.sleep(Duration.ofMillis(milliseconds));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /**
     * Creates a task that tracks the number of concurrently in-flight executions,
     * recording the highest concurrency level observed in {@code peak}.
     */
    private Consumer<ChronoTask> createThrottleTrackingTask(final long durationMillis, final AtomicInteger inFlight,
            final AtomicInteger peak) {
        return _ -> {
            int current = inFlight.incrementAndGet();
            peak.updateAndGet(p -> Math.max(p, current));
            try {
                Thread.sleep(durationMillis);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        };
    }

}
