package com.thinklab.infrastructure.adapter.out.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.thinklab.domain.exception.InvalidJsonSchemaDefinitionException;
import com.thinklab.domain.port.JsonSchemaValidatorPort;
import jakarta.inject.Singleton;

/**
 * Validates that a supplied string is a well-formed JSON Schema document, using
 * {@code networknt/json-schema-validator} (draft 2020-12). Compiling the schema is enough to catch
 * malformed JSON and structurally invalid schemas eagerly, at write time — before a definition can
 * ever become ACTIVE and silently break every future validation call against it.
 */
@Singleton
public class NetworkNtJsonSchemaValidatorAdapter implements JsonSchemaValidatorPort {

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void validateSyntax(String jsonSchema) {
        try {
            JsonNode schemaNode = objectMapper.readTree(jsonSchema);
            factory.getSchema(schemaNode);
        } catch (RuntimeException | java.io.IOException e) {
            throw new InvalidJsonSchemaDefinitionException(
                    "The supplied text is not a valid JSON Schema document: " + e.getMessage(), e);
        }
    }
}
