# High-Level Architecture

The MVP architecture is intentionally small. These are logical responsibilities, not required deployment boundaries.

![Self-learning FMCG promotion agent high-level architecture](../../assets/high-level-architecture.svg)

Editable source: [`high-level-architecture.mmd`](high-level-architecture.mmd)

## Components

- **Market Data Source** is a prepared fixture for the MVP and can later be SAP, a database, or another API.
- **Scenario Generator** is a Kotlin microservice that fetches source data through an adapter, normalizes it, and publishes immutable scenario events.
- **Kafka** topic `promotion.scenarios.v1` is the contract boundary between data acquisition and decision logic.
- **Promotion Agent** consumes scenarios, reads a few relevant past Lessons from **xmemory**, chooses one discount action, and durably journals the decision for idempotency/traceability.
- **Kafka** topic `promotion.decisions.v1` is the contract boundary between agent/model execution and the Market Simulator.
- **Market Simulator** consumes the decision event, applies the hidden market model, and produces the business outcome.
- **Evaluator / Learner** replays all four allowed discounts, measures regret, and turns evaluated evidence into reusable experience.
- **xmemory** persists evaluated PromotionCases and Lessons across runs.

The core feedback loop is:

`Market data -> Scenario event -> Memory-backed decision -> Decision event -> Outcome -> Learning -> Memory -> Better next decision`

Kafka is deliberately used only at two meaningful runtime boundaries:

```text
Scenario Generator -> promotion.scenarios.v1 -> Promotion Agent
Promotion Agent     -> promotion.decisions.v1 -> Market Simulator
```

The second topic is justified because it makes decisions replayable and prevents the Market Simulator from being coupled to model latency or xmemory availability. The decision event carries the normalized scenario snapshot forward, so the simulator consumes one topic and does not need to join scenario and decision streams.

Internal memory reads, prompt construction, model validation, simulator arithmetic, and learner calculations remain ordinary in-process calls. Two Kafka topics are enough for this MVP; Kafka does not receive a participation trophy for every method boundary.

Detailed components:

- Scenario Generator: [`../scenario-generator/`](../scenario-generator/)
- Promotion Agent: [`../promotion-agent/`](../promotion-agent/)
- xmemory: [`../xmemory/`](../xmemory/)

Counterfactual replay, lesson aggregation, and benchmark orchestration remain outside this high-level view.
