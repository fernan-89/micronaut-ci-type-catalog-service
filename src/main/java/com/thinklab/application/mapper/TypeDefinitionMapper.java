package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.InitiateTypeDefinitionRequest;
import com.thinklab.application.dto.response.ActiveSchemaResponse;
import com.thinklab.application.dto.response.TypeDefinitionAuditEntryResponse;
import com.thinklab.application.dto.response.TypeDefinitionResponse;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;

import java.util.UUID;

/**
 * Static factory mapper for TypeDefinition DTOs and Domain Entities. Enforces the DTO Isolation
 * Pattern.
 */
public final class TypeDefinitionMapper {

    private TypeDefinitionMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static TypeDefinition toDomain(InitiateTypeDefinitionRequest request, UUID sovereignId,
                                           UUID organisationId, String executor) {
        return TypeDefinition.createNew(sovereignId, organisationId, request.category(), request.jsonSchema(), executor);
    }

    public static TypeDefinitionResponse toResponse(TypeDefinition typeDefinition) {
        return new TypeDefinitionResponse(
                typeDefinition.getId(),
                typeDefinition.getOrganisationId(),
                typeDefinition.getCategory().name(),
                typeDefinition.getJsonSchema(),
                typeDefinition.getStatus().name(),
                typeDefinition.getCreatedAt(),
                typeDefinition.getUpdatedAt()
        );
    }

    public static TypeDefinitionAuditEntryResponse toResponse(TypeDefinitionAuditEntry entry) {
        return new TypeDefinitionAuditEntryResponse(
                entry.occurredAt(),
                entry.action(),
                entry.executor(),
                entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(),
                entry.detail()
        );
    }

    public static ActiveSchemaResponse toActiveSchemaResponse(TypeDefinition typeDefinition) {
        return new ActiveSchemaResponse(
                typeDefinition.getId(),
                typeDefinition.getCategory().name(),
                typeDefinition.getJsonSchema(),
                typeDefinition.getUpdatedAt()
        );
    }
}
