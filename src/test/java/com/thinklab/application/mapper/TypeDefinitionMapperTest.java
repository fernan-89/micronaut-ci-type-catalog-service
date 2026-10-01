package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.InitiateTypeDefinitionRequest;
import com.thinklab.application.dto.response.ActiveSchemaResponse;
import com.thinklab.application.dto.response.TypeDefinitionAuditEntryResponse;
import com.thinklab.application.dto.response.TypeDefinitionResponse;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeDefinitionMapperTest {

    @Test
    @DisplayName("toDomain should build a DRAFT aggregate from the request, ID and tenant")
    void toDomain() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        InitiateTypeDefinitionRequest request = new InitiateTypeDefinitionRequest(CiCategory.STORAGE_ARRAY, "{\"type\":\"object\"}");

        TypeDefinition typeDefinition = TypeDefinitionMapper.toDomain(request, id, organisationId, "exec");

        assertEquals(id, typeDefinition.getId());
        assertEquals(organisationId, typeDefinition.getOrganisationId());
        assertEquals(CiCategory.STORAGE_ARRAY, typeDefinition.getCategory());
        assertEquals(TypeDefinitionStatus.DRAFT, typeDefinition.getStatus());
        assertEquals("exec", typeDefinition.getAuditTrail().get(0).executor());
    }

    @Test
    @DisplayName("toResponse should flatten enums to names and copy every field")
    void toResponse() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        TypeDefinition typeDefinition = TypeDefinition.createNew(id, organisationId, CiCategory.STORAGE_ARRAY,
                "{\"type\":\"object\"}", "exec");

        TypeDefinitionResponse response = TypeDefinitionMapper.toResponse(typeDefinition);

        assertEquals(id, response.id());
        assertEquals(organisationId, response.organisationId());
        assertEquals("STORAGE_ARRAY", response.category());
        assertEquals("{\"type\":\"object\"}", response.jsonSchema());
        assertEquals("DRAFT", response.status());
        assertEquals(typeDefinition.getCreatedAt(), response.createdAt());
        assertEquals(typeDefinition.getUpdatedAt(), response.updatedAt());
    }

    @Test
    @DisplayName("toResponse(audit entry) should tolerate a null fromStatus (initiating entry)")
    void auditEntryToResponse() {
        Instant now = Instant.now();

        TypeDefinitionAuditEntryResponse initiating = TypeDefinitionMapper.toResponse(
                new TypeDefinitionAuditEntry(now, "INITIATED", "exec", null, TypeDefinitionStatus.DRAFT, "created"));
        TypeDefinitionAuditEntryResponse transition = TypeDefinitionMapper.toResponse(
                new TypeDefinitionAuditEntry(now, "STATUS_CHANGED", "exec", TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.ACTIVE, "activated"));

        assertNull(initiating.fromStatus());
        assertEquals("DRAFT", initiating.toStatus());
        assertEquals("INITIATED", initiating.action());
        assertEquals(now, initiating.occurredAt());
        assertEquals("DRAFT", transition.fromStatus());
        assertEquals("ACTIVE", transition.toStatus());
        assertEquals("activated", transition.detail());
    }

    @Test
    @DisplayName("toActiveSchemaResponse should copy the id, category, schema text and updatedAt")
    void toActiveSchemaResponse() {
        UUID id = UUID.randomUUID();
        TypeDefinition typeDefinition = TypeDefinition.createNew(id, UUID.randomUUID(), CiCategory.SERVER,
                "{\"type\":\"object\"}", "exec");
        typeDefinition.activate("exec");

        ActiveSchemaResponse response = TypeDefinitionMapper.toActiveSchemaResponse(typeDefinition);

        assertEquals(id, response.id());
        assertEquals("SERVER", response.category());
        assertEquals("{\"type\":\"object\"}", response.jsonSchema());
        assertEquals(typeDefinition.getUpdatedAt(), response.updatedAt());
    }

    @Test
    @DisplayName("The mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<TypeDefinitionMapper> constructor = TypeDefinitionMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }
}
