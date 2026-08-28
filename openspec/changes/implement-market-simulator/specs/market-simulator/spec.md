## Purpose

Provide a hidden, deterministic and versioned market model that turns one valid promotion decision into reproducible units-sold and gross-profit ground truth without performing evaluation or learning.

## ADDED Requirements

### Requirement: Validated decision input
The Market Simulator SHALL validate `promotion.decisions.v1` against the committed decision contract and simulator invariants before simulation.

#### Scenario: Valid decision arrives
- **WHEN** the decision event has an allowed discount, supported category, valid price/cost/stock/baseline values, and configured context buckets
- **THEN** the event SHALL be simulated

#### Scenario: Permanent invalid decision arrives
- **WHEN** the event violates contract or simulator invariants
- **THEN** no outcome SHALL be produced and the listener SHALL fail observably rather than acknowledge corrupt ground truth

### Requirement: Pure deterministic simulation
The simulation result SHALL depend only on `scenario_id`, normalized scenario fields, chosen discount, and immutable simulator-version configuration.

#### Scenario: Simulation is repeated
- **WHEN** the same scenario, discount, and simulator version are evaluated after retry or restart
- **THEN** `units_sold` and `gross_profit` SHALL be exactly reproducible

### Requirement: Simulator v1 arithmetic
The simulator SHALL implement the documented v1 demand, promotion-affinity, discount-lift, stock-cap, and gross-profit formula using committed coefficient tables.

#### Scenario: Discount is simulated
- **WHEN** a supported scenario/action pair is evaluated
- **THEN** context demand and promotion affinity SHALL influence demand separately and stock SHALL cap final units sold

### Requirement: Deterministic rounding
Money SHALL use `BigDecimal` scale 2 `HALF_UP`, deterministic noise scale 6 `HALF_UP`, and units scale 0 `HALF_UP` according to the documented calculation order.

#### Scenario: Intermediate values contain fractions
- **WHEN** simulation arithmetic requires rounding
- **THEN** the final values SHALL match the documented rounding sequence and committed example expectations

### Requirement: Common scenario noise across actions
Simulator v1 SHALL derive one deterministic noise factor from SHA-256 of `v1|<scenario_id>` in the range `[0.98,1.02]`, without including discount in the noise key.

#### Scenario: Counterfactual discounts are replayed
- **WHEN** the same scenario is simulated at 0, 10, 20, and 30 percent
- **THEN** all four simulations SHALL use the same deterministic scenario shock

### Requirement: Versioned outcome contract
The simulator SHALL produce `PromotionOutcomeV1` matching `docs/market-simulator/promotion-outcome-v1.schema.json` with deterministic `OUT-<decision_id>` identity.

#### Scenario: Simulation completes
- **WHEN** chosen-action simulation succeeds
- **THEN** the outcome SHALL contain IDs, simulator version, unchanged scenario snapshot, chosen discount, units sold, and gross profit but SHALL omit hidden coefficients/noise/oracle/regret

### Requirement: Downstream handoff and acknowledgement
The Kafka source offset SHALL be acknowledged only after the `OutcomeSink` accepts the complete outcome.

#### Scenario: Outcome sink temporarily fails
- **WHEN** downstream handoff fails transiently
- **THEN** the simulator SHALL use bounded short retries and SHALL NOT acknowledge the source record until handoff succeeds

### Requirement: Simulator learning boundary
The Market Simulator SHALL NOT select oracle-best discounts, calculate regret, create PromotionCases/Lessons, access xmemory, or own benchmark metrics.

#### Scenario: Evaluator needs counterfactual simulation
- **WHEN** Evaluator/Learner requests another allowed discount for the same scenario
- **THEN** it MAY call the same pure simulation capability while replay orchestration and interpretation remain outside Market Simulator scope
