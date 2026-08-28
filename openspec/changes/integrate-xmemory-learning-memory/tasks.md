## 1. Schema and configuration

- [ ] 1.1 Validate/provision an xmemory instance from `docs/xmemory/schema.xmd.yaml` and document required environment variables without committing secrets.
- [ ] 1.2 Add typed application mappings for SKU, PromotionCase, Lesson, and relation identities matching the XMD schema.
- [ ] 1.3 Add fixture-based tests for xmemory read/write envelopes and schema mapping failures.

## 2. Deterministic write primitives

- [ ] 2.1 Implement read-before-write support for deterministic object keys and integrity comparison of immutable PromotionCases.
- [ ] 2.2 Implement structured mutation batches for SKU + PromotionCase + `case_sku` checkpoint writes.
- [ ] 2.3 Implement structured mutation support for Lesson upsert + `lesson_evidence` + optional `lesson_sku_scope` in one logical update call.
- [ ] 2.4 Add duplicate/idempotency tests proving repeated processing does not create duplicate evidence relations.

## 3. Read primitives

- [ ] 3.1 Implement structured `/read` support for candidate Lessons with `skip_suggestion_capture=true`, trace/session IDs, timeouts, and response validation.
- [ ] 3.2 Implement scoped provenance reads for a known Lesson and its supporting PromotionCases for diagnostics/demo mode.
- [ ] 3.3 Add tests proving raw response prose, unknown object types, and malformed candidates are not passed through as trusted Lesson data.

## 4. Durability and benchmark acceptance

- [ ] 4.1 Add a restart/durability integration test that writes evidence, recreates the client/application process, and reads the same Lesson by durable instance state.
- [ ] 4.2 Add `LEARNING_ENABLED` and clean/trained instance configuration behavior with tests that disabled learning performs zero writes.
- [ ] 4.3 Produce one acceptance trace showing `PromotionCase -> lesson_evidence -> Lesson -> later read` with deterministic IDs.
