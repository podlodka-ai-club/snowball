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
- **average regret** — `best_gross_profit - agent_gross_profit`;
- **gross profit** — profit produced by the agent recommendation across the fixed scenario set;
- **human correction rate** — how often a category manager would need to override the recommendation in an interactive run.

For the clean-vs-trained automated benchmark, evaluate the agent recommendation itself before any human override. Human decisions belong to training/product runs and are stored in `PromotionCase`; otherwise a helpful manager could hide a bad agent and make the benchmark rather inspirational than scientific.

The intended claim is deliberately narrow: **same agent, better decisions because accumulated memory changes its behaviour.**

The memory objects being trained and retrieved are documented in [`../xmemory/`](../xmemory/).
