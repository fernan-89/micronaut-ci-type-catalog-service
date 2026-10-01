package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.TypeDefinitionAuditEntryResponse;
import com.thinklab.application.mapper.TypeDefinitionMapper;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Projects the immutable forensic ledger of a TypeDefinition (BIAN Behavior Qualifier:
 * {@code audit-log/retrieve}).
 *
 * <p>Returns {@code Mono<List<T>>}, never a bare {@code Flux<T>} — a controller returning {@code Flux}
 * directly bypasses the RFC 7807 exception handlers and can reorder streamed JSON elements relative to
 * their true array order (found live in Journey 6's own {@code WorkOrder.audit-log/retrieve}).
 */
@Singleton
public class RetrieveTypeDefinitionAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveTypeDefinitionAuditLogUseCase.class);

    private final TypeDefinitionRepository typeDefinitionRepository;

    public RetrieveTypeDefinitionAuditLogUseCase(TypeDefinitionRepository typeDefinitionRepository) {
        this.typeDefinitionRepository = typeDefinitionRepository;
    }

    public Mono<List<TypeDefinitionAuditEntryResponse>> execute(UUID id) {
        log.info("[USE CASE] Retrieving audit ledger for type definition ID: {}", id);

        return typeDefinitionRepository.findById(id)
                .switchIfEmpty(Mono.error(new TypeDefinitionNotFoundException(id)))
                .flatMapMany(typeDefinition -> Flux.fromIterable(typeDefinition.getAuditTrail()))
                .map(TypeDefinitionMapper::toResponse)
                .collectList();
    }
}
