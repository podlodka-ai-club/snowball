## Purpose

Provide durable, structured learning memory whose stored evidence survives restarts and can be traced from evaluated promotion cases to reusable lessons read by later decisions.

## ADDED Requirements

### Requirement: Task-specific memory schema
The xmemory instance SHALL use the committed `docs/xmemory/schema.xmd.yaml` model with `SKU`, `PromotionCase`, and `Lesson` as the product-memory objects.

#### Scenario: Memory instance is initialized
- **WHEN** the hackathon xmemory instance is provisioned
- **THEN** its object and relation schema SHALL match the committed XMD definition used by application mappings

### Requirement: Immutable PromotionCase identity
The system SHALL identify a case as `CASE-<simulator_version>-<scenario_id>` and SHALL treat its evaluated business facts as immutable.

#### Scenario: New case is written
- **WHEN** no case exists for the deterministic key
- **THEN** the complete evaluated case SHALL be created with its SKU relation

#### Scenario: Existing case differs
- **WHEN** the same case key already exists with different evaluated values
- **THEN** the write SHALL fail as an integrity error rather than overwrite evidence

### Requirement: Provenance relations
The system SHALL preserve `case_sku`, `lesson_evidence`, and optional `lesson_sku_scope` relations so every Lesson can be traced to unique PromotionCases.

#### Scenario: Case contributes to a lesson
- **WHEN** a new unique PromotionCase is added to a deterministic lesson bucket
- **THEN** a `lesson_evidence` relation SHALL exist exactly once for that case and lesson

### Requirement: Structured deterministic writes
Known application objects SHALL be persisted using structured mutations rather than free-form extraction.

#### Scenario: Lesson is recomputed
- **WHEN** application code has calculated the updated Lesson fields
- **THEN** the Lesson update and missing evidence relation SHALL be submitted as structured mutations without asking xmemory or an LLM to choose the recommendation

### Requirement: Contradiction updates the same lesson
A Lesson SHALL use deterministic `lesson_key` identity and SHALL be updated in place when accumulated evidence changes the recommended discount.

#### Scenario: New evidence changes the aggregate winner
- **WHEN** the highest aggregate gross-profit action changes for an existing lesson bucket
- **THEN** the existing Lesson SHALL receive the new recommendation and recomputed confidence instead of creating a conflicting Lesson

### Requirement: Bounded structured read path
The Promotion Agent SHALL retrieve stored Lesson candidates through xmemory `/read` in structured response mode and SHALL NOT send raw memory prose directly to the decision model.

#### Scenario: Relevant lessons are requested
- **WHEN** the Promotion Agent supplies current SKU/category/context search criteria
- **THEN** xmemory SHALL return candidate stored Lesson objects that application code can validate, rank, and bound before model use

### Requirement: Durable write-read loop
Memory content required for later decisions SHALL survive application process restarts and SHALL be addressable by deterministic object keys.

#### Scenario: Services restart after training
- **WHEN** a PromotionCase and Lesson were successfully written before restart
- **THEN** later reads from the same xmemory instance SHALL be able to retrieve that durable Lesson and its provenance

### Requirement: Benchmark isolation
The system SHALL support separate clean and trained xmemory instances and SHALL allow evaluation with memory writes disabled.

#### Scenario: Benchmark measurement runs
- **WHEN** `LEARNING_ENABLED=false`
- **THEN** evaluation MAY calculate oracle/regret but SHALL NOT create or update PromotionCases, Lessons, or evidence relations

### Requirement: Memory excludes operational and hidden state
The xmemory instance SHALL NOT store Kafka offsets, retry markers, Promotion Agent journal state, full prompts, hidden chain-of-thought, simulator coefficients, or deterministic noise values.

#### Scenario: Operational retry state changes
- **WHEN** a Kafka consumer retry or journal transition occurs
- **THEN** no xmemory mutation SHALL be required for that operational bookkeeping
