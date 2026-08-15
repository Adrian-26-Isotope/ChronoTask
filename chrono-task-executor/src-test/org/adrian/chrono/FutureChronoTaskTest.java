package org.adrian.chrono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
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
        return Stream.of(Arguments.of(new ThreadExecutor()), Arguments.of(new PoolExecutor(ElasticThreadPool.builder()
                .setMinThreads(0).setIdleTime(Duration.ofSeconds(2)).setName("test-pool").start())));

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
        Thread.sleep(50);

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
        Thread.sleep(50);

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
     * Tests that the future returned by start() is completed by whichever
     * overlapping execution finishes first (completion order), not necessarily by
     * the execution that started first. The first invocation is slow and keeps
     * <<<<<<<< HEAD:src-test/org/adrian/chrono/FutureChronoTaskTest.java
     * running while the second, fast invocation completes first and self-stops the
     * task to keep the scenario deterministic.
     * ========
     * running while the second, fast invocation completes first.
     * >>>>>>>>
     * bugfixing:chrono-task-executor/src-test/org/adrian/chrono/FutureChronoTaskTest.java
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testNextResultCompletesInFinishOrderNotStartOrder(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        AtomicInteger counter = new AtomicInteger(0);
        FutureChronoTask<String> task = executor.<String>createFutureTask(_ -> {
            int n = counter.incrementAndGet();
            if (n == 1) {
                sleepUninterruptibly(200);
                return "slow-" + n;
            }
            if (n == 2) {
                sleepUninterruptibly(20);
                return "fast-" + n;
            }
            return "extra";
        }).setPeriodicDelay(Duration.ofMillis(150)).build();

        CompletableFuture<String> first = task.start();

        assertEquals("fast-2", first.get(1, TimeUnit.SECONDS),
                "Future from start() should be completed by the first execution to finish, not the first to start");

        CompletableFuture<String> next = task.getNextResult();
        assertEquals("slow-1", next.get(1, TimeUnit.SECONDS),
                "Following future should still be completed by the slow execution once it finishes");

        next = task.getNextResult();
        assertEquals("extra", next.get(1, TimeUnit.SECONDS));

        task.stop();
    }

    private static void sleepUninterruptibly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tests that start() returns null when the task is already running.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStartOnAlreadyRunningTaskReturnsNull(final AbstractExecutor executor) {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 1)
                .setPeriodicDelay(Duration.ofSeconds(10)).build();

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
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 7)
                .setInitialDelay(Duration.ofMillis(100)).build();

        CompletableFuture<Integer> future = task.start();
        assertFalse(future.isDone(), "Future should not be done immediately after start (initial delay)");

        Thread.sleep(50);
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
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 1)
                .setPeriodicDelay(Duration.ofSeconds(10)).build();

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
    @Test
    void testNegativeInitialDelayThrows() {
        this.currentExecutor = new ThreadExecutor();
        assertThrows(IllegalArgumentException.class,
                () -> this.currentExecutor.createFutureTask(_ -> 1).setInitialDelay(Duration.ofMillis(-100)));
    }

    /**
     * Tests that a negative periodic delay throws IllegalArgumentException.
     */
    @Test
    void testNegativePeriodicDelayThrows() {
        this.currentExecutor = new ThreadExecutor();
        assertThrows(IllegalArgumentException.class,
                () -> this.currentExecutor.createFutureTask(_ -> 1).setPeriodicDelay(Duration.ofMillis(-100)));
    }

    /**
     * Tests that a negative repetitive delay throws IllegalArgumentException.
     */
    @Test
    void testNegativeRepetitiveDelayThrows() {
        this.currentExecutor = new ThreadExecutor();
        assertThrows(IllegalArgumentException.class,
                () -> this.currentExecutor.createFutureTask(_ -> 1).setRepetitiveDelay(Duration.ofMillis(-100)));
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
        }).setRepetitiveDelay(Duration.ofMillis(20)).build();

        task.start();
        Thread.sleep(100);

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

    /**
     * Tests the setMaxConcurrentExecutions() passthrough: builder validation
     * rejects invalid values, and the instance method returns false while
     * running. Future/result correctness under overlap is out of scope here.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testSetMaxConcurrentExecutionsPassthrough(final AbstractExecutor executor) throws InterruptedException {
        this.currentExecutor = executor;

        assertThrows(IllegalArgumentException.class,
                () -> executor.createFutureTask(_ -> 1).setMaxConcurrentExecutions(0));
        assertThrows(IllegalArgumentException.class,
                () -> executor.createFutureTask(_ -> 1).setMaxConcurrentExecutions(-1));

        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 1)
                .setPeriodicDelay(Duration.ofSeconds(2)).build();
        task.start();
        Thread.sleep(50);

        assertFalse(task.setMaxConcurrentExecutions(2),
                "setMaxConcurrentExecutions() should return false while the task is running");

        task.stop();
    }

    /**
     * Tests that stop() cancels a pending future so a caller's get() does not
     * hang forever.
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testStopCancelsPendingFuture(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 1)
                .setInitialDelay(Duration.ofSeconds(2)).build();

        CompletableFuture<Integer> future = task.start();
        assertFalse(future.isDone(), "future should not be done before stop()");

        task.stop();
        Thread.sleep(50);

        assertTrue(future.isCancelled(), "pending future should be cancelled by stop()");
        assertThrows(CancellationException.class, () -> future.get(1, TimeUnit.SECONDS));
    }

    /**
     * Tests that after a one-shot task self-terminates internally (its
     * {@link Schedule.OneShot} completes and the timer stops on its own),
     * the orphan {@link CompletableFuture} left by the last execution's
     * claim-and-replace is cancelled, so {@link #getNextResult()} does not
     * hand out a future that hangs forever. (F31)
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testOneShotSelfTerminationCancelsOrphanFuture(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> 42).build();

        CompletableFuture<Integer> first = task.start();
        assertEquals(42, first.get(1, TimeUnit.SECONDS), "First execution should complete with the result");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (task.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(task.isRunning(), "One-shot task should have self-terminated");
        Thread.sleep(50);

        CompletableFuture<Integer> orphan = task.getNextResult();
        long orphanDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!orphan.isDone() && System.nanoTime() < orphanDeadline) {
            Thread.sleep(10);
        }
        assertTrue(orphan.isDone(), "Orphan future should be cancelled after self-termination, not hang forever");
        assertTrue(orphan.isCancelled(), "Orphan future should be cancelled");
    }

    /**
     * Tests that restarting a task after it self-terminates returns a usable
     * (non-cancelled) future. This guards against the race where the
     * termination callback cancels a future that belongs to the new
     * lifecycle. (F31)
     */
    @ParameterizedTest
    @MethodSource("executorProvider")
    void testRestartAfterSelfTerminationReturnsUsableFuture(final AbstractExecutor executor) throws Exception {
        this.currentExecutor = executor;
        AtomicInteger callCount = new AtomicInteger(0);
        FutureChronoTask<Integer> task = executor.<Integer>createFutureTask(_ -> callCount.incrementAndGet())
                .build();

        CompletableFuture<Integer> first = task.start();
        assertEquals(1, first.get(1, TimeUnit.SECONDS));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (task.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(task.isRunning(), "One-shot task should have self-terminated");
        Thread.sleep(50);

        CompletableFuture<Integer> second = task.start();
        assertNotNull(second, "restart after self-termination should succeed");
        assertFalse(second.isCancelled(), "restart future must not be cancelled");
        assertEquals(2, second.get(1, TimeUnit.SECONDS), "restart future should complete with new result");
        task.stop();
    }
}
