package com.thinklab.application.dto.request;

import com.thinklab.domain.model.TypeDefinition.CiCategory;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for TypeDefinition creation (BIAN Behavior Qualifier: {@code initiate}). Protective barrier to
 * the Domain Layer. organisationId travels via the {@code X-Tenant-Id} header, not the body.
 */
@Serdeable
public record InitiateTypeDefinitionRequest(

        @NotNull(message = "Category is required")
        CiCategory category,

        @NotBlank(message = "JSON Schema is required")
        String jsonSchema
) {}
