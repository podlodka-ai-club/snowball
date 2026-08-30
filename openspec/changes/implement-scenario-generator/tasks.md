## 1. Service foundation

- [ ] 1.1 Create the Kotlin/Spring Boot Scenario Generator module with configuration for Kafka, market identity, source fixture, and generation trigger.
- [ ] 1.2 Add domain/transport models mapped to the committed `promotion-scenario-v1` contract.
- [ ] 1.3 Add JSON Schema and domain-invariant validation tests using the committed valid example plus invalid fixtures.

## 2. Pure generation logic

- [ ] 2.1 Implement `BaselineSource` and `DatasetBaselineSource` for the normalized fixture format.
- [ ] 2.2 Implement deterministic `ContextEnricher`, `stock_level` derivation, market injection, and deterministic `scenario_id` generation.
- [ ] 2.3 Add repeatability tests proving identical logical input produces identical scenario fields and ID.

## 3. Application and triggers

- [ ] 3.1 Implement `ScenarioGenerationService` orchestration with no source-specific logic.
- [ ] 3.2 Add scheduled and manual internal triggers that invoke the same service.
- [ ] 3.3 Add structured rejection/logging behavior for invalid source or generated data.

## 4. Kafka output

- [ ] 4.1 Implement `ScenarioPublisher` and Kafka adapter for `promotion.scenarios.v1` using `<store_id>:<sku_id>` keys.
- [ ] 4.2 Add integration tests verifying one message per valid scenario, schema-valid payload, correct key, and no publication for invalid scenarios.
- [ ] 4.3 Run an end-to-end local acceptance test from fixture row to consumed Kafka event and document the run command/configuration.
