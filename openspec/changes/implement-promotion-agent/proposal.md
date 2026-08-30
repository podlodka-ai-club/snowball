## Why

The Promotion Agent is the experimental decision boundary: the same model and prompt must produce decisions with and without durable Lessons so the benchmark can attribute improvement to memory. This requires explicit memory retrieval, durable idempotency, strict model/output validation, and a replayable Kafka decision contract.

## What Changes

- Add a Kotlin/Spring Boot Promotion Agent consuming `promotion.scenarios.v1`.
- Add a file-backed H2 `DecisionJournal` with `STARTED`, `DECIDED`, and `COMPLETED` recovery semantics.
- Retrieve candidate Lessons from xmemory, deterministically filter/rank them, and pass at most three compact Lessons to a stable model prompt.
- Require exactly one discount from `0 | 10 | 20 | 30`, retry invalid/failed model output once, then use a deterministic 0% fallback.
- Persist the exact decision event before Kafka publication and publish `promotion.decisions.v1`.
- Add observability proving which Lessons were read before each changed decision.

The agent will not see simulator internals, oracle answers, or current-scenario counterfactuals.

## Capabilities

- `promotion-agent`: restart-safe memory-backed selection and publication of one promotion action.

## Impact

Consumes the Scenario Generator contract, reads xmemory, calls a model-provider port, stores only operational execution state locally, and publishes the Market Simulator contract.
