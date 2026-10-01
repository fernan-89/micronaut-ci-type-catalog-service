package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for TypeDefinition output payload (CI Type Catalog Control Record). Enforces the DTO Isolation
 * Pattern by preventing the pure Domain Model from bleeding out to the HTTP boundary.
 */
@Serdeable
public record TypeDefinitionResponse(
        UUID id,
        UUID organisationId,
        String category,
        String jsonSchema,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
