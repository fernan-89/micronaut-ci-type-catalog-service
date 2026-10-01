package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateTypeDefinitionRequest;
import com.thinklab.application.dto.response.TypeDefinitionResponse;
import com.thinklab.application.mapper.TypeDefinitionMapper;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.JsonSchemaValidatorPort;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates the business flow for TypeDefinition creation (BIAN Behavior Qualifier:
 * {@code initiate}). The schema text is parsed eagerly so a malformed schema is rejected (400) before
 * a Sovereign ID is ever requested.
 */
@Singleton
public class InitiateTypeDefinitionUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateTypeDefinitionUseCase.class);

    private final HashServicePort hashServicePort;
    private final TypeDefinitionRepository typeDefinitionRepository;
    private final JsonSchemaValidatorPort jsonSchemaValidatorPort;

    public InitiateTypeDefinitionUseCase(HashServicePort hashServicePort, TypeDefinitionRepository typeDefinitionRepository,
                                          JsonSchemaValidatorPort jsonSchemaValidatorPort) {
        this.hashServicePort = hashServicePort;
        this.typeDefinitionRepository = typeDefinitionRepository;
        this.jsonSchemaValidatorPort = jsonSchemaValidatorPort;
    }

    public Mono<TypeDefinitionResponse> execute(UUID organisationId, InitiateTypeDefinitionRequest request, String executor) {
        log.info("[USE CASE] Initiating type definition for organisation: {} category: {}", organisationId, request.category());

        jsonSchemaValidatorPort.validateSyntax(request.jsonSchema());

        return hashServicePort.generateSovereignId("type-definition-creation")
                .map(sovereignId -> TypeDefinitionMapper.toDomain(request, sovereignId, organisationId, executor))
                .flatMap(typeDefinitionRepository::create)
                .map(TypeDefinitionMapper::toResponse);
    }
}
