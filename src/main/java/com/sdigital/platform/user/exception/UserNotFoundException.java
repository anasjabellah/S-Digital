package com.sdigital.platform.user.exception;

import java.util.UUID;

/**
 * Thrown when a user cannot be found by its identifier.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID id) {
        super("User not found: " + id);
    }
}
