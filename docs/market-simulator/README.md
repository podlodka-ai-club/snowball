# Market Simulator

The Market Simulator is the hidden market world of the hackathon. Its responsibility is deliberately narrow: consume one validated promotion decision and produce one deterministic business outcome for that exact `(scenario, discount)` pair.

![Market Simulator architecture](../../assets/market-simulator-architecture.svg)

Editable diagram source: [`architecture.mmd`](architecture.mmd)  
Outcome contract: [`promotion-outcome-v1.schema.json`](promotion-outcome-v1.schema.json)  
Example outcome: [`promotion-outcome-v1.example.json`](promotion-outcome-v1.example.json)

## Scope

The Market Simulator owns:

- hidden market coefficients;
- deterministic simulation of one `(scenario, discount)` pair;
- stock-constrained units sold;
- gross-profit calculation;
- deterministic scenario noise;
- simulator versioning;
- creation of `PromotionOutcomeV1`.

The Market Simulator does **not** own:

- replay orchestration across all four actions;
- oracle-best discount selection;
- regret calculation;
- `PromotionCase` creation;
- `Lesson` creation or update;
- xmemory reads or writes;
- benchmark metrics.

Those responsibilities belong to the separate Evaluator / Learner scope. Deployment may still place the components in one Spring Boot process for the MVP, but that is only a deployment convenience. The architectural boundary of this document ends at `PromotionOutcomeV1`.

```text
promotion.decisions.v1
        |
        v
Market Simulator
        |
        v
PromotionOutcomeV1
        |
        +---- Market Simulator scope ends here
```

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
5. hand the outcome to a downstream `OutcomeSink` port;
6. acknowledge the Kafka offset only after the downstream handoff succeeds.

Conceptual boundary:

```kotlin
interface OutcomeSink {
    fun accept(outcome: PromotionOutcomeV1)
}
```

The Evaluator may implement this port in the MVP, but its behavior is intentionally outside this document.

Simulator-specific validation:

- discount is exactly `0 | 10 | 20 | 30`;
- category exists in simulator v1 configuration;
- `price > 0`;
- `0 <= cost < price`;
- `stock >= 0`;
- `baseline_sales >= 0`;
- every normalized context bucket has configured coefficients.

`temperature_c`, `stock_level`, and `event_note` remain in the scenario snapshot. In v1, `temperature_c` and `event_note` do not directly change arithmetic because `weather` and `event_type` are already normalized upstream. `stock_level` is a learnable coarse feature; exact `stock` is the simulator constraint.

Invalid events are permanent failures: do not simulate or acknowledge them; log the reason, stop the listener, and fail health checks. For transient downstream handoff failures, retry with short bounded backoff (`250 ms`, `1 s`, `3 s`), then stop unhealthy without acknowledging the input.

## Idempotency

Use stable IDs:

```text
decision_id = DEC-<scenario_id>
outcome_id  = OUT-<decision_id>
```

The simulator needs no database in the MVP. Reprocessing the same decision under the same `SIMULATOR_VERSION` recomputes identical business values and the same `outcome_id`.

The downstream consumer should use `outcome_id` or `decision_id` as its own idempotency key. If the same ID ever appears with different business values or simulator version, fail loudly instead of silently replacing evidence.

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

The same pure function can be called later by another component for counterfactual replay. The simulator exposes the capability; deciding which actions to replay and what to learn from them is outside Market Simulator scope.

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

Margin, elasticity, context affinity, and stock should make different actions win. 0% can win with weak lift or thin margin; 10% can win when stock caps deeper discounts; 20% can win in promotion-responsive contexts; 30% may win for high-margin elastic SKUs but often increases units while destroying profit.

Calibrate these values against the prepared fixture before the first official benchmark and then freeze them. After training evidence exists, changing formula, coefficients, noise, or rounding requires a new simulator version.

## Stock

```text
units_sold = min(stock, demand_units)
```

A deeper discount may generate more demand without selling more units once stock is exhausted, leaving only lower margin. This creates a useful learnable interaction instead of a global discount lookup table.

## Deterministic noise

Simulator v1 uses one small scenario shock:

```text
noise range = [0.98, 1.02]
```

The same scenario uses the same noise regardless of discount.

Exact algorithm:

1. UTF-8 encode `v1|<scenario_id>`.
2. SHA-256.
3. Read the first 8 digest bytes as unsigned big-endian integer `u`.
4. Compute:

```text
unit = u / 18446744073709551615
noise = round6(0.98 + 0.04 * unit)
```

Discount is not in the key. This ensures any later counterfactual caller compares actions under the same market shock.

The noise key/factor may be logged internally for reproducibility, but must not be written to xmemory, the Promotion Agent prompt, or the outcome contract.

## Outcome contract

`PromotionOutcomeV1` is the complete business output of Market Simulator.

It carries:

- `outcome_id`, `decision_id`, `scenario_id`;
- `simulated_at`;
- `simulator_version`;
- unchanged scenario snapshot;
- chosen discount;
- `units_sold`;
- `gross_profit`.

It does not carry coefficients, promotion affinity, noise, raw demand, oracle action, regret, counterfactual profits, `PromotionCase`, or `Lesson`.

There is deliberately no `promotion.outcomes.v1` Kafka topic yet. For the MVP the outcome can cross an in-process `OutcomeSink` boundary. If the components are split into separate processes later, this exact versioned contract can become the Kafka payload without changing the Market Simulator domain.

## Failure and observability

Fail application startup if simulator configuration is incomplete or invalid. A simulation failure produces no outcome. A downstream handoff failure must not acknowledge the source Kafka message.

Structured logs for chosen-action simulation should contain:

- `scenario_id`, `decision_id`, `outcome_id`;
- discount, units sold, gross profit;
- `simulator_version`;
- SHA-256 noise-key prefix and noise factor;
- timestamp.

Simulator logs must not be available to the Promotion Agent or xmemory retrieval path.

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
market-simulator/
  src/main/kotlin/.../
    domain/
      SimulationResult.kt
      PromotionOutcomeV1.kt
    application/
      SimulationEngine.kt
      SimulationService.kt
    port/
      OutcomeSink.kt
    config/
      SimulatorV1Config.kt
      SimulatorConfigValidator.kt
    adapter/kafka/
      DecisionListener.kt
```

Do not put Evaluator, Learner, `PromotionCase`, Lesson logic, or xmemory adapters in this package. If the MVP uses one deployable runtime, keep them in sibling modules/packages with explicit interfaces.

## MVP implementation order

1. Create Kotlin DTOs from decision/outcome schemas.
2. Implement and test deterministic SHA-256 noise.
3. Implement pure `SimulationEngine`.
4. Add table-driven category/discount tests and stock-cap tests.
5. Snapshot-test the committed example outcome.
6. Consume `promotion.decisions.v1` and hand `PromotionOutcomeV1` to an `OutcomeSink`.
7. Test that the same `(scenario, discount, simulator version)` reproduces exactly after restart.
8. Only then connect a separate Evaluator / Learner implementation to the output port.

The Market Simulator proof is deliberately modest: **same scenario + same action + same simulator version = same outcome, with hidden ground truth staying hidden.**
