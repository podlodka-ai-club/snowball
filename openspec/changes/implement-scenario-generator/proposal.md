## Why

The Promotion Agent needs a stable, source-independent stream of market scenarios. The repository already defines the Scenario Generator architecture and the `PromotionScenario` v1 contract, but no runtime implementation exists yet.

## What Changes

- Add a plain Kotlin Scenario Generator, no application framework.
- Read normalized baseline rows through a `BaselineSource` port, initially from the prepared dataset fixture.
- Inject the fixed `LONDON_CENTRAL` market identity and deterministic context enrichment.
- Build deterministic scenario identities, validate against the committed JSON Schema, and hand each scenario to `ScenarioPublisher`.
- Support both scheduled generation and a manual trigger through the same application service.
- Add contract, deterministic-generation, and end-to-end handoff tests.

Four gaps in the original plan are closed here, because leaving them open means the first implementation decides them by accident:

- **The fixture carries a `date` per row.** `docs/scenario-generator/dataset-preparation.md` recommends a column set without one, but the scenario contract requires `scenario.date`, the documented `scenario_id` is built from the date, `day_type` is derived from it, and `AGENTS.md` requires the training/benchmark split to be by time. All four need a date that is decided once during offline preparation, not invented at generation time.
- **The `stock_level` threshold is fixed.** Preparation derives stock as a multiple of baseline demand but never says where `normal` ends and `high` begins.
- **The context rules are specified as observable properties**, so that the enricher is reproducible and actually exercises the values a Lesson key ranges over.
- **The training and benchmark sets are split by time**, as `AGENTS.md` requires, not by a seeded shuffle.

Non-goals:

- No Spring Boot or other application framework. The two things it was there for were scheduling and a Kafka adapter; the transport is deferred by `adopt-in-process-transport`, and a scheduled trigger does not justify a framework on its own.
- No Kafka. Scenarios are handed off through `ScenarioPublisher`, per `adopt-in-process-transport`.
- No SAP/JDBC/HTTP production adapters, no runtime database, no xmemory, no promotion decisions, no simulation.
- No change to any committed JSON Schema.
- Preparing the fixture itself is offline work and is not part of this change; this change only fixes the format it must produce.

## Capabilities

- `scenario-generator`: source-independent creation and validated publication of promotion scenarios.

## Impact

Upstream is the prepared baseline fixture. Downstream is the Promotion Agent, which receives scenarios through `ScenarioPublisher` rather than a topic. The authoritative design and contracts remain in `docs/scenario-generator/`; where this change is stricter than that material, it says so and why.
