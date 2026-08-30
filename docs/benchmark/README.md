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

## Memory isolation

Benchmark evaluation must not teach either memory while it is being measured.

Use two xmemory instances:

```text
clean-memory   -> schema only, no training evidence
trained-memory -> state after the 200-300 scenario training phase
```

For **both** benchmark runs:

```text
LEARNING_ENABLED=false
```

The Promotion Agent still reads the selected xmemory instance normally. The Evaluator still replays all four actions and calculates oracle/regret for benchmark metrics, but it does not write `PromotionCase`, `lesson_evidence`, or `Lesson` objects.

That gives the comparison:

```text
same 50 scenarios
same model + prompt
same simulator
learning disabled during measurement

clean memory   -> decisions A
trained memory -> decisions B
```

The difference is therefore the durable experience already present before the run, not extra online learning during the test.

## Metrics

Compare:

- **optimal action rate** — how often the agent chooses the simulator oracle discount;
- **average regret** — `best_gross_profit - gross_profit`;
- **gross profit** — profit produced by the chosen discount across the fixed scenario set.

Also keep a small trace set showing which Lesson IDs were retrieved for decisions that changed between clean and trained runs. Aggregate metrics prove the delta; the traces explain why the delta happened.

Record `simulator_version` with benchmark results and reject comparisons that mix versions. Once training evidence has been generated, silently changing simulator coefficients would turn the experiment into two different games and then congratulate memory for the score difference.

## Expected phases

Training:

```text
200-300 generated scenarios
LEARNING_ENABLED=true
XMEM_INSTANCE_ID=trained-memory
```

Benchmark:

```text
50 fixed scenarios
LEARNING_ENABLED=false
run once with clean-memory
run once with trained-memory
```

The intended claim is deliberately narrow: **same agent, same market, better decisions because persistent memory changes its behaviour.**

The memory objects and learning algorithm are documented in [`../xmemory/`](../xmemory/) and [`../evaluator-learner/`](../evaluator-learner/). The hidden market model and replay contract are documented in [`../market-simulator/`](../market-simulator/).
