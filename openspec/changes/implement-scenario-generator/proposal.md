## Why

The Promotion Agent needs a stable, source-independent stream of market scenarios. The repository already defines the Scenario Generator architecture and `PromotionScenario` v1 contract, but no runtime implementation exists yet.

## What Changes

- Add a Kotlin/Spring Boot Scenario Generator service.
- Read normalized baseline rows through a `BaselineSource` port, initially from the prepared dataset fixture.
- Inject the fixed `LONDON_CENTRAL` market identity and deterministic context enrichment.
- Build deterministic scenario identities, validate the committed JSON Schema, and publish one event per Kafka message to `promotion.scenarios.v1`.
- Support both scheduled generation and a manual internal trigger through the same application service.
- Add contract, deterministic-generation, and Kafka integration tests.

The service will not contain xmemory, promotion-decision logic, simulator logic, or a runtime database.

## Capabilities

- `scenario-generator`: source-independent creation and publication of valid promotion scenarios.

## Impact

Upstream is a prepared baseline dataset adapter for the MVP. Downstream is the Promotion Agent consuming `promotion.scenarios.v1`. The authoritative design and contracts are in `docs/scenario-generator/`.
