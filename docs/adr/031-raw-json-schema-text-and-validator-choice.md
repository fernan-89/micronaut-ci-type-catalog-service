# ADR-031: Schema Stored as Raw JSON Text, Syntax Checked Eagerly

## Status
Accepted

## Context
A `TypeDefinition`'s `jsonSchema` field needs to hold an arbitrary JSON Schema document — a format
that permits constructs (boolean sub-schemas, `$ref`, deep nesting, `oneOf`/`anyOf`/`allOf`) that do
not map cleanly onto a flat `Map<String, String>` the way `Asset.specifications` does today. Storing a
decomposed representation risks lossy round-trips and semantic drift between what a tenant authored
and what is actually enforced later.

This service only *authors and stores* schemas — it never validates a payload against one itself (that
happens in each consuming service, starting with `it-asset-registry`'s own `SpecificationValidatorPort`,
which fetches the raw schema text from this service's `/active-schema/retrieve` and validates locally).
A malformed schema should never be allowed to reach `ACTIVE` and silently break every future
validation call made against it by a consumer that trusts this service's data.

## Decision
1. `jsonSchema` is stored as a **raw JSON string**, verbatim, both in the domain aggregate and in
   MongoDB — never decomposed into a `Map`/nested BSON document.
2. `initiate`/`update` **eagerly parse** the supplied text through
   `com.networknt:json-schema-validator` (Apache License 2.0, actively maintained, pure-Java/Jackson,
   supports JSON Schema drafts 4 through 2020-12) — compiling the schema is enough to catch malformed
   JSON and structurally invalid documents at write time. A failure maps to
   `InvalidJsonSchemaDefinitionException` / `ERR-CTC-00400` (malformed content, not a state conflict —
   ADR-019).
3. This service depends on `json-schema-validator` only to *parse/compile* a schema for syntax
   checking — it does not expose a `validate(schema, payload)` capability of its own. Each consuming
   service's own `SpecificationValidatorPort` carries its own copy of the same library to actually
   validate a payload, matching the platform's "own copy, no shared JAR" posture for cross-service
   integration types.

## Consequences
- Positive: no lossy round-trip of arbitrary JSON Schema constructs; a bad schema is rejected at
  authoring time, not discovered the first time a consumer tries to use it.
- Negative: an extra compile-time dependency (`json-schema-validator`) duplicated across this service
  and every consumer, rather than shared via the kit — a deliberate, already-established platform
  trade-off (see the kit's own "own copy of the enum" precedent) favoring per-service decoupling over a
  shared library dependency.
