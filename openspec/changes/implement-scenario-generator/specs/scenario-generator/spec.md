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

### Requirement: Stable scenario identity
The system SHALL create `scenario_id` deterministically from stable scenario/source identity and SHALL treat a scenario as immutable once identified.

#### Scenario: Same source fact is retried
- **WHEN** the same logical source record is processed after a retry or restart
- **THEN** the system SHALL produce the same `scenario_id`

### Requirement: Contract validation before publication
The system SHALL validate every outgoing event against `docs/scenario-generator/promotion-scenario-v1.schema.json` before publishing it.

#### Scenario: Valid event
- **WHEN** the generated event satisfies the v1 schema and domain invariants
- **THEN** it SHALL be eligible for Kafka publication

#### Scenario: Invalid event
- **WHEN** the generated event violates the v1 schema or required invariants
- **THEN** it SHALL NOT be published and the rejection reason SHALL be observable

### Requirement: Kafka publication contract
The system SHALL publish one scenario per message to `promotion.scenarios.v1` using `<store_id>:<sku_id>` as the Kafka key and SHALL tolerate at-least-once delivery semantics.

#### Scenario: Valid scenario is published
- **WHEN** a validated scenario is emitted
- **THEN** exactly one event payload SHALL be sent for that scenario generation attempt with the required key and committed v1 envelope

### Requirement: Narrow component boundary
The Scenario Generator SHALL NOT read or write xmemory, choose discounts, simulate outcomes, or persist a runtime business database.

#### Scenario: Source adapter is replaced
- **WHEN** a future SAP/JDBC/API adapter replaces the fixture adapter
- **THEN** the `promotion.scenarios.v1` consumer contract SHALL remain unchanged
