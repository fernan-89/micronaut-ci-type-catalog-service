package com.thinklab.infrastructure.adapter.out.validation;

import com.thinklab.domain.exception.InvalidJsonSchemaDefinitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkNtJsonSchemaValidatorAdapterTest {

    private final NetworkNtJsonSchemaValidatorAdapter adapter = new NetworkNtJsonSchemaValidatorAdapter();

    @Test
    @DisplayName("should accept a well-formed JSON Schema document")
    void acceptsValidSchema() {
        assertDoesNotThrow(() -> adapter.validateSyntax(
                "{\"type\":\"object\",\"properties\":{\"cpu\":{\"type\":\"string\"}},\"required\":[\"cpu\"]}"));
    }

    @Test
    @DisplayName("should reject text that is not valid JSON at all")
    void rejectsMalformedJson() {
        InvalidJsonSchemaDefinitionException ex = assertThrows(InvalidJsonSchemaDefinitionException.class,
                () -> adapter.validateSyntax("{not json"));

        org.junit.jupiter.api.Assertions.assertEquals("ERR-CTC-00400", ex.getErrorCode());
    }
}
