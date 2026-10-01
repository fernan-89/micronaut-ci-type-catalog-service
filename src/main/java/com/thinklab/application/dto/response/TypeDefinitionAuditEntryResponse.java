package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** DTO for a single immutable forensic ledger entry of a TypeDefinition. */
@Serdeable
public record TypeDefinitionAuditEntryResponse(
        Instant occurredAt,
        String action,
        String executor,
        String fromStatus,
        String toStatus,
        String detail
) {}
