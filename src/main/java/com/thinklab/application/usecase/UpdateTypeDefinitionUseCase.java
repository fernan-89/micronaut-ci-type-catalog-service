package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateTypeDefinitionRequest;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.port.JsonSchemaValidatorPort;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates schema edits to a TypeDefinition (BIAN Behavior Qualifier: {@code update}). The domain
 * model guards that this is only legal outside {@code ACTIVE} and produces the audit entry persisted
 * atomically with the granular update.
 */
@Singleton
public class UpdateTypeDefinitionUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateTypeDefinitionUseCase.class);

    private final TypeDefinitionRepository typeDefinitionRepository;
    private final JsonSchemaValidatorPort jsonSchemaValidatorPort;

    public UpdateTypeDefinitionUseCase(TypeDefinitionRepository typeDefinitionRepository,
                                        JsonSchemaValidatorPort jsonSchemaValidatorPort) {
        this.typeDefinitionRepository = typeDefinitionRepository;
        this.jsonSchemaValidatorPort = jsonSchemaValidatorPort;
    }

    public Mono<Void> execute(UUID id, UpdateTypeDefinitionRequest request, String executor) {
        log.info("[USE CASE] Updating schema for type definition ID: {}", id);

        jsonSchemaValidatorPort.validateSyntax(request.jsonSchema());

        return typeDefinitionRepository.findById(id)
                .switchIfEmpty(Mono.error(new TypeDefinitionNotFoundException(id)))
                .flatMap(typeDefinition -> {
                    TypeDefinitionAuditEntry entry = typeDefinition.updateSchema(request.jsonSchema(), executor);
                    return typeDefinitionRepository.updateSchema(id, request.jsonSchema(), entry);
                });
    }
}
