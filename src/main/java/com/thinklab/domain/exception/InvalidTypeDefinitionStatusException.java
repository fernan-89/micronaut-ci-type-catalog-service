package com.thinklab.domain.exception;

/**
 * Domain Exception: Indicates an illegal lifecycle transition or a business-rule violation on a
 * {@link com.thinklab.domain.model.TypeDefinition} (for example, editing an ACTIVE schema, or
 * deactivating a definition that isn't ACTIVE).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019). The request is well formed but collides with
 * the aggregate's current state, the same contract used for every other state conflict on the platform.
 */
public class InvalidTypeDefinitionStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-CTC-00409";

    public InvalidTypeDefinitionStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
