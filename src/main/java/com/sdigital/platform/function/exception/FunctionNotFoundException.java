package com.sdigital.platform.function.exception;

import java.util.UUID;

/**
 * Thrown when a function cannot be found by its identifier.
 */
public class FunctionNotFoundException extends RuntimeException {

    public FunctionNotFoundException(UUID id) {
        super("Function not found: " + id);
    }
}
