# High-Level Architecture

The MVP architecture is intentionally small. These are logical responsibilities, not required deployment boundaries.

![Self-learning FMCG promotion agent high-level architecture](../../assets/high-level-architecture.svg)

Editable source: [`high-level-architecture.mmd`](high-level-architecture.mmd)

## Components

- **Scenario Generator** creates a normalized promotion scenario.
- **Promotion Agent** reads relevant past lessons from **xmemory** and chooses one discount action.
- **Market Simulator** acts as the hidden external world and returns the business outcome.
- **Evaluator / Learner** measures the decision and turns the result into reusable experience.
- **xmemory** persists evaluated cases and lessons across runs.

The core feedback loop is:

`Scenario -> Decision -> Outcome -> Learning -> Memory -> Better next decision`

Counterfactual replay, regret calculation, lesson confidence, and memory structure are deliberately omitted from the high-level diagram. See [`../xmemory/`](../xmemory/) for the memory design.
