package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ActiveSchemaResponse;
import com.thinklab.application.mapper.TypeDefinitionMapper;
import com.thinklab.domain.exception.TypeDefinitionNotFoundException;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The critical cross-service lookup (BIAN Behavior Qualifier: {@code active-schema/retrieve}): the
 * single ACTIVE definition for a tenant+category, if any. A 404 here is an expected, routine outcome
 * for every consuming service — "no schema configured for this category," not an error — see
 * {@code it-asset-registry}'s own {@code CiTypeCatalogServiceAdapter}, which treats this 404 as
 * "skip validation" rather than propagating a failure.
 */
@Singleton
public class GetActiveSchemaUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetActiveSchemaUseCase.class);

    private final TypeDefinitionRepository typeDefinitionRepository;

    public GetActiveSchemaUseCase(TypeDefinitionRepository typeDefinitionRepository) {
        this.typeDefinitionRepository = typeDefinitionRepository;
    }

    public Mono<ActiveSchemaResponse> execute(UUID organisationId, CiCategory category) {
        log.info("[USE CASE] Retrieving active schema for organisation: {} category: {}", organisationId, category);

        return typeDefinitionRepository.findActiveByOrganisationIdAndCategory(organisationId, category)
                .switchIfEmpty(Mono.error(new TypeDefinitionNotFoundException(String.format(
                        "No ACTIVE TypeDefinition is configured for organisation [%s] and category [%s].",
                        organisationId, category))))
                .map(TypeDefinitionMapper::toActiveSchemaResponse);
    }
}
