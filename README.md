# Hacker Sprint 2 — Self-Learning FMCG Promotion Agent

An autonomous promotion agent that improves its discount decisions from outcomes stored in persistent memory.

## Self-learning loop

![Self-learning promotion agent](assets/self-learning-loop.svg)

The demo is intentionally small: the agent chooses one of `0%`, `10%`, `20%`, or `30%` discount levels. A simulator produces sales and gross profit. An evaluator replays all allowed actions, calculates regret, and writes reusable lessons to xmemory. Future decisions retrieve those lessons.

The core behavior is:

**scenario → decision → simulated outcome → counterfactual evaluation → lesson write → lesson read → changed next decision**

## MVP architecture

```mermaid
flowchart LR
    A[Scenario Generator] --> B[Promotion Agent]
    X[(xmemory)] --> B
    B --> C[Chosen Discount]
    C --> D[Market Simulator]
    A --> D
    D --> E[Sales + Gross Profit]
    E --> F[Evaluator / Learner]
    B --> F
    A --> F
    F -->|Save case + lesson| X
    X -->|Relevant lessons| B
```

## Evaluation

Keep everything constant except memory:

- same model
- same prompt
- same simulator
- same fixed test scenarios

Compare:

- optimal action rate
- average regret
- gross profit
- memory retrieval hit rate

The hackathon claim should be simple: **same agent, better decisions because accumulated memory changes its behavior.**
