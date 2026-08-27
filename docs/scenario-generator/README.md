# Scenario Generator

The Scenario Generator is the ingestion boundary of the promotion system. Its job is to turn market data from any source into one stable, validated `PromotionScenario` event that the Promotion Agent can consume without knowing whether the data came from a CSV file, a simulator, a database, or SAP.

![Scenario Generator architecture](../../assets/scenario-generator-architecture.svg)

Editable diagram source: [`architecture.mmd`](architecture.mmd)  
Kafka contract: [`promotion-scenario-v1.schema.json`](promotion-scenario-v1.schema.json)  
Example event: [`promotion-scenario-v1.example.json`](promotion-scenario-v1.example.json)

## Design goal

Keep this boundary stable:

`data source -> Scenario Generator -> promotion.scenarios.v1 -> Promotion Agent`

The Promotion Agent must depend only on the Kafka event contract. It must not know how data was fetched or generated.

That gives us a useful extension model:

```text
Today:
Dataset / simulation adapter -> Scenario Generator -> Kafka -> Promotion Agent

Later:
SAP adapter                -> Scenario Generator -> Kafka -> Promotion Agent
Database adapter           -> Scenario Generator -> Kafka -> Promotion Agent
API adapter                -> Scenario Generator -> Kafka -> Promotion Agent
```

Nothing downstream changes when the source changes.

## Kotlin microservice

Recommended MVP stack:

- Kotlin
- Spring Boot
- Spring Kafka
- Jackson
- Bean Validation

Spring Boot is not architecturally important; it is simply the shortest path to scheduling, Kafka publishing, configuration, metrics, and health checks for a two-developer hackathon team.

Do not add a database to this service for the MVP.

## Internal architecture

Use a very small ports-and-adapters structure.

### 1. Trigger

Starts one generation cycle.

For the MVP support two triggers:

- scheduled trigger using configurable cron/fixed delay;
- manual internal HTTP trigger for demo and testing.

Both call the same `ScenarioGenerationService`.

The scheduler decides **when** to generate. It does not contain source-specific or scenario-building logic.

### 2. `BaselineSource` port

Fetches market baseline records needed to construct scenarios.

Conceptually:

```kotlin
interface BaselineSource {
    fun fetchBatch(limit: Int): List<BaselineRecord>
}
```

Implement only one source adapter first.

Recommended MVP options:

- `DatasetBaselineSource` reading prepared dunnhumby/M5-derived fixtures;
- or `SimulationBaselineSource` generating deterministic baseline records.

Future adapters can implement the same port:

- `SapBaselineSource`
- `JdbcBaselineSource`
- `HttpBaselineSource`

A baseline record should contain only source facts such as SKU, store, price, cost, stock, and baseline sales. It should not know anything about xmemory, promotion decisions, or lessons.

### 3. `ContextEnricher` port

Adds context that is useful for promotion decisions but may come from another source.

Conceptually:

```kotlin
interface ContextEnricher {
    fun enrich(record: BaselineRecord): ScenarioContext
}
```

For the MVP implement one deterministic enricher that creates:

- `day_type`: `weekday | weekend`
- `weather`: `normal | hot | rain`
- `temperature_c`
- `event_type`: `none | local_event`

This keeps the source boundary realistic without requiring Open-Meteo, Ticketmaster, SAP, and whatever other integration humans can invent before lunch.

Later the deterministic enricher can be replaced or composed with real weather/event adapters without changing the Kafka contract.

### 4. `ScenarioGenerationService`

The application service orchestrates one generation cycle:

1. fetch baseline records;
2. enrich context;
3. normalize source-specific values;
4. derive stable buckets such as `stock_level`;
5. create deterministic `scenario_id`;
6. validate the contract;
7. publish one Kafka message per scenario.

It should contain orchestration, not source-specific code.

### 5. `ScenarioPublisher` port

Publishes validated domain scenarios.

Conceptually:

```kotlin
interface ScenarioPublisher {
    fun publish(event: PromotionScenarioEvent)
}
```

The MVP implementation is `KafkaScenarioPublisher`.

## Kafka boundary

### Topic

```text
promotion.scenarios.v1
```

Use one scenario per Kafka message. Do not batch multiple scenarios into one event.

### Message key

Use:

```text
<store_id>:<sku_id>
```

This preserves ordering for one store/SKU stream if the system later runs multiple consumers. For the hackathon a single consumer is sufficient, but choosing a useful key now costs nothing.

### Delivery semantics

Design for **at-least-once delivery**.

Kafka or the producer may deliver the same scenario again after retries or restarts. The Promotion Agent must therefore treat `scenario_id` as an idempotency key and ignore an already processed scenario.

Do not attempt distributed exactly-once processing for the MVP. That particular rabbit hole has consumed enough engineering careers already.

### Serialization

Use plain JSON validated against [`promotion-scenario-v1.schema.json`](promotion-scenario-v1.schema.json).

Do not add Schema Registry for the first version unless the team already has it running. The schema file in the repository is enough to make producer/consumer contract tests deterministic.

If the system grows, the same schema can later be registered in Confluent/Redpanda Schema Registry or migrated to Avro/Protobuf.

## Event contract

The event has a small envelope plus the normalized scenario.

```json
{
  "event_type": "promotion.scenario.created",
  "schema_version": 1,
  "scenario_id": "SCN-20260718-LONDON_CENTRAL-ICE500",
  "generated_at": "2026-07-18T06:00:00Z",
  "source": {
    "type": "simulation",
    "reference": "fixture-0018"
  },
  "scenario": {
    "date": "2026-07-18",
    "store_id": "LONDON_CENTRAL",
    "store_name": "London Central",
    "sku_id": "ICE500",
    "sku_name": "Ice Cream 500ml",
    "category": "ice_cream",
    "price": 5.0,
    "cost": 3.0,
    "stock": 320,
    "baseline_sales": 100,
    "stock_level": "high",
    "day_type": "weekend",
    "weather": "hot",
    "temperature_c": 31.0,
    "event_type": "local_event",
    "event_note": "concert_nearby"
  }
}
```

### Contract ownership

The Scenario Generator owns this event schema.

The Promotion Agent owns only its consumer mapping and business decision logic.

Neither side should import source-specific DTOs. A SAP field rename must be fixed inside `SapBaselineSource`, not propagated through the rest of the application like a contagious administrative disease.

## `scenario_id`

`scenario_id` is the business identity of an immutable scenario.

Generate it deterministically from stable source identity when possible, for example:

```text
<date>|<store_id>|<sku_id>|<source_reference>
```

Hash or normalize that value if a shorter identifier is useful.

The important properties are:

- the same source scenario gets the same ID after a retry;
- a different scenario gets a different ID;
- the Promotion Agent can safely deduplicate retries.

For fixed benchmark fixtures, IDs should be committed with the fixture data so the same scenario set is reproducible across runs.

## Contract versioning

Within `v1`, make only backward-compatible additive changes:

- adding an optional field is allowed;
- changing meaning or type of an existing field is not;
- removing a field is not.

For a breaking contract change, publish a new topic:

```text
promotion.scenarios.v2
```

This is intentionally boring. Boring contracts are useful contracts.

## Data gathering strategy

### MVP

Use prepared baseline data plus deterministic context generation.

Recommended pipeline:

```text
Dunnhumby/M5 sample
        |
        v
small normalized fixture
        |
        v
DatasetBaselineSource
        |
        +--> DeterministicContextEnricher
        |
        v
PromotionScenarioEvent
        |
        v
promotion.scenarios.v1
```

Prepare the dataset offline into a tiny fixture instead of teaching the running service to understand a large public dataset. The service should exercise your architecture, not become an ETL internship.

A fixture of a few hundred baseline rows is enough to train and benchmark the memory loop.

### Real-world extension

A real adapter can fetch the same normalized fields from SAP or a database on a schedule:

```text
SAP / DB
   |
   v
SapBaselineSource / JdbcBaselineSource
   |
   v
same ScenarioGenerationService
   |
   v
same promotion.scenarios.v1
```

The source adapter is responsible for mapping external identifiers, prices, stock, and sales history into `BaselineRecord`.

## Scheduling

Make schedule configuration external:

```text
SCENARIO_GENERATION_ENABLED=true
SCENARIO_GENERATION_CRON=0 0 6 * * *
SCENARIO_BATCH_SIZE=100
```

For the hackathon, use a faster schedule or manual trigger during demos.

Do not encode business cadence into the Kafka consumer. The producer decides when a scenario exists; the Promotion Agent reacts to scenarios as they arrive.

## Failure handling

Keep failure handling small and explicit.

### Source unavailable

- log the failure;
- expose unhealthy/degraded status if appropriate;
- retry on the next scheduled cycle;
- do not publish partial invalid scenarios.

### Invalid source record

- reject that record;
- log source reference + validation reason;
- continue processing the rest of the batch.

### Kafka publish failure

- use Kafka producer retries;
- do not mark the generation cycle successful until publish completes;
- safe re-fetch/re-publish is acceptable because `scenario_id` is idempotent.

No dead-letter topic is required on day one. Add one only when failures actually need asynchronous inspection.

## Observability

For the MVP expose/log:

- generation cycles started/completed;
- source records fetched;
- scenarios published;
- invalid records rejected;
- Kafka publish failures;
- generation duration;
- `scenario_id`, `source.type`, `source.reference` in structured logs.

That is enough to answer the important demo question: **where did this scenario come from?**

## Suggested Kotlin package structure

```text
scenario-generator/
  src/main/kotlin/.../
    domain/
      BaselineRecord.kt
      PromotionScenario.kt
      PromotionScenarioEvent.kt
    application/
      ScenarioGenerationService.kt
    port/
      BaselineSource.kt
      ContextEnricher.kt
      ScenarioPublisher.kt
    adapter/
      source/
        DatasetBaselineSource.kt
        SimulationBaselineSource.kt
      context/
        DeterministicContextEnricher.kt
      kafka/
        KafkaScenarioPublisher.kt
      http/
        GenerationController.kt
    config/
      KafkaConfig.kt
      ScenarioGenerationConfig.kt
```

Do not create `SapBaselineSource`, weather APIs, or several source implementations until the basic loop is running.

## MVP implementation order

1. Define Kotlin DTOs from the JSON schema.
2. Add producer/consumer contract tests using the example event.
3. Implement `DatasetBaselineSource` or `SimulationBaselineSource`.
4. Implement deterministic context enrichment.
5. Implement `ScenarioGenerationService`.
6. Publish to local Kafka/Redpanda.
7. Make the Promotion Agent consume `promotion.scenarios.v1` and deduplicate by `scenario_id`.
8. Add scheduler + manual trigger.
9. Only then consider a second real data adapter.

The architectural proof is simple: switch the configured `BaselineSource`, produce the same contract, and the Promotion Agent continues working unchanged.
