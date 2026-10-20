package com.sdigital.platform.agency.exception;

import java.util.UUID;

/**
 * Thrown when an agency cannot be found by its identifier.
 */
public class AgencyNotFoundException extends RuntimeException {

    public AgencyNotFoundException(UUID id) {
        super("Agency not found: " + id);
    }
}
