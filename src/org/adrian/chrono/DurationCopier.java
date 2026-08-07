package org.adrian.chrono;

import java.time.Duration;

/**
 * Internal helper that produces true defensive copies of {@link Duration}
 * instances. {@link Duration#multipliedBy(long)} has a fast path that
 * returns {@code this} unchanged when the multiplicand is {@code 1}, so it
 * does not actually decouple the stored value from caller-supplied input.
 * This helper guarantees a genuinely new instance instead.
 */
final class DurationCopier {

    private DurationCopier() {}

    /**
     * Creates a new {@link Duration} instance equal in value to the given
     * duration, but guaranteed to be a distinct object reference so that no
     * strong reference to the caller's instance is retained.
     *
     * @param duration the duration to copy.
     * @return a new {@link Duration} instance equal to {@code duration}.
     */
    static Duration copyOf(final Duration duration) {
        return Duration.ofSeconds(duration.getSeconds(), duration.getNano());
    }
}
