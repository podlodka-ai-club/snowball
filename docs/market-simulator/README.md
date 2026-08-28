# Market Simulator

The Market Simulator is the hidden market world of the hackathon. It consumes one validated promotion decision, simulates the chosen action, and exposes the exact same deterministic engine to the Evaluator for counterfactual replay.

![Market Simulator architecture](../../assets/market-simulator-architecture.svg)

Editable diagram source: [`architecture.mmd`](architecture.mmd)  
Outcome contract: [`promotion-outcome-v1.schema.json`](promotion-outcome-v1.schema.json)  
Example outcome: [`promotion-outcome-v1.example.json`](promotion-outcome-v1.example.json)

## Design goal

Keep this boundary strict:

```text
Promotion Agent: scenario + Lessons + allowed discounts
Market Simulator: hidden coefficients + deterministic market formula
Evaluator: observed outcome + four replay results
```

Simulator coefficients, noise, oracle action, and current counterfactual results must never enter the Promotion Agent prompt or xmemory.

## Runtime boundary

For the MVP, **do not deploy Market Simulator as a separate service**. Market Simulator and Evaluator / Learner are separate modules inside one Kotlin/Spring Boot process.

```text
promotion.decisions.v1
        |
        v
DecisionListener -> SimulationEngine -> PromotionOutcomeV1 -> Evaluator
                         ^                         |
                         +---- replay 0/10/20/30 --+
```

This keeps the hidden market model encapsulated without adding a third service, a third Kafka topic, or four replay messages per case. `SimulationEngine` must remain a pure module with no Kafka, xmemory, HTTP, model, or clock dependency.

## Kafka consumer

```text
topic: promotion.decisions.v1
consumer group: market-simulator-v1
concurrency: 1
offsets: manual acknowledgement
```

Flow:

1. validate raw JSON against Promotion Decision v1;
2. validate simulator invariants;
3. call `SimulationEngine`;
4. build `PromotionOutcomeV1`;
5. hand it to Evaluator in-process;
6. acknowledge the Kafka offset only after Evaluator accepts it successfully.

Simulator-specific validation:

- discount is exactly `0 | 10 | 20 | 30`;
- category exists in simulator v1 configuration;
- `price > 0`;
- `0 <= cost < price`;
- `stock >= 0`;
- `baseline_sales >= 0`;
- every normalized context bucket has configured coefficients.

`temperature_c`, `stock_level`, and `event_note` remain in the scenario snapshot. In v1, `temperature_c` and `event_note` do not directly change arithmetic because `weather` and `event_type` are already normalized upstream. `stock_level` is a learnable coarse feature; exact `stock` is the simulator constraint.

Invalid events are permanent failures: do not simulate or acknowledge them; log the reason, stop the listener, and fail health checks. For transient Evaluator failures retry with short bounded backoff (`250 ms`, `1 s`, `3 s`), then stop unhealthy without acknowledging the input.

## Idempotency

Use stable IDs:

```text
decision_id = DEC-<scenario_id>
outcome_id  = OUT-<decision_id>
```

The simulator needs no database in the MVP. Reprocessing the same decision under the same `SIMULATOR_VERSION` recomputes identical business values and the same `outcome_id`.

Evaluator / Learner should later use `outcome_id` or `decision_id` as its persistence idempotency key. If the same ID ever appears with different business values or simulator version, fail loudly instead of overwriting evidence.

## Pure engine contract

```kotlin
interface SimulationEngine {
    fun simulate(
        scenarioId: String,
        scenario: PromotionScenario,
        discount: Int
    ): SimulationResult
}

data class SimulationResult(
    val unitsSold: Int,
    val grossProfit: BigDecimal
)
```

The result depends only on `scenarioId`, scenario fields, discount, and immutable simulator configuration.

## Simulator v1 formula

```text
discounted_price =
round_money(price * (1 - discount / 100))

context_demand =
baseline_sales
x day_demand_factor
x weather_demand_factor
x event_demand_factor

promotion_affinity =
day_promo_factor
x weather_promo_factor
x event_promo_factor

discount_effect =
1 + base_discount_lift(category, discount) * promotion_affinity

raw_demand =
context_demand
x discount_effect
x deterministic_noise

demand_units = round_units(raw_demand)
units_sold = min(stock, max(0, demand_units))

gross_profit =
round_money(units_sold * (discounted_price - cost))
```

Context demand and promotion affinity are separate deliberately. If weather/weekend/event were only common demand multipliers, they would mostly cancel when comparing discounts and memory would have little contextual behavior to learn.

Rounding:

- money: `BigDecimal`, scale `2`, `HALF_UP`;
- deterministic noise: scale `6`, `HALF_UP`;
- other intermediate coefficient multiplication is not rounded;
- units: scale `0`, `HALF_UP`;
- gross profit uses the already rounded discounted unit price;
- negative gross profit is allowed if discount pushes price below cost.

## Hidden coefficients

Initial simulator v1 base discount lift:

| Category | 0% | 10% | 20% | 30% |
| --- | ---: | ---: | ---: | ---: |
| `ice_cream` | 0.00 | 0.24 | 0.60 | 1.00 |
| `beer` | 0.00 | 0.22 | 0.50 | 0.82 |
| `soft_drinks` | 0.00 | 0.24 | 0.58 | 0.95 |
| `chips` | 0.00 | 0.28 | 0.66 | 1.05 |
| `meat` | 0.00 | 0.20 | 0.48 | 0.78 |
| `yogurt` | 0.00 | 0.24 | 0.56 | 0.92 |

Weekend demand / promo factors; weekday is `1.00 / 1.00`:

| Category | Demand | Promo |
| --- | ---: | ---: |
| `ice_cream` | 1.15 | 1.15 |
| `beer` | 1.15 | 1.20 |
| `soft_drinks` | 1.10 | 1.10 |
| `chips` | 1.10 | 1.10 |
| `meat` | 1.10 | 1.05 |
| `yogurt` | 1.00 | 1.05 |

Weather demand / promo factors; normal is `1.00 / 1.00`:

| Category | Hot | Rain |
| --- | ---: | ---: |
| `ice_cream` | 1.25 / 1.35 | 0.85 / 0.80 |
| `beer` | 1.10 / 1.15 | 0.90 / 0.90 |
| `soft_drinks` | 1.15 / 1.25 | 0.95 / 0.90 |
| `chips` | 1.00 / 1.05 | 1.05 / 1.10 |
| `meat` | 0.95 / 0.95 | 1.00 / 1.00 |
| `yogurt` | 1.00 / 1.00 | 1.00 / 1.05 |

Local-event demand / promo factors; none is `1.00 / 1.00`:

| Category | Demand | Promo |
| --- | ---: | ---: |
| `ice_cream` | 1.10 | 1.10 |
| `beer` | 1.20 | 1.25 |
| `soft_drinks` | 1.15 | 1.15 |
| `chips` | 1.15 | 1.20 |
| `meat` | 1.05 | 1.05 |
| `yogurt` | 1.00 | 1.00 |

Margin, elasticity, context affinity, and stock should make different actions win. 0% can win with weak lift/thin margin; 10% can win when stock caps deeper discounts; 20% can win in promotion-responsive contexts; 30% may win for high-margin elastic SKUs but often increases units while destroying profit.

Calibrate these values against the prepared fixture before the first official benchmark and then freeze them. After training evidence exists, changing formula, coefficients, noise, or rounding requires a new simulator version.

## Stock

```text
units_sold = min(stock, demand_units)
```

This is intentionally important. A deeper discount may generate more demand without selling more units once stock is exhausted, leaving only lower margin. In the committed example, 20% already reaches the `320`-unit stock cap; 30% creates more demand but still sells `320`, so profit drops sharply.

## Deterministic noise

Simulator v1 uses one small scenario shock:

```text
noise range = [0.98, 1.02]
```

The same scenario uses the same noise for all four actions.

Exact algorithm:

1. UTF-8 encode `v1|<scenario_id>`.
2. SHA-256.
3. Read the first 8 digest bytes as unsigned big-endian integer `u`.
4. Compute:

```text
unit = u / 18446744073709551615
noise = round6(0.98 + 0.04 * unit)
```

Discount is not in the key.

For `SCN-20260718-LONDON_CENTRAL-ICE500`, digest prefix is `a0756941c651c1c0` and noise is `1.005072`.

The noise key/factor may be logged internally for reproducibility, but must not be written to xmemory, the decision prompt, or the outcome contract.

## Outcome contract

`PromotionOutcomeV1` is a versioned **in-process** contract for the MVP, defined by [`promotion-outcome-v1.schema.json`](promotion-outcome-v1.schema.json).

It carries:

- `outcome_id`, `decision_id`, `scenario_id`;
- `simulated_at`;
- `simulator_version`;
- unchanged scenario snapshot;
- chosen discount;
- `units_sold`;
- `gross_profit`.

It does not carry coefficients, affinity, noise, raw demand, oracle action, or counterfactual profits.

There is deliberately no `promotion.outcomes.v1` Kafka topic yet. If the runtime is split later, this contract can become that topic payload; publish must then succeed before acknowledging `promotion.decisions.v1`.

## Replay for Evaluator

Evaluator calls the same engine directly:

```kotlin
val results = listOf(0, 10, 20, 30)
    .associateWith { d ->
        simulationEngine.simulate(
            outcome.scenarioId,
            outcome.scenario,
            d
        )
    }
```

No replay HTTP endpoint and no replay Kafka flow.

Market Simulator owns deterministic simulation of one `(scenario, discount)` pair and chosen-action outcome generation.

Evaluator / Learner owns all-four-action replay, oracle selection, regret, PromotionCase persistence, and Lesson creation/update.

## Failure and observability

Fail application startup if simulator configuration is incomplete or invalid. A replay failure aborts evaluation; never write a partial PromotionCase. Outcome-publication failure is not applicable until an outcome Kafka topic exists.

Structured logs for chosen-action simulation should contain:

- `scenario_id`, `decision_id`, `outcome_id`;
- discount, units sold, gross profit;
- `simulator_version`;
- SHA-256 noise-key prefix and noise factor;
- timestamp.

Replay logs should include the same `scenario_id` and `simulator_version` plus replay discount. Simulator logs must not be available to the Promotion Agent.

## Versioning

```text
SIMULATOR_VERSION=v1
```

The version covers formula, coefficient tables, noise algorithm/range, rounding, and supported context buckets. Configuration is loaded once at startup and is not hot-reloaded.

Clean-memory and trained-memory benchmarks must use the same:

```text
scenario IDs
Promotion Agent model
Promotion Agent prompt
simulator version
simulator configuration
```

## Suggested Kotlin structure

```text
simulation-learning-runtime/
  src/main/kotlin/.../
    simulator/
      domain/
        SimulationResult.kt
        PromotionOutcomeV1.kt
      application/
        SimulationEngine.kt
      config/
        SimulatorV1Config.kt
        SimulatorConfigValidator.kt
      adapter/kafka/
        DecisionListener.kt
    evaluator/
      ...
```

Keep `SimulatorV1Config` inside the simulator module; never place it in a shared module imported by Promotion Agent.

## MVP implementation order

1. Create Kotlin DTOs from decision/outcome schemas.
2. Implement and test deterministic SHA-256 noise.
3. Implement pure `SimulationEngine`.
4. Add table-driven category/discount tests and stock-cap tests.
5. Add replay tests proving one scenario gets the same noise across all actions.
6. Snapshot-test the committed example outcome.
7. Consume `promotion.decisions.v1` and hand outcomes to an Evaluator port.
8. Only after the full learning loop works, consider extracting a simulator service or adding an outcome Kafka topic.

The proof is simple: the same scenario/action reproduces the same result after restart, all four replays use the same hidden market, and Promotion Agent cannot access the answer key.
