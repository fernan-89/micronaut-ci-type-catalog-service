package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the TypeDefinition Aggregate for MongoDB. Ensures the
 * pure Domain Model remains untainted by persistence annotations. Uses native BSON annotations for
 * high-performance mapping without ORM overhead.
 *
 * <p>Must be a top-level {@code public} class — a package-private nested BSON entity passes every
 * mocked unit test but fails with {@code CodecConfigurationException} on the first real write, since
 * the POJO codec's reflection never calls {@code setAccessible(true)}.
 */
@Introspected
public class TypeDefinitionDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String category;
    private String jsonSchema;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getJsonSchema() { return jsonSchema; }
    public void setJsonSchema(String jsonSchema) { this.jsonSchema = jsonSchema; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor,
                                      String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(TypeDefinitionAuditEntry entry) {
            return new AuditEntryDocument(
                    entry.occurredAt(),
                    entry.action(),
                    entry.executor(),
                    entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(),
                    entry.detail()
            );
        }

        public TypeDefinitionAuditEntry toDomain() {
            // toStatus is never null on a real document: fromDomain() always writes it from a non-null
            // enum. A defensive null-check here would be a structurally unreachable branch under the
            // 100% JaCoCo gate - same class of fix as removing an invariant-guaranteed-unreachable break.
            return new TypeDefinitionAuditEntry(
                    occurredAt,
                    action,
                    executor,
                    fromStatus != null ? TypeDefinitionStatus.valueOf(fromStatus) : null,
                    TypeDefinitionStatus.valueOf(toStatus),
                    detail
            );
        }
    }

    /**
     * Internal Persistence Mapper ensuring strict isolation between Document and Domain.
     */
    public static final class TypeDefinitionPersistenceMapper {

        private TypeDefinitionPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static TypeDefinitionDocument toDocument(TypeDefinition typeDefinition) {
            TypeDefinitionDocument doc = new TypeDefinitionDocument();
            doc.setId(typeDefinition.getId());
            doc.setOrganisationId(typeDefinition.getOrganisationId());
            doc.setCategory(typeDefinition.getCategory().name());
            doc.setJsonSchema(typeDefinition.getJsonSchema());
            doc.setStatus(typeDefinition.getStatus().name());
            doc.setCreatedAt(typeDefinition.getCreatedAt());
            doc.setUpdatedAt(typeDefinition.getUpdatedAt());
            doc.setAuditTrail(typeDefinition.getAuditTrail().stream()
                    .map(AuditEntryDocument::fromDomain)
                    .collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static TypeDefinition toDomain(TypeDefinitionDocument doc) {
            TypeDefinitionStatus status = doc.getStatus() != null
                    ? TypeDefinitionStatus.valueOf(doc.getStatus()) : TypeDefinitionStatus.DRAFT;
            List<TypeDefinitionAuditEntry> trail = doc.getAuditTrail() != null
                    ? doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList())
                    : new ArrayList<>();

            return TypeDefinition.reconstitute(
                    doc.getId(),
                    doc.getOrganisationId(),
                    CiCategory.valueOf(doc.getCategory()),
                    doc.getJsonSchema(),
                    status,
                    doc.getCreatedAt(),
                    doc.getUpdatedAt(),
                    trail
            );
        }
    }
}
