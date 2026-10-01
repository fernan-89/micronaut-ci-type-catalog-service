package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidTypeDefinitionStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Core Domain Model representing the TypeDefinition Aggregate Root.
 *
 * <p><b>BIAN Alignment (ADR-013):</b> This is the Control Record of the {@code ci-type-catalog}
 * Service Domain — a per-tenant JSON Schema contract that other Service Domains (starting with
 * {@code it-asset-registry}) validate free-form attribute payloads against (ADR-030).
 *
 * <p><b>Why keyed by {@link CiCategory}, not a free-form type key (ADR-030):</b> this is a local,
 * intentional duplicate of {@code Asset.AssetCategory}'s ten values, not a shared JAR type — the same
 * "own copy of the enum" posture {@code workflow-approval-service}'s own {@code ApprovalOutcome}
 * already uses to stay decoupled from the service it integrates with.
 *
 * <p><b>Forensic Audit Ledger:</b> every mutation appends an immutable {@link TypeDefinitionAuditEntry}
 * to the aggregate's trail. Mutating behaviors return the entry they produced so the application layer
 * can persist it atomically with the granular update.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class TypeDefinition {

    private final UUID id;
    private final UUID organisationId;
    private final CiCategory category;
    private String jsonSchema;
    private TypeDefinitionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<TypeDefinitionAuditEntry> auditTrail;

    private TypeDefinition(UUID id, UUID organisationId, CiCategory category, String jsonSchema, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.category = category;
        this.jsonSchema = jsonSchema;
        this.status = TypeDefinitionStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new TypeDefinitionAuditEntry(this.createdAt, "INITIATED", executor, null,
                TypeDefinitionStatus.DRAFT, "Type definition drafted."));
    }

    private TypeDefinition(UUID id, UUID organisationId, CiCategory category, String jsonSchema,
                            TypeDefinitionStatus status, Instant createdAt, Instant updatedAt,
                            List<TypeDefinitionAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.category = category;
        this.jsonSchema = jsonSchema;
        this.status = status != null ? status : TypeDefinitionStatus.DRAFT;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /**
     * Static factory for aggregate creation (BIAN Behavior Qualifier: {@code initiate}). The UUID must
     * be provided by the orchestration layer after calling the Hash Token Registry.
     */
    public static TypeDefinition createNew(UUID id, UUID organisationId, CiCategory category, String jsonSchema,
                                            String executor) {
        if (id == null || organisationId == null || category == null) {
            throw new IllegalArgumentException("ID, Organisation ID, and Category are mandatory for TypeDefinition creation.");
        }
        if (jsonSchema == null || jsonSchema.isBlank()) {
            throw new IllegalArgumentException("JSON Schema is mandatory for TypeDefinition creation.");
        }
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable TypeDefinition creation.");
        }
        return new TypeDefinition(id, organisationId, category, jsonSchema, executor);
    }

    /**
     * Reconstitutes an existing TypeDefinition aggregate from the persistence layer.
     */
    public static TypeDefinition reconstitute(UUID id, UUID organisationId, CiCategory category, String jsonSchema,
                                               TypeDefinitionStatus status, Instant createdAt, Instant updatedAt,
                                               List<TypeDefinitionAuditEntry> auditTrail) {
        if (id == null || organisationId == null || category == null || jsonSchema == null) {
            throw new IllegalArgumentException(
                    "ID, Organisation ID, Category, and JSON Schema are mandatory to reconstitute a TypeDefinition.");
        }
        return new TypeDefinition(id, organisationId, category, jsonSchema, status, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors (State Mutations) ---

    /**
     * Behavior Qualifier: {@code update}. Replaces the schema text. Only legal outside {@code ACTIVE} —
     * an {@code ACTIVE} schema is a live contract other services are validating against right now;
     * rewriting it out from under in-flight callers is exactly the kind of blind partial write the
     * platform's FSM-before-mutation rule exists to prevent elsewhere.
     */
    public TypeDefinitionAuditEntry updateSchema(String newJsonSchema, String executor) {
        requireExecutor(executor);
        if (this.status == TypeDefinitionStatus.ACTIVE) {
            throw new InvalidTypeDefinitionStatusException(
                    "Compliance Violation: cannot edit an ACTIVE type definition's schema; deactivate it first.");
        }
        if (newJsonSchema == null || newJsonSchema.isBlank()) {
            throw new IllegalArgumentException("JSON Schema cannot be empty.");
        }
        this.jsonSchema = newJsonSchema;
        return record("UPDATED", executor, "Schema text updated.");
    }

    /**
     * Behavior Qualifier: {@code control/activate}. {@code DRAFT->ACTIVE} or {@code INACTIVE->ACTIVE}.
     * Uniqueness (at most one {@code ACTIVE} definition per organisation+category) is enforced by the
     * repository's partial unique index, not here — the aggregate alone cannot see sibling documents.
     */
    public TypeDefinitionAuditEntry activate(String executor) {
        requireExecutor(executor);
        if (this.status == TypeDefinitionStatus.ACTIVE) {
            throw new InvalidTypeDefinitionStatusException("Idempotency Violation: The TypeDefinition is already ACTIVE.");
        }
        TypeDefinitionStatus previous = this.status;
        this.status = TypeDefinitionStatus.ACTIVE;
        return transitionRecord(previous, executor, "Activated.");
    }

    /** Behavior Qualifier: {@code control/deactivate}. {@code ACTIVE->INACTIVE}. */
    public TypeDefinitionAuditEntry deactivate(String executor) {
        requireExecutor(executor);
        if (this.status != TypeDefinitionStatus.ACTIVE) {
            throw new InvalidTypeDefinitionStatusException(String.format(
                    "Compliance Violation: cannot deactivate a TypeDefinition in [%s] state; only ACTIVE can be deactivated.",
                    this.status));
        }
        TypeDefinitionStatus previous = this.status;
        this.status = TypeDefinitionStatus.INACTIVE;
        return transitionRecord(previous, executor, "Deactivated.");
    }

    // --- Internal helpers ---

    private TypeDefinitionAuditEntry record(String action, String executor, String detail) {
        this.updatedAt = Instant.now();
        TypeDefinitionAuditEntry entry = new TypeDefinitionAuditEntry(this.updatedAt, action, executor, this.status,
                this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private TypeDefinitionAuditEntry transitionRecord(TypeDefinitionStatus previous, String executor, String detail) {
        this.updatedAt = Instant.now();
        TypeDefinitionAuditEntry entry = new TypeDefinitionAuditEntry(this.updatedAt, "STATUS_CHANGED", executor,
                previous, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable TypeDefinition mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public CiCategory getCategory() { return category; }
    public String getJsonSchema() { return jsonSchema; }
    public TypeDefinitionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<TypeDefinitionAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    /** Local duplicate of {@code Asset.AssetCategory} — see the class-level javadoc for why. */
    public enum CiCategory {
        LAPTOP, DESKTOP, SERVER, NETWORK_DEVICE, STORAGE_ARRAY, PERIPHERAL,
        MOBILE_DEVICE, IOT_SENSOR, VIRTUAL_MACHINE, SOFTWARE_LICENSE
    }

    /**
     * Lifecycle state machine for the TypeDefinition Control Record.
     *
     * <pre>
     * DRAFT -> ACTIVE
     * ACTIVE -> INACTIVE
     * INACTIVE -> ACTIVE
     * </pre>
     */
    public enum TypeDefinitionStatus { DRAFT, ACTIVE, INACTIVE }

    /**
     * Immutable forensic ledger entry.
     *
     * @param fromStatus status before the action ({@code null} for the initiating entry)
     * @param toStatus   status after the action (equal to {@code fromStatus} for non-transition actions)
     */
    public record TypeDefinitionAuditEntry(Instant occurredAt, String action, String executor,
                                            TypeDefinitionStatus fromStatus, TypeDefinitionStatus toStatus,
                                            String detail) {}
}
