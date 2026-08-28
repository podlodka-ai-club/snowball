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
- **Evaluator** consumes that outcome, asks the simulator to replay the same scenario under `0`, `10`, `20`, and `30`, selects the oracle-best action, calculates regret, and produces one immutable **PromotionCase**.
- **Learner** uses evaluated PromotionCases as evidence and creates or updates a reusable **Lesson**.
- **xmemory** persists PromotionCases and Lessons across runs. Later Promotion Agent decisions retrieve Lessons, not simulator internals.

The core feedback loop is:

`Market data -> Scenario -> Decision -> Outcome -> PromotionCase -> Lesson -> Memory-backed next decision`

## Explicit component outputs

The important contracts are deliberately different:

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
  - scope + context conditions
  - recommended discount
  - confidence + evidence count
  - short evidence-grounded rationale
```

This separation matters for the hackathon story. The simulator provides ground truth, the Evaluator turns ground truth into evaluated evidence, and the Learner turns accumulated evidence into reusable memory. Combining those three concepts into one rectangle makes the diagram shorter and the system considerably harder to explain, a classic engineering bargain.

## Transport boundaries

Kafka is deliberately used only at two meaningful runtime boundaries:

```text
Scenario Generator -> promotion.scenarios.v1 -> Promotion Agent
Promotion Agent     -> promotion.decisions.v1 -> Market Simulator
```

There is deliberately no `promotion.outcomes.v1` Kafka topic in the MVP. `PromotionOutcomeV1` can be handed to the Evaluator through an in-process port.

Likewise, counterfactual replay is a direct call to the simulator's pure `SimulationEngine` capability rather than four additional Kafka messages. This does **not** make Evaluator part of Market Simulator. It only means two logical components can share a deployment for the MVP without confusing deployment topology with responsibility boundaries.

## Boundaries

Market Simulator scope ends at `PromotionOutcomeV1`.

Evaluator / Learner scope begins after that outcome and owns:

- replay orchestration;
- oracle selection;
- regret;
- PromotionCase creation;
- Lesson creation/update;
- xmemory writes.

The Promotion Agent never receives simulator coefficients, deterministic noise, oracle results, or current-scenario counterfactual profits. The hidden simulator configuration is never written to xmemory.

Detailed components:

- Scenario Generator: [`../scenario-generator/`](../scenario-generator/)
- Promotion Agent: [`../promotion-agent/`](../promotion-agent/)
- Market Simulator: [`../market-simulator/`](../market-simulator/)
- xmemory: [`../xmemory/`](../xmemory/)

The detailed Evaluator / Learner design remains a separate task; this page only fixes its input/output boundary so the overall loop is unambiguous.
