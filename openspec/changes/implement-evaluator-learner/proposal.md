## Why

A self-learning loop is incomplete until each finished decision is evaluated against all allowed actions and converted into durable reusable experience. Evaluation must remain deterministic and separate from the hidden Market Simulator's chosen-action responsibility.

## What Changes

- Receive one complete `PromotionOutcomeV1` from the Market Simulator output boundary.
- Replay the same scenario at 0, 10, 20, and 30 percent through the same pure simulation capability.
- Verify replay consistency, choose oracle best with lower-discount tie-breaking, and calculate regret.
- Create one deterministic immutable PromotionCase only after all four replays succeed.
- Update exactly two deterministic Lesson buckets per case: SKU and category, keyed by day type, weather, and stock level.
- Recompute recommendation, evidence count, average profit advantage, confidence, and rationale from linked cases in deterministic code.
- Persist evidence and Lesson updates through xmemory when learning is enabled.

No LLM performs accounting or selects the Lesson recommendation.

## Capabilities

- `evaluator-learner`: deterministic evaluation-to-evidence-to-Lesson learning loop.

## Impact

Consumes Market Simulator outcomes and pure simulation capability. Writes xmemory. Supplies the durable Lessons later read by Promotion Agent.
