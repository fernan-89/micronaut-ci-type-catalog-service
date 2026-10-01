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
import com.thinklab.domain.exception.InvalidTypeDefinitionStatusException;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TypeDefinitionControllerTest {

    private static final String EXECUTOR = "catalog-admin";

    @Mock private InitiateTypeDefinitionUseCase initiateTypeDefinitionUseCase;
    @Mock private RetrieveTypeDefinitionUseCase retrieveTypeDefinitionUseCase;
    @Mock private RetrieveTypeDefinitionsUseCase retrieveTypeDefinitionsUseCase;
    @Mock private UpdateTypeDefinitionUseCase updateTypeDefinitionUseCase;
    @Mock private ControlTypeDefinitionUseCase controlTypeDefinitionUseCase;
    @Mock private RetrieveTypeDefinitionAuditLogUseCase retrieveTypeDefinitionAuditLogUseCase;
    @Mock private GetActiveSchemaUseCase getActiveSchemaUseCase;

    @InjectMocks
    private TypeDefinitionController controller;

    private UUID organisationId;
    private UUID typeDefinitionId;
    private TypeDefinitionResponse sample;

    @BeforeEach
    void setUp() {
        organisationId = UUID.randomUUID();
        typeDefinitionId = UUID.randomUUID();
        sample = new TypeDefinitionResponse(typeDefinitionId, organisationId, "NETWORK_DEVICE", "{}", "DRAFT",
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("initiate should return 201 Created with the new type definition")
    void initiate() {
        InitiateTypeDefinitionRequest request = new InitiateTypeDefinitionRequest(CiCategory.NETWORK_DEVICE, "{}");
        when(initiateTypeDefinitionUseCase.execute(eq(organisationId), eq(request), eq(EXECUTOR))).thenReturn(Mono.just(sample));

        StepVerifier.create(controller.initiate(organisationId.toString(), EXECUTOR, request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatus());
                    assertEquals(typeDefinitionId, response.body().id());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate should reject a malformed tenant header before reaching the use case")
    void initiateMalformedTenant() {
        InitiateTypeDefinitionRequest request = new InitiateTypeDefinitionRequest(CiCategory.LAPTOP, "{}");

        assertThrows(IllegalArgumentException.class, () -> controller.initiate("not-a-uuid", EXECUTOR, request));
    }

    @Test
    @DisplayName("initiate should propagate a domain error")
    void initiatePropagatesError() {
        InitiateTypeDefinitionRequest request = new InitiateTypeDefinitionRequest(CiCategory.LAPTOP, "{}");
        when(initiateTypeDefinitionUseCase.execute(any(), any(), any()))
                .thenReturn(Mono.error(new TypeDefinitionNotFoundException("boom")));

        StepVerifier.create(controller.initiate(organisationId.toString(), EXECUTOR, request))
                .expectError(TypeDefinitionNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("retrieveById should return 200 OK")
    void retrieveById() {
        when(retrieveTypeDefinitionUseCase.execute(typeDefinitionId)).thenReturn(Mono.just(sample));

        StepVerifier.create(controller.retrieveById(typeDefinitionId))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatus());
                    assertEquals("NETWORK_DEVICE", response.body().category());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("retrieveById should surface not-found from the use case")
    void retrieveByIdNotFound() {
        when(retrieveTypeDefinitionUseCase.execute(typeDefinitionId)).thenReturn(Mono.error(new TypeDefinitionNotFoundException(typeDefinitionId)));

        StepVerifier.create(controller.retrieveById(typeDefinitionId)).expectError(TypeDefinitionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieveAll should scope by tenant and forward category/status filters")
    void retrieveAll() {
        when(retrieveTypeDefinitionsUseCase.execute(organisationId, CiCategory.SERVER, TypeDefinitionStatus.ACTIVE)).thenReturn(Flux.just(sample));
        when(retrieveTypeDefinitionsUseCase.execute(organisationId, null, null)).thenReturn(Flux.just(sample, sample));

        StepVerifier.create(controller.retrieveAll(organisationId.toString(), CiCategory.SERVER, TypeDefinitionStatus.ACTIVE))
                .expectNext(List.of(sample)).verifyComplete();
        StepVerifier.create(controller.retrieveAll(organisationId.toString(), null, null))
                .assertNext(list -> assertEquals(2, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("update should return 204 No Content and pass the executor to the use case")
    void update() {
        UpdateTypeDefinitionRequest request = new UpdateTypeDefinitionRequest("{\"type\":\"string\"}");
        when(updateTypeDefinitionUseCase.execute(typeDefinitionId, request, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.update(typeDefinitionId, EXECUTOR, request))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus()))
                .verifyComplete();
        verify(updateTypeDefinitionUseCase).execute(typeDefinitionId, request, EXECUTOR);
    }

    @Test
    @DisplayName("each control endpoint should dispatch its action and return 204")
    void controlEndpoints() {
        when(controlTypeDefinitionUseCase.execute(eq(typeDefinitionId), any(ControlTypeDefinitionUseCase.Action.class), eq(EXECUTOR)))
                .thenReturn(Mono.empty());

        StepVerifier.create(controller.controlActivate(typeDefinitionId, EXECUTOR))
                .assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlDeactivate(typeDefinitionId, EXECUTOR))
                .assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();

        verify(controlTypeDefinitionUseCase).execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.ACTIVATE, EXECUTOR);
        verify(controlTypeDefinitionUseCase).execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.DEACTIVATE, EXECUTOR);
    }

    @Test
    @DisplayName("a control endpoint should surface an illegal transition (409) from the use case")
    void controlIllegalTransition() {
        when(controlTypeDefinitionUseCase.execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.DEACTIVATE, EXECUTOR))
                .thenReturn(Mono.error(new InvalidTypeDefinitionStatusException("illegal")));

        StepVerifier.create(controller.controlDeactivate(typeDefinitionId, EXECUTOR))
                .expectError(InvalidTypeDefinitionStatusException.class)
                .verify();
    }

    @Test
    @DisplayName("retrieveAuditLog should return the ledger entries as a list")
    void retrieveAuditLog() {
        TypeDefinitionAuditEntryResponse entry = new TypeDefinitionAuditEntryResponse(Instant.now(), "INITIATED", EXECUTOR, null, "DRAFT", "d");
        when(retrieveTypeDefinitionAuditLogUseCase.execute(typeDefinitionId)).thenReturn(Mono.just(List.of(entry)));

        StepVerifier.create(controller.retrieveAuditLog(typeDefinitionId))
                .assertNext(list -> {
                    assertEquals(1, list.size());
                    assertEquals("INITIATED", list.get(0).action());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("retrieveActiveSchema should return 200 OK with the active schema")
    void retrieveActiveSchema() {
        ActiveSchemaResponse response = new ActiveSchemaResponse(typeDefinitionId, "NETWORK_DEVICE", "{}", Instant.now());
        when(getActiveSchemaUseCase.execute(organisationId, CiCategory.NETWORK_DEVICE)).thenReturn(Mono.just(response));

        StepVerifier.create(controller.retrieveActiveSchema(organisationId.toString(), CiCategory.NETWORK_DEVICE))
                .assertNext(r -> {
                    assertEquals(HttpStatus.OK, r.getStatus());
                    assertEquals(typeDefinitionId, r.body().id());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("retrieveActiveSchema should surface a 404 when none is configured")
    void retrieveActiveSchemaNotFound() {
        when(getActiveSchemaUseCase.execute(organisationId, CiCategory.NETWORK_DEVICE))
                .thenReturn(Mono.error(new TypeDefinitionNotFoundException("none configured")));

        StepVerifier.create(controller.retrieveActiveSchema(organisationId.toString(), CiCategory.NETWORK_DEVICE))
                .expectError(TypeDefinitionNotFoundException.class)
                .verify();
    }
}
