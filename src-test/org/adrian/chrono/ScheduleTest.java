package org.adrian.chrono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Schedule} sealed type and its record
 * implementations. These tests exercise the next-execution-time policy in
 * isolation — no timer thread or executor is needed.
 */
class ScheduleTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");


    // ---- Periodic ----

    @Test
    void periodicAfterDispatchReturnsDispatchPlusDelay() {
        Duration delay = Duration.ofSeconds(5);
        Schedule.Periodic schedule = new Schedule.Periodic(delay);
        assertEquals(T0.plus(delay), schedule.afterDispatch(T0));
    }

    @Test
    void periodicAfterCompletionReturnsNull() {
        Schedule.Periodic schedule = new Schedule.Periodic(Duration.ofSeconds(1));
        assertNull(schedule.afterCompletion(T0));
    }

    @Test
    void periodicRejectsNullDelay() {
        assertThrowsExactly(NullPointerException.class, () -> new Schedule.Periodic(null));
    }

    @Test
    void periodicRejectsNegativeDelay() {
        assertThrowsExactly(IllegalArgumentException.class, () -> new Schedule.Periodic(Duration.ofMillis(-1)));
    }


    // ---- Repetitive ----

    @Test
    void repetitiveAfterDispatchReturnsNull() {
        Schedule.Repetitive schedule = new Schedule.Repetitive(Duration.ofSeconds(1));
        assertNull(schedule.afterDispatch(T0));
    }

    @Test
    void repetitiveAfterCompletionReturnsCompletionPlusDelay() {
        Duration delay = Duration.ofSeconds(5);
        Schedule.Repetitive schedule = new Schedule.Repetitive(delay);
        assertEquals(T0.plus(delay), schedule.afterCompletion(T0));
    }

    @Test
    void repetitiveRejectsNullDelay() {
        assertThrowsExactly(NullPointerException.class, () -> new Schedule.Repetitive(null));
    }

    @Test
    void repetitiveRejectsNegativeDelay() {
        assertThrowsExactly(IllegalArgumentException.class, () -> new Schedule.Repetitive(Duration.ofMillis(-1)));
    }


    // ---- OneShot ----

    @Test
    void oneShotAfterDispatchReturnsNull() {
        Schedule.OneShot schedule = new Schedule.OneShot();
        assertNull(schedule.afterDispatch(T0));
    }

    @Test
    void oneShotAfterCompletionReturnsNull() {
        Schedule.OneShot schedule = new Schedule.OneShot();
        assertNull(schedule.afterCompletion(T0));
    }


    // ---- Defensive copy ----

    @Test
    void periodicDecouplesDelayFromCaller() {
        Duration input = Duration.ofSeconds(3);
        Schedule.Periodic schedule = new Schedule.Periodic(input);
        input = input.plusSeconds(2);
        assertEquals(Duration.ofSeconds(3), schedule.delay());
        assertEquals(T0.plusSeconds(3), schedule.afterDispatch(T0));
    }
}
