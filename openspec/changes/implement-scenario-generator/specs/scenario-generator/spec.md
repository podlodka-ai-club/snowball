## Purpose

Provide a deterministic ingestion boundary that converts baseline market facts into stable, validated promotion scenario events without exposing source-specific details downstream.

## ADDED Requirements

### Requirement: Shared generation workflow
The system SHALL route scheduled and manual generation triggers through the same scenario-generation application service.

#### Scenario: Scheduled generation
- **WHEN** the configured scheduler fires
- **THEN** the system SHALL execute one normal scenario-generation cycle through the shared service

#### Scenario: Manual generation
- **WHEN** the internal manual trigger is invoked
- **THEN** the system SHALL execute the same generation workflow without duplicating source or mapping logic

### Requirement: Source-independent baseline input
The system SHALL read baseline facts through a `BaselineSource` boundary and SHALL initially support the normalized prepared dataset fixture.

#### Scenario: Fixture record is read
- **WHEN** a valid normalized fixture row is selected
- **THEN** the system SHALL map only source facts into the baseline domain model and SHALL NOT require downstream components to know the fixture format

#### Scenario: A fixture row is missing its date or split
- **WHEN** a row lacks `date` or `split`, or `split` is neither `training` nor `benchmark`
- **THEN** the row SHALL be rejected observably, because scenario identity, `day_type`, and the experiment split all depend on them

#### Scenario: A fixture row carries no demand
- **WHEN** `baseline_sales` is zero
- **THEN** the row SHALL be rejected, since no meaningful stock level can be derived from it

### Requirement: Fixed hackathon market
The system SHALL inject `store_id=LONDON_CENTRAL`, `store_name=London Central`, and `timezone=Europe/London` from configuration for the MVP.

#### Scenario: Scenario is created from a baseline row
- **WHEN** the source row does not contain deployment market identity
- **THEN** the generated scenario SHALL contain the configured London Central identity

### Requirement: Deterministic normalized context
The system SHALL derive normalized decision context using deterministic rules for `day_type`, `weather`, `temperature_c`, `event_type`, and derived `stock_level`.

#### Scenario: Same source input is regenerated
- **WHEN** the same baseline record, market configuration, and generation context are processed again
- **THEN** the normalized business scenario fields SHALL be identical

### Requirement: Dates come from the fixture
Every scenario date SHALL come from its fixture row and SHALL NOT be assigned at generation time.

#### Scenario: The same fixture is regenerated later
- **WHEN** the same fixture row is processed on another day, in another process, or on another machine
- **THEN** `scenario.date` SHALL be the value committed in the fixture, and `scenario_id` SHALL therefore be unchanged

#### Scenario: The calendar context is derived
- **WHEN** `day_type` is derived
- **THEN** it SHALL follow from the fixture date in `Europe/London`, so that the same row always yields the same day type

### Requirement: Derived stock level
`stock_level` SHALL be `high` when `stock` is at least twice `baseline_sales`, and `normal` otherwise.

#### Scenario: Stock is at the boundary
- **WHEN** `stock` equals exactly twice `baseline_sales`
- **THEN** `stock_level` SHALL be `high`, so the boundary is defined rather than left to the implementation

### Requirement: Context enrichment is a pure function of the row
The enricher SHALL derive `weather`, `temperature_c`, and `event_type` from the baseline row alone, and SHALL NOT use wall-clock time, an unseeded random source, or any value that varies between processes or JVM sessions.

#### Scenario: The same row is enriched twice in different processes
- **WHEN** the same fixture row is enriched in separate runs
- **THEN** every enriched field SHALL be identical

#### Scenario: Temperature accompanies weather
- **WHEN** `weather` is `hot` or `rain`
- **THEN** `temperature_c` SHALL be consistent with it rather than contradict it

### Requirement: Generated sets cover the Lesson key space
Across a generated set, `weather` and `event_type` SHALL each take every value the contract allows.

#### Scenario: A training or benchmark set is generated
- **WHEN** either set is produced
- **THEN** every `weather` value and every `event_type` value SHALL appear within it, because both are part of Lesson identity and a value that never occurs removes a whole family of lessons from the experiment

### Requirement: Training and benchmark are split by time
The fixture SHALL mark each row as `training` or `benchmark`, and every benchmark date SHALL be strictly later than every training date.

#### Scenario: The split is validated
- **WHEN** a fixture is loaded
- **THEN** the system SHALL reject it if any benchmark row is dated on or before any training row, since a split that is not by time lets the benchmark measure memorised homework

#### Scenario: A benchmark scenario is generated
- **WHEN** scenarios are generated for the benchmark set
- **THEN** they SHALL carry the same identities on every run, so the clean-memory and trained-memory arms are comparable

### Requirement: Stable scenario identity
The system SHALL create `scenario_id` deterministically from stable scenario/source identity and SHALL treat a scenario as immutable once identified.

#### Scenario: Same source fact is retried
- **WHEN** the same logical source record is processed after a retry or restart
- **THEN** the system SHALL produce the same `scenario_id`

### Requirement: Contract validation before publication
The system SHALL validate every outgoing event against `docs/scenario-generator/promotion-scenario-v1.schema.json` before publishing it.

#### Scenario: Valid event
- **WHEN** the generated event satisfies the v1 schema and domain invariants
- **THEN** it SHALL be eligible for handoff

#### Scenario: Invalid event
- **WHEN** the generated event violates the v1 schema or required invariants
- **THEN** it SHALL NOT be published and the rejection reason SHALL be observable

### Requirement: Transport-neutral publication
The system SHALL hand off exactly one scenario per validated generation through `ScenarioPublisher`, and SHALL tolerate the same scenario being handed off again after a retry.

#### Scenario: Valid scenario is published
- **WHEN** a validated scenario is emitted
- **THEN** exactly one committed v1 envelope SHALL be handed to `ScenarioPublisher` for that generation attempt

#### Scenario: A transport is added later
- **WHEN** a broker or another transport is introduced
- **THEN** it SHALL be an adapter behind `ScenarioPublisher`, and the generation logic SHALL NOT change

### Requirement: Narrow component boundary
The Scenario Generator SHALL NOT read or write xmemory, choose discounts, simulate outcomes, or persist a runtime business database.

#### Scenario: Source adapter is replaced
- **WHEN** a future SAP/JDBC/API adapter replaces the fixture adapter
- **THEN** the committed scenario contract seen by the Promotion Agent SHALL remain unchanged
