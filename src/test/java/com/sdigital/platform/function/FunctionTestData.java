package com.sdigital.platform.function;

import com.sdigital.platform.function.entity.Function;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

/**
 * Test fixtures for {@link Function}.
 *
 * <p>The entity intentionally exposes no setter for {@code id}, {@code createdAt} or
 * {@code updatedAt} (they are owned by the JPA lifecycle). To assert that application
 * code never mutates them, tests need to populate them, which is done here through
 * reflection rather than by weakening the entity.</p>
 */
public final class FunctionTestData {

    private FunctionTestData() {
    }

    public static Function function(UUID id,
                                    String code,
                                    String name,
                                    Instant createdAt,
                                    Instant updatedAt) {
        Function function = new Function(code, name);
        setField(function, "id", id);
        setField(function, "createdAt", createdAt);
        setField(function, "updatedAt", updatedAt);
        return function;
    }

    /**
     * Assigns the identifier that the JPA lifecycle would normally generate on save.
     *
     * <p>Used by service tests that stub the repository: the audited code reads the
     * identifier of the saved entity, so the fixture must expose one.</p>
     */
    public static void setId(Function function, UUID id) {
        setField(function, "id", id);
    }

    private static void setField(Function function, String fieldName, Object value) {
        try {
            Field field = Function.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(function, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot set test field " + fieldName, ex);
        }
    }
}
