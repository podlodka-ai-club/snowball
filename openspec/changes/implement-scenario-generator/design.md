## Context

`docs/scenario-generator/README.md` defines this service as the ingestion boundary, and the committed JSON Schema and example event are authoritative. The MVP uses one synthetic market and a fixture prepared offline from dunnhumby data.

Two things have changed since that material was written. Transport is deferred by `adopt-in-process-transport`, so scenarios are handed off through `ScenarioPublisher` rather than published to a topic. And `AGENTS.md` now requires that measurement data be separated from training data by time rather than at random, which the preparation guide predates.

## The four gaps, and their common root

The original plan left four things undecided, and three of them turn out to be the same problem. The recommended fixture columns in `docs/scenario-generator/dataset-preparation.md` carry no date, yet:

- `scenario.date` is required by the committed schema;
- the documented `scenario_id` shape is `<date>|<store_id>|<sku_id>|<source_reference>`;
- `day_type` is derived from the date, and it is part of every Lesson key;
- a split by time is impossible without dates to split on.

So the fixture gains a `date` column, decided once during offline preparation. That is stricter than the preparation guide, deliberately: a date invented at generation time would make `scenario_id` depend on when the generator ran, which contradicts the committed requirement that the same source fact always yields the same identity. The guide's own instruction to commit benchmark identifiers alongside the fixture already implies this, since those identifiers contain the date.

The fourth gap, the `stock_level` threshold, is unrelated but equally cheap to close.

## Goals / Non-Goals

**Goals:**
- A small plain-Kotlin generator with ports for input and output.
- Generation that is deterministic and replayable in tests.
- A contract stable across baseline sources.
- Simple scheduled and manual control, with useful logs.

**Non-Goals:**
- Spring Boot or any application framework.
- Kafka, Schema Registry, or distributed exactly-once processing.
- SAP/JDBC/HTTP production adapters, database persistence, xmemory, decisions, or simulation.
- Preparing the fixture, which is offline work.

## Decisions

1. `Trigger -> ScenarioGenerationService -> BaselineSource + ContextEnricher -> ScenarioPublisher` remains the internal flow.
2. Implement `DatasetBaselineSource` first. The runtime reads the normalized fixture, never the raw public dataset.
3. Market identity stays in configuration and is injected after source mapping.
4. **The fixture carries `date` and `split` per row**, in addition to the columns the preparation guide recommends. `split` is `training` or `benchmark`, and every benchmark date is strictly later than every training date. Putting the split in the data rather than in a runtime flag makes the two sets immutable and reviewable, which is what the preparation guide asks for and what `AGENTS.md` requires; putting it in a config value would let a rerun silently reclassify a scenario.
5. **`stock_level` is `high` when `stock >= 2 * baseline_sales`, otherwise `normal`.** Preparation derives stock as roughly `1.5x` baseline for normal and `2.5x` for high, so the midpoint separates them cleanly and tolerates the tuning the guide allows. Rows with `baseline_sales = 0` carry no demand signal, cannot produce a meaningful stock level, and are rejected by the fixture invariants rather than defaulted.
6. **The `ContextEnricher` is specified by properties, not by a formula.** It must be a pure function of the baseline row, so that regenerating the same fixture yields identical scenarios in a different process or on a different machine; `day_type` comes from the date in `Europe/London`; and `temperature_c` must agree with `weather` rather than contradict it. The concrete derivation is an implementation choice, but it must not use a JVM-session-dependent hash, wall-clock time, or an unseeded random.
7. **The enricher must exercise the values a Lesson key ranges over.** `weather` and `event_type` feed directly into Lesson identity, so a training set in which one weather value never appears silently removes a whole family of lessons and flattens the delta being measured. This is a property of the generated set, and it is asserted as such.
8. Validate the schema and the domain invariants before handing a scenario off. The committed `promotion-scenario-v1.schema.json` is the contract.
9. Exactly one scenario per handoff, with no local database. Duplicate delivery stays a downstream concern handled by deterministic `scenario_id`.

## Where the context comes from, and when to revisit it

The Lesson key is `sku + day_type + weather + stock_level`. Three of those four are synthetic: `weather` is not in the dataset at all, `day_type` follows from a date assigned during preparation, and `stock_level` follows from a stock figure derived by multiplier. The simulator then reacts to them through its own coefficient tables, which `docs/market-simulator/README.md` deliberately separates from promotion affinity so that context changes which discount is best. Both halves of that relationship are therefore ours, and the team recorded exactly this risk when the case was chosen: the agent may end up learning the generator rather than a market.

For the MVP this is accepted. The dataset supplies `price`, `baseline_sales`, `category`, and `sku`; the context stays synthetic and deterministic. The alternative - deriving `weather` and `day_type` from the real `WEEK_END_DATE` so that the seasonal signal and the demand in those same rows come from the same real weeks - costs the same preparation effort and would make the answer to "did the agent just learn your generator" a real one rather than a deflection. It is deliberately held in reserve.

**Revisit trigger.** Move the context onto real dates if the first training runs show any of: a trained agent that does not beat the best constant action; lessons whose recommendation is constant across every context bucket; or a clean-versus-trained delta that survives shuffling the context labels. Any of those means the learned signal is an artifact of our own rules rather than of the data, and the reserve option becomes the cheaper fix.

## Risks / Trade-offs

- **Deterministic synthetic context is less realistic** than real weather and events, but it keeps training and benchmark behavior reproducible, which is the whole point of the exercise.
- **A single fixed market limits generality** and sharply reduces both benchmark variance and scope. Accepted.
- **A time split can correlate with seasonality.** If the benchmark tail happens to be all hot weather, the trained agent is measured on a slice its training under-represents. This is a real cost of splitting by time rather than at random, and it is the reason property 7 exists: the coverage assertion has to hold on both sides of the split, not just overall.
- **The fixture format is now stricter than `docs/scenario-generator/dataset-preparation.md`.** That guide is design material, not a versioned contract, so this change does not edit it; but whoever prepares the fixture must follow this spec, and the divergence is called out here rather than discovered later.

## References

- `docs/scenario-generator/README.md`
- `docs/scenario-generator/dataset-preparation.md`
- `docs/scenario-generator/promotion-scenario-v1.schema.json`
- `docs/scenario-generator/promotion-scenario-v1.example.json`
- `openspec/changes/adopt-in-process-transport/`
