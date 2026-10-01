package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for the critical cross-service lookup ({@code GET /active-schema/retrieve}): the single ACTIVE
 * definition for a tenant+category, if any. A 404 (no body) means "no schema configured" — every
 * consuming service treats that as "skip validation," not an error.
 */
@Serdeable
public record ActiveSchemaResponse(
        UUID id,
        String category,
        String jsonSchema,
        Instant updatedAt
) {}
