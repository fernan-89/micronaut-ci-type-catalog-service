package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when a TypeDefinition is activated while another TypeDefinition for the
 * same Organisation and {@link com.thinklab.domain.model.TypeDefinition.CiCategory} is already ACTIVE
 * (ADR-032: at most one ACTIVE definition per organisation+category).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateTypeDefinitionException extends BusinessException {

    private static final String ERROR_CODE = "ERR-CTC-00409";

    public DuplicateTypeDefinitionException(String message) {
        super(ERROR_CODE, message);
    }
}
