# ADR-032: TypeDefinition Lifecycle and the One-ACTIVE-per-Category Invariant

## Status
Accepted

## Context
`TypeDefinition` needs a lifecycle that lets a tenant draft and iterate on a schema before it starts
being enforced, and that guarantees every consuming service's lookup
(`GET /active-schema/retrieve?category=`) is unambiguous — at most one schema can be "the" active
contract for a given organisation+category at any moment.

An aggregate instance, by itself, cannot see its siblings in the same collection — uniqueness across
documents can only be enforced by the repository/database layer, the same lesson already learned
platform-wide from `it-asset-registry`'s own `(organisationId, serialNumber)` race (closed by a unique
index in kit 0.5.0's generation, after a check-then-insert alone proved non-atomic under concurrency).

## Decision
1. Lifecycle: `DRAFT -> ACTIVE`, `ACTIVE -> INACTIVE`, `INACTIVE -> ACTIVE` (`control/activate` /
   `control/deactivate`). There is no terminal state and no physical delete, consistent with every
   other Service Domain on the platform.
2. **Editing the schema text (`update`) is only legal in `DRAFT` or `INACTIVE`** — an `ACTIVE` schema
   is a live contract other services are validating against right now; rewriting it out from under
   in-flight callers is exactly the kind of blind partial write the platform's "validate before any
   partial mutation" rule exists to prevent elsewhere. A caller must `deactivate` first.
3. **At most one `ACTIVE` definition per `(organisationId, category)`.** Enforced in two layers,
   mirroring the Asset Registry's own serial-number precedent:
   - The use case checks `existsActiveByOrganisationIdAndCategory` before activating — the common case
     gets a clean `DuplicateTypeDefinitionException` (`ERR-CTC-00409`) immediately.
   - A **partial unique index** on `(organisationId, category)`, scoped to `status: "ACTIVE"` via
     `partialFilterExpression`, is the atomic backstop for two concurrent activations that both pass
     the check — the losing update is mapped to the same exception. A *partial* index (not a plain
     unique index) is required because multiple `DRAFT`/`INACTIVE` documents for the same
     organisation+category are expected and legal; only two simultaneously `ACTIVE` ones collide.

## Consequences
- Positive: every consumer's active-schema lookup is provably unambiguous; a tenant can safely draft
  and test a replacement schema (as a second `DRAFT`) while the current one stays `ACTIVE`, then cut
  over with a `deactivate` + `activate` pair.
- Negative: cutting over requires two calls, not one atomic "replace the active schema" operation — an
  acceptable v1 trade-off, since a dedicated atomic-swap endpoint can be added later without changing
  this invariant.
