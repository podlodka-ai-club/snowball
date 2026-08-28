## Why

Persistent memory is the core hackathon claim. The system must retain evaluated experience across restarts, retrieve it later, and show exactly which evidence changed a future decision. The repository already defines an XMD schema and deterministic memory policy; implementation must preserve those semantics rather than treating xmemory as an unstructured text bag.

## What Changes

- Provision/validate the committed xmemory schema with exactly `SKU`, `PromotionCase`, and `Lesson` domain objects plus provenance relations.
- Implement deterministic structured write semantics for immutable cases, lesson evidence, and lesson updates.
- Define bounded structured reads used by the Promotion Agent.
- Preserve durability, provenance, duplicate safety, contradiction handling, and benchmark isolation.
- Add integration tests against an xmemory instance or deterministic test double matching the REST envelopes.

Operational Kafka offsets, agent journals, simulator coefficients, prompts, and hidden reasoning remain outside xmemory.

## Capabilities

- `xmemory-learning-memory`: durable evidence and reusable lesson storage with visible write/read provenance.

## Impact

Evaluator/Learner is the main writer. Promotion Agent is the main reader. `docs/xmemory/schema.xmd.yaml` is authoritative for memory shape.
