package com.sdigital.platform.user.exception;

import java.util.UUID;

/**
 * Thrown when a referenced function does not exist or is not usable.
 */
public class UserFunctionNotFoundException extends RuntimeException {

    public UserFunctionNotFoundException(UUID id) {
        super("Function not found: " + id);
    }
}
