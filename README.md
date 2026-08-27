# Hacker Sprint 2 — Self-Learning FMCG Promotion Agent

An autonomous promotion agent that improves its discount decisions from outcomes stored in persistent memory.

## Self-learning loop

![Self-learning promotion agent](assets/self-learning-loop.svg)

The demo is intentionally small: the agent chooses one of `0%`, `10%`, `20%`, or `30%` discount levels. A simulator produces sales and gross profit. An evaluator replays all allowed actions, calculates regret, and writes reusable lessons to xmemory. Future decisions retrieve those lessons.

The core behavior is:

**scenario → decision → simulated outcome → counterfactual evaluation → lesson write → lesson read → changed next decision**

## MVP architecture

![High-level architecture](assets/high-level-architecture.svg)

The architecture is intentionally small:

- **Scenario Generator** fetches baseline market data through a source adapter, normalizes it, and publishes scenario events to Kafka.
- **Kafka** topic `promotion.scenarios.v1` decouples data collection/scheduling from decision logic.
- **Promotion Agent** consumes scenarios, reads a few relevant lessons from **xmemory**, chooses one discount, and journals the exact decision durably for idempotency and traceability.
- **Kafka** topic `promotion.decisions.v1` carries the validated decision plus scenario snapshot to the simulator.
- **Market Simulator** produces the business outcome.
- **Evaluator / Learner** measures the decision and writes new experience back to **xmemory**.

The important property is:

**market data → scenario event → memory-backed decision → decision event → outcome → learning → memory → better next decision**

- Architecture notes: [`docs/architecture/README.md`](docs/architecture/README.md)
- Editable diagram source: [`docs/architecture/high-level-architecture.mmd`](docs/architecture/high-level-architecture.mmd)

## Component deep dives

Detailed design lives in a separate directory per component as we implement it. Keep each block independently understandable and resist turning hackathon documentation into a distributed-systems archaeology site.

### Scenario Generator

![Scenario Generator architecture](assets/scenario-generator-architecture.svg)

The Scenario Generator is a Kotlin microservice and the ingestion boundary of the system:

- source adapters hide whether baseline data comes from a fixture, SAP, database, or API;
- a scheduler or manual trigger starts scenario generation;
- context enrichment adds normalized weather/event/day context;
- the service publishes one validated immutable event to `promotion.scenarios.v1` per scenario;
- the Promotion Agent depends only on the versioned event contract, never source-specific DTOs.

For the hackathon the market is fixed to **London Central** (`LONDON_CENTRAL`, `Europe/London`) and the primary baseline source is a small fixture prepared offline from **dunnhumby Breakfast at the Frat**. The raw public dataset never becomes a runtime dependency.

- Detailed design: [`docs/scenario-generator/README.md`](docs/scenario-generator/README.md)
- Dataset preparation: [`docs/scenario-generator/dataset-preparation.md`](docs/scenario-generator/dataset-preparation.md)
- Baseline fixture example: [`docs/scenario-generator/baseline-fixture.example.csv`](docs/scenario-generator/baseline-fixture.example.csv)
- Kafka JSON Schema: [`docs/scenario-generator/promotion-scenario-v1.schema.json`](docs/scenario-generator/promotion-scenario-v1.schema.json)
- Example event: [`docs/scenario-generator/promotion-scenario-v1.example.json`](docs/scenario-generator/promotion-scenario-v1.example.json)
- Editable diagram source: [`docs/scenario-generator/architecture.mmd`](docs/scenario-generator/architecture.mmd)

### Promotion Agent

![Promotion Agent architecture](assets/promotion-agent-architecture.svg)

The Promotion Agent is also a Kotlin/Spring Boot service for the MVP. Its runtime path is deliberately narrow:

- consume `promotion.scenarios.v1` using consumer group `promotion-agent-v1`;
- validate the event before business logic;
- use `scenario_id` as the idempotency key in a file-backed H2 `DecisionJournal`;
- query xmemory through its REST `/read` API for candidate Lessons;
- deterministically filter/rank candidates and pass at most `3` Lessons to the model;
- keep the model prompt identical between clean-memory and trained-memory benchmark runs;
- validate the model result against allowed discounts `0 | 10 | 20 | 30`;
- retry an invalid/failed model call once, then use deterministic `0%` fallback;
- persist the exact decision payload before publishing it;
- publish a versioned `promotion.decisions.v1` event for the Market Simulator.

The decision event carries the validated scenario snapshot forward. The Market Simulator therefore consumes one event and does not need to join the scenario and decision topics.

Operational idempotency/trace data stays in the agent's H2 journal, not in xmemory. xmemory remains product learning memory containing only SKU, PromotionCase, and Lesson.

- Detailed design: [`docs/promotion-agent/README.md`](docs/promotion-agent/README.md)
- Kafka JSON Schema: [`docs/promotion-agent/promotion-decision-v1.schema.json`](docs/promotion-agent/promotion-decision-v1.schema.json)
- Example event: [`docs/promotion-agent/promotion-decision-v1.example.json`](docs/promotion-agent/promotion-decision-v1.example.json)
- Editable diagram source: [`docs/promotion-agent/architecture.mmd`](docs/promotion-agent/architecture.mmd)

### xmemory

![xmemory schema](assets/xmemory-schema.svg)

The MVP memory schema contains only three domain objects:

- **SKU** — stable product identity and basic economics.
- **PromotionCase** — immutable evaluated evidence: scenario, chosen discount, outcome, all four replay profits, simulator optimum, and regret.
- **Lesson** — compact reusable knowledge updated from linked cases and retrieved before later decisions.

Each Lesson links back to the PromotionCases that produced it, making the hackathon write → read → changed behaviour trace visible and reproducible.

- Detailed design: [`docs/xmemory/README.md`](docs/xmemory/README.md)
- XMD v1 schema: [`docs/xmemory/schema.xmd.yaml`](docs/xmemory/schema.xmd.yaml)
- Editable diagram source: [`docs/xmemory/schema.mmd`](docs/xmemory/schema.mmd)

## Benchmark

![Benchmark clean memory vs learned memory](assets/benchmark.svg)

To prove self-improvement, the Benchmark Runner compares the same agent with:

- **clean xmemory**
- **trained xmemory**

Everything else stays constant:

- same model
- same prompt
- same simulator
- same fixed scenarios

Compare:

- optimal action rate
- average regret
- gross profit

- Benchmark notes: [`docs/benchmark/README.md`](docs/benchmark/README.md)
- Editable diagram source: [`docs/benchmark/benchmark.mmd`](docs/benchmark/benchmark.mmd)

The hackathon claim should be simple: **same agent, better decisions because accumulated memory changes its behavior.**
