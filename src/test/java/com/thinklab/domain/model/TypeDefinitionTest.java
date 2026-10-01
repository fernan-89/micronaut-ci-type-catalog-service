package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidTypeDefinitionStatusException;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeDefinitionTest {

    private static final String EXECUTOR = "catalog-admin";
    private static final String SCHEMA = "{\"type\":\"object\"}";

    private UUID id;
    private UUID organisationId;
    private TypeDefinition typeDefinition;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        organisationId = UUID.randomUUID();
        typeDefinition = TypeDefinition.createNew(id, organisationId, CiCategory.LAPTOP, SCHEMA, EXECUTOR);
    }

    // ---------------------------------------------------------------- creation

    @Test
    @DisplayName("Should create a new TypeDefinition in DRAFT state with an INITIATED audit entry")
    void shouldCreateInDraftState() {
        assertEquals(id, typeDefinition.getId());
        assertEquals(organisationId, typeDefinition.getOrganisationId());
        assertEquals(CiCategory.LAPTOP, typeDefinition.getCategory());
        assertEquals(SCHEMA, typeDefinition.getJsonSchema());
        assertEquals(TypeDefinitionStatus.DRAFT, typeDefinition.getStatus());
        assertNotNull(typeDefinition.getCreatedAt());
        assertEquals(typeDefinition.getCreatedAt(), typeDefinition.getUpdatedAt());

        assertEquals(1, typeDefinition.getAuditTrail().size());
        TypeDefinitionAuditEntry first = typeDefinition.getAuditTrail().get(0);
        assertEquals("INITIATED", first.action());
        assertEquals(EXECUTOR, first.executor());
        assertNull(first.fromStatus());
        assertEquals(TypeDefinitionStatus.DRAFT, first.toStatus());
    }

    @Test
    @DisplayName("Should reject creation when any mandatory field is missing")
    void shouldRejectInvalidCreation() {
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.createNew(null, organisationId, CiCategory.LAPTOP, SCHEMA, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.createNew(id, null, CiCategory.LAPTOP, SCHEMA, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.createNew(id, organisationId, null, SCHEMA, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.createNew(id, organisationId, CiCategory.LAPTOP, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.createNew(id, organisationId, CiCategory.LAPTOP, "  ", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.createNew(id, organisationId, CiCategory.LAPTOP, SCHEMA, null));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.createNew(id, organisationId, CiCategory.LAPTOP, SCHEMA, " "));
    }

    @Test
    @DisplayName("Should expose all ten CI categories, a local duplicate of AssetCategory")
    void shouldExposeCategories() {
        assertEquals(10, CiCategory.values().length);
        assertEquals(CiCategory.IOT_SENSOR, CiCategory.valueOf("IOT_SENSOR"));
    }

    // ---------------------------------------------------------------- reconstitution

    @Test
    @DisplayName("Should reconstitute a TypeDefinition from persisted state, defaulting missing status/timestamps/trail")
    void shouldReconstitute() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-02-01T00:00:00Z");
        List<TypeDefinitionAuditEntry> trail = new ArrayList<>();
        trail.add(new TypeDefinitionAuditEntry(created, "INITIATED", "x", null, TypeDefinitionStatus.DRAFT, "d"));

        TypeDefinition restored = TypeDefinition.reconstitute(id, organisationId, CiCategory.SERVER, SCHEMA,
                TypeDefinitionStatus.ACTIVE, created, updated, trail);

        assertEquals(TypeDefinitionStatus.ACTIVE, restored.getStatus());
        assertEquals(created, restored.getCreatedAt());
        assertEquals(updated, restored.getUpdatedAt());
        assertEquals(1, restored.getAuditTrail().size());

        TypeDefinition defaults = TypeDefinition.reconstitute(id, organisationId, CiCategory.SERVER, SCHEMA,
                null, null, null, null);
        assertEquals(TypeDefinitionStatus.DRAFT, defaults.getStatus());
        assertNotNull(defaults.getCreatedAt());
        assertEquals(defaults.getCreatedAt(), defaults.getUpdatedAt());
        assertTrue(defaults.getAuditTrail().isEmpty());
    }

    @Test
    @DisplayName("Should reject reconstitution without mandatory persisted fields")
    void shouldRejectInvalidReconstitution() {
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.reconstitute(null, organisationId, CiCategory.LAPTOP, SCHEMA, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.reconstitute(id, null, CiCategory.LAPTOP, SCHEMA, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.reconstitute(id, organisationId, null, SCHEMA, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> TypeDefinition.reconstitute(id, organisationId, CiCategory.LAPTOP, null, null, null, null, null));
    }

    // ---------------------------------------------------------------- updateSchema

    @Test
    @DisplayName("Should update the schema text while DRAFT and append an UPDATED audit entry")
    void shouldUpdateSchemaWhileDraft() {
        TypeDefinitionAuditEntry entry = typeDefinition.updateSchema("{\"type\":\"string\"}", "editor-2");

        assertEquals("{\"type\":\"string\"}", typeDefinition.getJsonSchema());
        assertEquals("UPDATED", entry.action());
        assertEquals("editor-2", entry.executor());
        assertEquals(TypeDefinitionStatus.DRAFT, entry.fromStatus());
        assertEquals(TypeDefinitionStatus.DRAFT, entry.toStatus());
        assertEquals(2, typeDefinition.getAuditTrail().size());
        assertSame(entry, typeDefinition.getAuditTrail().get(1));
    }

    @Test
    @DisplayName("Should update the schema text while INACTIVE")
    void shouldUpdateSchemaWhileInactive() {
        typeDefinition.activate(EXECUTOR);
        typeDefinition.deactivate(EXECUTOR);

        typeDefinition.updateSchema("{\"type\":\"string\"}", EXECUTOR);

        assertEquals("{\"type\":\"string\"}", typeDefinition.getJsonSchema());
    }

    @Test
    @DisplayName("Should reject editing the schema of an ACTIVE TypeDefinition")
    void shouldRejectUpdateWhileActive() {
        typeDefinition.activate(EXECUTOR);

        InvalidTypeDefinitionStatusException ex = assertThrows(InvalidTypeDefinitionStatusException.class,
                () -> typeDefinition.updateSchema("{\"type\":\"string\"}", EXECUTOR));

        assertEquals("ERR-CTC-00409", ex.getErrorCode());
        assertEquals(SCHEMA, typeDefinition.getJsonSchema());
    }

    @Test
    @DisplayName("Should reject an update with a blank schema or without an executor")
    void shouldRejectInvalidUpdate() {
        assertThrows(IllegalArgumentException.class, () -> typeDefinition.updateSchema(" ", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> typeDefinition.updateSchema(null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> typeDefinition.updateSchema(SCHEMA, null));
        assertThrows(IllegalArgumentException.class, () -> typeDefinition.updateSchema(SCHEMA, " "));
        assertEquals(1, typeDefinition.getAuditTrail().size());
    }

    // ---------------------------------------------------------------- activate / deactivate

    @Test
    @DisplayName("Should activate from DRAFT and append a STATUS_CHANGED audit entry")
    void shouldActivateFromDraft() {
        TypeDefinitionAuditEntry entry = typeDefinition.activate(EXECUTOR);

        assertEquals(TypeDefinitionStatus.ACTIVE, typeDefinition.getStatus());
        assertEquals("STATUS_CHANGED", entry.action());
        assertEquals(TypeDefinitionStatus.DRAFT, entry.fromStatus());
        assertEquals(TypeDefinitionStatus.ACTIVE, entry.toStatus());
    }

    @Test
    @DisplayName("Should activate from INACTIVE")
    void shouldActivateFromInactive() {
        typeDefinition.activate(EXECUTOR);
        typeDefinition.deactivate(EXECUTOR);

        TypeDefinitionAuditEntry entry = typeDefinition.activate(EXECUTOR);

        assertEquals(TypeDefinitionStatus.ACTIVE, typeDefinition.getStatus());
        assertEquals(TypeDefinitionStatus.INACTIVE, entry.fromStatus());
    }

    @Test
    @DisplayName("Should reject activating an already-ACTIVE definition as an idempotency violation")
    void shouldRejectActivatingTwice() {
        typeDefinition.activate(EXECUTOR);

        InvalidTypeDefinitionStatusException ex = assertThrows(InvalidTypeDefinitionStatusException.class,
                () -> typeDefinition.activate(EXECUTOR));

        assertTrue(ex.getMessage().contains("Idempotency Violation"));
    }

    @Test
    @DisplayName("Should reject activate without an executor")
    void shouldRejectActivateWithoutExecutor() {
        assertThrows(IllegalArgumentException.class, () -> typeDefinition.activate(null));
        assertThrows(IllegalArgumentException.class, () -> typeDefinition.activate(" "));
    }

    @Test
    @DisplayName("Should deactivate an ACTIVE definition")
    void shouldDeactivate() {
        typeDefinition.activate(EXECUTOR);

        TypeDefinitionAuditEntry entry = typeDefinition.deactivate(EXECUTOR);

        assertEquals(TypeDefinitionStatus.INACTIVE, typeDefinition.getStatus());
        assertEquals(TypeDefinitionStatus.ACTIVE, entry.fromStatus());
        assertEquals(TypeDefinitionStatus.INACTIVE, entry.toStatus());
    }

    @Test
    @DisplayName("Should reject deactivating a DRAFT or INACTIVE definition")
    void shouldRejectDeactivatingNonActive() {
        InvalidTypeDefinitionStatusException fromDraft = assertThrows(InvalidTypeDefinitionStatusException.class,
                () -> typeDefinition.deactivate(EXECUTOR));
        assertEquals("ERR-CTC-00409", fromDraft.getErrorCode());

        typeDefinition.activate(EXECUTOR);
        typeDefinition.deactivate(EXECUTOR);
        assertThrows(InvalidTypeDefinitionStatusException.class, () -> typeDefinition.deactivate(EXECUTOR));
    }

    @Test
    @DisplayName("Should reject deactivate without an executor")
    void shouldRejectDeactivateWithoutExecutor() {
        typeDefinition.activate(EXECUTOR);

        assertThrows(IllegalArgumentException.class, () -> typeDefinition.deactivate(null));
        assertThrows(IllegalArgumentException.class, () -> typeDefinition.deactivate(" "));
    }
}
