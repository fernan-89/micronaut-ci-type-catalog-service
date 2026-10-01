package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.TypeDefinitionResponse;
import com.thinklab.application.mapper.TypeDefinitionMapper;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Orchestrates single-TypeDefinition retrieval (BIAN Behavior Qualifier: {@code retrieve}). */
@Singleton
public class RetrieveTypeDefinitionUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveTypeDefinitionUseCase.class);

    private final TypeDefinitionRepository typeDefinitionRepository;

    public RetrieveTypeDefinitionUseCase(TypeDefinitionRepository typeDefinitionRepository) {
        this.typeDefinitionRepository = typeDefinitionRepository;
    }

    public Mono<TypeDefinitionResponse> execute(UUID id) {
        log.info("[USE CASE] Retrieving type definition ID: {}", id);

        return typeDefinitionRepository.findById(id)
                .switchIfEmpty(Mono.error(new TypeDefinitionNotFoundException(id)))
                .map(TypeDefinitionMapper::toResponse);
    }
}
