package com.sdigital.platform.function.exception;

/**
 * Thrown when a function code is already used by another function.
 */
public class FunctionCodeAlreadyUsedException extends RuntimeException {

    public FunctionCodeAlreadyUsedException(String code) {
        super("Function code already in use: " + code);
    }
}
