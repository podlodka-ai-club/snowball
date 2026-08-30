# OpenSpec implementation plan

This directory turns the existing architecture into one foundation change plus five independently executable coding-agent changes.

Recommended implementation order:

0. `implement-project-skeleton`
1. `implement-scenario-generator`
2. `integrate-xmemory-learning-memory`
3. `implement-promotion-agent`
4. `implement-market-simulator`
5. `implement-evaluator-learner`

`implement-project-skeleton` comes first because every component change starts by asking for typed models of the same committed JSON contracts, so building them once removes the merge conflict that parallel work would otherwise create.

`adopt-in-process-transport` is a cross-cutting decision rather than a component, so it is not a numbered step. It defers the two Kafka topics to in-process handoff behind ports and amends the transport requirements of `implement-scenario-generator`, `implement-promotion-agent`, and `implement-market-simulator`. Read it after the skeleton and before any of the three, because their committed transport tasks predate it.

Promotion Agent and Market Simulator can be developed in parallel once the committed scenario/decision contracts are treated as fixed. Evaluator/Learner depends on the pure simulation capability and xmemory schema.

Each change follows the OpenSpec `spec-driven` workflow: `proposal.md -> specs + design.md -> tasks.md -> apply`.

The detailed architecture remains under `docs/`. OpenSpec is the implementation contract, not a replacement for the technical design documentation.
