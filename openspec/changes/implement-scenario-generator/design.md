## Context

`docs/scenario-generator/README.md` already defines the service as the ingestion boundary. The committed JSON Schema and example event are authoritative. The MVP uses one synthetic market and a prepared fixture derived offline from dunnhumby data.

## Goals / Non-Goals

**Goals:**
- Small Kotlin/Spring Boot service with ports for input and Kafka output.
- Deterministic scenario generation that can be replayed in tests.
- Stable external contract independent of the baseline source.
- Simple scheduling/manual demo control and useful logs/health.

**Non-Goals:**
- SAP/JDBC/HTTP production adapters.
- Database persistence.
- xmemory integration.
- Promotion decisions or market simulation.
- Schema Registry or distributed exactly-once processing.

## Decisions

1. Use `Trigger -> ScenarioGenerationService -> BaselineSource + ContextEnricher -> ScenarioPublisher` as the internal flow.
2. Implement `DatasetBaselineSource` first. Runtime consumes a normalized fixture, never the raw public dataset.
3. Keep market identity in configuration and inject it after source mapping.
4. Keep `ContextEnricher` deterministic for the benchmark. Real weather/event integrations can replace it later without changing the event schema.
5. Validate JSON/domain invariants before sending to Kafka. The committed `promotion-scenario-v1.schema.json` is the transport contract.
6. Publish one event per Kafka message to `promotion.scenarios.v1` with key `<store_id>:<sku_id>`.
7. Do not introduce a local database. Duplicate delivery is handled downstream by deterministic `scenario_id`.

Suggested implementation shape follows `docs/scenario-generator/README.md`; exact package names may follow the repository's eventual Gradle module convention.

## Risks / Trade-offs

- Deterministic synthetic context is less realistic than external weather/events, but it keeps training and benchmark behavior reproducible.
- At-least-once publication can duplicate events, intentionally delegated to downstream idempotency.
- A single fixed market limits generality but sharply reduces benchmark variance and implementation scope.

## References

- `docs/scenario-generator/README.md`
- `docs/scenario-generator/dataset-preparation.md`
- `docs/scenario-generator/promotion-scenario-v1.schema.json`
- `docs/scenario-generator/promotion-scenario-v1.example.json`
