package com.sdigital.platform.email.exception;

/**
 * Thrown when an email template code is already used by another template.
 */
public class EmailTemplateCodeAlreadyUsedException extends RuntimeException {

    public EmailTemplateCodeAlreadyUsedException(String code) {
        super("Email template code already in use: " + code);
    }
}
