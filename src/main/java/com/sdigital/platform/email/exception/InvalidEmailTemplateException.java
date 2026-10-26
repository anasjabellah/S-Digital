package com.sdigital.platform.email.exception;

/**
 * Thrown when subject/body placeholders are malformed or undeclared.
 */
public class InvalidEmailTemplateException extends RuntimeException {

    public InvalidEmailTemplateException(String message) {
        super(message);
    }
}
