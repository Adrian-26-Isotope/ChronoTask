package org.adrian.chrono.util;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates thread names against an allow-list of safe characters and a length
 * cap. This prevents control characters, ANSI escape sequences, and excessively
 * long names from leaking into thread dumps, profiler output, and logs — an
 * information-exposure risk if task names ever derive from untrusted input.
 */
public final class ThreadNames {

    /**
     * Allow-list pattern for thread names: ^[a-zA-Z0-9_\\-#:.\\[\\]]{1,128}$
     */
    public static final Pattern ALLOWED = Pattern.compile("^[a-zA-Z0-9_\\-#:.\\[\\]]{1,128}$");

    private ThreadNames() {}

    /**
     * Validates that the given name contains only allow-listed characters and is
     * within the length cap. See {@link #ALLOWED} for the allow-list regex.
     *
     * @param name the name to validate; must not be {@code null}
     * @return the validated name
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} does not match the allow-list pattern.
     */
    public static String sanitize(final String name) {
        Objects.requireNonNull(name, "name");
        if (!ALLOWED.matcher(name).matches()) {
            throw new IllegalArgumentException("name needs to match the regex '^[a-zA-Z0-9_\\-#:.\\[\\]]{1,128}$'");
        }
        return name;
    }
}
