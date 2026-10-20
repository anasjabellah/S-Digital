package com.sdigital.platform.user.exception;

import java.util.UUID;

/**
 * Thrown when a referenced agency does not exist or is not usable.
 */
public class UserAgencyNotFoundException extends RuntimeException {

    public UserAgencyNotFoundException(UUID id) {
        super("Agency not found: " + id);
    }
}
