## 1. Domain evaluation

- [x] 1.1 Create evaluator domain models for four-action replay, oracle result, regret, PromotionCase, and Lesson aggregate inputs/outputs.
- [x] 1.2 Implement four-action replay orchestration against the pure `SimulationEngine` and chosen-action consistency verification.
- [x] 1.3 Implement exact oracle selection/lower-discount tie break and regret/regret-percent calculation with unit tests.
- [x] 1.4 Add failure tests proving any incomplete/inconsistent replay produces zero learning writes.

## 2. PromotionCase

- [x] 2.1 Implement deterministic `CASE-<simulator_version>-<scenario_id>` builder containing the complete feedback vector.
- [ ] 2.2 Persist the case checkpoint through the durable memory implementation. The `LearningMemory` port and an in-process implementation exist; the xmemory client belongs to `integrate-xmemory-learning-memory`.
- [x] 2.3 Add tests proving duplicate processing cannot double-count the same case.

## 3. Lesson learning

- [x] 3.1 Implement the exact SKU/category Lesson key builders with `store:any`, `event:any`, day, weather, and stock level.
- [x] 3.2 Implement pure aggregation of `profit_0/10/20/30`, lower-discount tie break, average advantage, confidence formula, and deterministic rationale.
- [x] 3.3 Read, recompute and upsert the lesson for both buckets, linking each case once - durable relation writes arrive with the xmemory client.
- [x] 3.4 Add tests where contradictory new evidence changes the recommendation on the same Lesson key.

## 4. Runtime and benchmark integration

- [x] 4.1 Implement the `OutcomeSink` adapter/application entry point connecting completed Market Simulator outcomes to Evaluator/Learner.
- [x] 4.2 Implement `LEARNING_ENABLED=false` path that calculates evaluation results but performs zero xmemory writes.
- [x] 4.3 Add an observer/result boundary usable by benchmark code without adding another Kafka topic.
- [x] 4.4 Run an acceptance case proving one outcome creates one immutable case, updates exactly two Lessons, and a retry does not increase evidence count twice.
