## 1. Contracts and configuration

- [x] 1.1 Reuse the committed decision/outcome models from the project skeleton rather than declaring new ones.
- [x] 1.2 Encode the simulator v1 coefficient tables and refuse a category the tables do not cover, instead of guessing one.
- [x] 1.3 Add contract and invariant tests for valid and permanently invalid decision events.

## 2. Pure simulation engine

- [x] 2.1 Implement the exact SHA-256 deterministic noise algorithm with tests pinning its range, scale, and stability.
- [x] 2.2 Implement the pure v1 formula with separate context demand and promotion affinity, discount lift, stock cap, and the documented rounding order.
- [x] 2.3 Add tests covering discount monotonicity, stock-capped cases, thin margins where gross profit goes negative, and repeatability.
- [x] 2.4 Snapshot-test the committed `promotion-outcome-v1.example.json` against the committed decision example.

## 3. Runtime boundary

- [x] 3.1 Receive decisions through a port rather than a Kafka listener - the transport is deferred by `adopt-in-process-transport`, not ruled out, and a broker adapter would sit behind this same boundary.
- [x] 3.2 Build the schema-valid deterministic outcome and hand it to `OutcomeSink`, with no hidden coefficients, noise, oracle or regret in it.
- [x] 3.3 Let a failed handoff fail the simulation rather than be reported as a completed one; bounded retries belong to whichever transport is chosen later.

## 4. Acceptance

- [x] 4.1 Prove that the same scenario, discount and simulator version reproduce the outcome and its identity exactly.
- [x] 4.2 Prove all four actions for one scenario share the same noise factor while producing independently calculated outcomes.
- [x] 4.3 Prove the simulator path contains no evaluator, regret or memory behaviour, and that context changes which action wins rather than only how much sells.
