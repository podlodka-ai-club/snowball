## Purpose

Provide one reproducible build, one typed projection of the three committed v1 contracts, and one set of transport-neutral internal ports, so that the five component changes start from a shared foundation instead of each inventing its own DTOs, style rules, and boundary names.

## ADDED Requirements

### Requirement: Reproducible single-command build
The repository SHALL build and verify itself through a committed Gradle wrapper with a single command, on a JDK 21 toolchain, without any tool installed beyond a JVM.

#### Scenario: Contributor builds a clean clone
- **WHEN** `./gradlew spotlessCheck build` is run on a freshly cloned repository
- **THEN** the build SHALL resolve its own Gradle distribution, compile with the configured Kotlin toolchain, run all tests, and succeed

#### Scenario: Sources violate the formatting rules
- **WHEN** a Kotlin source or Gradle Kotlin script does not match the configured ktlint style
- **THEN** `spotlessCheck` SHALL fail and SHALL report the offending file before compilation results are interpreted

### Requirement: Single declaration of versions
Dependency and plugin versions SHALL be declared in the Gradle version catalog and SHALL NOT be duplicated in build scripts.

#### Scenario: A dependency version is changed
- **WHEN** a contributor updates a library or plugin version
- **THEN** exactly one catalog entry SHALL change and the build scripts SHALL remain untouched

### Requirement: Contract models mirror the committed schemas
The module SHALL expose Kotlin models for `promotion-scenario-v1`, `promotion-decision-v1`, and `promotion-outcome-v1` that mirror the committed JSON Schemas under `docs/` and SHALL NOT define any alternative or parallel contract format.

#### Scenario: A committed example is read
- **WHEN** a committed `*.example.json` is deserialized into its model
- **THEN** every property present in the example SHALL be represented by a model property, with no property silently discarded

#### Scenario: A schema declares a shared payload by reference
- **WHEN** the decision and outcome schemas reference the scenario payload defined by the scenario schema
- **THEN** the models SHALL reuse one scenario payload type rather than redeclaring the same fields

### Requirement: Lossless round trip against the committed examples
Each of the three contracts SHALL support a lossless round trip: parsing a committed example and serializing it back SHALL produce a semantically identical JSON document.

#### Scenario: Scenario example is round-tripped
- **WHEN** `docs/scenario-generator/promotion-scenario-v1.example.json` is parsed into the model and serialized back
- **THEN** the result SHALL equal the source document as a JSON tree, with no field dropped, renamed, added, or retyped

#### Scenario: Decision example is round-tripped
- **WHEN** `docs/promotion-agent/promotion-decision-v1.example.json` is parsed into the model and serialized back
- **THEN** the result SHALL equal the source document as a JSON tree

#### Scenario: Outcome example is round-tripped
- **WHEN** `docs/market-simulator/promotion-outcome-v1.example.json` is parsed into the model and serialized back
- **THEN** the result SHALL equal the source document as a JSON tree

#### Scenario: Optional fields are absent
- **WHEN** a document omits fields the schema marks as optional
- **THEN** parsing SHALL succeed and serialization SHALL NOT emit those fields as explicit nulls

### Requirement: Contract violations fail at the boundary
Deserialization SHALL reject a document that the committed schema would reject on required fields, closed enumerations, constant envelope values, and unknown properties.

#### Scenario: A required field is missing
- **WHEN** a document omits a field listed as required by its schema
- **THEN** deserialization SHALL fail rather than produce a partially populated model

#### Scenario: A value falls outside a closed enumeration
- **WHEN** a discount other than 0, 10, 20, or 30, or a `stock_level`, `day_type`, `weather`, or scenario `event_type` value outside its enumeration is supplied
- **THEN** deserialization SHALL fail

#### Scenario: An unknown property is supplied
- **WHEN** a document carries a property that the schema forbids through `additionalProperties: false`
- **THEN** deserialization SHALL fail

#### Scenario: A constant envelope value is wrong
- **WHEN** `event_type`, `schema_version`, or `simulator_version` does not equal the constant fixed by the schema
- **THEN** deserialization SHALL fail, and for a valid document those constants SHALL survive the round trip unchanged

### Requirement: Transport-neutral internal ports
The module SHALL define `SimulationPort`, `OutcomeSink`, and `ScenarioPublisher` as plain interfaces over the contract models, and their signatures SHALL NOT name any messaging, framework, persistence, or configuration type.

#### Scenario: A component change implements a port
- **WHEN** a later change supplies an adapter for a port
- **THEN** it SHALL do so without altering the interface, whichever transport the team ultimately chooses

#### Scenario: Tests need a port implementation
- **WHEN** a test requires a working port
- **THEN** a trivial in-process implementation SHALL be available that records or returns fixed values and contains no domain arithmetic

### Requirement: The skeleton carries no domain behavior
The module SHALL NOT contain simulator coefficients or formulas, oracle selection, regret calculation, Lesson derivation, prompt construction, model invocation, or xmemory access.

#### Scenario: The simulation port is exercised
- **WHEN** `SimulationPort` is called through the implementation shipped by this change
- **THEN** it SHALL return only the committed outcome payload and SHALL expose no coefficient, noise factor, counterfactual set, or oracle-best action

#### Scenario: A component change begins
- **WHEN** the Market Simulator, Promotion Agent, Scenario Generator, Evaluator/Learner, or xmemory change is implemented
- **THEN** it SHALL own its behavior itself, and the skeleton SHALL require no change beyond additive extension

### Requirement: No secret material in the repository
The repository SHALL commit a secret-free example environment file and SHALL ignore real environment files.

#### Scenario: A contributor configures xmemory access
- **WHEN** an API key is needed locally
- **THEN** `.env.example` SHALL declare `XMEMORY_API_KEY` with an empty value, the real file SHALL be ignored by version control, and no key value SHALL appear in any tracked file

### Requirement: Continuous verification of the same command
Continuous integration SHALL run the same verification command as a local contributor, on every push and pull request.

#### Scenario: A pull request is opened
- **WHEN** a pull request targets the default branch
- **THEN** CI SHALL provision a Temurin JDK 21 toolchain and run `./gradlew spotlessCheck build`, and SHALL fail the check if either step fails
