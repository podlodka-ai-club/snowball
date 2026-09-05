## 1. Contracts and service skeleton

- [ ] 1.1 Durable configuration and a persistent decision journal. No Spring and no Kafka - the transport is deferred and a framework was not needed; the journal is in-process, so idempotency holds within a run but not across a restart.
- [x] 1.2 Add typed mappings and contract validation for scenario input and decision output using committed schemas.
- [ ] 1.3 Add tests proving invalid input is rejected before journal decision, xmemory, or model calls.

## 2. Durable journal

- [x] 2.1 Implement `DecisionJournal` and H2 schema for `STARTED`, `DECIDED`, `COMPLETED` plus observability fields.
- [x] 2.2 Implement deterministic `decision_id` and state-machine recovery behavior.
- [x] 2.3 Add restart/idempotency tests for new, `DECIDED`, and `COMPLETED` scenarios.

## 3. Memory path

- [x] 3.1 Implement `PromotionMemory` xmemory adapter using the structured read contract and bounded timeout.
- [x] 3.2 Implement local Lesson validation/filtering/ranking exactly as documented and cap model Lessons at three.
- [x] 3.3 Add tests for SKU-over-category ordering, context mismatch rejection, deterministic ties, empty memory, malformed response, and unavailable memory.

## 4. Model path

- [x] 4.1 Implement stable `DecisionModelInput` and versioned prompt builder containing only scenario, allowed discounts, and compact Lessons.
- [x] 4.2 Implement provider adapter behind `DecisionModel` and strict structured output validation.
- [x] 4.3 Add tests for valid result, invalid scenario ID/discount, one retry, deterministic 0% fallback, and byte/semantic equivalence of clean vs trained prompt template.

## 5. Kafka workflow

- [x] 5.1 Implement `ScenarioListener` with manual acknowledgement and `PromotionDecisionService` orchestration.
- [x] 5.2 Persist exact decision payload as `DECIDED`, publish to `promotion.decisions.v1`, mark `COMPLETED`, then acknowledge source offset.
- [x] 5.3 Add integration tests for publish failure recovery and `DECIDED` republish without memory/model calls.
- [x] 5.4 Add structured logs/journal trace proving which Lesson keys were supplied to a completed decision.
- [x] 5.5 Run an end-to-end acceptance case from a scenario Kafka event to a schema-valid decision event for both empty-memory and seeded-memory paths. Transport deferred by `adopt-in-process-transport`; the agent hands off through `DecisionSink`.
