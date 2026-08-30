## Context

The Market Simulator owns hidden deterministic outcome generation only. Evaluator/Learner owns counterfactual orchestration and learning. `docs/evaluator-learner/README.md` deliberately keeps the flow small: outcome -> four replays -> best/regret -> PromotionCase -> two Lessons.

## Goals / Non-Goals

**Goals:**
- Pure deterministic evaluation and aggregation functions with strong tests.
- Strict integrity check against the chosen Market Simulator outcome.
- One immutable case and exactly two reusable Lessons per completed scenario.
- Idempotent xmemory writes and benchmark write-disable mode.

**Non-Goals:**
- LLM arithmetic/recommendation.
- New Kafka topics for in-process evaluator handoff.
- Lesson combinatorial explosion across every context field.
- Human validation.
- Forgetting/time decay in v1.

## Decisions

1. Implement Evaluator/Learner as a sibling logical module/package to Market Simulator, optionally in the same Spring Boot deployable for MVP.
2. Depend on the pure `SimulationEngine` capability, not on simulator Kafka transport or hidden logs.
3. Replay all four actions and verify the chosen replay exactly matches the original outcome before learning.
4. Compare gross profit at exact persisted money precision; exact ties pick lower discount.
5. Build deterministic case ID including simulator version and persist the full four-profit feedback vector.
6. Build exactly two Lesson keys per case as documented. `event_type` and store-specific bucketing remain future extensions.
7. Recompute Lessons from all unique linked cases; never increment evidence counters blindly.
8. Recommendation, advantage, confidence, and rationale are deterministic application functions. xmemory stores their results and relations.
9. A case checkpoint may exist before a failed Lesson update; retry reads the same immutable case and repairs/recomputes Lesson state.
10. With learning disabled, expose evaluation results to benchmark observer/runner without memory writes.

## Risks / Trade-offs

- Category Lessons can overgeneralize, intentionally balanced by ranking SKU Lessons first in Promotion Agent.
- Persisting Lessons after one case makes weak evidence visible; confidence communicates weakness rather than suppressing all early learning.
- Case checkpoint plus later Lesson writes are not one distributed transaction, so retry/repair logic is essential.

## References

- `docs/evaluator-learner/README.md`
- `docs/evaluator-learner/sequence.mmd`
- `docs/market-simulator/README.md`
- `docs/xmemory/README.md`
