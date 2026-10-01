# ADR-030: TypeDefinition is Keyed by AssetCategory, not a Free-Form Type Key

## Status
Accepted

## Context
The product blueprint's taxonomy of technology items spans nine broad families (user computing,
datacenter, network, cloud/containers, software/services, IoT/OT, security/data, people/commercial,
facilities) and envisions a "type catalog with fields validated by JSON Schema, editable per tenant,"
so a homelabber can define "Zigbee sensor" and an enterprise can define "Siemens S7 PLC" without a
code change.

`it-asset-registry-service`'s existing `Asset.AssetCategory` enum (ten values: `LAPTOP`, `DESKTOP`,
`SERVER`, `NETWORK_DEVICE`, `STORAGE_ARRAY`, `PERIPHERAL`, `MOBILE_DEVICE`, `IOT_SENSOR`,
`VIRTUAL_MACHINE`, `SOFTWARE_LICENSE`) is a rough, coarse-grained subset of that taxonomy — it covers
most of the user-computing/datacenter/network/cloud/software/IoT families, but has no concept at all
for security/data, people/commercial or facilities (the last of which is already its own Service
Domain, `site-reference-data-directory`, not an Asset concept).

Two designs were available for this journey: key `TypeDefinition` by the existing `AssetCategory` enum
(additive to the already-shipped Asset Registry), or introduce a free-form `typeKey` string that a
tenant defines themselves (closer to the blueprint's long-term vision, but a bigger change today).

## Decision
`TypeDefinition.category` is a **local duplicate of `Asset.AssetCategory`'s ten values**, not a shared
JAR type and not a free-form key — the same "own copy of the enum" posture `workflow-approval-service`
already uses for `ApprovalOutcome` to stay decoupled from the service it integrates with.

This is additive and reversible: it requires zero migration to the already-shipped, already-public,
100%-covered `Asset` aggregate, and the lookup contract between `it-asset-registry` and
`ci-type-catalog` is a plain enum match with no silent-failure mode (a free-form key risks a tenant
typing `"laptop"` instead of `"LAPTOP"`, configuring a schema that then silently never applies — bad
for what is effectively a compliance control). The real gap a free-form key would close — sub-typing
*within* a category, e.g. "ThinkPad" vs. "MacBook," both `LAPTOP` — a one-schema-per-category design
doesn't solve any better with a free-form key either.

A free-form `typeKey` remains a legitimate future direction and can be added later as an additive,
nullable field defaulting to `category.name()` when absent — the reversible path. Collapsing a live
free-form taxonomy back onto a closed enum would not be.

## Consequences
- Positive: zero risk to the Asset Registry's existing contract; no silent misconfiguration mode;
  reversible if the platform later needs true per-tenant free-form types.
- Negative: a tenant cannot define a wholly new category this service's enum doesn't already have
  (e.g. "PLC" under IoT/OT) — they can only configure a schema for one of the ten existing values. The
  security/data, people/commercial and facilities families from the blueprint's taxonomy are entirely
  out of this service's reach in v1, by design.
