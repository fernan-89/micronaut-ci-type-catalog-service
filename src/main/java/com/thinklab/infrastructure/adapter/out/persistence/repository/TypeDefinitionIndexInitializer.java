package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates the partial unique {@code (organisationId, category)} index on {@code type_definitions} at
 * startup, scoped to {@code status: "ACTIVE"} documents only (ADR-032: at most one ACTIVE definition
 * per organisation+category).
 *
 * <p>The use case checks for an existing ACTIVE definition before activating, but check-then-activate
 * is not atomic: two concurrent activations could both pass the check. The partial unique index makes
 * the database the arbiter, and {@link TypeDefinitionMongoRepositoryAdapter} turns the losing update
 * into the same {@code DuplicateTypeDefinitionException} (409) the check produces. A partial filter
 * (not a plain unique index) is required because {@code DRAFT}/{@code INACTIVE} documents for the same
 * organisation+category are expected and legal — only two simultaneously {@code ACTIVE} ones collide.
 *
 * <p>Fail-open, like {@code AssetIndexInitializer}: {@code createIndex} is idempotent; if it fails the
 * error is logged and the application still starts. Turn it off with
 * {@code thinklab.mongo.create-indexes=false}, as unit-test contexts without MongoDB do.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class TypeDefinitionIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String ACTIVE_CATEGORY_INDEX = "organisationId_1_category_1_active";

    private static final Logger log = LoggerFactory.getLogger(TypeDefinitionIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public TypeDefinitionIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    /** Test seam: how long to wait for the server. */
    TypeDefinitionIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : TypeDefinitionMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        Document keys = new Document("organisationId", 1).append("category", 1);
        Document partialFilter = new Document("status", "ACTIVE");
        try {
            Mono.from(mongoClient.getDatabase(database)
                    .getCollection(TypeDefinitionMongoRepositoryAdapter.COLLECTION_NAME)
                    .createIndex(keys, new IndexOptions().unique(true).partialFilterExpression(partialFilter)
                            .name(ACTIVE_CATEGORY_INDEX)))
                    .block(timeout);
            log.info("[MONGO_INDEXES] Ensured partial unique index [{}] on [{}.{}]", ACTIVE_CATEGORY_INDEX, database,
                    TypeDefinitionMongoRepositoryAdapter.COLLECTION_NAME);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", ACTIVE_CATEGORY_INDEX, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create partial unique index [{}] on [{}.{}] (existing duplicate ACTIVE definitions?): {}",
                    ACTIVE_CATEGORY_INDEX, database, TypeDefinitionMongoRepositoryAdapter.COLLECTION_NAME, e.getMessage());
        }
    }
}
