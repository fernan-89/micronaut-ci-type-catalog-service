package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

/** DTO for TypeDefinition schema edits (BIAN Behavior Qualifier: {@code update}). */
@Serdeable
public record UpdateTypeDefinitionRequest(

        @NotBlank(message = "JSON Schema is required")
        String jsonSchema
) {}
