# xmemory Design

xmemory is the durable learning layer of the promotion agent. The MVP should not remember every prompt, chain-of-thought, simulator detail, or intermediate calculation. It should remember only information that can change a future promotion decision.

![xmemory schema](../../assets/xmemory-schema.svg)

Editable diagram source: [`schema.mmd`](schema.mmd)  
Proposed XMD v1 schema: [`schema.xmd.yaml`](schema.xmd.yaml)

## MVP goal

Close one visible loop:

`evaluated promotion case -> reusable rule -> later retrieval -> changed decision`

For the hackathon, this is more important than building a general knowledge graph. The memory needs to be small enough that we can explain every object on a demo screen.

## Objects

### Product

Stable product identity used to connect many promotion cases for the same SKU.

Important fields:

- `sku` — stable product key.
- `name` — human-readable product name.
- `brand` — optional brand.
- `category` — product category used for generalisation and retrieval.

### StoreCluster

Stable store/market segment where the promotion was evaluated.

Important fields:

- `cluster_id` — stable cluster key.
- `name` — human-readable cluster name.
- `market` — city/region/country label when useful.

### PromotionCase

One completed scenario after counterfactual evaluation. This is the factual evidence layer.

It contains both what the agent did and what the evaluator later discovered:

- scenario context: price, baseline units, stock level, weekday/weekend, competitor discount;
- chosen action: `0`, `10`, `20`, or `30` percent;
- observed outcome: units and gross profit;
- counterfactual optimum: best discount and best gross profit;
- evaluation: regret and decision quality.

A case is immutable after evaluation, except for fixing bad data.

### PromotionRule

A compact reusable lesson that is read before the next decision. This is the behavioural layer.

Example:

> For high-stock soft drinks on weekends with a competitor around 10% off, prefer 10% discount. 20%+ usually gives away margin without enough incremental volume.

Important fields:

- `rule_key` — deterministic segment key produced by the learner, for example `soft_drinks|high|weekend|comp10`;
- `context_summary` — concise applicability description;
- `recommended_discount` — one allowed action;
- `rationale` — why the rule exists;
- `evidence_count` — how many evaluated cases support the current rule;
- `confidence` — learner confidence from `0.0` to `1.0`;
- `avg_regret_pct` — average regret observed for decisions covered by this rule;
- `status` — `active` or `superseded`.

Unlike raw cases, a rule is expected to evolve. Repeated evidence should update the same `rule_key` instead of creating endless near-duplicate lessons.

## Relations

The schema uses explicit relations rather than storing one giant text record:

- `case_product`: each case belongs to one product; one product has many cases.
- `case_cluster`: each case belongs to one store cluster; one cluster has many cases.
- `case_supports_rule`: evaluated cases provide evidence for reusable rules. A case can support more than one rule later, although the MVP will normally write one.

This gives us a useful demo story: open a rule, show the cases behind it, then show a later decision that retrieved that rule.

## Write path

The Evaluator / Learner writes memory only after it has replayed all promotion actions.

1. Upsert `Product` and `StoreCluster`.
2. Write one `PromotionCase` with chosen action, outcome, optimum, and regret.
3. Build a coarse `rule_key` from stable scenario buckets.
4. Create or update the corresponding `PromotionRule`.
5. Link the case to the rule with `case_supports_rule`.

For the first MVP, keep rule generation deterministic where possible. Let the model write the human-readable `context_summary` and `rationale`, but calculate discounts, regret, evidence counts, and the rule key in code. Humans have already invented enough exciting ways for floating-point arithmetic to become a creative-writing exercise.

## Read path

Before choosing a promotion, the Promotion Agent asks xmemory for the smallest useful slice of experience.

Recommended retrieval order:

1. active rules matching the same SKU or category and similar scenario context;
2. rules from the same store cluster when available;
3. a few supporting cases only when the agent needs evidence or explanation.

For the MVP, return at most `3` rules and optionally `2` supporting cases per rule. Put those rules into the decision prompt as evidence, not as mandatory commands.

Example read intent:

> Find active promotion rules relevant to SKU COKE-15L in London Central, with high stock, weekend demand, and a competitor discount around 10%. Include the strongest supporting cases.

## How memory changes behaviour

The Promotion Agent should receive the same base system prompt in both benchmark modes.

- **Clean memory:** no relevant `PromotionRule` records exist, so the model decides from scenario data alone.
- **Learned memory:** relevant rules and evidence are injected before the decision.

The benchmark then measures whether learned memory improves:

- optimal action rate;
- average regret;
- gross profit.

This isolates the thing the sprint actually cares about: the model did not change, the prompt did not change, but persistent experience changed the decision.

## Conflict handling for MVP

Do not build a full truth-maintenance system in week one.

Use three simple rules:

1. newer evidence updates the same `rule_key`;
2. confidence increases only when supporting cases agree on the same recommended discount;
3. when evidence flips the recommendation, mark the old rule `superseded` and write/update the replacement.

The evaluator should log which case caused the rule change. That gives us observability without constructing a tiny enterprise data-governance department inside a hackathon project.

## What we deliberately do not store

For the MVP, do **not** store:

- complete prompts or model conversations;
- every simulator counterfactual as a separate memory object;
- embeddings or manually maintained vector indexes;
- agent reasoning traces;
- low-level execution logs;
- dozens of FMCG attributes that are not used by the current simulator.

If a field cannot affect retrieval, learning, evaluation, or the demo, it probably does not belong in xmemory yet.

## Implementation order

Minimal path for two developers:

1. Create the xmemory instance from [`schema.xmd.yaml`](schema.xmd.yaml).
2. Add one `write_case_and_rule()` function in the Evaluator / Learner.
3. Add one `read_relevant_rules()` function in the Promotion Agent.
4. Include retrieved rule IDs in decision logs.
5. Run fixed benchmark scenarios with clean and trained memory.
6. Only then add confidence tuning, superseding, or richer retrieval.

## Demo trace

The demo should make the write -> read link visible:

```text
Case CASE-0042
chosen: 20%
optimal: 10%
regret: 8.4%
        |
        v
Rule soft_drinks|high|weekend|comp10
recommended_discount: 10%
evidence_count: 6
confidence: 0.86
        |
        v
Later case CASE-0051 retrieves this rule
Agent chooses: 10%
Result: optimal action, 0% regret
```

That trace is small, reproducible, and directly demonstrates why persistent memory is part of the product rather than a side database.