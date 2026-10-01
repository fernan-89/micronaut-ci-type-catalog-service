package com.thinklab.domain.port;

/**
 * Outbound Port for JSON Schema syntax validation. This service only authors and stores schemas — it
 * never validates a payload against one (that happens in each consuming service, starting with
 * {@code it-asset-registry}'s own {@code SpecificationValidatorPort}, which fetches the raw schema
 * text from this service's {@code /active-schema/retrieve} and validates locally).
 */
public interface JsonSchemaValidatorPort {

    /**
     * @throws com.thinklab.domain.exception.InvalidJsonSchemaDefinitionException if {@code jsonSchema}
     *                                                                            cannot be parsed as a JSON Schema document.
     */
    void validateSyntax(String jsonSchema);
}
