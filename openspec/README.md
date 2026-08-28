# OpenSpec implementation plan

This directory turns the existing architecture into five independently executable coding-agent changes.

Recommended implementation order:

1. `implement-scenario-generator`
2. `integrate-xmemory-learning-memory`
3. `implement-promotion-agent`
4. `implement-market-simulator`
5. `implement-evaluator-learner`

Promotion Agent and Market Simulator can be developed in parallel once the committed scenario/decision contracts are treated as fixed. Evaluator/Learner depends on the pure simulation capability and xmemory schema.

Each change follows the OpenSpec `spec-driven` workflow: `proposal.md -> specs + design.md -> tasks.md -> apply`.

The detailed architecture remains under `docs/`. OpenSpec is the implementation contract, not a replacement for the technical design documentation.
