package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import io.micronaut.context.annotation.Property;
import com.thinklab.domain.exception.DuplicateTypeDefinitionException;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TypeDefinitionDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TypeDefinitionDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.TypeDefinitionDocument.TypeDefinitionPersistenceMapper;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter.
 * Implements the pure Domain Port using the low-level Reactive Streams MongoDB Driver and strictly
 * enforces Partial State Mutations: every transition is a single atomic {@code $set} + {@code $push}
 * that also appends the forensic audit entry, so state and ledger can never diverge.
 */
@Singleton
public class TypeDefinitionMongoRepositoryAdapter implements TypeDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(TypeDefinitionMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_ci_type_catalog_db";
    static final String COLLECTION_NAME = "type_definitions";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_AUDIT_TRAIL = "auditTrail";
    private static final String FIELD_ORGANISATION_ID = "organisationId";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public TypeDefinitionMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<TypeDefinitionDocument> getCollection() {
        return mongoClient.getDatabase(database)
                .getCollection(COLLECTION_NAME, TypeDefinitionDocument.class)
                .withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<TypeDefinition> create(TypeDefinition typeDefinition) {
        log.debug("[PERSISTENCE] Monolithic create for TypeDefinition Aggregate: {}", typeDefinition.getId());

        TypeDefinitionDocument document = TypeDefinitionPersistenceMapper.toDocument(typeDefinition);

        return Mono.from(getCollection().insertOne(document))
                .map(result -> typeDefinition);
    }

    @Override
    public Mono<TypeDefinition> findById(UUID id) {
        return Mono.from(getCollection().find(Filters.eq(FIELD_ID, id)).first())
                .map(TypeDefinitionPersistenceMapper::toDomain);
    }

    @Override
    public Flux<TypeDefinition> findAllByOrganisationId(UUID organisationId, CiCategory category, TypeDefinitionStatus status) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION_ID, organisationId));
        if (category != null) {
            filters.add(Filters.eq(FIELD_CATEGORY, category.name()));
        }
        if (status != null) {
            filters.add(Filters.eq(FIELD_STATUS, status.name()));
        }

        return Flux.from(getCollection().find(Filters.and(filters)))
                .map(TypeDefinitionPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> updateSchema(UUID id, String jsonSchema, TypeDefinitionAuditEntry auditEntry) {
        Bson update = Updates.combine(
                Updates.set("jsonSchema", jsonSchema),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    @Override
    public Mono<Void> updateStatus(UUID id, TypeDefinitionStatus status, TypeDefinitionAuditEntry auditEntry) {
        Bson update = Updates.combine(
                Updates.set(FIELD_STATUS, status.name()),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update)
                .onErrorMap(TypeDefinitionMongoRepositoryAdapter::isDuplicateActiveDefinition, e -> new DuplicateTypeDefinitionException(
                        "An ACTIVE TypeDefinition already exists for this organisation and category."));
    }

    @Override
    public Mono<Boolean> existsActiveByOrganisationIdAndCategory(UUID organisationId, CiCategory category) {
        Bson filter = Filters.and(
                Filters.eq(FIELD_ORGANISATION_ID, organisationId),
                Filters.eq(FIELD_CATEGORY, category.name()),
                Filters.eq(FIELD_STATUS, TypeDefinitionStatus.ACTIVE.name())
        );

        return Mono.from(getCollection().countDocuments(filter, new CountOptions().limit(1)))
                .map(count -> count > 0)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<TypeDefinition> findActiveByOrganisationIdAndCategory(UUID organisationId, CiCategory category) {
        Bson filter = Filters.and(
                Filters.eq(FIELD_ORGANISATION_ID, organisationId),
                Filters.eq(FIELD_CATEGORY, category.name()),
                Filters.eq(FIELD_STATUS, TypeDefinitionStatus.ACTIVE.name())
        );

        return Mono.from(getCollection().find(filter).first())
                .map(TypeDefinitionPersistenceMapper::toDomain);
    }

    /**
     * The losing update of two concurrent activations for the same organisation+category: the use
     * case's existence check passed for both, and the partial unique index
     * ({@link TypeDefinitionIndexInitializer}) rejected the second.
     */
    private static boolean isDuplicateActiveDefinition(Throwable error) {
        return error instanceof MongoWriteException write
                && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                && write.getError().getMessage().contains(TypeDefinitionIndexInitializer.ACTIVE_CATEGORY_INDEX);
    }

    private Mono<Void> executeUpdate(UUID id, Bson update) {
        return Mono.from(getCollection().updateOne(Filters.eq(FIELD_ID, id), update))
                .flatMap(result -> {
                    if (result.getMatchedCount() == 0) {
                        return Mono.error(new TypeDefinitionNotFoundException(id));
                    }
                    return Mono.empty();
                });
    }
}
