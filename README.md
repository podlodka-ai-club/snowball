# Hacker Sprint 2 - Self-Learning FMCG Promotion Agent

An autonomous promotion agent that improves its discount decisions from outcomes stored in
persistent memory.

## What it does, and what that is worth

The agent picks one of `0%`, `10%`, `20%`, `30%` for a product in a store on a given day. A
deterministic simulator plays the market. An evaluator replays **all four** actions on the same
market shock and computes regret - the profit given up against the best action. Lessons are derived
from accumulated cases and read back before later decisions.

Measured on 50 held-out scenarios, split by time, with learning switched off in both arms and the
two memories in separate instances:

| | no memory | memory, exact key | memory with fallback |
|---|---|---|---|
| optimal decisions | 44% | 76% | 80% |
| total regret | 44.04 | 12.17 | 7.18 |
| memory answered | - | 44 of 50 | 50 of 50 |

Paired over the same scenarios the trained arm is better on 25 and worse on 6 (exact binomial
p = 0.0009). Full numbers, caveats and the raw logs: [`runs/2026-09-04/`](runs/2026-09-04/).

## Running it

```bash
./gradlew spotlessCheck build          # what CI runs
./gradlew classes                      # compile before the CLIs below

CP="build/classes/kotlin/main:build/resources/main:$(find ~/.gradle/caches/modules-2 -name '*.jar' | tr '\n' ':')"

# one benchmark arm; --arm reads XMEM_INSTANCE_ID_CLEAN / _TRAINED from the environment
java -cp "$CP" club.podlodka.snowball.adapter.cli.RunExperiment --split=benchmark --no-learning --arm=trained

# the training run
java -cp "$CP" club.podlodka.snowball.adapter.cli.RunExperiment --split=training --arm=trained

# offline: what a different lesson-key policy would be worth, no model and no memory quota spent
java -cp "$CP" club.podlodka.snowball.adapter.cli.AnalyzeLessonKeys
```

Needs `XMEM_API_KEY` and an instance id in the environment, plus an OpenAI-compatible model
endpoint. Copy [`.env.example`](.env.example); the filled-in file is never committed.

## Self-learning loop

![Self-learning promotion agent](assets/self-learning-loop.svg)

**scenario -> decision -> simulated outcome -> PromotionCase -> Lesson -> lesson read -> changed
next decision**

## Architecture as built

![High-level architecture](assets/high-level-architecture.svg)

The committed diagrams and the component documents under `docs/` describe the **original design**.
Two of its decisions were deliberately reversed during the sprint, and the diagrams were left as
they were because they are committed artifacts. Where this section and a diagram disagree, this
section is what the code does.

- **Scenario Generator** reads baseline market data through a source adapter, normalizes it, adds
  deterministic weather/event/day context, and publishes one validated event per scenario.
- **Promotion Agent** consumes scenarios, reads the relevant Lessons from **xmemory**, keeps at
  most three, asks the model for one discount, validates it against the committed schema, and
  journals the decision before handing it on.
- **Market Simulator** applies a hidden deterministic market model and produces one versioned
  `PromotionOutcomeV1` for the chosen action only.
- **Evaluator** replays all four discounts through the same simulation capability, picks the
  oracle-best action, computes regret, and creates one immutable **PromotionCase**.
- **Learner** assigns the case to its Lesson buckets, recomputes each from all linked cases, and
  writes them back.
- **xmemory** persists SKU, PromotionCases, Lessons and their relations across restarts.

**No Kafka.** The design had two topics; the transport was deferred to in-process handoff behind
ports, because a broker costs sprint days and buys nothing a benchmark can measure. The decision
and its consequences are written down in the `adopt-in-process-transport` OpenSpec change. It was
deferred, not ruled out: every component talks through a port, so a transport can be dropped in
without touching component logic.

**No framework.** Plain Kotlin on the JVM, no Spring. The dependency list is Jackson, a JSON Schema
validator, and test libraries.

**The decision journal is in memory, not H2.** It gives idempotency inside a run - a scenario is
never decided twice - but it does not survive a restart. A durable journal remains an open task in
the OpenSpec change; today an interrupted run starts over, and that has cost us one full training
run.

## Component deep dives

### Scenario Generator

![Scenario Generator architecture](assets/scenario-generator-architecture.svg)

- source adapters hide whether baseline data comes from a fixture, SAP, database, or API;
- context enrichment adds normalized weather/event/day context, derived deterministically;
- one validated immutable event per scenario, against the committed contract;
- downstream depends only on the versioned event contract, never on source-specific types.

The market is fixed to **London Central** (`LONDON_CENTRAL`, `Europe/London`) and the baseline
source is a fixture prepared offline from **dunnhumby Breakfast at the Frat**: 300 product-days,
250 for training and 50 held out, split by date rather than at random. The raw public dataset never
becomes a runtime dependency.

- Detailed design: [`docs/scenario-generator/README.md`](docs/scenario-generator/README.md)
- Dataset preparation: [`docs/scenario-generator/dataset-preparation.md`](docs/scenario-generator/dataset-preparation.md)
- JSON Schema: [`docs/scenario-generator/promotion-scenario-v1.schema.json`](docs/scenario-generator/promotion-scenario-v1.schema.json)
- Example event: [`docs/scenario-generator/promotion-scenario-v1.example.json`](docs/scenario-generator/promotion-scenario-v1.example.json)

### Promotion Agent

![Promotion Agent runtime flow](assets/promotion-agent-flow.svg)

- validate the incoming scenario against its contract before anything with a consequence happens;
- use `scenario_id` as the idempotency key in the decision journal;
- walk the lesson buckets strictest first, stop at the first hit within each scope, keep at most
  three lessons;
- build the same prompt for clean-memory and trained-memory runs, so the delta is the memory;
- ask the model for exactly one action from `0 | 10 | 20 | 30`;
- validate the answer, retry once, then fall back to a deterministic `0%`, recorded as its own
  decision source so an outage cannot masquerade as a cautious agent;
- persist the decision before handing it on, and mark it complete only after it is accepted.

The agent never receives the simulator, by construction and by test: it cannot replay the four
actions and read off the answer.

- Runtime flow: [`docs/promotion-agent/FLOW.md`](docs/promotion-agent/FLOW.md)
- Detailed design: [`docs/promotion-agent/README.md`](docs/promotion-agent/README.md)
- JSON Schema: [`docs/promotion-agent/promotion-decision-v1.schema.json`](docs/promotion-agent/promotion-decision-v1.schema.json)

### Market Simulator

![Market Simulator architecture](assets/market-simulator-architecture.svg)

- a pure deterministic `SimulationEngine`;
- category and context demand factors plus context-sensitive promotion elasticity;
- units capped by stock, gross profit with fixed rounding;
- a deterministic SHA-256 shock derived from the scenario id **and not from the discount**, so all
  four actions are played against the same market and their difference is the effect of the
  discount rather than noise;
- one versioned `PromotionOutcomeV1` with `units_sold` and `gross_profit`.

**Its scope ends at `PromotionOutcomeV1`.** It does not compute oracle actions or regret, create
cases, update lessons, or write memory. Hidden coefficients never reach the agent's prompt or the
memory.

- Detailed design: [`docs/market-simulator/README.md`](docs/market-simulator/README.md)
- Outcome JSON Schema: [`docs/market-simulator/promotion-outcome-v1.schema.json`](docs/market-simulator/promotion-outcome-v1.schema.json)

### Evaluator / Learner

![Evaluator / Learner architecture](assets/evaluator-learner-architecture.svg)

**take one finished promotion -> compare all four discounts -> save one evaluated PromotionCase ->
recompute the lessons it supports.**

```text
Agent chose 10% -> 252

Replay:
0%  -> 240
10% -> 252
20% -> 281  <- best
30% -> 263

Regret = 29
```

That becomes one immutable PromotionCase. The case then feeds **six** lesson buckets, not two:
three levels of generality - all conditions, then without weather, then without any - for each of
two scopes, the exact product and its category. Reads walk them strictest first and stop at the
first hit, so a covered scenario costs the same two reads it always did. The design started with
one bucket per scope; the benchmark showed the ceiling was coverage rather than lesson quality, and
the cascade cut total regret from 12.17 to 7.18 while raising coverage from 44 of 50 to all 50.

Each Lesson is recomputed from all linked cases, so new evidence can strengthen a recommendation or
overturn it. No model does arithmetic or picks the recommendation.

- Detailed design and examples: [`docs/evaluator-learner/README.md`](docs/evaluator-learner/README.md)

### xmemory

![xmemory schema](assets/xmemory-schema.svg)

- **SKU** - stable product identity and basic economics.
- **PromotionCase** - immutable evaluated evidence: scenario, chosen discount, outcome, all four
  replay profits, simulator optimum, and regret.
- **Lesson** - compact reusable knowledge recomputed from linked cases and read before later
  decisions.

Each Lesson links back to the cases that produced it, which is what makes the write -> read ->
changed-behaviour trace visible and reproducible.

Writes use `structured_mutations`, which the service applies without a model - that is what makes a
few thousand records affordable. Reads ask for `raw-tables` rather than a natural-language answer,
because the same scoped read returns a different amount of the record depending on how the query is
worded. Everything measured about this service, including several counterintuitive behaviours that
cost us a day each, is in [`GOTCHAS.md`](GOTCHAS.md).

- Detailed design: [`docs/xmemory/README.md`](docs/xmemory/README.md)
- XMD v1 schema: [`docs/xmemory/schema.xmd.yaml`](docs/xmemory/schema.xmd.yaml)

## Benchmark

![Benchmark clean memory vs learned memory](assets/benchmark.svg)

The same agent runs against **clean** and **trained** memory in separate instances. Everything else
is held constant: same model, same prompt, same simulator version, same scenarios in the same
order. Training uses the 250 training scenarios with learning on; both benchmark arms use the 50
held-out scenarios with learning off, so measurement cannot create new lessons - and a test proves
a run with learning disabled performs zero writes.

Results, caveats and every raw log are in [`runs/2026-09-04/`](runs/2026-09-04/), including a
side-by-side page and the things these numbers do **not** establish.

The claim is deliberately narrow: **the same agent makes better decisions because accumulated
memory changes its behaviour.**

- Benchmark notes: [`docs/benchmark/README.md`](docs/benchmark/README.md)
