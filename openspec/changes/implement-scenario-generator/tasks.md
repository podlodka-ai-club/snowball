## 1. Fixture format

- [x] 1.1 Define the normalized fixture columns - the set in `docs/scenario-generator/dataset-preparation.md` plus `date` and `split` - and commit a small example fixture in the project's test resources. Do not edit anything under `docs/`; the divergence from the preparation guide is recorded in this change's design instead.
- [ ] 1.2 Add fixture invariant checks: `date` and `split` present, `split` is `training` or `benchmark`, every benchmark date strictly later than every training date, `baseline_sales` non-zero, prices and costs within the schema bounds.
- [ ] 1.3 Add tests for each rejection above, using a small hand-written fixture rather than the real dataset.

- [x] 1.4 Prepare the real fixture from dunnhumby with `tools/prepare_dunnhumby.py`. The dataset must be downloaded manually from the dunnhumby source-files page and extracted outside this repository; the raw data is never committed.

## 2. Generation logic

- [ ] 2.1 Implement `BaselineSource` and `DatasetBaselineSource` over the fixture format.
- [ ] 2.2 Implement deterministic `ContextEnricher`: `day_type` from the fixture date in `Europe/London`, and `weather`, `temperature_c`, `event_type` as a pure function of the row, with temperature consistent with weather.
- [ ] 2.3 Implement `stock_level` derivation at the `2 * baseline_sales` boundary, market identity injection, and deterministic `scenario_id` from the committed identity shape.
- [ ] 2.4 Add repeatability tests proving the same row yields identical scenario fields and identical `scenario_id` across separate runs.
- [ ] 2.5 Add a coverage test proving each generated set exercises every `weather` and every `event_type` value.

## 3. Contract validation

- [ ] 3.1 Validate every generated event against the committed `promotion-scenario-v1.schema.json` before handoff, reusing the schema-validation approach already established in the skeleton tests.
- [ ] 3.2 Add tests that a schema-valid scenario is handed off and an invalid one is not, with an observable rejection reason.

## 4. Application and triggers

- [ ] 4.1 Implement `ScenarioGenerationService` orchestration with no source-specific logic.
- [ ] 4.2 Add scheduled and manual triggers that invoke the same service, without introducing an application framework.
- [ ] 4.3 Add structured rejection and logging behavior carrying `scenario_id`, `source.type`, and `source.reference`.

## 5. Handoff

- [ ] 5.1 Hand each validated scenario to `ScenarioPublisher`, exactly one per generation.
- [ ] 5.2 Add tests verifying one handoff per valid scenario, a schema-valid payload, and no handoff for an invalid one.

## 6. Acceptance

- [ ] 6.1 Run end to end from fixture rows to handed-off scenarios and document the command and configuration.
- [ ] 6.2 Prove a rerun over the same fixture produces byte-identical scenarios and identifiers.
- [ ] 6.3 Confirm no committed JSON Schema changed, nothing under `docs/` was edited, and the generator holds no port beyond `BaselineSource` and `ScenarioPublisher`.
