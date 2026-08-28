## Purpose

Close the autonomous learning loop by deterministically evaluating each finished promotion against every allowed action and converting complete counterfactual evidence into durable reusable Lessons.

## ADDED Requirements

### Requirement: Complete promotion input
Evaluator/Learner SHALL accept the exact scenario snapshot, chosen discount, chosen outcome, and simulator version from `PromotionOutcomeV1`.

#### Scenario: Outcome is accepted
- **WHEN** a complete valid Market Simulator outcome is handed off
- **THEN** evaluation SHALL use that immutable scenario/action/outcome as its reference observation

### Requirement: Four-action counterfactual replay
The evaluator SHALL replay exactly `0`, `10`, `20`, and `30` percent through the same pure simulation capability with all other scenario inputs and simulator version unchanged.

#### Scenario: Evaluation succeeds
- **WHEN** all four replay calls succeed
- **THEN** the evaluator SHALL possess one comparable gross-profit result for every allowed action

#### Scenario: Any replay fails
- **WHEN** one or more action replays cannot be completed
- **THEN** no new PromotionCase or Lesson evidence SHALL be written

### Requirement: Chosen-action consistency check
The evaluator SHALL verify that replaying the chosen action reproduces the supplied chosen outcome business values.

#### Scenario: Replay differs from original outcome
- **WHEN** chosen-action replay does not match original units/profit under the same simulator version
- **THEN** evaluation SHALL fail as an integrity error and SHALL create no learning evidence

### Requirement: Deterministic oracle and regret
The evaluator SHALL choose the action with highest gross profit, preferring the lower discount on exact ties, and SHALL calculate regret as best gross profit minus chosen gross profit.

#### Scenario: Unique best action exists
- **WHEN** one action has the highest gross profit
- **THEN** that action SHALL be the oracle best and regret SHALL use its profit

#### Scenario: Best profits tie
- **WHEN** multiple discounts have exactly equal highest gross profit
- **THEN** the lowest tied discount SHALL be selected as best

### Requirement: Immutable PromotionCase
A successful complete evaluation SHALL create deterministic `CASE-<simulator_version>-<scenario_id>` evidence containing scenario context, chosen result, all four replay profits, best action/profit, and regret metrics.

#### Scenario: Same promotion is evaluated again
- **WHEN** an identical case already exists
- **THEN** the evaluator SHALL verify/reuse it rather than create duplicate evidence

### Requirement: Exactly two Lesson buckets
Each PromotionCase SHALL contribute to exactly two v1 Lesson keys: one `sku:<sku_id>` bucket and one `category:<category>` bucket using `store:any`, current `day_type`, current `weather`, `event:any`, and current `stock_level`.

#### Scenario: Case is learned
- **WHEN** a complete new case is eligible for learning
- **THEN** exactly the deterministic SKU and category Lesson identities SHALL be targeted

### Requirement: Deterministic Lesson aggregation
For each Lesson, application code SHALL aggregate the four stored replay-profit columns across unique linked cases and recommend the action with greatest aggregate profit, preferring lower discount on exact ties.

#### Scenario: Lesson has multiple cases
- **WHEN** linked PromotionCases are recomputed
- **THEN** recommendation SHALL derive from aggregate numeric evidence rather than LLM judgment or prior prose

### Requirement: Deterministic Lesson strength
Evaluator/Learner SHALL calculate evidence count, average profit advantage percentage, confidence, and rationale using the documented formulas and rounding.

#### Scenario: Lesson is updated
- **WHEN** recommendation/evidence is recomputed
- **THEN** persisted strength fields SHALL be reproducible from the same unique linked PromotionCases

### Requirement: Contradictory evidence updates in place
New evidence SHALL update the same deterministic Lesson key even when it changes the recommended action.

#### Scenario: Aggregate winner changes
- **WHEN** newly linked cases make another discount highest in aggregate profit
- **THEN** the existing Lesson SHALL be updated to the new recommendation with recomputed strength and provenance

### Requirement: Learning can be disabled
Evaluator/Learner SHALL support evaluation with xmemory writes disabled for benchmark measurement.

#### Scenario: Learning disabled benchmark
- **WHEN** `LEARNING_ENABLED=false`
- **THEN** all four replays, oracle selection, and regret SHALL still be available while PromotionCase/Lesson writes SHALL be skipped

### Requirement: Evaluator boundary is separate from market simulation
Evaluator/Learner SHALL orchestrate replay, oracle, regret, and learning outside the Market Simulator chosen-action component boundary.

#### Scenario: Chosen outcome is produced
- **WHEN** Market Simulator finishes `PromotionOutcomeV1`
- **THEN** simulator responsibility SHALL be complete before evaluator replay/learning logic begins
