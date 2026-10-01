# ADR-019: HTTP 409 Conflict for Every State Conflict

## Status
Accepted — platform-wide convention, adopted as-is by this Service Domain from day one.

## Context
RFC 9110 defines the three relevant status codes precisely:

- **409 Conflict** — the request is valid but cannot be applied because of the *current state* of the
  target resource. The client may succeed later if the state changes.
- **422 Unprocessable Content** — the request is well formed but its *content* is semantically
  invalid on its own, whatever the state of the resource.
- **400 Bad Request** — malformed syntax, a missing header, an unparsable identifier, or (in this
  Service Domain specifically) a `jsonSchema` string that does not parse as a valid JSON Schema
  document at all (ADR-031) — a structural problem with the content, not a state conflict.

An illegal lifecycle transition (editing an `ACTIVE` schema, deactivating a non-`ACTIVE` definition),
an idempotent self-activation, and activating a definition when another is already `ACTIVE` for the
same organisation+category are all decided by the aggregate's *current state*, so they are all 409.

## Decision
1. Platform contract, adopted unchanged: **409 for every state conflict** (FSM violations,
   duplicates), **422** reserved for state-independent content violations in services that need it
   (not used by this one in v1), **400** for malformed input, including a malformed JSON Schema.
2. `InvalidTypeDefinitionStatusException` and `DuplicateTypeDefinitionException` both carry
   `ERR-CTC-00409` and map to 409 — the `detail` member tells the two apart.

## Consequences
- Positive: one predictable error contract across the platform; clients need no per-service rules.
- Negative: none specific to this service — there is no prior status-code decision being superseded
  here, unlike the asset/change-management Service Domains that migrated an earlier 422 usage.
