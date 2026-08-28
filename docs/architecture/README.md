# High-Level Architecture

The MVP architecture is intentionally small. These are logical responsibilities, not required deployment boundaries.

![Self-learning FMCG promotion agent high-level architecture](../../assets/high-level-architecture.svg)

Editable source: [`high-level-architecture.mmd`](high-level-architecture.mmd)

## Components

- **Market Data Source** is a prepared fixture for the MVP and can later be SAP, a database, or another API.
- **Scenario Generator** is a Kotlin microservice that fetches source data through an adapter, normalizes it, and publishes immutable scenario events.
- **Kafka** topic `promotion.scenarios.v1` is the contract boundary between data acquisition and decision logic.
- **Promotion Agent** consumes scenarios, reads a few relevant past Lessons from **xmemory**, chooses one discount action, and durably journals the decision for idempotency/traceability.
- **Kafka** topic `promotion.decisions.v1` is the contract boundary between agent/model execution and the hidden market world.
- **Market Simulator** consumes the decision, applies immutable hidden `SIMULATOR_VERSION=v1` logic, and produces a deterministic chosen-action `PromotionOutcomeV1`.
- **Evaluator / Learner** shares the same Kotlin/Spring Boot runtime as the simulator, replays all four discounts through the exact same `SimulationEngine`, measures regret, and turns evaluated evidence into reusable experience.
- **xmemory** persists evaluated PromotionCases and Lessons across runs.

The core feedback loop is:

`Market data -> Scenario event -> Memory-backed decision -> Decision event -> Outcome -> Learning -> Memory -> Better next decision`

Kafka is deliberately used only at two meaningful runtime boundaries:

```text
Scenario Generator -> promotion.scenarios.v1 -> Promotion Agent
Promotion Agent     -> promotion.decisions.v1 -> Simulation + Learning Runtime
```

The second topic is justified because it makes decisions replayable and prevents the hidden market world from being coupled to model latency or xmemory availability. The decision event carries the normalized scenario snapshot forward, so the simulator consumes one topic and does not need to join scenario and decision streams.

There is deliberately **no `promotion.outcomes.v1` Kafka topic in the MVP**. Market Simulator and Evaluator / Learner are separate modules inside one process. The chosen-action result is handed over as versioned `PromotionOutcomeV1`, while evaluator replay calls the same pure simulation engine directly for `0`, `10`, `20`, and `30`.

This keeps the hidden ground-truth boundary explicit without inventing another service and four replay messages per case. Kafka has two useful jobs here; it does not need a third for morale.

The Promotion Agent never receives simulator coefficients, deterministic noise, or current counterfactual results. The Evaluator sees only simulation results and uses them to create PromotionCases and Lessons. The hidden simulator configuration is not written to xmemory.

Detailed components:

- Scenario Generator: [`../scenario-generator/`](../scenario-generator/)
- Promotion Agent: [`../promotion-agent/`](../promotion-agent/)
- Market Simulator: [`../market-simulator/`](../market-simulator/)
- xmemory: [`../xmemory/`](../xmemory/)

Counterfactual replay arithmetic, regret calculation, lesson aggregation, and benchmark orchestration remain owned by Evaluator / Learner and Benchmark Runner rather than Market Simulator.
