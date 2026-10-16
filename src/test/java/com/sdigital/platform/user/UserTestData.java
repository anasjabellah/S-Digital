package com.sdigital.platform.user;

import com.sdigital.platform.user.entity.User;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

/**
 * Test fixtures for {@link User}.
 *
 * <p>The entity intentionally exposes no setter for {@code id}, {@code createdAt} or
 * {@code updatedAt} (they are owned by the JPA lifecycle). To assert that application
 * code never mutates them, tests need to populate them, which is done here through
 * reflection rather than by weakening the entity.</p>
 */
public final class UserTestData {

    private UserTestData() {
    }

    public static User user(UUID id,
                            String email,
                            String firstName,
                            String lastName,
                            String keycloakId,
                            Instant createdAt,
                            Instant updatedAt) {
        User user = new User(email, firstName, lastName);
        setField(user, "id", id);
        setField(user, "keycloakId", keycloakId);
        setField(user, "createdAt", createdAt);
        setField(user, "updatedAt", updatedAt);
        return user;
    }

    private static void setField(User user, String fieldName, Object value) {
        try {
            Field field = User.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(user, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set field '" + fieldName + "' on User test fixture", e);
        }
    }
}
