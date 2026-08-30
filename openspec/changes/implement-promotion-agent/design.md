## Context

The repository defines the Promotion Agent runtime flow in `docs/promotion-agent/README.md` and `FLOW.md`. Kafka input is at-least-once. xmemory is learning memory, while a local file-backed H2 journal is operational durability. The benchmark requires the prompt/model to remain fixed across clean/trained runs.

## Goals / Non-Goals

**Goals:**
- Restart-safe single-decision workflow.
- Explicit bounded xmemory read path with deterministic local ranking.
- Provider-independent model port and strict structured result validation.
- Exact persisted decision payload and Kafka replay semantics.
- Traceability from retrieved Lesson IDs to final decision.

**Non-Goals:**
- Agent frameworks or multi-agent debate.
- Human approval.
- Kafka transactions/outbox.
- Schema Registry.
- Python sidecar.
- More actions or dynamic promotion duration.

## Decisions

1. Use Kotlin/Spring Boot and one-record-at-a-time Kafka consumer with manual acknowledgement and concurrency 1 for the MVP.
2. Persist `decision_execution` in file-backed H2 with `STARTED | DECIDED | COMPLETED`. Store exact output payload at `DECIDED`.
3. Keep `PromotionMemory` and `DecisionModel` as ports. xmemory/model SDK details stay in adapters.
4. xmemory returns up to eight candidate Lesson objects; application code applies exact eligibility/ranking and takes at most three.
5. Prompt builder accepts only scenario, `[0,10,20,30]`, and compact Lesson snapshots. No simulator/evaluator data path may enter this module.
6. Validate structured model output. Retry once. Then use conservative 0% fallback.
7. Persist before publish; on `DECIDED` restart republish stored bytes/semantic payload instead of recomputing.
8. `COMPLETED` duplicate performs no memory/model calls.
9. Input contract violations are observable non-retryable errors in the controlled MVP; xmemory failures degrade to memoryless decisions; output Kafka failures preserve redelivery.

## Risks / Trade-offs

- There remains a publish-success/crash-before-COMPLETED duplicate window without transactions. Deterministic `decision_id` makes this harmless when downstream honors idempotency.
- H2 is intentionally single-instance operational state, not a horizontally scalable design.
- xmemory semantic retrieval may return noisy candidates, hence strict local filtering/ranking.

## References

- `docs/promotion-agent/README.md`
- `docs/promotion-agent/FLOW.md`
- `docs/promotion-agent/flow.puml`
- `docs/promotion-agent/promotion-decision-v1.schema.json`
- `docs/scenario-generator/promotion-scenario-v1.schema.json`
- `docs/xmemory/README.md`
