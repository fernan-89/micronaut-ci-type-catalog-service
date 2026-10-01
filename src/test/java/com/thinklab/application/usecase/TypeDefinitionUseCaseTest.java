package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateTypeDefinitionRequest;
import com.thinklab.application.dto.request.UpdateTypeDefinitionRequest;
import com.thinklab.domain.exception.DuplicateTypeDefinitionException;
import com.thinklab.domain.exception.InvalidTypeDefinitionStatusException;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.JsonSchemaValidatorPort;
import com.thinklab.domain.repository.TypeDefinitionRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TypeDefinitionUseCaseTest {

    private static final String EXECUTOR = "catalog-admin";
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Mock private TypeDefinitionRepository typeDefinitionRepository;
    @Mock private HashServicePort hashServicePort;
    @Mock private JsonSchemaValidatorPort jsonSchemaValidatorPort;

    private UUID organisationId;
    private UUID typeDefinitionId;
    private TypeDefinition typeDefinition;

    @BeforeEach
    void setUp() {
        organisationId = UUID.randomUUID();
        typeDefinitionId = UUID.randomUUID();
        typeDefinition = TypeDefinition.createNew(typeDefinitionId, organisationId, CiCategory.NETWORK_DEVICE, SCHEMA, EXECUTOR);
    }

    // ---------------------------------------------------------------- initiate

    @Test
    @DisplayName("Initiate: should validate syntax, obtain a sovereign ID, persist and return the response")
    void initiateSuccess() {
        UUID sovereignId = UUID.randomUUID();
        InitiateTypeDefinitionUseCase useCase = new InitiateTypeDefinitionUseCase(hashServicePort, typeDefinitionRepository, jsonSchemaValidatorPort);
        InitiateTypeDefinitionRequest request = new InitiateTypeDefinitionRequest(CiCategory.NETWORK_DEVICE, SCHEMA);

        when(hashServicePort.generateSovereignId("type-definition-creation")).thenReturn(Mono.just(sovereignId));
        when(typeDefinitionRepository.create(any(TypeDefinition.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .assertNext(response -> {
                    assertEquals(sovereignId, response.id());
                    assertEquals(organisationId, response.organisationId());
                    assertEquals("DRAFT", response.status());
                    assertEquals("NETWORK_DEVICE", response.category());
                })
                .verifyComplete();

        verify(jsonSchemaValidatorPort).validateSyntax(SCHEMA);
        ArgumentCaptor<TypeDefinition> captor = ArgumentCaptor.forClass(TypeDefinition.class);
        verify(typeDefinitionRepository).create(captor.capture());
        assertEquals(1, captor.getValue().getAuditTrail().size());
    }

    @Test
    @DisplayName("Initiate: should reject a malformed schema before calling the hash service")
    void initiateRejectsMalformedSchema() {
        InitiateTypeDefinitionUseCase useCase = new InitiateTypeDefinitionUseCase(hashServicePort, typeDefinitionRepository, jsonSchemaValidatorPort);
        InitiateTypeDefinitionRequest request = new InitiateTypeDefinitionRequest(CiCategory.NETWORK_DEVICE, "{not json");
        com.thinklab.domain.exception.InvalidJsonSchemaDefinitionException schemaError =
                new com.thinklab.domain.exception.InvalidJsonSchemaDefinitionException("bad", new RuntimeException());
        org.mockito.Mockito.doThrow(schemaError).when(jsonSchemaValidatorPort).validateSyntax("{not json");

        // validateSyntax runs eagerly, outside the reactive chain (same posture as UUID.fromString in a
        // controller) - execute() itself throws rather than returning a Mono that errors on subscribe.
        com.thinklab.domain.exception.InvalidJsonSchemaDefinitionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                com.thinklab.domain.exception.InvalidJsonSchemaDefinitionException.class,
                () -> useCase.execute(organisationId, request, EXECUTOR));
        assertEquals(schemaError, thrown);

        verifyNoInteractions(hashServicePort);
        verify(typeDefinitionRepository, never()).create(any());
    }

    @Test
    @DisplayName("Initiate: should propagate a hash-service failure without persisting anything")
    void initiatePropagatesHashFailure() {
        InitiateTypeDefinitionUseCase useCase = new InitiateTypeDefinitionUseCase(hashServicePort, typeDefinitionRepository, jsonSchemaValidatorPort);
        InitiateTypeDefinitionRequest request = new InitiateTypeDefinitionRequest(CiCategory.NETWORK_DEVICE, SCHEMA);

        when(hashServicePort.generateSovereignId(anyString())).thenReturn(Mono.error(new IllegalStateException("hash down")));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .expectErrorMessage("hash down")
                .verify();

        verify(typeDefinitionRepository, never()).create(any());
    }

    // ---------------------------------------------------------------- retrieve

    @Test
    @DisplayName("Retrieve: should project the aggregate to a response")
    void retrieveSuccess() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));

        StepVerifier.create(new RetrieveTypeDefinitionUseCase(typeDefinitionRepository).execute(typeDefinitionId))
                .assertNext(response -> {
                    assertEquals(typeDefinitionId, response.id());
                    assertEquals("NETWORK_DEVICE", response.category());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Retrieve: should fail with TypeDefinitionNotFoundException (404) when absent")
    void retrieveNotFound() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.empty());

        StepVerifier.create(new RetrieveTypeDefinitionUseCase(typeDefinitionRepository).execute(typeDefinitionId))
                .expectErrorSatisfies(error -> {
                    assertEquals(TypeDefinitionNotFoundException.class, error.getClass());
                    assertEquals("ERR-CTC-00404", ((TypeDefinitionNotFoundException) error).getErrorCode());
                })
                .verify();
    }

    @Test
    @DisplayName("Retrieve collection: should forward tenant, category and status filters")
    void retrieveCollection() {
        when(typeDefinitionRepository.findAllByOrganisationId(organisationId, CiCategory.NETWORK_DEVICE, TypeDefinitionStatus.DRAFT))
                .thenReturn(Flux.just(typeDefinition));
        when(typeDefinitionRepository.findAllByOrganisationId(organisationId, null, null)).thenReturn(Flux.empty());
        RetrieveTypeDefinitionsUseCase useCase = new RetrieveTypeDefinitionsUseCase(typeDefinitionRepository);

        StepVerifier.create(useCase.execute(organisationId, CiCategory.NETWORK_DEVICE, TypeDefinitionStatus.DRAFT))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(useCase.execute(organisationId, null, null))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- update

    @Test
    @DisplayName("Update: should validate syntax, mutate the aggregate and persist the change with its audit entry")
    void updateSuccess() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));
        when(typeDefinitionRepository.updateSchema(eq(typeDefinitionId), eq("{\"type\":\"string\"}"), any(TypeDefinitionAuditEntry.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(new UpdateTypeDefinitionUseCase(typeDefinitionRepository, jsonSchemaValidatorPort)
                        .execute(typeDefinitionId, new UpdateTypeDefinitionRequest("{\"type\":\"string\"}"), "tech-9"))
                .verifyComplete();

        verify(jsonSchemaValidatorPort).validateSyntax("{\"type\":\"string\"}");
        ArgumentCaptor<TypeDefinitionAuditEntry> captor = ArgumentCaptor.forClass(TypeDefinitionAuditEntry.class);
        verify(typeDefinitionRepository).updateSchema(eq(typeDefinitionId), any(), captor.capture());
        assertEquals("UPDATED", captor.getValue().action());
        assertEquals("tech-9", captor.getValue().executor());
    }

    @Test
    @DisplayName("Update: should fail with 404 when the type definition does not exist and never write")
    void updateNotFound() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.empty());

        StepVerifier.create(new UpdateTypeDefinitionUseCase(typeDefinitionRepository, jsonSchemaValidatorPort)
                        .execute(typeDefinitionId, new UpdateTypeDefinitionRequest(SCHEMA), EXECUTOR))
                .expectError(TypeDefinitionNotFoundException.class)
                .verify();

        verify(typeDefinitionRepository, never()).updateSchema(any(), any(), any());
    }

    @Test
    @DisplayName("Update: should reject editing an ACTIVE definition (409) and never write")
    void updateRejectedWhenActive() {
        typeDefinition.activate(EXECUTOR);
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));

        StepVerifier.create(new UpdateTypeDefinitionUseCase(typeDefinitionRepository, jsonSchemaValidatorPort)
                        .execute(typeDefinitionId, new UpdateTypeDefinitionRequest(SCHEMA), EXECUTOR))
                .expectError(InvalidTypeDefinitionStatusException.class)
                .verify();

        verify(typeDefinitionRepository, never()).updateSchema(any(), any(), any());
    }

    // ---------------------------------------------------------------- control (activate/deactivate)

    @Test
    @DisplayName("Control: ACTIVATE on a DRAFT definition persists ACTIVE with its audit entry, after checking for siblings")
    void controlActivateSuccess() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));
        when(typeDefinitionRepository.existsActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .thenReturn(Mono.just(false));
        when(typeDefinitionRepository.updateStatus(eq(typeDefinitionId), eq(TypeDefinitionStatus.ACTIVE), any(TypeDefinitionAuditEntry.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(new ControlTypeDefinitionUseCase(typeDefinitionRepository)
                        .execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.ACTIVATE, EXECUTOR))
                .verifyComplete();

        ArgumentCaptor<TypeDefinitionAuditEntry> captor = ArgumentCaptor.forClass(TypeDefinitionAuditEntry.class);
        verify(typeDefinitionRepository).updateStatus(eq(typeDefinitionId), eq(TypeDefinitionStatus.ACTIVE), captor.capture());
        assertEquals(TypeDefinitionStatus.DRAFT, captor.getValue().fromStatus());
        assertEquals(TypeDefinitionStatus.ACTIVE, captor.getValue().toStatus());
    }

    @Test
    @DisplayName("Control: ACTIVATE is rejected (409) when a sibling is already ACTIVE, before mutating the aggregate")
    void controlActivateRejectedWhenSiblingActive() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));
        when(typeDefinitionRepository.existsActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .thenReturn(Mono.just(true));

        StepVerifier.create(new ControlTypeDefinitionUseCase(typeDefinitionRepository)
                        .execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.ACTIVATE, EXECUTOR))
                .expectErrorSatisfies(error -> {
                    assertEquals(DuplicateTypeDefinitionException.class, error.getClass());
                    assertEquals("ERR-CTC-00409", ((DuplicateTypeDefinitionException) error).getErrorCode());
                })
                .verify();

        verify(typeDefinitionRepository, never()).updateStatus(any(), any(), any());
        assertEquals(TypeDefinitionStatus.DRAFT, typeDefinition.getStatus());
    }

    @Test
    @DisplayName("Control: DEACTIVATE on an ACTIVE definition persists INACTIVE, with no sibling check")
    void controlDeactivateSuccess() {
        typeDefinition.activate(EXECUTOR);
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));
        when(typeDefinitionRepository.updateStatus(eq(typeDefinitionId), eq(TypeDefinitionStatus.INACTIVE), any(TypeDefinitionAuditEntry.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(new ControlTypeDefinitionUseCase(typeDefinitionRepository)
                        .execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.DEACTIVATE, EXECUTOR))
                .verifyComplete();

        verify(typeDefinitionRepository, never()).existsActiveByOrganisationIdAndCategory(any(), any());
    }

    @Test
    @DisplayName("Control: DEACTIVATE on a DRAFT definition is an illegal transition (409) and never writes")
    void controlDeactivateIllegalTransition() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));

        StepVerifier.create(new ControlTypeDefinitionUseCase(typeDefinitionRepository)
                        .execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.DEACTIVATE, EXECUTOR))
                .expectError(InvalidTypeDefinitionStatusException.class)
                .verify();

        verify(typeDefinitionRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("Control: should fail with 404 when the type definition does not exist")
    void controlNotFound() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.empty());

        StepVerifier.create(new ControlTypeDefinitionUseCase(typeDefinitionRepository)
                        .execute(typeDefinitionId, ControlTypeDefinitionUseCase.Action.ACTIVATE, EXECUTOR))
                .expectError(TypeDefinitionNotFoundException.class)
                .verify();
    }

    // ---------------------------------------------------------------- audit log

    @Test
    @DisplayName("Audit log: should project every ledger entry in order as a Mono<List<...>>")
    void auditLogSuccess() {
        typeDefinition.activate(EXECUTOR);
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.just(typeDefinition));

        StepVerifier.create(new RetrieveTypeDefinitionAuditLogUseCase(typeDefinitionRepository).execute(typeDefinitionId))
                .assertNext(entries -> {
                    assertEquals(2, entries.size());
                    assertEquals("INITIATED", entries.get(0).action());
                    assertEquals("STATUS_CHANGED", entries.get(1).action());
                    assertEquals("DRAFT", entries.get(1).fromStatus());
                    assertEquals("ACTIVE", entries.get(1).toStatus());
                    assertNotNull(entries.get(1).occurredAt());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Audit log: should fail with 404 when the type definition does not exist")
    void auditLogNotFound() {
        when(typeDefinitionRepository.findById(typeDefinitionId)).thenReturn(Mono.empty());

        StepVerifier.create(new RetrieveTypeDefinitionAuditLogUseCase(typeDefinitionRepository).execute(typeDefinitionId))
                .expectError(TypeDefinitionNotFoundException.class)
                .verify();
    }

    // ---------------------------------------------------------------- active schema

    @Test
    @DisplayName("GetActiveSchema: should project the ACTIVE definition's schema text")
    void getActiveSchemaSuccess() {
        typeDefinition.activate(EXECUTOR);
        when(typeDefinitionRepository.findActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .thenReturn(Mono.just(typeDefinition));

        StepVerifier.create(new GetActiveSchemaUseCase(typeDefinitionRepository).execute(organisationId, CiCategory.NETWORK_DEVICE))
                .assertNext(response -> {
                    assertEquals(typeDefinitionId, response.id());
                    assertEquals(SCHEMA, response.jsonSchema());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GetActiveSchema: should fail with 404 when no ACTIVE definition is configured - callers treat this as 'skip validation'")
    void getActiveSchemaNotFound() {
        when(typeDefinitionRepository.findActiveByOrganisationIdAndCategory(organisationId, CiCategory.NETWORK_DEVICE))
                .thenReturn(Mono.empty());

        StepVerifier.create(new GetActiveSchemaUseCase(typeDefinitionRepository).execute(organisationId, CiCategory.NETWORK_DEVICE))
                .expectErrorSatisfies(error -> {
                    assertEquals(TypeDefinitionNotFoundException.class, error.getClass());
                    assertEquals("ERR-CTC-00404", ((TypeDefinitionNotFoundException) error).getErrorCode());
                })
                .verify();
    }
}
