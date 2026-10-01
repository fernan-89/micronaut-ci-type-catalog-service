package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.TypeDefinitionResponse;
import com.thinklab.application.mapper.TypeDefinitionMapper;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Orchestrates the tenant-scoped listing of TypeDefinitions (BIAN Behavior Qualifier: {@code retrieve}
 * — collection). Every query is strictly bound to the {@code organisationId} from {@code X-Tenant-Id}.
 */
@Singleton
public class RetrieveTypeDefinitionsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveTypeDefinitionsUseCase.class);

    private final TypeDefinitionRepository typeDefinitionRepository;

    public RetrieveTypeDefinitionsUseCase(TypeDefinitionRepository typeDefinitionRepository) {
        this.typeDefinitionRepository = typeDefinitionRepository;
    }

    public Flux<TypeDefinitionResponse> execute(UUID organisationId, CiCategory category, TypeDefinitionStatus status) {
        log.info("[USE CASE] Retrieving type definitions for organisation: {} category: {} status: {}",
                organisationId, category, status);

        return typeDefinitionRepository.findAllByOrganisationId(organisationId, category, status)
                .map(TypeDefinitionMapper::toResponse);
    }
}
