package com.sdigital.platform.agency;

import com.sdigital.platform.agency.entity.Agency;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

/**
 * Test fixtures for {@link Agency}.
 *
 * <p>The entity intentionally exposes no setter for {@code id}, {@code createdAt} or
 * {@code updatedAt} (they are owned by the JPA lifecycle). To assert that application
 * code never mutates them, tests need to populate them, which is done here through
 * reflection rather than by weakening the entity.</p>
 */
public final class AgencyTestData {

    private AgencyTestData() {
    }

    public static Agency agency(UUID id,
                                String code,
                                String name,
                                Instant createdAt,
                                Instant updatedAt) {
        Agency agency = new Agency(code, name);
        setField(agency, "id", id);
        setField(agency, "createdAt", createdAt);
        setField(agency, "updatedAt", updatedAt);
        return agency;
    }

    /**
     * Assigns the identifier that the JPA lifecycle would normally generate on save.
     *
     * <p>Used by service tests that stub the repository: the audited code reads the
     * identifier of the saved entity, so the fixture must expose one.</p>
     */
    public static void setId(Agency agency, UUID id) {
        setField(agency, "id", id);
    }

    private static void setField(Agency agency, String fieldName, Object value) {
        try {
            Field field = Agency.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(agency, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot set test field " + fieldName, ex);
        }
    }
}
