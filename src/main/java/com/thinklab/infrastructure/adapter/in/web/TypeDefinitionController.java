package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateTypeDefinitionRequest;
import com.thinklab.application.dto.request.UpdateTypeDefinitionRequest;
import com.thinklab.application.dto.response.ActiveSchemaResponse;
import com.thinklab.application.dto.response.TypeDefinitionAuditEntryResponse;
import com.thinklab.application.dto.response.TypeDefinitionResponse;
import com.thinklab.application.usecase.ControlTypeDefinitionUseCase;
import com.thinklab.application.usecase.GetActiveSchemaUseCase;
import com.thinklab.application.usecase.InitiateTypeDefinitionUseCase;
import com.thinklab.application.usecase.RetrieveTypeDefinitionAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveTypeDefinitionUseCase;
import com.thinklab.application.usecase.RetrieveTypeDefinitionsUseCase;
import com.thinklab.application.usecase.UpdateTypeDefinitionUseCase;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code ci-type-catalog} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.TypeDefinition} is
 * the Control Record. Every route follows {@code /ci-type-catalog/v1/{control-record-id}/{behavior-qualifier}}.
 * There is no {@code DELETE}: no physical delete exists in this Service Domain.
 *
 * <p><b>The one route every consuming service calls, not just operators:</b>
 * {@code GET /active-schema/retrieve?category=} — a 404 there means "no schema configured," which
 * every caller treats as a routine signal to skip validation, not an error.
 */
@Controller("/ci-type-catalog/v1")
public class TypeDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(TypeDefinitionController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";

    private final InitiateTypeDefinitionUseCase initiateTypeDefinitionUseCase;
    private final RetrieveTypeDefinitionUseCase retrieveTypeDefinitionUseCase;
    private final RetrieveTypeDefinitionsUseCase retrieveTypeDefinitionsUseCase;
    private final UpdateTypeDefinitionUseCase updateTypeDefinitionUseCase;
    private final ControlTypeDefinitionUseCase controlTypeDefinitionUseCase;
    private final RetrieveTypeDefinitionAuditLogUseCase retrieveTypeDefinitionAuditLogUseCase;
    private final GetActiveSchemaUseCase getActiveSchemaUseCase;

    public TypeDefinitionController(
            InitiateTypeDefinitionUseCase initiateTypeDefinitionUseCase,
            RetrieveTypeDefinitionUseCase retrieveTypeDefinitionUseCase,
            RetrieveTypeDefinitionsUseCase retrieveTypeDefinitionsUseCase,
            UpdateTypeDefinitionUseCase updateTypeDefinitionUseCase,
            ControlTypeDefinitionUseCase controlTypeDefinitionUseCase,
            RetrieveTypeDefinitionAuditLogUseCase retrieveTypeDefinitionAuditLogUseCase,
            GetActiveSchemaUseCase getActiveSchemaUseCase
    ) {
        this.initiateTypeDefinitionUseCase = initiateTypeDefinitionUseCase;
        this.retrieveTypeDefinitionUseCase = retrieveTypeDefinitionUseCase;
        this.retrieveTypeDefinitionsUseCase = retrieveTypeDefinitionsUseCase;
        this.updateTypeDefinitionUseCase = updateTypeDefinitionUseCase;
        this.controlTypeDefinitionUseCase = controlTypeDefinitionUseCase;
        this.retrieveTypeDefinitionAuditLogUseCase = retrieveTypeDefinitionAuditLogUseCase;
        this.getActiveSchemaUseCase = getActiveSchemaUseCase;
    }

    /** Behavior Qualifier: {@code initiate}. Drafts a new TypeDefinition Control Record. */
    @Post("/initiate")
    public Mono<HttpResponse<TypeDefinitionResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid InitiateTypeDefinitionRequest request
    ) {
        log.info("[ACTION: INITIATE_TYPE_DEFINITION] [EXECUTOR: {}] Received request to draft a type definition for organisation: {} category: {}",
                executor, tenantId, request.category());

        return initiateTypeDefinitionUseCase.execute(UUID.fromString(tenantId), request, executor)
                .map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. Fetches a single TypeDefinition by UUID. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<TypeDefinitionResponse>> retrieveById(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_TYPE_DEFINITION] Received request to get type definition by ID: {}", id);

        return retrieveTypeDefinitionUseCase.execute(id).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). Lists TypeDefinitions scoped to a tenant. */
    @Get("/retrieve")
    public Mono<List<TypeDefinitionResponse>> retrieveAll(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @QueryValue @Nullable CiCategory category,
            @QueryValue @Nullable TypeDefinitionStatus status
    ) {
        log.info("[ACTION: RETRIEVE_TYPE_DEFINITIONS] Received request to list type definitions for organisation: {} category: {} status: {}",
                tenantId, category, status);

        return Mono.defer(() -> retrieveTypeDefinitionsUseCase.execute(UUID.fromString(tenantId), category, status).collectList());
    }

    /** Behavior Qualifier: {@code update}. Replaces the schema text; only legal outside ACTIVE. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(
            @PathVariable UUID id,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid UpdateTypeDefinitionRequest request
    ) {
        log.info("[ACTION: UPDATE_TYPE_DEFINITION] [EXECUTOR: {}] Received request to update type definition ID: {}", executor, id);

        return updateTypeDefinitionUseCase.execute(id, request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/activate}. DRAFT or INACTIVE -> ACTIVE. */
    @Put("/{id}/control/activate")
    public Mono<HttpResponse<Void>> controlActivate(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlTypeDefinitionUseCase.Action.ACTIVATE, executor);
    }

    /** Behavior Qualifier: {@code control/deactivate}. ACTIVE -> INACTIVE. */
    @Put("/{id}/control/deactivate")
    public Mono<HttpResponse<Void>> controlDeactivate(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlTypeDefinitionUseCase.Action.DEACTIVATE, executor);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. Immutable forensic ledger of the TypeDefinition. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<TypeDefinitionAuditEntryResponse>> retrieveAuditLog(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_TYPE_DEFINITION_AUDIT_LOG] Received request for audit ledger of type definition ID: {}", id);

        return retrieveTypeDefinitionAuditLogUseCase.execute(id);
    }

    /**
     * Behavior Qualifier: {@code active-schema/retrieve}. The cross-service lookup every consuming
     * service calls — a 404 means "no schema configured," a routine, expected outcome.
     */
    @Get("/active-schema/retrieve")
    public Mono<HttpResponse<ActiveSchemaResponse>> retrieveActiveSchema(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @QueryValue @NotNull CiCategory category
    ) {
        log.info("[ACTION: RETRIEVE_ACTIVE_SCHEMA] Received request for active schema, organisation: {} category: {}", tenantId, category);

        return getActiveSchemaUseCase.execute(UUID.fromString(tenantId), category).map(HttpResponse::ok);
    }

    private Mono<HttpResponse<Void>> control(UUID id, ControlTypeDefinitionUseCase.Action action, String executor) {
        log.info("[ACTION: CONTROL_TYPE_DEFINITION] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return controlTypeDefinitionUseCase.execute(id, action, executor).thenReturn(HttpResponse.noContent());
    }
}
