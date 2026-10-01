package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateTypeDefinitionException;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TypeDefinitionDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TypeDefinitionDocument.TypeDefinitionPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class TypeDefinitionMongoRepositoryAdapterTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<TypeDefinitionDocument> mongoCollection;

    private TypeDefinitionMongoRepositoryAdapter adapter;
    private UUID organisationId;
    private UUID typeDefinitionId;
    private TypeDefinition typeDefinition;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("thinklab_ci_type_catalog_db")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("type_definitions", TypeDefinitionDocument.class)).thenReturn(mongoCollection);
        when(mongoCollection.withCodecRegistry(any())).thenReturn(mongoCollection);
        adapter = new TypeDefinitionMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/thinklab_ci_type_catalog_db");

        organisationId = UUID.randomUUID();
        typeDefinitionId = UUID.randomUUID();
        typeDefinition = TypeDefinition.createNew(typeDefinitionId, organisationId, CiCategory.NETWORK_DEVICE,
                "{\"type\":\"object\"}", "catalog-admin");
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, REGISTRY);
    }

    private static MongoWriteException writeError(int code, String message) {
        return new MongoWriteException(new WriteError(code, message, new BsonDocument()), new ServerAddress());
    }

    @Test
    @DisplayName("create should insert the mapped document and emit the aggregate")
    void createSuccess() {
        when(mongoCollection.insertOne(any(TypeDefinitionDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(adapter.create(typeDefinition))
                .expectNextMatches(saved -> saved.getId().equals(typeDefinitionId))
                .verifyComplete();

        ArgumentCaptor<TypeDefinitionDocument> captor = ArgumentCaptor.forClass(TypeDefinitionDocument.class);
        verify(mongoCollection).insertOne(captor.capture());
        assertEquals("DRAFT", captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getAuditTrail().size());
    }

    @Test
    @DisplayName("findById should map the document back to the aggregate")
    void findByIdSuccess() {
        FindPublisher<TypeDefinitionDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(TypeDefinitionPersistenceMapper.toDocument(typeDefinition)));

        StepVerifier.create(adapter.findById(typeDefinitionId))
                .expectNextMatches(found -> found.getId().equals(typeDefinitionId)
                        && found.getStatus() == TypeDefinitionStatus.DRAFT)
                .verifyComplete();
    }

    @Test
    @DisplayName("findById should complete empty when the type definition does not exist")
    void findByIdEmpty() {
        FindPublisher<TypeDefinitionDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findById(typeDefinitionId)).verifyComplete();
    }

    @Test
    @DisplayName("findAllByOrganisationId should always filter by tenant and add category/status when given")
    void findAllFilters() {
        FindPublisher<TypeDefinitionDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<TypeDefinitionDocument> subscriber = invocation.getArgument(0);
            Flux.just(TypeDefinitionPersistenceMapper.toDocument(typeDefinition)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAllByOrganisationId(organisationId, CiCategory.NETWORK_DEVICE, TypeDefinitionStatus.DRAFT))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(captor.capture());
        String rendered = render(captor.getValue()).toJson();
        assertTrue(rendered.contains("organisationId"));
        assertTrue(rendered.contains("NETWORK_DEVICE"));
        assertTrue(rendered.contains("DRAFT"));
    }

    @Test
    @DisplayName("findAllByOrganisationId without optional filters should only constrain the tenant")
    void findAllTenantOnly() {
        FindPublisher<TypeDefinitionDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<TypeDefinitionDocument> subscriber = invocation.getArgument(0);
            Flux.<TypeDefinitionDocument>empty().subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAllByOrganisationId(organisationId, null, null)).verifyComplete();

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(captor.capture());
        String rendered = render(captor.getValue()).toJson();
        assertTrue(rendered.contains("organisationId"));
        assertTrue(!rendered.contains("category") && !rendered.contains("status"));
    }

    @Test
    @DisplayName("updateSchema should $set jsonSchema/updatedAt and $push the audit entry atomically")
    void updateSchema() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        TypeDefinitionAuditEntry auditEntry = typeDefinition.updateSchema("{\"type\":\"string\"}", "tech");

        StepVerifier.create(adapter.updateSchema(typeDefinitionId, "{\"type\":\"string\"}", auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("{\"type\":\"string\"}", doc.getDocument("$set").getString("jsonSchema").getValue());
        assertTrue(doc.getDocument("$set").containsKey("updatedAt"));
        assertEquals("UPDATED", doc.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("updateStatus should $set status and $push a STATUS_CHANGED audit entry")
    void updateStatus() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        TypeDefinitionAuditEntry auditEntry = typeDefinition.activate("tech");

        StepVerifier.create(adapter.updateStatus(typeDefinitionId, TypeDefinitionStatus.ACTIVE, auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("ACTIVE", doc.getDocument("$set").getString("status").getValue());
        BsonDocument pushed = doc.getDocument("$push").getDocument("auditTrail");
        assertEquals("STATUS_CHANGED", pushed.getString("action").getValue());
        assertEquals("DRAFT", pushed.getString("fromStatus").getValue());
        assertEquals("ACTIVE", pushed.getString("toStatus").getValue());
    }

    @Test
    @DisplayName("updateStatus maps a duplicate on the active-category index to DuplicateTypeDefinitionException (the concurrent-activate race)")
    void updateStatusDuplicateActive() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.error(writeError(11000,
                "E11000 duplicate key error collection: thinklab_ci_type_catalog_db.type_definitions index: organisationId_1_category_1_active dup key")));
        TypeDefinitionAuditEntry auditEntry = typeDefinition.activate("tech");

        StepVerifier.create(adapter.updateStatus(typeDefinitionId, TypeDefinitionStatus.ACTIVE, auditEntry))
                .expectError(DuplicateTypeDefinitionException.class)
                .verify();
    }

    @Test
    @DisplayName("updateStatus propagates other write errors, including a duplicate on another index and a non-duplicate-key category")
    void updateStatusOtherWriteErrors() {
        MongoWriteException duplicateId = writeError(11000, "E11000 duplicate key error collection: thinklab_ci_type_catalog_db.type_definitions index: _id_ dup key");
        MongoWriteException validation = writeError(121, "Document failed validation index: organisationId_1_category_1_active");
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.error(duplicateId)).thenReturn(Mono.error(validation));
        TypeDefinitionAuditEntry auditEntry = typeDefinition.activate("tech");

        StepVerifier.create(adapter.updateStatus(typeDefinitionId, TypeDefinitionStatus.ACTIVE, auditEntry))
                .expectErrorMatches(e -> e == duplicateId)
                .verify();
        StepVerifier.create(adapter.updateStatus(typeDefinitionId, TypeDefinitionStatus.ACTIVE, auditEntry))
                .expectErrorMatches(e -> e == validation)
                .verify();
    }

    @Test
    @DisplayName("every partial update should fail with TypeDefinitionNotFoundException when no document matches")
    void updatesFailWhenNothingMatches() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        TypeDefinitionAuditEntry auditEntry = typeDefinition.activate("tech");

        StepVerifier.create(adapter.updateStatus(typeDefinitionId, TypeDefinitionStatus.ACTIVE, auditEntry))
                .expectError(TypeDefinitionNotFoundException.class).verify();
        StepVerifier.create(adapter.updateSchema(typeDefinitionId, "{}", auditEntry))
                .expectError(TypeDefinitionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("existsActiveByOrganisationIdAndCategory should be true when the count is positive")
    void existsActiveTrue() {
        when(mongoCollection.countDocuments(any(Bson.class), any(CountOptions.class))).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.existsActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("existsActiveByOrganisationIdAndCategory should be false when the count is zero or the publisher is empty")
    void existsActiveFalse() {
        when(mongoCollection.countDocuments(any(Bson.class), any(CountOptions.class)))
                .thenReturn(Mono.just(0L))
                .thenReturn(Mono.empty());

        StepVerifier.create(adapter.existsActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .expectNext(false).verifyComplete();
        StepVerifier.create(adapter.existsActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("findActiveByOrganisationIdAndCategory should map the found ACTIVE document back to the aggregate")
    void findActiveSuccess() {
        typeDefinition.activate("tech");
        FindPublisher<TypeDefinitionDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(TypeDefinitionPersistenceMapper.toDocument(typeDefinition)));

        StepVerifier.create(adapter.findActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .expectNextMatches(found -> found.getStatus() == TypeDefinitionStatus.ACTIVE)
                .verifyComplete();
    }

    @Test
    @DisplayName("findActiveByOrganisationIdAndCategory should complete empty when none is ACTIVE")
    void findActiveEmpty() {
        FindPublisher<TypeDefinitionDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .verifyComplete();
    }
}
