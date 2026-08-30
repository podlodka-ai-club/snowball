## 1. Domain evaluation

- [ ] 1.1 Create evaluator domain models for four-action replay, oracle result, regret, PromotionCase, and Lesson aggregate inputs/outputs.
- [ ] 1.2 Implement four-action replay orchestration against the pure `SimulationEngine` and chosen-action consistency verification.
- [ ] 1.3 Implement exact oracle selection/lower-discount tie break and regret/regret-percent calculation with unit tests.
- [ ] 1.4 Add failure tests proving any incomplete/inconsistent replay produces zero learning writes.

## 2. PromotionCase

- [ ] 2.1 Implement deterministic `CASE-<simulator_version>-<scenario_id>` builder containing the complete feedback vector.
- [ ] 2.2 Implement xmemory case checkpoint through the memory write port with identical-retry and conflicting-case behavior.
- [ ] 2.3 Add tests proving duplicate processing cannot double-count the same case.

## 3. Lesson learning

- [ ] 3.1 Implement the exact SKU/category Lesson key builders with `store:any`, `event:any`, day, weather, and stock level.
- [ ] 3.2 Implement pure aggregation of `profit_0/10/20/30`, lower-discount tie break, average advantage, confidence formula, and deterministic rationale.
- [ ] 3.3 Implement Lesson read/recompute/upsert and unique `lesson_evidence` relation writes for both buckets.
- [ ] 3.4 Add tests where contradictory new evidence changes the recommendation on the same Lesson key.

## 4. Runtime and benchmark integration

- [ ] 4.1 Implement the `OutcomeSink` adapter/application entry point connecting completed Market Simulator outcomes to Evaluator/Learner.
- [ ] 4.2 Implement `LEARNING_ENABLED=false` path that calculates evaluation results but performs zero xmemory writes.
- [ ] 4.3 Add an observer/result boundary usable by benchmark code without adding another Kafka topic.
- [ ] 4.4 Run an acceptance case proving one outcome creates one immutable case, updates exactly two Lessons, and a retry does not increase evidence count twice.
