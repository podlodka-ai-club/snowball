## Purpose

Move the two remaining component boundaries off Kafka and onto in-process handoff behind ports, without changing a single committed event contract, while keeping runs durable, resumable, reproducible, and isolated between benchmark arms.

## ADDED Requirements

### Requirement: Contract-preserving handoff
Components SHALL exchange the committed `promotion-scenario-v1`, `promotion-decision-v1`, and `promotion-outcome-v1` payloads through ports, and the payloads SHALL be identical to what a broker would have carried.

#### Scenario: A scenario reaches the agent
- **WHEN** the Scenario Generator produces a scenario
- **THEN** it SHALL be handed to the Promotion Agent as the committed scenario event, with no field added, removed, or reshaped for transport

#### Scenario: A transport is introduced later
- **WHEN** a broker or any other transport is added
- **THEN** it SHALL be an adapter behind the same ports, and no component's domain logic SHALL change

### Requirement: The agent emits decisions without reaching simulation
The Promotion Agent SHALL hand off its decision through a port that accepts a decision event and returns no result, and SHALL NOT hold a reference to `SimulationPort`.

#### Scenario: The agent completes a decision
- **WHEN** the agent has validated and journalled its chosen discount
- **THEN** it SHALL emit the decision through `DecisionSink` and SHALL learn nothing about the outcome from that call

#### Scenario: Wiring is assembled
- **WHEN** the application graph is constructed
- **THEN** `SimulationPort` SHALL be reachable only from the Market Simulator and the Evaluator, since any holder of a real simulation can call it once per allowed discount and reconstruct the scenario's counterfactual profits

### Requirement: A run is durable and resumable
The system SHALL persist, for each run, which scenarios have been produced, decided, evaluated, and learned, together with the exact decision payload.

#### Scenario: A run is interrupted
- **WHEN** the process dies mid-run and is restarted with the same run identity
- **THEN** it SHALL continue from the first unfinished scenario, SHALL NOT re-decide a scenario already marked decided, and SHALL NOT silently skip a scenario that never completed

#### Scenario: A scenario fails downstream
- **WHEN** evaluation or learning fails for a scenario
- **THEN** that scenario SHALL remain unfinished in the ledger rather than being recorded as complete, and the failure SHALL be observable

### Requirement: A run is reproducible
Each run SHALL record the inputs that determine its result, and repeating a run with the same inputs SHALL produce the same sequence of decisions and the same learned state, up to the model's own nondeterminism.

#### Scenario: A run starts
- **WHEN** a run begins
- **THEN** it SHALL record the dataset identity, the scenario order, the model and prompt versions, the simulator version, the seed, the memory instance role, the learning flag, and any concurrency settings

#### Scenario: A result must be defended
- **WHEN** a run's numbers are reported as evidence of the before/after delta
- **THEN** the raw model outputs and the run record SHALL be retained as artifacts, because a run that cannot be repeated is not a result

### Requirement: Learning order does not depend on scheduling
The order in which lessons are learned SHALL be a property of the run, not of thread completion order.

#### Scenario: Scenarios are processed one at a time
- **WHEN** the run is sequential
- **THEN** each decision SHALL see the memory state produced by every scenario before it and none of the scenarios after it

#### Scenario: Concurrency is enabled
- **WHEN** measured latency justifies processing several decisions at once
- **THEN** they SHALL be grouped into bounded waves, no lesson SHALL be written while a wave is in flight, every decision in a wave SHALL see the same pre-wave memory, cases SHALL be written in canonical scenario order after it, and the wave size SHALL be recorded as part of the run

### Requirement: Benchmark arms are isolated
The clean-memory and trained-memory runs SHALL NOT share decision journal state.

#### Scenario: Both arms replay the same scenarios
- **WHEN** the benchmark runs the same fixed `scenario_id` values against a clean and a trained memory instance
- **THEN** each arm SHALL use its own journal namespace, so that neither arm can reuse or skip a decision made by the other

### Requirement: Invalid input is rejected observably
Each component SHALL reject input that violates its committed contract, whether it arrives as a typed call or as a document.

#### Scenario: A component receives input violating its contract
- **WHEN** a payload breaks the committed schema or a documented component invariant
- **THEN** the component SHALL reject it, SHALL NOT record the scenario as complete, and SHALL fail observably rather than continue with corrupt input

### Requirement: The task stream remains demonstrable
The loop SHALL process scenarios one at a time with observable per-scenario progress, rather than presenting only an aggregate result at the end.

#### Scenario: The loop is demonstrated
- **WHEN** the run is shown as evidence that the agent processes a stream and improves
- **THEN** per-scenario state SHALL be visible, an interrupted run SHALL be shown resuming, and a later scenario SHALL be shown retrieving a lesson created by an earlier one
