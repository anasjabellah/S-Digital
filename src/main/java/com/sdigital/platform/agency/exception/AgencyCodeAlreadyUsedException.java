package com.sdigital.platform.agency.exception;

/**
 * Thrown when an agency code is already used by another agency.
 */
public class AgencyCodeAlreadyUsedException extends RuntimeException {

    public AgencyCodeAlreadyUsedException(String code) {
        super("Agency code already in use: " + code);
    }
}
