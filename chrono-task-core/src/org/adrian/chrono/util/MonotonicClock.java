package org.adrian.chrono.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A clock implementation that provides monotonic time using {@link System#nanoTime()}.
 * This ensures time never goes backwards, unlike wall-clock implementations.
 */
public class MonotonicClock extends Clock {

    private final Instant baseInstant;
    private final long baseNanoTime;
    private final ZoneId zone;


    /**
     * Creates a monotonic clock with the specified time zone.
     *
     * @param zone the time zone to use for this clock
     */
    public MonotonicClock(final ZoneId zone) {
        this(zone, Instant.now(), System.nanoTime());
    }

    private MonotonicClock(final ZoneId zone, final Instant baseInstant, final long baseNanoTime) {
        this.zone = zone;
        this.baseInstant = baseInstant;
        this.baseNanoTime = baseNanoTime;
    }

    @Override
    public Instant instant() {
        long deltaNanos = System.nanoTime() - this.baseNanoTime;
        return this.baseInstant.plusNanos(deltaNanos);
    }

    @Override
    public ZoneId getZone() {
        return this.zone;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        if (zone.equals(this.zone)) {
            return this;
        }
        return new MonotonicClock(zone, this.baseInstant, this.baseNanoTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.baseInstant, this.baseNanoTime, this.zone);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MonotonicClock other = (MonotonicClock) obj;
        return Objects.equals(this.baseInstant, other.baseInstant) && (this.baseNanoTime == other.baseNanoTime) &&
                Objects.equals(this.zone, other.zone);
    }

}
