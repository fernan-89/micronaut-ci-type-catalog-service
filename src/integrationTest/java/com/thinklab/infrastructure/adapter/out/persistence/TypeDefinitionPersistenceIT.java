package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateTypeDefinitionException;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TypeDefinition aggregate through its repository against a real MongoDB: every granular update,
 * tenant-scoped filtering, not-found handling, the database taken from {@code mongodb.uri}, and the
 * partial unique index {@link com.thinklab.infrastructure.adapter.out.persistence.repository.TypeDefinitionIndexInitializer}
 * creates at startup - including the real concurrent-activation race it guards against.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypeDefinitionPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "ci_type_catalog_it";
    private static final String EXECUTOR = "catalog-admin";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject
    TypeDefinitionRepository repository;

    @Inject
    MongoClient mongoClient;

    private static TypeDefinition newDefinition(UUID organisationId, CiCategory category) {
        return TypeDefinition.createNew(UUID.randomUUID(), organisationId, category, "{\"type\":\"object\"}", EXECUTOR);
    }

    @Test
    @DisplayName("a created TypeDefinition is read back as-is")
    void createAndFind() {
        UUID organisationId = UUID.randomUUID();
        TypeDefinition created = repository.create(newDefinition(organisationId, CiCategory.LAPTOP)).block();

        TypeDefinition found = repository.findById(created.getId()).block();

        assertEquals(TypeDefinitionStatus.DRAFT, found.getStatus());
        assertEquals(CiCategory.LAPTOP, found.getCategory());
        assertEquals(1, found.getAuditTrail().size());
        assertNotNull(found.getCreatedAt());
    }

    @Test
    @DisplayName("updateSchema persists the new schema text and appends the audit entry")
    void updateSchema() {
        TypeDefinition created = repository.create(newDefinition(UUID.randomUUID(), CiCategory.SERVER)).block();
        TypeDefinitionAuditEntry entry = new TypeDefinitionAuditEntry(
                java.time.Instant.now(), "UPDATED", EXECUTOR, TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.DRAFT, "edited");

        repository.updateSchema(created.getId(), "{\"type\":\"string\"}", entry).block();

        TypeDefinition found = repository.findById(created.getId()).block();
        assertEquals("{\"type\":\"string\"}", found.getJsonSchema());
        assertEquals(2, found.getAuditTrail().size());
    }

    @Test
    @DisplayName("updateStatus persists the transition and appends the audit entry")
    void updateStatus() {
        TypeDefinition created = repository.create(newDefinition(UUID.randomUUID(), CiCategory.NETWORK_DEVICE)).block();
        TypeDefinitionAuditEntry entry = new TypeDefinitionAuditEntry(
                java.time.Instant.now(), "STATUS_CHANGED", EXECUTOR, TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.ACTIVE, "activated");

        repository.updateStatus(created.getId(), TypeDefinitionStatus.ACTIVE, entry).block();

        TypeDefinition found = repository.findById(created.getId()).block();
        assertEquals(TypeDefinitionStatus.ACTIVE, found.getStatus());
    }

    @Test
    @DisplayName("the partial unique (organisationId, category) index rejects a second concurrent ACTIVE for the same category")
    void duplicateActiveIsRejectedByTheIndex() {
        UUID organisationId = UUID.randomUUID();
        TypeDefinition first = repository.create(newDefinition(organisationId, CiCategory.STORAGE_ARRAY)).block();
        TypeDefinition second = repository.create(newDefinition(organisationId, CiCategory.STORAGE_ARRAY)).block();
        TypeDefinitionAuditEntry activateEntry = new TypeDefinitionAuditEntry(
                java.time.Instant.now(), "STATUS_CHANGED", EXECUTOR, TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.ACTIVE, "activated");

        repository.updateStatus(first.getId(), TypeDefinitionStatus.ACTIVE, activateEntry).block();

        assertThrows(DuplicateTypeDefinitionException.class,
                () -> repository.updateStatus(second.getId(), TypeDefinitionStatus.ACTIVE, activateEntry).block());
    }

    @Test
    @DisplayName("a DRAFT/INACTIVE sibling for the same category never collides with an unrelated ACTIVE one")
    void nonActiveSiblingsDoNotCollide() {
        UUID organisationId = UUID.randomUUID();
        TypeDefinition active = repository.create(newDefinition(organisationId, CiCategory.IOT_SENSOR)).block();
        TypeDefinition draft = repository.create(newDefinition(organisationId, CiCategory.IOT_SENSOR)).block();
        TypeDefinitionAuditEntry activateEntry = new TypeDefinitionAuditEntry(
                java.time.Instant.now(), "STATUS_CHANGED", EXECUTOR, TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.ACTIVE, "activated");
        repository.updateStatus(active.getId(), TypeDefinitionStatus.ACTIVE, activateEntry).block();

        TypeDefinition stillDraft = repository.findById(draft.getId()).block();
        assertEquals(TypeDefinitionStatus.DRAFT, stillDraft.getStatus());
    }

    @Test
    @DisplayName("existsActiveByOrganisationIdAndCategory reflects the real ACTIVE state")
    void existsActiveReflectsRealState() {
        UUID organisationId = UUID.randomUUID();
        TypeDefinition created = repository.create(newDefinition(organisationId, CiCategory.MOBILE_DEVICE)).block();

        assertEquals(false, repository.existsActiveByOrganisationIdAndCategory(organisationId, CiCategory.MOBILE_DEVICE).block());

        TypeDefinitionAuditEntry activateEntry = new TypeDefinitionAuditEntry(
                java.time.Instant.now(), "STATUS_CHANGED", EXECUTOR, TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.ACTIVE, "activated");
        repository.updateStatus(created.getId(), TypeDefinitionStatus.ACTIVE, activateEntry).block();

        assertEquals(true, repository.existsActiveByOrganisationIdAndCategory(organisationId, CiCategory.MOBILE_DEVICE).block());
    }

    @Test
    @DisplayName("findActiveByOrganisationIdAndCategory returns the real ACTIVE document, or empty when none")
    void findActiveReflectsRealState() {
        UUID organisationId = UUID.randomUUID();
        TypeDefinition created = repository.create(newDefinition(organisationId, CiCategory.VIRTUAL_MACHINE)).block();

        assertNull(repository.findActiveByOrganisationIdAndCategory(organisationId, CiCategory.VIRTUAL_MACHINE).block());

        TypeDefinitionAuditEntry activateEntry = new TypeDefinitionAuditEntry(
                java.time.Instant.now(), "STATUS_CHANGED", EXECUTOR, TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.ACTIVE, "activated");
        repository.updateStatus(created.getId(), TypeDefinitionStatus.ACTIVE, activateEntry).block();

        TypeDefinition active = repository.findActiveByOrganisationIdAndCategory(organisationId, CiCategory.VIRTUAL_MACHINE).block();
        assertEquals(created.getId(), active.getId());
    }

    @Test
    @DisplayName("listing is tenant-scoped and honours the optional category/status filters")
    void listingFilters() {
        UUID organisation = UUID.randomUUID();
        TypeDefinition laptop = repository.create(newDefinition(organisation, CiCategory.LAPTOP)).block();
        TypeDefinition server = repository.create(newDefinition(organisation, CiCategory.SERVER)).block();
        repository.create(newDefinition(UUID.randomUUID(), CiCategory.LAPTOP)).block();

        assertEquals(Set.of(laptop.getId(), server.getId()),
                ids(repository.findAllByOrganisationId(organisation, null, null).collectList().block()));
        assertEquals(Set.of(laptop.getId()),
                ids(repository.findAllByOrganisationId(organisation, CiCategory.LAPTOP, null).collectList().block()));
        assertEquals(Set.of(laptop.getId(), server.getId()),
                ids(repository.findAllByOrganisationId(organisation, null, TypeDefinitionStatus.DRAFT).collectList().block()));
    }

    @Test
    @DisplayName("an unknown TypeDefinition is empty on read and TypeDefinitionNotFoundException on update")
    void notFound() {
        UUID unknown = UUID.randomUUID();
        TypeDefinitionAuditEntry entry = new TypeDefinitionAuditEntry(
                java.time.Instant.now(), "UPDATED", EXECUTOR, TypeDefinitionStatus.DRAFT, TypeDefinitionStatus.DRAFT, "x");

        assertNull(repository.findById(unknown).block());
        assertThrows(TypeDefinitionNotFoundException.class, () -> repository.updateSchema(unknown, "{}", entry).block());
    }

    @Test
    @DisplayName("the partial unique (organisationId, category) index exists on type_definitions, scoped to ACTIVE")
    void partialIndexExists() {
        repository.create(newDefinition(UUID.randomUUID(), CiCategory.PERIPHERAL)).block();

        List<Document> indexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("type_definitions").listIndexes()).collectList().block();

        assertTrue(indexes.stream().anyMatch(index ->
                        new Document("organisationId", 1).append("category", 1).equals(index.get("key", Document.class))
                                && new Document("status", "ACTIVE").equals(index.get("partialFilterExpression", Document.class))),
                () -> "type_definitions: " + indexes);
    }

    private static Set<UUID> ids(List<TypeDefinition> list) {
        return list.stream().map(TypeDefinition::getId).collect(Collectors.toSet());
    }
}
