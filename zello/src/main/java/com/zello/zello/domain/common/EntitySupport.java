package com.zello.zello.domain.common;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class EntitySupport {
    private EntitySupport() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " is required");
    }

    public static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static void same(Object expected, Object actual, String scope) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException("Different " + scope);
        }
    }
}
