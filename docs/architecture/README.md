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
- **Market Simulator** consumes one decision and produces exactly one deterministic `PromotionOutcomeV1`: chosen discount, units sold, and gross profit under `SIMULATOR_VERSION=v1`.
- **Evaluator** receives that chosen outcome in-process, replays `0`, `10`, `20`, and `30` through the same pure simulator capability, selects the oracle action, calculates regret, and creates one immutable **PromotionCase**.
- **Learner** links the case into two deterministic context buckets, recomputes Lesson statistics from durable evidence, and creates or updates reusable **Lessons**.
- **xmemory** persists SKU, PromotionCases, Lessons, and their evidence relations across runs. Later Promotion Agent decisions retrieve Lessons, never simulator internals.

The core feedback loop is:

`Market data -> Scenario -> Decision -> Outcome -> PromotionCase -> Lesson -> Memory-backed next decision`

## Explicit component outputs

```text
Market Simulator output
  PromotionOutcomeV1
  - chosen discount
  - units sold
  - gross profit
  - simulator version

Evaluator output
  PromotionCase
  - scenario + chosen action + realised outcome
  - profit_0 / profit_10 / profit_20 / profit_30
  - best_discount + best_gross_profit
  - regret + regret_pct

Learner output
  Lesson
  - deterministic scope + coarse context
  - recommended discount from aggregate replay profits
  - deterministic confidence + evidence count
  - average profit advantage
  - evidence-grounded rationale
```

The simulator provides ground truth, the Evaluator turns ground truth into evaluated evidence, and the Learner turns accumulated evidence into reusable memory. Combining those concepts into one rectangle makes the diagram shorter and the system considerably harder to explain, a classic engineering bargain.

## Deployment topology

For the MVP, **Market Simulator + Evaluator + Learner share one Kotlin/Spring Boot deployable** while remaining sibling logical components.

```text
promotion.decisions.v1
        |
        v
[ Market Simulator -> PromotionOutcomeV1 -> Evaluator -> Learner ]
                            |
                            +-> xmemory
```

The Evaluator implements the Market Simulator `OutcomeSink` port. It also calls the same pure `SimulationEngine` capability for counterfactual replay. It cannot access hidden coefficients directly.

This keeps the runtime small without moving learning responsibility into the hidden market model.

## Transport boundaries

Kafka is deliberately used only at two meaningful external boundaries:

```text
Scenario Generator -> promotion.scenarios.v1 -> Promotion Agent
Promotion Agent     -> promotion.decisions.v1 -> Market Simulator
```

There is deliberately no `promotion.outcomes.v1` Kafka topic in the MVP. `PromotionOutcomeV1` crosses an in-process port.

The Market Simulator listener acknowledges its input only after the downstream Evaluator / Learner workflow succeeds. At-least-once redelivery is handled with deterministic IDs and idempotent xmemory writes rather than distributed exactly-once machinery.

## Learning policy

Each evaluated PromotionCase contributes to exactly two Lesson buckets:

```text
sku:<sku_id> + day_type + weather + stock_level
category:<category> + day_type + weather + stock_level
```

`store:any` and `event:any` are fixed in Lesson keys for v1. Store/event facts remain on PromotionCase for later experiments.

For each Lesson, the Learner recomputes all action totals from linked PromotionCases. The action with the highest aggregate gross profit becomes `recommended_discount`; exact ties prefer the lower discount. Confidence is deterministic from evidence count, oracle agreement, and profit advantage.

Detailed algorithm: [`../evaluator-learner/`](../evaluator-learner/)

## Benchmark boundary

Training runs with:

```text
LEARNING_ENABLED=true
```

Clean-memory and trained-memory benchmark runs both use:

```text
LEARNING_ENABLED=false
```

The Evaluator still calculates oracle/regret for metrics, but no PromotionCases or Lessons are written during benchmark measurement. Clean and trained runs point to separate xmemory instances.

## Boundaries

Market Simulator scope ends at `PromotionOutcomeV1`.

Evaluator / Learner scope begins after that outcome and owns:

- replay orchestration;
- oracle selection and regret;
- PromotionCase creation;
- deterministic Lesson candidate selection;
- Lesson aggregation/recalculation;
- xmemory evidence writes.

Promotion Agent scope stays read-only with respect to learning memory. It never receives simulator coefficients, deterministic noise, oracle results, or current-scenario counterfactual profits.

Detailed components:

- Scenario Generator: [`../scenario-generator/`](../scenario-generator/)
- Promotion Agent: [`../promotion-agent/`](../promotion-agent/)
- Market Simulator: [`../market-simulator/`](../market-simulator/)
- Evaluator / Learner: [`../evaluator-learner/`](../evaluator-learner/)
- xmemory: [`../xmemory/`](../xmemory/)
- Benchmark: [`../benchmark/`](../benchmark/)
