## 1. Contracts and configuration

- [ ] 1.1 Create the Market Simulator Kotlin module/logical package and typed decision/outcome models from committed schemas.
- [ ] 1.2 Encode simulator v1 coefficient tables/configuration and implement startup validation for all supported actions/categories/context buckets.
- [ ] 1.3 Add contract/invariant tests for valid and permanently invalid decision events.

## 2. Pure simulation engine

- [ ] 2.1 Implement exact SHA-256 deterministic noise algorithm and golden tests for known scenario IDs.
- [ ] 2.2 Implement the pure v1 formula with separate context demand/promotion affinity, discount lift, stock cap, and required rounding.
- [ ] 2.3 Add table-driven tests covering all categories/discounts, stock-capped cases, thin margins, ties/repeatability, and negative gross profit where valid.
- [ ] 2.4 Snapshot-test the committed `promotion-outcome-v1.example.json` or an equivalent golden case.

## 3. Runtime boundary

- [ ] 3.1 Implement Kafka `DecisionListener` for `promotion.decisions.v1` with manual acknowledgement and consumer group `market-simulator-v1`.
- [ ] 3.2 Implement `OutcomeSink` port and build schema-valid deterministic `PromotionOutcomeV1` without hidden fields.
- [ ] 3.3 Implement bounded downstream handoff retries and unhealthy/no-ack behavior for permanent failures.

## 4. Acceptance

- [ ] 4.1 Add restart/idempotency tests proving identical `(scenario, discount, simulator version)` produces identical outcome and ID.
- [ ] 4.2 Add a test proving all four actions for one scenario share the same noise factor while producing independently calculated outcomes.
- [ ] 4.3 Run an integration acceptance from a Kafka decision event through `OutcomeSink`, confirming no evaluator/xmemory behavior exists in the simulator path.
