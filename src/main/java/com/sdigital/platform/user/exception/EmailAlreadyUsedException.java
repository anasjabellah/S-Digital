package com.sdigital.platform.user.exception;

/**
 * Thrown when a user creation or update would violate the uniqueness of the email.
 */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException(String email) {
        super("Email already in use: " + email);
    }
}
