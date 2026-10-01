package com.thinklab.application.usecase;

import com.thinklab.domain.exception.DuplicateTypeDefinitionException;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionAuditEntry;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case governing the TypeDefinition lifecycle (BIAN Behavior Qualifier: {@code control}).
 *
 * <p><b>State Machine Enforcement:</b> loads the aggregate first, delegates the transition to the
 * domain model (which throws {@link com.thinklab.domain.exception.InvalidTypeDefinitionStatusException}
 * — HTTP 409 — on an illegal move) and only then issues the granular persistence update together with
 * the audit entry — never a blind partial write.
 *
 * <p><b>Uniqueness (ADR-032):</b> the aggregate alone cannot see sibling documents, so
 * {@code ACTIVATE} checks {@link TypeDefinitionRepository#existsActiveByOrganisationIdAndCategory}
 * explicitly before activating — the common case gets a clean {@link DuplicateTypeDefinitionException}
 * immediately; the repository's partial unique index is the atomic backstop for the race between two
 * concurrent activations that both pass this check.
 */
@Singleton
public class ControlTypeDefinitionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlTypeDefinitionUseCase.class);

    private final TypeDefinitionRepository typeDefinitionRepository;

    public ControlTypeDefinitionUseCase(TypeDefinitionRepository typeDefinitionRepository) {
        this.typeDefinitionRepository = typeDefinitionRepository;
    }

    public Mono<Void> execute(UUID id, Action action, String executor) {
        log.info("[USE CASE] Controlling type definition lifecycle: {} for ID: {}", action, id);

        return typeDefinitionRepository.findById(id)
                .switchIfEmpty(Mono.error(new TypeDefinitionNotFoundException(id)))
                .flatMap(typeDefinition -> action.apply(typeDefinition, executor, typeDefinitionRepository));
    }

    public enum Action {
        ACTIVATE {
            @Override
            Mono<Void> apply(TypeDefinition typeDefinition, String executor, TypeDefinitionRepository repository) {
                return repository.existsActiveByOrganisationIdAndCategory(typeDefinition.getOrganisationId(), typeDefinition.getCategory())
                        .flatMap(alreadyActive -> {
                            if (Boolean.TRUE.equals(alreadyActive)) {
                                return Mono.error(new DuplicateTypeDefinitionException(String.format(
                                        "An ACTIVE TypeDefinition already exists for organisation [%s] and category [%s].",
                                        typeDefinition.getOrganisationId(), typeDefinition.getCategory())));
                            }
                            TypeDefinitionAuditEntry entry = typeDefinition.activate(executor);
                            return repository.updateStatus(typeDefinition.getId(), entry.toStatus(), entry);
                        });
            }
        },
        DEACTIVATE {
            @Override
            Mono<Void> apply(TypeDefinition typeDefinition, String executor, TypeDefinitionRepository repository) {
                TypeDefinitionAuditEntry entry = typeDefinition.deactivate(executor);
                return repository.updateStatus(typeDefinition.getId(), entry.toStatus(), entry);
            }
        };

        abstract Mono<Void> apply(TypeDefinition typeDefinition, String executor, TypeDefinitionRepository repository);
    }
}
