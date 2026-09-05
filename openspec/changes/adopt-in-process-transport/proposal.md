## Why

The committed architecture puts Kafka on two boundaries: `promotion.scenarios.v1` between Scenario Generator and Promotion Agent, and `promotion.decisions.v1` between Promotion Agent and Market Simulator. Building them is days of work - broker configuration, consumer groups, acknowledgement policy, integration tests - and the sprint is scored on memory and self-improvement, explicitly not on the processing loop.

The broker also does not buy what a broker is usually bought for, and this is visible in the committed documents rather than being an opinion:

- At-least-once redelivery is already handled by deterministic `SCN-`/`DEC-`/`OUT-` identifiers and idempotent xmemory writes, not by broker offsets - `docs/architecture/README.md`, "Transport boundaries".
- Throughput is not on the table either: `docs/promotion-agent/README.md` fixes the MVP to one record at a time, so a broker would deliver no parallelism without rewriting that spec first.
- Durability of the decision path already lives in the H2 `DecisionJournal`, and durability of product memory lives in xmemory. Neither depends on a topic.

Meanwhile the schedule has one hard, unmet requirement - the agent must have run on real data and learned before the final - and an unresolved quota blocker on xmemory. Time spent on transport is time not spent on the thing being judged.

## What Changes

- Replace the two Kafka topics with direct in-process handoff behind ports. The committed JSON event contracts do not change.
- Add a narrow `DecisionSink` port for the Promotion Agent to hand off a decision. This boundary is missing today: the skeleton has `ScenarioPublisher`, `OutcomeSink`, and `SimulationPort`, and `SimulationPort` must not be reachable from the agent, so the agent currently has no legal way to emit a decision.
- Add a durable run ledger that replaces what the broker offset provided: a record of which scenarios a run has produced, decided, evaluated, and learned, so a run can resume after a restart without losing or repeating work.
- Define a deterministic learning order, so that concurrency - if measurement later shows it is needed - cannot make a run unreproducible.
- Give each benchmark arm its own decision journal namespace. This is a defect independent of transport: `docs/benchmark/README.md` requires both arms to reuse the same `scenario_id` values, while `docs/promotion-agent/README.md` makes `scenario_id` the journal idempotency key, so a shared journal would let the trained arm replay or skip the clean arm's decisions and destroy the delta being measured.
- Amend the Kafka-specific requirements in three sibling changes - `implement-scenario-generator`, `implement-promotion-agent`, `implement-market-simulator` - to transport-neutral handoff and recovery semantics. `AGENTS.md` forbids editing another component's change directory quietly, so the edits are enumerated here and carried out as part of applying this change.

Non-goals:

- This does not remove Kafka from the architecture documents or forbid it later. It defers it.
- No in-memory queue with worker threads. Asynchrony is not the goal and would cost reproducibility for nothing at this scale.
- No general-purpose queue machinery on top of the ledger: no leases, partitions, or distributed workers.
- No change to any committed JSON Schema, and no change to the learning algorithm, the simulator formula, or the xmemory schema.
- This does not solve the xmemory quota problem. That is a separate and more urgent blocker; this change only stops transport work from competing with it.

## Capabilities

- `in-process-transport`: contract-preserving handoff between components without a broker, with durable, resumable, reproducible runs.

## Impact

Upstream is the committed event contracts and the ports in `implement-project-skeleton`, which this change extends by one port. Downstream are the three component changes whose transport requirements it amends, and the benchmark, which gains the journal isolation it needs to be trustworthy. Returning to Kafka later means adding producer and listener adapters, restoring raw-JSON boundary validation, and deciding whether `COMPLETED` means broker-accepted or learned end to end - real work, but the event models, schemas, and serialization survive it untouched.
