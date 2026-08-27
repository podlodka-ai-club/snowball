# High-Level Architecture

The MVP architecture is intentionally small. These are logical responsibilities, not required deployment boundaries.

![Self-learning FMCG promotion agent high-level architecture](../assets/high-level-architecture.svg)

Editable source: [`high-level-architecture.mmd`](high-level-architecture.mmd)

## Components

- **Scenario Generator** creates a normalized promotion scenario.
- **Promotion Agent** reads relevant past lessons from **xmemory** and chooses one discount action.
- **Market Simulator** acts as the hidden external world and returns the business outcome.
- **Evaluator / Learner** measures the decision and turns the result into reusable experience.
- **xmemory** persists cases and lessons across runs.

The core feedback loop is:

`Scenario -> Decision -> Outcome -> Learning -> Memory -> Better next decision`

Counterfactual replay, regret calculation, lesson confidence, and memory structure are implementation details of the Evaluator / Learner and xmemory. They are deliberately omitted from the high-level diagram.

## Benchmark

Benchmarking is kept separate from the product architecture:

![Benchmark clean memory vs learned memory](../assets/benchmark.svg)

Editable source: [`benchmark.mmd`](benchmark.mmd)

The Benchmark Runner executes the same fixed scenarios with clean and trained memory and compares optimal action rate, average regret, and gross profit.
