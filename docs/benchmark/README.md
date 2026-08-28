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
- same `SIMULATOR_VERSION`;
- same simulator coefficients, noise algorithm, and rounding rules;
- same fixed scenarios and `scenario_id` values.

Using the same `scenario_id` values matters because Market Simulator v1 derives one deterministic noise shock from each scenario identity and reuses that same shock for all four actions. Clean-memory and trained-memory runs therefore face the exact same hidden market.

Compare:

- **optimal action rate** — how often the agent chooses the simulator oracle discount;
- **average regret** — `best_gross_profit - gross_profit`;
- **gross profit** — profit produced by the chosen discount across the fixed scenario set.

Record `simulator_version` with benchmark results and reject comparisons that mix versions. Once training evidence has been generated, silently changing simulator coefficients would turn the experiment into two different games and then congratulate memory for the score difference.

The intended claim is deliberately narrow: **same agent, same market, better decisions because persistent memory changes its behaviour.**

The memory objects being trained and retrieved are documented in [`../xmemory/`](../xmemory/). The hidden market model and replay contract are documented in [`../market-simulator/`](../market-simulator/).
