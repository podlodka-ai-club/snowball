# Benchmark

The Benchmark Runner proves that persistent memory changes behaviour rather than merely storing history.

![Benchmark clean memory vs learned memory](../../assets/benchmark.svg)

Editable source: [`benchmark.mmd`](benchmark.mmd)

Run the same fixed scenarios twice:

- once with **clean xmemory**;
- once with **trained xmemory**.

Keep everything else constant:

- same model;
- same prompt;
- same simulator;
- same fixed scenarios.

Compare:

- **optimal action rate** — how often the agent chooses the simulator oracle discount;
- **average regret** — `best_gross_profit - gross_profit`;
- **gross profit** — profit produced by the chosen discount across the fixed scenario set.

The intended claim is deliberately narrow: **same agent, better decisions because accumulated memory changes its behaviour.**

The memory objects being trained and retrieved are documented in [`../xmemory/`](../xmemory/).
