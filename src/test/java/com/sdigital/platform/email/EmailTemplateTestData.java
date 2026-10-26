package com.sdigital.platform.email;

import com.sdigital.platform.email.entity.EmailTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Test fixtures for {@link EmailTemplate}.
 */
public final class EmailTemplateTestData {

    private EmailTemplateTestData() {
    }

    public static EmailTemplate template(UUID id,
                                         String code,
                                         String name,
                                         Instant createdAt,
                                         Instant updatedAt) {
        EmailTemplate template = new EmailTemplate(
                code, name, "Welcome {{firstName}}", "Hello {{firstName}}", "fr", List.of("firstName"));
        setField(template, "id", id);
        setField(template, "createdAt", createdAt);
        setField(template, "updatedAt", updatedAt);
        return template;
    }

    public static void setId(EmailTemplate template, UUID id) {
        setField(template, "id", id);
    }

    private static void setField(EmailTemplate template, String fieldName, Object value) {
        try {
            Field field = EmailTemplate.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(template, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot set test field " + fieldName, ex);
        }
    }
}
