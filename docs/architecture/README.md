# High-Level Architecture

The MVP architecture is intentionally small. These are logical responsibilities, not required deployment boundaries.

![Self-learning FMCG promotion agent high-level architecture](../../assets/high-level-architecture.svg)

Editable source: [`high-level-architecture.mmd`](high-level-architecture.mmd)

## Components

- **Market Data Source** is a fixture/simulation for the MVP and can later be SAP, a database, or another API.
- **Scenario Generator** is a Kotlin microservice that fetches source data through an adapter, normalizes it, and publishes immutable scenario events.
- **Kafka** topic `promotion.scenarios.v1` is the contract boundary between data acquisition and decision logic.
- **Promotion Agent** consumes scenarios, reads relevant past lessons from **xmemory**, and chooses one discount action.
- **Market Simulator** acts as the hidden external world and returns the business outcome.
- **Evaluator / Learner** measures the decision and turns the result into reusable experience.
- **xmemory** persists evaluated cases and lessons across runs.

The core feedback loop is:

`Market data -> Scenario event -> Decision -> Outcome -> Learning -> Memory -> Better next decision`

Kafka is deliberately used at the ingestion boundary because it decouples source scheduling and adapters from the Promotion Agent. The rest of the MVP does not need to become an event-driven theme park.

Detailed components:

- Scenario Generator: [`../scenario-generator/`](../scenario-generator/)
- xmemory: [`../xmemory/`](../xmemory/)

Counterfactual replay, regret calculation, lesson confidence, and memory structure remain outside this high-level view.
