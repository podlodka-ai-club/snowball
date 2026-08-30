## Context

`docs/xmemory/README.md` and `schema.xmd.yaml` define product memory as structured, durable evidence rather than general conversation history. Evaluator/Learner owns evaluated facts and lesson arithmetic; Promotion Agent owns eligibility/ranking before model input.

## Goals / Non-Goals

**Goals:**
- Durable schema tied to FMCG promotion entities and relations.
- Idempotent evidence writes and explainable Lesson provenance.
- Structured reads/writes through xmemory REST.
- Clean/trained instance separation for benchmark proof.

**Non-Goals:**
- A separate xmemory microservice owned by this repository.
- Vector DB beside xmemory.
- Operational idempotency storage.
- LLM-authored recommendations or arithmetic.
- Time decay/forgetting in v1.

## Decisions

1. Treat `docs/xmemory/schema.xmd.yaml` as the source of truth for xmemory schema.
2. Use `/instances/{instance_id}/read` and `/write` with bearer-token configuration supplied only from environment/secret storage.
3. Use deterministic primary keys for cases and lessons. Reads before writes distinguish create, identical retry, and integrity conflict.
4. Use `structured_mutations` for known objects/relations. Free-form extraction is inappropriate for deterministic application state.
5. Preserve `lesson_evidence` as authoritative provenance. `evidence_count` is recomputed from unique relations, never incremented blindly.
6. Keep Lesson recommendation/confidence/rationale calculation in deterministic Evaluator/Learner code. xmemory stores the result and graph.
7. Runtime decision reads request candidate Lesson objects; the Promotion Agent performs final local matching/ranking and sends at most three compact snapshots to the model.
8. Use distinct instance IDs for trained and clean memory. Disable writes during benchmark measurement.

## Risks / Trade-offs

- xmemory API latency/failure cannot be allowed to stall all decisions; Promotion Agent therefore degrades to memoryless behavior while preserving status/traceability.
- Structured reads still rely on service response quality, so application validation is mandatory.
- No forgetting is acceptable only because simulator v1 is stationary during the benchmark.

## References

- `docs/xmemory/README.md`
- `docs/xmemory/schema.xmd.yaml`
- `docs/promotion-agent/README.md`
- `docs/evaluator-learner/README.md`
