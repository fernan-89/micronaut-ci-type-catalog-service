package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when the JSON Schema text supplied to {@code initiate}/{@code update}
 * cannot be parsed as a valid JSON Schema document. Checked eagerly, at write time, so a malformed
 * schema can never reach {@code ACTIVE} and silently break every future validation call against it.
 *
 * <p>RFC 7807 mapping: HTTP 400 Bad Request — malformed content, not a state conflict.
 */
public class InvalidJsonSchemaDefinitionException extends BusinessException {

    private static final String ERROR_CODE = "ERR-CTC-00400";

    public InvalidJsonSchemaDefinitionException(String message, Throwable cause) {
        super(ERROR_CODE, message, cause);
    }
}
