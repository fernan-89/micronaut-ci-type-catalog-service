package com.thinklab.domain.repository;

import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for TypeDefinition persistence operations (CI Type Catalog Service Domain).
 * Part of the pure Domain Layer.
 *
 * <p>ARCHITECTURAL RULE: Partial State Mutations (ADR-002). Monolithic save operations are reserved
 * for aggregate creation. Every state transition is a granular update that atomically appends its
 * forensic {@link TypeDefinitionAuditEntry} to the ledger, so the audit trail can never diverge from
 * state. There is no {@code deleteById} — no physical delete exists in this Service Domain.
 */
public interface TypeDefinitionRepository {

    Mono<TypeDefinition> create(TypeDefinition typeDefinition);

    Mono<TypeDefinition> findById(UUID id);

    /**
     * Tenant-scoped listing, optionally filtered by category and/or status.
     */
    Flux<TypeDefinition> findAllByOrganisationId(UUID organisationId, CiCategory category, TypeDefinitionStatus status);

    Mono<Void> updateSchema(UUID id, String jsonSchema, TypeDefinitionAuditEntry auditEntry);

    Mono<Void> updateStatus(UUID id, TypeDefinitionStatus status, TypeDefinitionAuditEntry auditEntry);

    /**
     * Duplicate-prevention check, called explicitly from the use case before activating a definition.
     *
     * @return {@code true} if an ACTIVE definition already exists for this Organisation+Category.
     */
    Mono<Boolean> existsActiveByOrganisationIdAndCategory(UUID organisationId, CiCategory category);

    /**
     * The critical cross-service lookup: the single ACTIVE definition for a tenant+category, if any.
     * Backs {@code GET /active-schema/retrieve}.
     */
    Mono<TypeDefinition> findActiveByOrganisationIdAndCategory(UUID organisationId, CiCategory category);
}
