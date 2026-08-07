package org.adrian.chrono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.adrian.threadpool.ElasticThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FutureChronoTaskTest {

    private AbstractExecutor currentExecutor;

    @AfterEach
    void cleanup() throws InterruptedException {
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
        return Stream.of(Arguments.of(new ThreadExecutor()),
                Arguments.of(new PoolExecutor(ElasticThreadPool.builder().setMinThreads(0)
                        .setIdleTime(Duration.ofSeconds(2)).setName("test-pool").build())));
    }

    /**
     * Tests that a one-shot callable returns the correct result via the future from
     * start().
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testOneShotResult(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 42).build();

        CompletableFuture<Integer> future = task.start();
        assertNotNull(future, "start() should return a non-null future");

        Integer result = future.get(1, TimeUnit.SECONDS);
        assertEquals(42, result, "Future should complete with callable's return value");
    }

    /**
     * Tests that getLastResult() is empty before first execution and populated
     * afterwards.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testLastResultBeforeAndAfterExecution(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<String> task = executor.<String>createFutureTask(_ -> "hello").build();

        assertTrue(task.getLastResult().isEmpty(), "getLastResult() should be empty before first execution");

        CompletableFuture<String> future = task.start();
        future.get(1, TimeUnit.SECONDS);

        assertTrue(task.getLastResult().isPresent(), "getLastResult() should be present after execution");
        assertEquals("hello", task.getLastResult().get());
    }

    /**
     * Tests that a callable exception completes the future exceptionally and does
     * not swallow the error.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testCallableExceptionPropagates(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        RuntimeException cause = new RuntimeException("callable failed");
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> {
            throw cause;
        }).build();

        CompletableFuture<Integer> future = task.start();
        // Wait long enough for the task to execute
        Thread.sleep(200);

        assertTrue(future.isCompletedExceptionally(), "Future should be completed exceptionally");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get());
        assertEquals(cause, ex.getCause(), "Cause should be the original exception from callable");
    }

    /**
     * Tests that getLastResult() remains empty when the callable throws an
     * exception.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testLastResultEmptyOnException(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> {
            throw new RuntimeException("error");
        }).build();

        CompletableFuture<Integer> future = task.start();
        Thread.sleep(200);

        assertTrue(future.isCompletedExceptionally());
        assertTrue(task.getLastResult().isEmpty(), "getLastResult() should remain empty after a failed execution");
    }

    /**
     * Tests periodic callable: start() gives the first-execution future and
     * getNextResult() gives subsequent futures.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testPeriodicCallable(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        AtomicInteger counter = new AtomicInteger(0);
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> counter.incrementAndGet())
                .setPeriodicDelay(Duration.ofMillis(200)).build();

        CompletableFuture<Integer> first = task.start();
        assertEquals(1, first.get(1, TimeUnit.SECONDS), "First future should hold first execution result");

        CompletableFuture<Integer> second = task.getNextResult();
        assertEquals(2, second.get(1, TimeUnit.SECONDS), "Second future should hold second execution result");

        task.stop();
    }

    /**
     * Tests that start() returns null when the task is already running.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStartOnAlreadyRunningTaskReturnsNull(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task =
                executor.<Integer>createFutureTask(_ -> 1).setPeriodicDelay(Duration.ofSeconds(10)).build();

        CompletableFuture<Integer> first = task.start();
        assertNotNull(first, "First start() should return a non-null future");
        assertTrue(task.isRunning());

        assertNull(task.start(), "start() on already-running task should return null");

        task.stop();
    }

    /**
     * Tests stop and restart: a new start() returns a fresh future for the
     * restarted execution.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStopAndRestart(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 99).build();

        CompletableFuture<Integer> first = task.start();
        assertEquals(99, first.get(1, TimeUnit.SECONDS));

        task.stop();
        assertFalse(task.isRunning());

        CompletableFuture<Integer> restarted = task.start();
        assertNotNull(restarted, "Restarted task should return a non-null future");
        assertNotSame(first, restarted, "Restarted task should provide a fresh future");
        Integer result = restarted.get(1, TimeUnit.SECONDS);
        assertEquals(99, result);

        task.stop();
    }

    /**
     * Tests that the callable task works correctly with ThreadExecutor.
     */
    @Test
    void testWithThreadExecutor() throws Exception {
        this.currentExecutor = new ThreadExecutor();
        FutureChronoTask<String> task = this.currentExecutor.<String>createFutureTask(_ -> "thread-result").build();

        String result = task.start().get(1, TimeUnit.SECONDS);
        assertEquals("thread-result", result);
    }

    /**
     * Tests that the callable task works correctly with PoolExecutor.
     */
    @Test
    void testWithPoolExecutor() throws Exception {
        PoolExecutor poolExecutor = new PoolExecutor("callable-pool");
        FutureChronoTask<String> task = poolExecutor.<String>createFutureTask(_ -> "pool-result").build();
        String result = task.start().get(1, TimeUnit.SECONDS);
        assertEquals("pool-result", result);
        poolExecutor.shutdown();
    }

    /**
     * Tests that an initial delay is respected before the first execution.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testInitialDelay(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task =
                executor.<Integer>createFutureTask(_ -> 7).setInitialDelay(Duration.ofMillis(400)).build();

        CompletableFuture<Integer> future = task.start();
        assertFalse(future.isDone(), "Future should not be done immediately after start (initial delay)");

        Thread.sleep(200);
        assertFalse(future.isDone(), "Future should not be done before initial delay expires");

        assertEquals(7, future.get(1, TimeUnit.SECONDS), "Future should complete after initial delay");
    }

    /**
     * Tests that a repetitive callable produces results sequentially after each
     * completion.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRepetitiveCallable(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        AtomicInteger counter = new AtomicInteger(0);
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> counter.incrementAndGet())
                .setRepetitiveDelay(Duration.ofMillis(200)).build();

        CompletableFuture<Integer> first = task.start();
        assertEquals(1, first.get(1, TimeUnit.SECONDS));

        CompletableFuture<Integer> second = task.getNextResult();
        assertEquals(2, second.get(1, TimeUnit.SECONDS));

        task.stop();
    }

    /**
     * Tests that isRunning() reflects the task state correctly.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testIsRunning(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task =
                executor.<Integer>createFutureTask(_ -> 1).setPeriodicDelay(Duration.ofSeconds(10)).build();

        assertFalse(task.isRunning(), "Task should not be running before start()");
        task.start();
        assertTrue(task.isRunning(), "Task should be running after start()");
        task.stop();
        assertFalse(task.isRunning(), "Task should not be running after stop()");
    }

    /**
     * Tests that getNextResult() before any execution returns the same future as
     * start().
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testGetNextResultBeforeFirstExecution(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        // Long delay: won't execute during test
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 5)
                .setInitialDelay(Duration.ofSeconds(10)).setPeriodicDelay(Duration.ofSeconds(10)).build();

        CompletableFuture<Integer> fromStart = task.start();
        CompletableFuture<Integer> fromGetNext = task.getNextResult();

        // Both references should point to the same pending future
        assertEquals(fromStart, fromGetNext,
                "getNextResult() should return the same future as start() before first execution");

        task.stop();
    }

    /**
     * Tests multiple restarts: each start() produces a new future and the callable
     * executes correctly each time.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testMultipleRestarts(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        AtomicInteger counter = new AtomicInteger(0);
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> counter.incrementAndGet()).build();

        for (int i = 1; i <= 3; i++) {
            CompletableFuture<Integer> future = task.start();
            assertNotNull(future);
            assertEquals(i, future.get(1, TimeUnit.SECONDS), "Execution " + i + " should return counter value " + i);
        }
    }

    /**
     * Tests that a negative initial delay throws IllegalArgumentException.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testNegativeInitialDelayThrows(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        assertThrows(IllegalArgumentException.class,
                () -> executor.createFutureTask(_ -> 1).setInitialDelay(Duration.ofMillis(-100)));
    }

    /**
     * Tests that a negative periodic delay throws IllegalArgumentException.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testNegativePeriodicDelayThrows(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        assertThrows(IllegalArgumentException.class,
                () -> executor.createFutureTask(_ -> 1).setPeriodicDelay(Duration.ofMillis(-100)));
    }

    /**
     * Tests that a negative repetitive delay throws IllegalArgumentException.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testNegativeRepetitiveDelayThrows(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        assertThrows(IllegalArgumentException.class,
                () -> executor.createFutureTask(_ -> 1).setRepetitiveDelay(Duration.ofMillis(-100)));
    }

    /**
     * Tests that a callable can stop the task on itself (self-termination). The
     * counter must not exceed the configured limit.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testSelfTermination(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        int limit = 3;
        AtomicInteger counter = new AtomicInteger(0);
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(self -> {
            int n = counter.incrementAndGet();
            if (n >= limit) {
                self.stop();
            }
            return n;
        }).setRepetitiveDelay(Duration.ofMillis(50)).build();

        task.start();
        Thread.sleep(500);

        assertFalse(task.isRunning(), "Task should have self-terminated");
        assertEquals(limit, counter.get(), "Counter should equal limit after self-termination");
    }

    /**
     * Tests that isRunning() returns true when queried from within the callable.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testIsRunningInsideCallable(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<Boolean> task = executor.<Boolean>createFutureTask(self -> self.isRunning()).build();

        Boolean isRunning = task.start().get(1, TimeUnit.SECONDS);
        assertTrue(isRunning, "Task should report isRunning() == true during execution");
    }
}
