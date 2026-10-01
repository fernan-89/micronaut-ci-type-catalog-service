package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TypeDefinitionDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TypeDefinitionDocument.TypeDefinitionPersistenceMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeDefinitionDocumentTest {

    @Test
    @DisplayName("toDocument / toDomain should round-trip the whole aggregate including the audit ledger")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        TypeDefinition typeDefinition = TypeDefinition.createNew(id, organisationId, CiCategory.SERVER,
                "{\"type\":\"object\"}", "ops");
        typeDefinition.activate("ops");

        TypeDefinitionDocument doc = TypeDefinitionPersistenceMapper.toDocument(typeDefinition);
        TypeDefinition restored = TypeDefinitionPersistenceMapper.toDomain(doc);

        assertEquals(id, doc.getId());
        assertEquals("ACTIVE", doc.getStatus());
        assertEquals("SERVER", doc.getCategory());
        assertEquals(2, doc.getAuditTrail().size());
        assertEquals(id, restored.getId());
        assertEquals(organisationId, restored.getOrganisationId());
        assertEquals(CiCategory.SERVER, restored.getCategory());
        assertEquals("{\"type\":\"object\"}", restored.getJsonSchema());
        assertEquals(TypeDefinitionStatus.ACTIVE, restored.getStatus());
        assertEquals(typeDefinition.getCreatedAt(), restored.getCreatedAt());
        assertEquals(typeDefinition.getUpdatedAt(), restored.getUpdatedAt());
        assertEquals(typeDefinition.getAuditTrail(), restored.getAuditTrail());
    }

    @Test
    @DisplayName("toDomain should default a missing status to DRAFT and a missing trail to empty")
    void defaultsWhenFieldsMissing() {
        TypeDefinitionDocument doc = new TypeDefinitionDocument();
        doc.setId(UUID.randomUUID());
        doc.setOrganisationId(UUID.randomUUID());
        doc.setCategory("LAPTOP");
        doc.setJsonSchema("{}");
        doc.setAuditTrail(null);

        TypeDefinition restored = TypeDefinitionPersistenceMapper.toDomain(doc);

        assertEquals(TypeDefinitionStatus.DRAFT, restored.getStatus());
        assertTrue(restored.getAuditTrail().isEmpty());
    }

    @Test
    @DisplayName("AuditEntryDocument should convert null statuses both ways")
    void auditEntryNullStatuses() {
        TypeDefinitionAuditEntry entry = new TypeDefinitionAuditEntry(Instant.parse("2026-03-01T10:00:00Z"),
                "INITIATED", "ops", null, TypeDefinitionStatus.DRAFT, "d");

        AuditEntryDocument doc = AuditEntryDocument.fromDomain(entry);

        assertNull(doc.fromStatus());
        assertEquals("DRAFT", doc.toStatus());
        assertEquals(entry, doc.toDomain());
    }

    @Test
    @DisplayName("the persistence mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<TypeDefinitionPersistenceMapper> constructor = TypeDefinitionPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }

    @Test
    @DisplayName("plain accessors should expose what was set (POJO codec contract)")
    void accessors() {
        TypeDefinitionDocument doc = new TypeDefinitionDocument();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        doc.setId(id);
        doc.setStatus("ACTIVE");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc.setJsonSchema("{}");

        assertEquals(id, doc.getId());
        assertEquals("ACTIVE", doc.getStatus());
        assertEquals(now, doc.getCreatedAt());
        assertEquals(now, doc.getUpdatedAt());
        assertEquals("{}", doc.getJsonSchema());
    }
}
