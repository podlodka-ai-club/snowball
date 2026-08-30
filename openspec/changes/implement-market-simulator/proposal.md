## Why

The learning loop requires reproducible ground truth. The Market Simulator must behave like a hidden market world: given the same scenario, action, and simulator version it produces the same sales/profit outcome, while its coefficients and noise remain unavailable to the Promotion Agent.

## What Changes

- Add the Market Simulator logical module/runtime consuming `promotion.decisions.v1`.
- Implement the pure deterministic simulator v1 formula, coefficient tables, stock cap, money rounding, and SHA-256 scenario noise already specified in documentation.
- Produce a schema-valid `PromotionOutcomeV1` through an `OutcomeSink` boundary.
- Add deterministic/idempotency, configuration, and Kafka handoff tests.
- Keep evaluator/oracle/regret/learning logic outside the simulator boundary.

## Capabilities

- `market-simulator`: hidden deterministic calculation of the chosen-action business outcome.

## Impact

Consumes the Promotion Agent decision contract. Provides `PromotionOutcomeV1` and a reusable pure simulation capability to Evaluator/Learner. It does not read/write xmemory.
