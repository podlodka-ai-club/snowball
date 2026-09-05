## Context

`docs/architecture/README.md` places Kafka on two of three component boundaries and already sends the third, `PromotionOutcomeV1`, across an in-process port. The architecture is therefore mixed by construction; this change moves the remaining two boundaries to the same footing rather than introducing a new principle.

The decision is time-bound, not aesthetic. The hackathon is scored on memory and self-improvement and explicitly not on the processing loop, one hard requirement - a real training run with a visible clean-versus-trained delta - is still unmet, and the xmemory quota is an open blocker. Transport is the cheapest thing on that list to postpone.

## Goals / Non-Goals

**Goals:**
- Keep the committed event contracts and their versioned JSON Schemas exactly as they are.
- Give the Promotion Agent a legal way to emit a decision that does not expose simulation.
- Make a run durable and resumable without broker offsets.
- Make a run reproducible, including if concurrency is added later.
- Keep the two benchmark arms from corrupting each other.
- Leave a cheap path back to Kafka.

**Non-Goals:**
- An in-memory queue with worker threads.
- Leases, partitions, dead-letter handling, or any other general queue semantics.
- Solving the xmemory quota.
- Any change to the simulator formula, the learning algorithm, or the xmemory schema.

## Decisions

1. **Handoff is a direct call through a port, not a queue.** The scenario set is finite and offline - a few hundred rows from a fixture - so buffering and backpressure solve nothing. A synchronous call gives an end-to-end stack trace on failure, an exact ordering, and a run that reproduces bit for bit, which matters because the evals are the deliverable and `AGENTS.md` requires a run that cannot be repeated to be treated as a non-result.

2. **A new `DecisionSink` port, deliberately narrow.** The agent hands off a `PromotionDecisionEvent` and gets nothing back. It must not be given `SimulationPort`: four calls to a real simulation reconstruct the counterfactual profits for the current scenario, which is exactly the ground truth the agent is supposed to learn rather than read. Only the simulator and evaluator wiring hold `SimulationPort`. This was the gap that Kafka was hiding - with a topic in between, the agent had no in-process handle to misuse.

3. **A durable run ledger replaces the offset, and nothing more.** Two tables in the journal database the Promotion Agent already needs: a run row carrying the dataset hash, the model, prompt and simulator versions, the memory instance role, the learning flag, and the concurrency settings; and one row per scenario carrying its position in the run, its state, the exact decision payload, and failure metadata. Deterministic identifiers already prevent duplicate work; the ledger is what prevents silently *skipping* work after a crash, which identifiers alone cannot do.

4. **Learning order is defined by the run, not by whoever finishes first.** Scenarios are processed in a fixed sequence derived from the dataset. If measurement later shows a sequential run is too slow, concurrency is introduced as bounded waves: within a wave several decisions may run at once, no lesson is written while a wave is in flight, every decision in a wave sees the same pre-wave memory, and cases are written in canonical order afterwards. Behavior then changes between waves and never as a function of thread scheduling. Wave size becomes part of the experiment protocol and is recorded in the run row. Concurrency is not added speculatively: it is added only if measured latency justifies it, and it saves wall clock, not quota.

5. **Each benchmark arm gets its own journal namespace.** `docs/benchmark/README.md` requires both arms to use identical `scenario_id` values, and `docs/promotion-agent/README.md` makes `scenario_id` the journal idempotency key. Sharing one journal would make the trained arm replay or skip the clean arm's decisions, and the measured delta would be an artifact of the journal rather than of memory. Separate journal databases per arm, or a run namespace outside the business identifiers - either works, both must be deliberate.

6. **Validation moves from the wire to the boundary, and this is a real revision.** With a broker, each component validated raw JSON on receipt and rejected bad records observably. Direct typed calls skip that path. The schema-conformance tests in the skeleton keep the models honest, but they are not a substitute for a runtime rejection path, so the component specs are amended to say what they now require rather than being left claiming something that no longer happens.

7. **The demonstration is a presentation concern, not a transport one.** A finite iterator satisfies "process a stream of tasks", but a single command that prints a final report can reasonably read as offline batch evaluation. The loop therefore emits and processes one scenario at a time with visible per-scenario state and durable checkpoints, and the demo shows an interrupted run resuming and a later scenario retrieving a lesson an earlier one created.

## Ground-truth leakage

This change adds a boundary next to the simulator, so the leakage question applies directly. `DecisionSink` accepts a decision and returns nothing: it cannot be used to learn an outcome, let alone four. `SimulationPort` stays out of the agent's dependency graph entirely, and the wiring is the enforcement - no field-level whitelist can prevent a caller that holds a real simulation from calling it once per allowed discount. Replaying all four actions remains the Evaluator's job.

## Risks / Trade-offs

- **Losing the broker loses a durable backlog.** Deterministic identifiers protect against doing work twice; only the ledger protects against not doing it at all. If the ledger is wrong, a crashed run silently under-trains, which is worse than crashing loudly. It needs its own tests.
- **Sequential runs may be too slow**, and that is unknown until measured. The mitigation is to measure early on five to ten real scenarios rather than to design for a number nobody has yet.
- **Kafka is not a literal drop-in later.** Beyond adapters, it needs the raw-JSON rejection path restored, an acknowledgement policy, and a decision about whether `COMPLETED` means broker-accepted or learned end to end. The event models, schemas, and serialization are reusable, which is the expensive part.
- **A jury may read in-process as less impressive.** Answered honestly: the boundaries are ports, the contracts are versioned, a broker is an adapter away, and it was left out because the sprint is scored on memory.

## References

- `docs/architecture/README.md`, "Transport boundaries"
- `docs/promotion-agent/README.md` - MVP settings, decision journal, idempotency key
- `docs/market-simulator/README.md` - listener and `OutcomeSink` boundary
- `docs/benchmark/README.md` - identical scenario identifiers across both arms
- `openspec/changes/implement-project-skeleton/` - the ports this change extends
