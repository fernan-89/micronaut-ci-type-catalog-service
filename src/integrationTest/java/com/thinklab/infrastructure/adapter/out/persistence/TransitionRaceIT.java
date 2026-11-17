package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.usecase.ControlTypeDefinitionUseCase;
import com.thinklab.domain.exception.InvalidTypeDefinitionStatusException;
import com.thinklab.domain.model.TypeDefinition;
import com.thinklab.domain.model.TypeDefinition.CiCategory;
import com.thinklab.domain.model.TypeDefinition.TypeDefinitionStatus;
import com.thinklab.domain.repository.TypeDefinitionRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The activation race against a real MongoDB (platform race audit, ACTION-PLAN 0.5): ten activations of one draft at once have exactly one winner. */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransitionRaceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_ci_type_catalog_race_it";
    private static final String EXECUTOR = "catalog-admin";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject TypeDefinitionRepository repository;
    @Inject ControlTypeDefinitionUseCase control;

    @Test
    @DisplayName("ten activations of one draft at once: exactly one wins, the others are refused with a conflict")
    void tenActivationsAtOnce() {
        TypeDefinition draft = repository.create(TypeDefinition.createNew(UUID.randomUUID(), UUID.randomUUID(), CiCategory.SERVER, "{\"type\":\"object\"}", EXECUTOR)).block();

        List<String> results = Flux.range(0, 10)
                .flatMap(i -> control.execute(draft.getId(), draft.getOrganisationId(), ControlTypeDefinitionUseCase.Action.ACTIVATE, EXECUTOR).thenReturn("OK")
                        .onErrorResume(e -> Mono.just(e.getClass().getSimpleName())), 10)
                .collectList().block(Duration.ofSeconds(30));

        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        TypeDefinition stored = repository.findById(draft.getId(), draft.getOrganisationId()).block();
        assertEquals(TypeDefinitionStatus.ACTIVE, stored.getStatus());
        assertEquals(2, stored.getAuditTrail().size(), "one entry for creating and one for the single activation that won");
        assertEquals(9, results.stream().filter(r -> !"OK".equals(r)).count(), results.toString());
    }
}
