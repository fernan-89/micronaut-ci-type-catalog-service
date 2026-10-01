package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TypeDefinitionIndexInitializerTest {

    private final StartupEvent startup = mock(StartupEvent.class);

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collectionIn(MongoClient client, String database) {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase(database)).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("type_definitions")).thenReturn(collection);
        return collection;
    }

    @Test
    @DisplayName("startup creates the partial unique (organisationId, category) index scoped to ACTIVE, in the database named by mongodb.uri")
    void createsThePartialUniqueIndex() {
        MongoClient client = mock(MongoClient.class);
        MongoCollection<Document> collection = collectionIn(client, "tenant_catalog");
        when(collection.createIndex(any(), any(IndexOptions.class)))
                .thenReturn(Mono.just(TypeDefinitionIndexInitializer.ACTIVE_CATEGORY_INDEX));

        new TypeDefinitionIndexInitializer(client, "mongodb://mongo:27017/tenant_catalog").onApplicationEvent(startup);

        ArgumentCaptor<Document> keys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection).createIndex(keys.capture(), options.capture());
        assertEquals(new Document("organisationId", 1).append("category", 1), keys.getValue());
        assertTrue(options.getValue().isUnique());
        assertEquals(new Document("status", "ACTIVE"), options.getValue().getPartialFilterExpression());
        assertEquals(TypeDefinitionIndexInitializer.ACTIVE_CATEGORY_INDEX, options.getValue().getName());
    }

    @Test
    @DisplayName("a URI without a database uses the service default")
    void defaultDatabase() {
        MongoClient client = mock(MongoClient.class);
        MongoCollection<Document> collection = collectionIn(client, TypeDefinitionMongoRepositoryAdapter.DEFAULT_DATABASE);
        when(collection.createIndex(any(), any(IndexOptions.class)))
                .thenReturn(Mono.just(TypeDefinitionIndexInitializer.ACTIVE_CATEGORY_INDEX));

        new TypeDefinitionIndexInitializer(client, "mongodb://mongo:27017").onApplicationEvent(startup);

        verify(collection).createIndex(any(), any(IndexOptions.class));
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated")
    void failOpen() {
        MongoClient client = mock(MongoClient.class);
        MongoCollection<Document> collection = collectionIn(client, "catalog_db");
        when(collection.createIndex(any(), any(IndexOptions.class)))
                .thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("E11000 existing duplicates")));
        TypeDefinitionIndexInitializer initializer = new TypeDefinitionIndexInitializer(client,
                "mongodb://mongo:27017/catalog_db", Duration.ofSeconds(1));

        assertDoesNotThrow(() -> initializer.onApplicationEvent(startup));
        assertDoesNotThrow(() -> initializer.onApplicationEvent(startup));
    }

    @Test
    @DisplayName("collaborators, mongodb.uri and the startup event are null-checked")
    void guards() {
        MongoClient client = mock(MongoClient.class);
        assertThrows(NullPointerException.class, () -> new TypeDefinitionIndexInitializer(null, "mongodb://mongo:27017/a"));
        assertThrows(NullPointerException.class, () -> new TypeDefinitionIndexInitializer(client, null));
        assertThrows(NullPointerException.class, () -> new TypeDefinitionIndexInitializer(client, "mongodb://mongo:27017/a").onApplicationEvent(null));
    }
}
