# ADR 033: Type-definition writes are guarded writes

## Status
Accepted (2026-11-17). Found by the platform race audit (ACTION-PLAN 0.5, `thinklab-platform/docs/race-audit.md`).

## Context
A type definition change read the definition, validated it in the domain and wrote it by `_id` only. Measured by `TransitionRaceIT` on the old code: ten activations of one draft at once all answered OK and left eleven entries in the trail. (The partial unique index that keeps one ACTIVE definition per category, ADR-032, only stops two different definitions; it cannot stop the same one being activated twice.)

## Decision
`TypeDefinition.updateStatus` and `updateSchema` are **guarded writes** on the status the caller read (the `fromStatus` of the audit entry they carry, so no use case changed): no match is `409 ERR-CTC-00409` when the definition exists and `404` when it does not (ADR-019). The duplicate-ACTIVE index keeps its own 409.

## Consequences
- **Positive:** one winner per race; the stored status and the trail agree.
