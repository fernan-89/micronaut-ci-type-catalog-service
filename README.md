# Thinklab CI Type Catalog Service

**Version:** v1.0.0-BIAN

**Status:** Reference implementation (ThinkLab portfolio project)

## Overview

The Thinklab CI Type Catalog Service is the platform's tenant-configurable schema registry for
configuration-item attributes (BIAN `ci-type-catalog`): a per-organisation, per-category JSON Schema
contract that other Service Domains validate free-form attribute payloads against - starting with
`it-asset-registry`'s own `Asset.specifications` field (today a completely unvalidated
`Map<String,String>`).

A `TypeDefinition` is drafted, iterated on while `DRAFT`/`INACTIVE`, then activated - at most one
`ACTIVE` definition can exist per organisation+category at any moment, so every consumer's lookup is
unambiguous (ADR-032). It is keyed by the same ten-value `AssetCategory` enum `it-asset-registry`
already uses, not a free-form type key (ADR-030) - additive, with zero migration risk to that
already-shipped service.

Built with Java 21 and Micronaut 4.4.2 on a strict Hexagonal Architecture and a fully reactive stack
(Project Reactor, reactive MongoDB driver).

## Technology Stack

* **Runtime:** Java 21 LTS
* **Framework:** Micronaut 4.4.2 (AOT optimized, reflection-free DI and Serde)
* **Reactive Engine:** Project Reactor (Mono / Flux)
* **Persistence:** Reactive MongoDB (`thinklab_ci_type_catalog_db`, collection `type_definitions`), BSON UUID standard representation, partial unique `(organisationId, category)` index scoped to `ACTIVE`
* **Schema validation:** `com.networknt:json-schema-validator` - syntax-checks authored schemas eagerly (ADR-031); never validates a payload itself, only stores and serves the schema text
* **Observability:** W3C Trace Context, SLF4J/Logback, Reactor MDC bridge
* **Containerization:** Google Distroless (nonroot), read-only root filesystem
* **Testing:** JUnit 5, Mockito, Reactor Test (FSM, use cases, controller, adapter, mapper, index initializer, validator)
* **Documentation:** OpenAPI 3.0 / Swagger generated at compile time

## Domain Model

```text
TypeDefinition {
  id, organisationId, category, jsonSchema, status, createdAt, updatedAt,
  auditTrail[ { occurredAt, action, executor, fromStatus?, toStatus, detail } ]
}
category: LAPTOP | DESKTOP | SERVER | NETWORK_DEVICE | STORAGE_ARRAY | PERIPHERAL |
          MOBILE_DEVICE | IOT_SENSOR | VIRTUAL_MACHINE | SOFTWARE_LICENSE
status:   DRAFT | ACTIVE | INACTIVE
```

### Lifecycle (ADR-032)

```text
DRAFT -> ACTIVE (control/activate)
ACTIVE -> INACTIVE (control/deactivate)
INACTIVE -> ACTIVE (control/activate)
```

`update` (schema edit) is only legal while `DRAFT` or `INACTIVE` - an `ACTIVE` schema is a live
contract other services are validating against right now. At most one `ACTIVE` definition can exist
per `(organisationId, category)`, enforced by a use-case check plus a partial unique index as the
atomic backstop for the concurrent-activation race.

## BIAN Behavior Qualifier Contract (`/ci-type-catalog/v1`)

`X-Tenant-Id` is mandatory on `initiate`, the collection `retrieve` and `active-schema/retrieve`;
`X-Executor` is mandatory on every mutation. There is no `DELETE`.

| Behavior Qualifier | Method & Path |
|---|---|
| initiate | `POST /ci-type-catalog/v1/initiate` |
| retrieve (single) | `GET /ci-type-catalog/v1/{id}/retrieve` |
| retrieve (collection, filter `category`, `status`) | `GET /ci-type-catalog/v1/retrieve` |
| update | `PUT /ci-type-catalog/v1/{id}/update` |
| control/activate | `PUT /ci-type-catalog/v1/{id}/control/activate` |
| control/deactivate | `PUT /ci-type-catalog/v1/{id}/control/deactivate` |
| audit-log/retrieve | `GET /ci-type-catalog/v1/{id}/audit-log/retrieve` |
| **active-schema/retrieve** | `GET /ci-type-catalog/v1/active-schema/retrieve?category=<CATEGORY>` |

`active-schema/retrieve` is the cross-service lookup every consumer calls: 200 with the schema text if
an `ACTIVE` definition exists for that tenant+category, **404 if none does** - every consumer treats
that 404 as a routine "no schema configured, skip validation" signal, not an error.

### Error catalog (RFC 7807, `error_code` field)

| error_code | HTTP | Meaning |
|---|---|---|
| `ERR-CTC-00404` | 404 | TypeDefinition not found, or no ACTIVE definition configured for a category |
| `ERR-CTC-00400` | 400 | The supplied `jsonSchema` text does not parse as a valid JSON Schema document (ADR-031) |
| `ERR-CTC-00409` | 409 | Illegal lifecycle transition, or activating a duplicate for an already-ACTIVE category (ADR-019/032) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

Example:

```bash
curl -X POST http://localhost:8093/ci-type-catalog/v1/initiate \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: 6f1c7a52-3d0b-4a44-9c3e-0a7d1f6e2b10" \
  -H "X-Executor: admin-user-01" \
  -d '{"category":"LAPTOP","jsonSchema":"{\"type\":\"object\",\"properties\":{\"cpu\":{\"type\":\"string\"}},\"required\":[\"cpu\"]}"}'
```

## Operational Procedures

```bash
# Build, run AOT optimizations and test
./gradlew clean build

# Start the service (default port 8093)
./gradlew run

# Container image
docker build -t thinklab-ci-type-catalog-service:latest .
```

* **Health:** `http://localhost:8093/health`
* **Swagger UI:** `http://localhost:8093/swagger-ui`
* **Postman suite:** `docs/postman/` (lifecycle + activation uniqueness + malformed-schema negatives)

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `MICRONAUT_SERVER_PORT` | `8093` | HTTP port |
| `MONGODB_URI` | `mongodb://localhost:27017/thinklab_ci_type_catalog_db` | MongoDB connection |
| `HASH_SERVICE_URL` | `http://localhost:8080` | Hash Token Registry base URL |

## Architecture Decision Records

`docs/adr/`: 001 hexagonal reactive stack · 005 UUID identity sovereignty · 013 BIAN service domain
conventions · 019 HTTP 409 for state conflicts · 030 keyed by AssetCategory, not a free-form type key ·
031 raw JSON Schema text + validator choice · 032 lifecycle and the one-ACTIVE-per-category invariant.

### Automated Tests

```bash
./gradlew test                          # unit suite + 100% line/branch coverage gate (no Docker needed)
./gradlew integrationTest               # Testcontainers suite against a real MongoDB (needs Docker)
./gradlew check                         # both, as CI runs it
```

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
