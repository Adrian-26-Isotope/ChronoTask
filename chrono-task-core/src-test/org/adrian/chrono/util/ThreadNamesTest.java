package org.adrian.chrono.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ThreadNames}, covering allow-list validation, length
 * capping, and null rejection.
 */
class ThreadNamesTest {

    @Test
    void acceptsAllowedCharacters() {
        assertEquals("Task-1.0:[prod]#2", ThreadNames.sanitize("Task-1.0:[prod]#2"));
    }

    @Test
    void acceptsAlphanumerics() {
        assertEquals("HelloWorld123", ThreadNames.sanitize("HelloWorld123"));
    }

    @Test
    void acceptsHashCharacter() {
        assertEquals("Task#42", ThreadNames.sanitize("Task#42"));
    }

    @Test
    void acceptsBracketsAndColon() {
        assertEquals("[name]:Task", ThreadNames.sanitize("[name]:Task"));
    }

    @Test
    void rejectsSpace() {
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("Hello World"));
    }

    @Test
    void rejectsSlash() {
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("pool/task"));
    }

    @Test
    void rejectsControlCharacters() {
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("cl\nean"));
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("cl\tean"));
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("cl\rean"));
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("cl\u0000ean"));
    }

    @Test
    void rejectsAnsiEscapeSequences() {
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("\u001b[31mtext\u001b[0m"));
    }

    @Test
    void rejectsSpecialCharacters() {
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("a@b$c%d^e&f*g(h)i"));
    }

    @Test
    void rejectsNewlineInjection() {
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("task\n[ERROR]fake"));
    }

    @Test
    void rejectsExceedingMaxLength() {
        String longName = "a".repeat(129);
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize(longName));
    }

    @Test
    void acceptsExactlyMaxLength() {
        String maxName = "a".repeat(128);
        assertEquals(maxName, ThreadNames.sanitize(maxName));
    }

    @Test
    void rejectsAllDisallowed() {
        assertThrowsExactly(IllegalArgumentException.class, () -> ThreadNames.sanitize("@$%^&*()"));
    }

    @Test
    void rejectsNull() {
        assertThrowsExactly(NullPointerException.class, () -> ThreadNames.sanitize(null));
    }
}
