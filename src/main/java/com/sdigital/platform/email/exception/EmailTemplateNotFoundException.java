package com.sdigital.platform.email.exception;

import java.util.UUID;

/**
 * Thrown when an email template cannot be found by its identifier.
 */
public class EmailTemplateNotFoundException extends RuntimeException {

    public EmailTemplateNotFoundException(UUID id) {
        super("Email template not found: " + id);
    }
}
