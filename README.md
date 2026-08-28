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
- **Market Simulator** applies a hidden deterministic market model and produces a versioned chosen-action outcome.
- **Evaluator / Learner** shares the same Kotlin/Spring Boot runtime as the simulator, replays all four discounts through the same pure engine, measures regret, and writes new experience back to **xmemory**.
- **xmemory** persists evaluated PromotionCases and Lessons across restarts.

The important property is:

**market data → scenario event → memory-backed decision → decision event → outcome → learning → memory → better next decision**

Kafka remains limited to two meaningful runtime boundaries. The simulator-to-evaluator handoff and all four counterfactual replays are in-process calls, because adding another topic to move six fields across one JVM would mostly demonstrate that Kafka has excellent marketing.

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

![Promotion Agent runtime flow](assets/promotion-agent-flow.svg)

The Promotion Agent is also a Kotlin/Spring Boot service for the MVP. The runtime flow above makes its job explicit:

- consume and validate `promotion.scenarios.v1`;
- use `scenario_id` as the durable idempotency key in the H2 `DecisionJournal`;
- retrieve candidate Lessons from xmemory and deterministically keep at most `3` relevant Lessons;
- build the same decision prompt for clean-memory and trained-memory runs;
- ask the model for exactly one action from `0 | 10 | 20 | 30`;
- validate the model result, retry once, then fall back to deterministic `0%` if necessary;
- persist the exact decision payload as `DECIDED` before publishing it;
- publish `promotion.decisions.v1`;
- mark the journal `COMPLETED` and acknowledge the source offset only after publish succeeds.

On restart, a `DECIDED` scenario republishes the already persisted decision instead of calling xmemory or the model again. A `COMPLETED` scenario is simply acknowledged as a duplicate. That keeps Kafka at-least-once delivery from quietly turning into repeated LLM decisions, because apparently one source of nondeterminism was enough.

The decision event carries the validated scenario snapshot forward. The Market Simulator therefore consumes one event and does not need to join the scenario and decision topics.

Operational idempotency/trace data stays in the agent's H2 journal, not in xmemory. xmemory remains product learning memory containing only SKU, PromotionCase, and Lesson.

- Runtime flow explanation: [`docs/promotion-agent/FLOW.md`](docs/promotion-agent/FLOW.md)
- PlantUML runtime flow source: [`docs/promotion-agent/flow.puml`](docs/promotion-agent/flow.puml)
- Detailed design: [`docs/promotion-agent/README.md`](docs/promotion-agent/README.md)
- Component/topology source: [`docs/promotion-agent/architecture.mmd`](docs/promotion-agent/architecture.mmd)
- Kafka JSON Schema: [`docs/promotion-agent/promotion-decision-v1.schema.json`](docs/promotion-agent/promotion-decision-v1.schema.json)
- Example event: [`docs/promotion-agent/promotion-decision-v1.example.json`](docs/promotion-agent/promotion-decision-v1.example.json)

### Market Simulator

![Market Simulator architecture](assets/market-simulator-architecture.svg)

The Market Simulator is the hidden market world used for both the chosen action and evaluator replay:

- consume and validate `promotion.decisions.v1` using consumer group `market-simulator-v1`;
- run a pure deterministic `SimulationEngine`;
- apply category/context demand factors plus context-sensitive promotion elasticity;
- cap units by exact stock and calculate gross profit with fixed rounding rules;
- use a small deterministic SHA-256 scenario shock shared by all four actions;
- return a versioned `PromotionOutcomeV1` directly to the Evaluator / Learner;
- expose the same engine in-process for replay of `0 | 10 | 20 | 30`.

For the MVP, Market Simulator and Evaluator / Learner are separate modules in one Kotlin/Spring Boot runtime. There is deliberately no `promotion.outcomes.v1` Kafka topic yet. Stable `outcome_id`, deterministic replay, and evaluator idempotency are enough without adding another database or transport boundary.

The hidden coefficients, noise factor, and formula internals never enter the Promotion Agent prompt or xmemory. `SIMULATOR_VERSION=v1` pins formula, coefficients, noise, and rounding for both training and benchmark runs.

- Detailed design: [`docs/market-simulator/README.md`](docs/market-simulator/README.md)
- Editable diagram source: [`docs/market-simulator/architecture.mmd`](docs/market-simulator/architecture.mmd)
- Outcome JSON Schema: [`docs/market-simulator/promotion-outcome-v1.schema.json`](docs/market-simulator/promotion-outcome-v1.schema.json)
- Example outcome: [`docs/market-simulator/promotion-outcome-v1.example.json`](docs/market-simulator/promotion-outcome-v1.example.json)

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
- same simulator version and configuration
- same fixed scenarios and scenario IDs

Compare:

- optimal action rate
- average regret
- gross profit

- Benchmark notes: [`docs/benchmark/README.md`](docs/benchmark/README.md)
- Editable diagram source: [`docs/benchmark/benchmark.mmd`](docs/benchmark/benchmark.mmd)

The hackathon claim should be simple: **same agent, better decisions because accumulated memory changes its behavior.**
