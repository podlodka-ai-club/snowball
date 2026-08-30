# TODO: Design Market Simulator

Use this prompt in a future session to continue the HackerSprint2 project.

## Prompt

We are building a HackerSprint2 project: a self-learning FMCG Promotion Agent with persistent xmemory.

Repository:

`zu50052f/hacker-sprint-2`

Before making changes, inspect the current repository, especially:

- `README.md`
- `docs/architecture/`
- `docs/scenario-generator/`
- `docs/promotion-agent/`
- `docs/xmemory/`
- `docs/benchmark/`

The current autonomous loop is:

`Scenario -> Promotion Agent -> Market Simulator -> Evaluator/Learner -> PromotionCase + Lesson in xmemory -> next Scenario`

The Promotion Agent chooses exactly one action:

- 0% discount
- 10%
- 20%
- 30%

Promotion duration is fixed to one day.

Human is completely removed from the MVP loop.

The Scenario Generator publishes:

`promotion.scenarios.v1`

The Promotion Agent consumes that topic, retrieves relevant xmemory Lessons, chooses one discount, journals the decision durably, and publishes:

`promotion.decisions.v1`

The decision event contains:

- `decision_id`
- `scenario_id`
- `decided_at`
- the normalized scenario snapshot
- the validated discount

The Market Simulator should consume `promotion.decisions.v1` and act as a small deterministic hidden market world.

## Goal of this task

Design the **Market Simulator architecture and contracts only**. Do not implement the runtime yet.

Keep the design at the same level of detail and simplicity as `docs/scenario-generator/` and `docs/promotion-agent/`.

The simulator must be reproducible enough for benchmark comparisons while still supporting controlled stochastic noise if useful.

The hidden simulator model must NEVER be exposed to the Promotion Agent or stored in xmemory.

## Main design questions to resolve

### 1. Runtime boundary

Decide whether Market Simulator should be:

- part of the same Kotlin/Spring Boot runtime as another component, or
- a separate small service.

Prefer the smallest architecture that preserves a clear experimental boundary.

Do not create a service merely because the diagram has room for another rectangle.

### 2. Kafka consumer

Define:

- topic: `promotion.decisions.v1`
- consumer group
- validation behavior
- retry behavior
- offset commit policy
- duplicate handling

Use `decision_id` and/or `scenario_id` deliberately for idempotency.

Avoid exactly-once complexity.

### 3. Simulator formula

Start from the existing conceptual model:

```text
demand =
baseline_demand
x weekday_effect
x weather_effect
x event_effect
x discount_effect
x noise

units_sold = min(stock, demand)

gross_profit =
units_sold x (discounted_price - cost)
```

Define the exact MVP calculation order and rounding rules.

The simulator must support the existing scenario fields:

- category / sku
- price
- cost
- stock
- baseline_sales
- stock_level
- day_type
- weather
- temperature_c
- event_type

Do not invent a huge retail model. The goal is a controlled world that produces understandable promotion trade-offs.

### 4. Hidden coefficients

Define where simulator coefficients live.

They should be explicit configuration/code owned by Market Simulator, for example:

- weekday/weekend multipliers
- weather multipliers by category
- event multiplier
- discount-response curves by category or SKU
- noise configuration

These coefficients are hidden ground truth.

They must NOT appear in:

- Promotion Agent prompt
- decision event
- xmemory
- Lesson rationale as direct coefficient disclosure

The learner should infer useful behavior only from evaluated outcomes.

### 5. Noise and reproducibility

Decide whether the MVP should use noise.

Strong preference:

- use deterministic pseudo-random noise seeded from stable scenario identity, or
- use no noise initially and add deterministic noise later.

The same scenario + same action must reproduce the same outcome when replayed by the Evaluator.

The Evaluator must not get a different market because someone called the simulator five milliseconds later.

Define the seed strategy precisely if noise is used.

### 6. Discount response

Design discount elasticity so that:

- 0% can sometimes be optimal because margin is already good;
- 10% and 20% are often useful;
- 30% can increase units but destroy margin;
- optimal action varies with category/context;
- memory can realistically improve decisions.

Avoid making one discount globally optimal.

The benchmark needs enough structure for Lessons such as:

`hot weekend + ice cream + high stock -> 20% often wins`

while other contexts should support different actions.

### 7. Stock effects

Stock must constrain sales:

`units_sold = min(stock, demand)`

Think through cases where deeper discounts create no extra profit because stock caps the response.

This is useful because it gives the agent a learnable interaction instead of a trivial discount lookup table.

### 8. Simulator output contract

Define a versioned output contract for the chosen action outcome.

Preferred direction:

`promotion.decisions.v1 -> Market Simulator -> promotion.outcomes.v1 -> Evaluator/Learner`

Evaluate whether Kafka is justified for this boundary.

A likely event shape is:

```json
{
  "event_type": "promotion.outcome.created",
  "schema_version": 1,
  "outcome_id": "...",
  "decision_id": "...",
  "scenario_id": "...",
  "simulated_at": "...",
  "scenario": { ... },
  "decision": {
    "discount": 20
  },
  "outcome": {
    "units_sold": 128,
    "gross_profit": 256.0
  }
}
```

Do not expose hidden coefficient values in the event.

Keep the event sufficient for the Evaluator to reconstruct the chosen-action result without joining unrelated topics.

### 9. Replay API for Evaluator

The Evaluator must replay the exact same scenario under all four actions:

- 0
- 10
- 20
- 30

Decide the cleanest contract for this.

Possible minimal choices:

- a local/internal `SimulationEngine.simulate(scenario, discount)` port reused by both runtime consumer and Evaluator;
- a small synchronous internal HTTP replay endpoint;
- another Kafka flow.

Strongly prefer a deterministic reusable simulation engine over creating four extra Kafka messages per case.

The hidden ground truth should remain encapsulated inside Market Simulator code/module.

### 10. Idempotency

Define how duplicate decision events are handled.

The same `decision_id` must not generate multiple logically different outcomes.

Prefer a small durable mechanism only if necessary. Reuse existing architectural patterns where sensible rather than creating a new database empire.

### 11. Failure behavior

Define behavior when:

- decision event is invalid
- discount is not one of 0/10/20/30
- duplicate decision arrives
- outcome publication fails
- replay request fails
- simulator configuration is invalid

The benchmark should fail loudly on invalid simulator configuration rather than silently changing the market model.

### 12. Observability

For each chosen-action simulation log/store at minimum:

- scenario_id
- decision_id
- discount
- units_sold
- gross_profit
- simulator version
- deterministic seed if noise exists
- timestamp

Do NOT log hidden coefficient values into anything that the Promotion Agent can retrieve.

It should be possible to demonstrate that chosen-action simulation and counterfactual replay used the same simulator version.

### 13. Simulator versioning

Introduce a small stable simulator version identifier, for example:

`SIMULATOR_VERSION=v1`

PromotionCase or observability metadata may record the version if useful, but do not let benchmark training and benchmark evaluation silently use different market models.

The clean-memory and trained-memory benchmark must use the same simulator version.

### 14. Contracts/docs to create

Follow the existing repo pattern:

```text
docs/market-simulator/
  README.md
  architecture.mmd
  promotion-outcome-v1.schema.json
  promotion-outcome-v1.example.json
```

Render:

`assets/market-simulator-architecture.svg`

Then update:

- root `README.md`
- `docs/architecture/README.md`
- `docs/architecture/high-level-architecture.mmd`
- `assets/high-level-architecture.svg`

Only change other docs if the simulator design genuinely requires it.

## Important boundary with Evaluator/Learner

Do NOT design the full learning logic in this task.

Market Simulator owns:

- the hidden market model
- deterministic simulation of one `(scenario, discount)` pair
- chosen-action outcome generation
- replay capability

Evaluator/Learner owns:

- replaying all four actions
- choosing the oracle-best discount
- regret calculation
- PromotionCase persistence
- Lesson creation/update

Keep that boundary explicit.

## Guiding principle

Keep the project small enough for two developers:

- deterministic contracts
- one understandable simulator
- reproducible outcomes
- hidden ground truth
- observable versioning

Avoid:

- complex retail forecasting
- ML training pipelines
- external weather APIs inside the simulator
- distributed exactly-once processing
- unnecessary Kafka topics
- separate objects for every counterfactual result

The final hackathon story remains:

```text
same model
same prompt
same simulator
same benchmark
clean memory   -> worse decisions
trained memory -> better decisions
```

Complete this task by committing the Market Simulator design and contracts to the repository. Do not implement the simulator runtime yet.
