## 1. Schema and configuration

- [x] 1.1 Verified the committed schema is accepted by instance creation, and documented the environment variables in `.env.example`. Provisioning the two benchmark instances is an operational step, not a code one.
- [x] 1.2 Add typed application mappings for SKU, PromotionCase, Lesson, and relation identities matching the XMD schema.
- [x] 1.3 Add fixture-based tests for xmemory read/write envelopes and schema mapping failures.

## 2. Deterministic write primitives

- [ ] 2.1 Read-before-write comparison of an existing immutable case. Superseded in part: `hasCase` was removed when writes became idempotent (create, falling back to update on "already exists"), so presence is no longer read before writing. What remains open is deciding whether a stored case that differs from the one being written should be reported rather than silently overwritten.
- [x] 2.2 Write the SKU and `case_sku` with the case. The SKU is upserted on its own (it nearly always exists already, and would reject a batch), then the case and its relation go in one atomic batch, falling back to single idempotent writes.
- [x] 2.3 Implement structured mutation support for Lesson upsert + `lesson_evidence` + `lesson_sku_scope`. The scope link is written after every lesson upsert: one product for a SKU lesson, every product of the category the memory knows for a category lesson.
- [x] 2.4 Add duplicate/idempotency tests proving repeated processing does not create duplicate evidence relations.

## 3. Read primitives

- [x] 3.1 Implement structured `/read` support for candidate Lessons with `skip_suggestion_capture=true`, trace/session IDs, timeouts, and response validation.
- [x] 3.2 Implement scoped provenance reads for a known Lesson and its supporting PromotionCases for diagnostics/demo mode.
- [x] 3.3 Add tests proving raw response prose, unknown object types, and malformed candidates are not passed through as trusted Lesson data.

## 4. Durability and benchmark acceptance

- [x] 4.1 Add a restart/durability integration test that writes evidence, recreates the client/application process, and reads the same Lesson by durable instance state.
- [x] 4.2 Add `LEARNING_ENABLED` and clean/trained instance configuration behavior with tests that disabled learning performs zero writes.
- [x] 4.3 Produce one acceptance trace showing `PromotionCase -> lesson_evidence -> Lesson -> later read` with deterministic IDs.
