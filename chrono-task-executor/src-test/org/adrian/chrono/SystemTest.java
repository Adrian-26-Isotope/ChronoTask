package org.adrian.chrono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.adrian.threadpool.ElasticThreadPool;
import org.junit.jupiter.api.Test;

class SystemTest {

    /**
     * ensure that uncaught errors thrown by a ChronoTask are forwarded to the
     * thread's uncaught exception handler.
     */
    @Test
    void testThreadExecutorForwardsUncaughtErrorsToHandler() throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ThreadExecutor executor = new ThreadExecutor();
        executor.setThreadFactory(r -> {
            Thread thread = Thread.ofVirtual().unstarted(r);
            thread.setUncaughtExceptionHandler((_, e) -> {
                caught.set(e);
                latch.countDown();
            });
            return thread;
        });

        ChronoTask task = executor.createTask(_ -> {
            throw new Error("boom-thread");
        }).build();
        task.start();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "uncaught exception handler should be invoked for ThreadExecutor");
        assertNotNull(caught.get());
        assertEquals("boom-thread", caught.get().getMessage());
    }

    /**
     * ensure that the pool executor forwards uncaught {@link Error}s to the thread.
     */
    @Test
    void testPoolExecutorForwardsUncaughtErrorsToHandler() throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ElasticThreadPool pool =
                ElasticThreadPool.builder().setMinThreads(0).setIdleTime(Duration.ofSeconds(2)).setThreadFactory(r -> {
                    Thread thread = Thread.ofVirtual().unstarted(r);
                    thread.setUncaughtExceptionHandler((_, e) -> {
                        caught.set(e);
                        latch.countDown();
                    });
                    return thread;
                }).start();

        PoolExecutor executor = new PoolExecutor(pool);
        try {
            ChronoTask task = executor.createTask(_ -> {
                throw new Error("boom-pool");
            }).build();
            task.start();

            assertTrue(latch.await(2, TimeUnit.SECONDS),
                    "uncaught exception handler should be invoked for PoolExecutor");
            assertNotNull(caught.get());
            assertEquals("boom-pool", caught.get().getMessage());
        }
        finally {
            executor.shutdown();
            executor.awaitTermination(Duration.ofSeconds(3));
        }
    }

    /**
     * ensure that stop() cancels a pending future and a subsequent start()
     * provides a fresh one.
     */
    @Test
    void testStopCancelsPendingFutureAndRestartProvidesFreshOne() throws InterruptedException {
        ThreadExecutor executor = new ThreadExecutor();
        FutureChronoTask<Integer> task =
                executor.<Integer> createFutureTask(_ -> 42).setInitialDelay(Duration.ofSeconds(10)).build();

        CompletableFuture<Integer> first = task.start();
        // stop before the 10s initial delay elapses: the consumer body never runs,
        // so the pending future is cancelled by stop().
        task.stop();
        Thread.sleep(50);

        assertTrue(first.isCancelled(), "the pending future should be cancelled by stop()");

        CompletableFuture<Integer> restarted = task.start();
        assertNotSame(first, restarted, "restart should provide a fresh future, not the cancelled one");

        task.stop();
    }

    /**
     * ensure that duration copy actually creates a new instance.
     */
    @Test
    void testDurationDefensiveCopyDoesNotActuallyCopy() throws Exception {
        Duration original = Duration.ofSeconds(42);

        ThreadExecutor executor = new ThreadExecutor();
        ChronoTaskBuilder builder = executor.createTask(_ -> {});
        builder.setInitialDelay(original);

        Field field = AbstractTaskBuilder.class.getDeclaredField("initialDelay");
        field.setAccessible(true);
        Duration stored = (Duration) field.get(builder);

        assertNotSame(original, stored, "setInitialDelay() should store a real defensive copy of the Duration, " +
                "not the exact same instance that was passed in");
        assertEquals(original, stored, "the copied Duration should still be equal in value to the original");
    }

    /**
     * ensure the timed task behaves correctly when the underlying pool executor
     * rejects a submission.
     */
    @Test
    void testStartDoesNotDeadlockWhenPoolExecutorRejectsSubmission() throws Exception {
        ElasticThreadPool pool = ElasticThreadPool.builder().setMinThreads(0).setIdleTime(Duration.ofSeconds(2))
                .setName("dropped-submission-pool").start();
        PoolExecutor executor = new PoolExecutor(pool);
        // shut down the pool BEFORE the task ever gets a chance to run
        executor.shutdown();
        Thread.sleep(50);

        ChronoTask task = executor.createTask(_ -> {}).build();

        boolean started = task.start();
        assertFalse(started, "start() should report failure (return false) when the pool rejects the submission, " +
                "rolling the task back to STOPPED instead of committing to RUNNING");

        task.stop();
        Thread.sleep(200);

        // the restart must NOT hang: run it on a separate thread and confirm it
        // completes quickly
        CountDownLatch restarted = new CountDownLatch(1);
        Thread restartThread = new Thread(() -> {
            task.start();
            restarted.countDown();
        });
        restartThread.setDaemon(true);
        restartThread.start();

        boolean completedInTime = restarted.await(2, TimeUnit.SECONDS);
        // clean up the thread regardless of the assertion outcome
        restartThread.interrupt();
        restartThread.join(1000);

        assertTrue(completedInTime,
                "start() on a ChronoTask whose pool executor rejected an earlier submission must return " +
                        "promptly instead of deadlocking, because the task is rolled back to STOPPED rather " +
                        "than left stuck in SHUTDOWN with no thread to notify its waiting monitor");
    }

    /**
     * {@code setThreadFactory} must reject {@code null} rather than
     * silently poisoning the executor, so a subsequent {@code run(...)} fails
     * with a clear {@link NullPointerException} at the call site.
     */
    @Test
    void testSetThreadFactoryRejectsNull() {
        ThreadExecutor executor = new ThreadExecutor();
        assertThrows(NullPointerException.class, () -> executor.setThreadFactory(null),
                "setThreadFactory(null) must throw NullPointerException");
    }

    /**
     * A factory swapped in after construction must be used by subsequent
     * {@code run(...)} invocations. Exercises the field read on the executor
     * side directly (the method is package-protected) so the assertion is
     * deterministic rather than timing-dependent.
     */
    @Test
    void testThreadExecutorUsesSwappedThreadFactory() throws InterruptedException {
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        ThreadFactory factoryA = r -> {
            aCount.incrementAndGet();
            return Thread.ofVirtual().unstarted(r);
        };
        ThreadFactory factoryB = r -> {
            bCount.incrementAndGet();
            return Thread.ofVirtual().unstarted(r);
        };

        ThreadExecutor executor = new ThreadExecutor();
        executor.setThreadFactory(factoryA);

        CountDownLatch firstRun = new CountDownLatch(1);
        executor.run(firstRun::countDown);
        assertTrue(firstRun.await(2, TimeUnit.SECONDS), "first run should complete on factory A");
        assertTrue(aCount.get() >= 1, "factory A should have created the first thread");
        assertEquals(0, bCount.get(), "factory B should not have been used before the swap");

        executor.setThreadFactory(factoryB);

        CountDownLatch secondRun = new CountDownLatch(1);
        executor.run(secondRun::countDown);
        assertTrue(secondRun.await(2, TimeUnit.SECONDS), "second run should complete on factory B");
        assertTrue(bCount.get() >= 1, "factory B should have created the thread after the swap");
    }
}
