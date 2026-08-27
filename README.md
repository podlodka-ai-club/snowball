# Hacker Sprint 2 — Self-Learning FMCG Promotion Agent

An autonomous promotion agent that improves its discount decisions from outcomes stored in persistent memory.

## Self-learning loop

![Self-learning promotion agent](assets/self-learning-loop.svg)

The demo is intentionally small: the agent chooses one of `0%`, `10%`, `20%`, or `30%` discount levels. A simulator produces sales and gross profit. An evaluator replays all allowed actions, calculates regret, and writes reusable lessons to xmemory. Future decisions retrieve those lessons.

The core behavior is:

**scenario → decision → simulated outcome → counterfactual evaluation → lesson write → lesson read → changed next decision**

## MVP architecture

![High-level architecture](assets/high-level-architecture.svg)

The architecture is intentionally small:

- **Scenario Generator** creates a promotion scenario.
- **Promotion Agent** reads relevant lessons from **xmemory** and chooses a discount.
- **Market Simulator** produces the business outcome.
- **Evaluator / Learner** measures the decision and writes new experience back to **xmemory**.

The important property is:

**scenario → decision → outcome → learning → memory → better next decision**

Editable Mermaid source: [`docs/high-level-architecture.mmd`](docs/high-level-architecture.mmd)

## Component deep dives

Detailed design lives in a separate directory per component as we implement it. Keep each block independently understandable and resist turning hackathon documentation into a distributed-systems archaeology site.

### xmemory

![xmemory schema](assets/xmemory-schema.svg)

xmemory stores two distinct layers:

- **PromotionCase** — immutable evaluated evidence: scenario, chosen promotion, result, counterfactual optimum, and regret.
- **PromotionRule** — compact reusable policy that is updated as evidence accumulates and retrieved before future decisions.

Products and store clusters are first-class objects, and evaluated cases are explicitly linked to the rules they support. This makes the hackathon write → read → changed behaviour trace visible and reproducible.

- Detailed design: [`docs/xmemory/README.md`](docs/xmemory/README.md)
- XMD v1 schema: [`docs/xmemory/schema.xmd.yaml`](docs/xmemory/schema.xmd.yaml)
- Editable diagram source: [`docs/xmemory/schema.mmd`](docs/xmemory/schema.mmd)

## Benchmark

![Benchmark clean memory vs learned memory](assets/benchmark.svg)

To prove self-improvement, the Benchmark Runner compares the same agent with:

- **clean xmemory**
- **trained xmemory**

Everything else stays constant:

- same model
- same prompt
- same simulator
- same fixed scenarios

Compare:

- optimal action rate
- average regret
- gross profit

Editable Mermaid source: [`docs/benchmark.mmd`](docs/benchmark.mmd)

The hackathon claim should be simple: **same agent, better decisions because accumulated memory changes its behavior.**
