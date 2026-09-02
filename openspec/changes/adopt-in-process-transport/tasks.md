## 1. Record the decision in the affected specs

- [x] 1.1 Amend `implement-scenario-generator`: replace the Kafka publisher tasks and the `promotion.scenarios.v1` requirements with transport-neutral publication through `ScenarioPublisher`, keeping the committed contract and the one-event-per-scenario rule.
- [ ] 1.2 Amend `implement-promotion-agent`: replace the Kafka listener and offset-acknowledgement requirements with consumption through a port and handoff through `DecisionSink`, and state that journal completion means the decision was accepted downstream rather than acknowledged to a broker.
- [ ] 1.3 Amend `implement-market-simulator`: replace the `DecisionListener` and consumer-group tasks with reception through a port, keeping the rule that the source scenario is not marked complete until `OutcomeSink` has accepted the outcome.
- [ ] 1.4 In each of the three, keep an explicit note that the transport was deferred rather than ruled out, so the next reader does not restore Kafka by accident or treat its absence as an oversight.
- [ ] 1.5 Add this change to `openspec/README.md` in the implementation order, with one sentence on why it precedes the component work.

## 2. Decision boundary

- [ ] 2.1 Add the `DecisionSink` port: accepts a `PromotionDecisionEvent`, returns nothing, names no transport, framework, or configuration type.
- [ ] 2.2 Add a trivial in-process implementation for tests, alongside the existing recording sink and publisher.
- [ ] 2.3 Add a test proving the Promotion Agent's dependency graph does not reach `SimulationPort`, so the agent cannot replay the four actions and recover the oracle.

## 3. Run ledger

- [ ] 3.1 Define the run and run-item tables next to the existing decision journal: run identity, dataset hash, model/prompt/simulator versions, memory role, learning flag, concurrency settings; per scenario its sequence, state, exact decision payload, and failure metadata.
- [ ] 3.2 Implement state transitions and the resume rule: continue from the first unfinished scenario, never re-decide a decided one, never skip an incomplete one.
- [ ] 3.3 Add restart tests that kill a run at each state and prove the resumed run neither repeats nor omits a scenario.
- [ ] 3.4 Add a test proving a scenario whose evaluation or learning failed stays unfinished and is reported.

## 4. Benchmark isolation

- [ ] 4.1 Give each benchmark arm its own journal namespace, so identical `scenario_id` values across the clean and trained arms cannot collide.
- [ ] 4.2 Add a test running the same scenario identifiers through both arms and proving neither arm reuses or skips the other's decisions.

## 5. Orchestration and measurement

- [ ] 5.1 Implement the sequential run orchestrator over the ports, emitting per-scenario progress and writing the run record.
- [ ] 5.2 Run five to ten real scenarios end to end and record measured wall-clock latency and xmemory operation counts per phase. This is the input to every later decision about concurrency and training volume; do not size either before this exists.
- [ ] 5.3 Only if 5.2 shows sequential runs are too slow: implement bounded waves - no lesson writes while a wave is in flight, identical pre-wave memory for every decision in it, cases written in canonical order after it, wave size recorded in the run.

## 6. Acceptance

- [ ] 6.1 Run a full loop from scenario to lesson without a broker, and show a later scenario retrieving a lesson an earlier one created.
- [ ] 6.2 Interrupt that run and resume it, proving no scenario is repeated and none is lost.
- [ ] 6.3 Repeat a run with identical inputs and show the same decisions and the same learned state, with the raw model outputs retained as artifacts.
- [ ] 6.4 Confirm no committed JSON Schema changed, no component holds a port it should not, and the deferral of Kafka is recorded in the three amended changes rather than only in this one.
