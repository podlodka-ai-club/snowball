## Purpose

Provide a reproducible promotion decision boundary that combines one validated scenario with bounded durable Lessons and emits exactly one restart-safe, replayable discount decision.

## ADDED Requirements

### Requirement: Scenario validation before side effects
The Promotion Agent SHALL validate raw `promotion.scenarios.v1` input against the committed scenario contract before memory, model, journal decision, or output publication work.

#### Scenario: Invalid scenario arrives
- **WHEN** a Kafka record violates the scenario schema or required invariants
- **THEN** it SHALL be observably rejected without calling xmemory or the decision model

### Requirement: Durable decision idempotency
The system SHALL use `scenario_id` as the journal idempotency key and `DEC-<scenario_id>` as deterministic `decision_id`.

#### Scenario: New scenario
- **WHEN** no terminal journal execution exists for the scenario
- **THEN** the agent SHALL create/recover the execution and produce at most one new semantic decision

#### Scenario: Completed scenario is redelivered
- **WHEN** journal status is `COMPLETED`
- **THEN** the agent SHALL acknowledge the input without calling xmemory or the model again

#### Scenario: Decided scenario is redelivered
- **WHEN** journal status is `DECIDED`
- **THEN** the agent SHALL republish the exact persisted decision payload without calling xmemory or the model again

### Requirement: Bounded Lesson retrieval
The agent SHALL retrieve candidate stored Lessons from xmemory, validate them locally, rank exact SKU scope before category scope, and include at most three eligible Lessons in model input.

#### Scenario: Mixed candidates are returned
- **WHEN** xmemory returns relevant and mismatching Lesson candidates
- **THEN** nonmatching store/context/scope candidates SHALL be discarded and the remaining candidates SHALL be sorted deterministically by the documented ranking rules

#### Scenario: xmemory is unavailable
- **WHEN** the memory read fails, times out, or yields no usable Lesson
- **THEN** the agent SHALL continue with `lessons=[]` and persist an observable memory status

### Requirement: Stable experiment prompt
The agent SHALL use the same versioned prompt and model-input structure for clean-memory and trained-memory runs, varying only the supplied Lesson data.

#### Scenario: Clean-memory decision
- **WHEN** no Lessons are available
- **THEN** the model SHALL receive the same prompt template with an empty Lessons array rather than a different memory-specific instruction

### Requirement: Ground-truth isolation
The agent SHALL NOT receive simulator coefficients, deterministic noise, current-scenario oracle action, future outcome, or current-scenario counterfactual profits.

#### Scenario: Decision input is built
- **WHEN** the prompt/model payload is assembled
- **THEN** it SHALL contain only normalized scenario data, allowed discounts, and eligible historical Lesson snapshots

### Requirement: Strict model result
The decision model SHALL return the same `scenario_id`, one discount from `0 | 10 | 20 | 30`, and short reason text; invalid/failed output SHALL be retried once with the same semantic input.

#### Scenario: Model succeeds
- **WHEN** a valid structured result is returned
- **THEN** its allowed discount SHALL become the model decision

#### Scenario: Model fails twice
- **WHEN** both model attempts fail or return invalid output
- **THEN** the decision SHALL deterministically fall back to `0` with source `fallback`

### Requirement: Persist before publish
The exact `promotion.decisions.v1` payload SHALL be stored durably as `DECIDED` before Kafka publication.

#### Scenario: Decision publication succeeds
- **WHEN** the Kafka producer acknowledges the persisted decision event
- **THEN** the journal SHALL be marked `COMPLETED` and only then SHALL the source offset be acknowledged

#### Scenario: Publication fails
- **WHEN** Kafka decision publication does not succeed
- **THEN** the source offset SHALL remain unacknowledged so recovery can republish the stored payload

### Requirement: Decision event contract
The agent SHALL publish one decision event matching `docs/promotion-agent/promotion-decision-v1.schema.json` and SHALL carry the validated input scenario snapshot forward unchanged.

#### Scenario: Decision is emitted
- **WHEN** an allowed discount has been selected
- **THEN** `promotion.decisions.v1` SHALL receive the deterministic decision ID, original scenario ID, unchanged scenario snapshot, and chosen discount using `<store_id>:<sku_id>` as key

### Requirement: Decision observability
The system SHALL record enough execution metadata to demonstrate memory write-read behavior without storing learning memory in the local journal.

#### Scenario: Memory-backed decision completes
- **WHEN** one or more Lessons were used
- **THEN** logs/journal SHALL identify scenario, decision, selected Lesson keys/order, memory status, model ID, selected discount, source, reason, and relevant Kafka positions
