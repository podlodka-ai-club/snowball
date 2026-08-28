# xmemory Design

xmemory is the durable learning layer of the FMCG promotion agent. The MVP stores only information that can change a later promotion decision or explain why that decision changed.

![xmemory schema](../../assets/xmemory-schema.svg)

Editable diagram source: [`schema.mmd`](schema.mmd)  
XMD v1 schema: [`schema.xmd.yaml`](schema.xmd.yaml)

## MVP goal

Close one visible and reproducible loop:

`scenario -> agent decision -> simulator outcome -> counterfactual oracle -> PromotionCase -> Lesson -> later retrieval -> changed decision`

The memory schema deliberately contains only three objects:

1. **SKU** — stable product identity and basic economics.
2. **PromotionCase** — immutable evaluated evidence from one autonomous decision.
3. **Lesson** — compact reusable knowledge recomputed from linked cases and retrieved before future decisions.

There is no separate Store, SimulatorResult, Counterfactual, Feedback, RuleVersion, or operational-retry object in xmemory. Those concepts either fit naturally inside PromotionCase or belong outside product memory.

## Objects

### SKU

Stable product identity shared by many promotion cases.

Important fields:

- `sku_id` — stable primary key;
- `name` — demo-friendly product name;
- `category` — normalized category used for generalization;
- `base_price` — baseline shelf price;
- `cost` — unit cost used by the simulator.

For the demo, six understandable categories are enough: ice cream, beer, soft drinks, chips, meat, and yogurt. There is no prize for reproducing a supermarket master-data system before the demo.

### PromotionCase

One completed scenario after the Promotion Agent acts and the Evaluator replays all four allowed actions.

A case contains:

#### Scenario context

- `scenario_date`;
- `store`;
- `price`;
- `baseline_sales`;
- `stock`;
- `stock_level`: `normal | high`;
- `day_type`: `weekday | weekend`;
- `weather`: `normal | hot | rain`;
- `temperature_c`;
- `event_type`: `none | local_event`;
- `event_note`.

#### Decision and chosen outcome

- `chosen_discount`: `0 | 10 | 20 | 30`;
- `units_sold`;
- `gross_profit`.

#### Counterfactual evaluation

- `profit_0`;
- `profit_10`;
- `profit_20`;
- `profit_30`;
- `best_discount`;
- `best_gross_profit`;
- `regret`;
- `regret_pct`.

Example:

```text
Agent chose:     10%
Gross profit:    £252
Replay profits:  0%=£240 · 10%=£252 · 20%=£281 · 30%=£263
Oracle best:     20% / £281
Regret:          £29
```

The four replay-profit fields deliberately preserve the full feedback vector. After restart, the learner can rebuild action rankings and Lesson strength without rerunning the hidden simulator.

`case_id` is deterministic:

```text
CASE-<scenario_id>
```

PromotionCase is immutable factual evidence. A duplicate case ID with different business values is an integrity failure, not an update.

### Lesson

A Lesson is compact reusable knowledge derived from multiple evaluated cases and read by the Promotion Agent.

Example:

```json
{
  "lesson_key": "category:ice_cream|store:any|weekend|hot|event:any|stock:high",
  "scope": "category:ice_cream",
  "day_type": "weekend",
  "weather": "hot",
  "stock_level": "high",
  "recommended_discount": 20,
  "confidence": 0.82,
  "evidence_count": 7,
  "avg_profit_advantage_pct": 9.3,
  "rationale": "For category:ice_cream on hot weekends with high stock, 20% has the highest mean gross profit across 7 evaluated cases, beating the next-best action by 9.3%."
}
```

Important fields:

- `lesson_key` — deterministic identity for one scope/context bucket;
- `scope` — `sku:<id>` or `category:<category>`;
- optional `store_scope`;
- optional conditions: `day_type`, `weather`, `event_type`, `stock_level`;
- `recommended_discount` — one of the four allowed actions;
- `rationale` — short evidence-grounded explanation;
- `evidence_count` — unique linked cases used by the Lesson;
- `confidence` — deterministic strength score from `0.0` to `1.0`;
- `avg_profit_advantage_pct` — strength versus the best alternative action.

## Lesson identity and candidate policy

Do not let prose generation invent a new Lesson whenever the same evidence can be described with different English.

For v1, every PromotionCase contributes to exactly two buckets:

```text
sku:<sku_id>|store:any|<day_type>|<weather>|event:any|stock:<stock_level>
category:<category>|store:any|<day_type>|<weather>|event:any|stock:<stock_level>
```

Example:

```text
sku:ICE500|store:any|weekend|hot|event:any|stock:high
category:ice_cream|store:any|weekend|hot|event:any|stock:high
```

This policy deliberately uses only:

- scope;
- day type;
- weather;
- stock level.

The hackathon has one store, so `store:any` avoids fake store specificity. `event_type` remains on PromotionCase but is `event:any` for v1 Lessons. Event-aware or store-specific buckets are future extensions, not extra combinations created from every case.

Two buckets per case are enough to demonstrate both exact-SKU learning and category transfer without flooding memory.

## Relations

The schema has three relations.

### `case_sku`

Each PromotionCase belongs to exactly one SKU. One SKU can have many cases.

### `lesson_evidence`

Many-to-many relation between Lesson and PromotionCase.

This is the authoritative provenance trace. `evidence_count` is recomputed from unique linked cases; it is never incremented blindly.

```text
Lesson #17
  <- CASE-0004
  <- CASE-0011
  <- CASE-0018
  <- CASE-0023
```

### `lesson_sku_scope`

Optional direct link for SKU-specific Lessons. Category-level Lessons use `scope=category:<name>` and do not need to link to every SKU in the category.

## Deterministic Lesson aggregation

For a Lesson with `N` linked PromotionCases, sum the stored replay profits for each action:

```text
sum_0  = Σ profit_0
sum_10 = Σ profit_10
sum_20 = Σ profit_20
sum_30 = Σ profit_30
```

`recommended_discount` is the action with the highest sum. Since every action has the same number of cases, this is equivalent to highest mean gross profit. Exact ties prefer the lower discount.

No LLM chooses the action.

### Average profit advantage

```text
mean_recommended = sum_recommended / N
mean_best_alternative = max(mean of other actions)

avg_profit_advantage_pct =
  (mean_recommended - mean_best_alternative)
  / max(abs(mean_best_alternative), 0.01)
  * 100
```

Round the persisted percentage to two decimals with `HALF_UP`.

### Confidence

```text
evidence_score  = min(N / 5, 1)

agreement_score =
  count(case.best_discount == recommended_discount) / N

advantage_score =
  clamp(avg_profit_advantage_pct / 10, 0, 1)

confidence = round2(
    0.60 * evidence_score
  + 0.25 * agreement_score
  + 0.15 * advantage_score
)
```

Evidence quantity has the largest weight. Repeated agreement matters next. Profit separation helps, but cannot make one anecdote look like certainty.

Lessons are persisted after the first case; there is no hard minimum-evidence suppression in v1. The Promotion Agent already treats Lessons as evidence rather than commands and ranks stronger evidence first.

### Rationale

For the MVP, generate rationale deterministically from the calculated facts:

```text
For <scope> on <weather> <day_type> with <stock_level> stock,
<discount>% has the highest mean gross profit across <N> evaluated cases,
beating the next-best action by <advantage>%.
```

An optional LLM may polish this sentence later, but it must not modify recommendation, evidence count, advantage, or confidence.

## Contradiction handling

Lessons update in place by deterministic `lesson_key`.

When a new case contributes:

```text
add missing lesson_evidence relation
-> read all linked PromotionCases
-> recompute all four action totals
-> recompute recommendation + advantage + confidence
-> update the same Lesson
```

If accumulated evidence changes the best action, `recommended_discount` changes on the same Lesson.

```text
old recommendation: 20%
new evidence makes 10% highest aggregate profit
-> same lesson_key
-> recommendation becomes 10%
-> confidence is recalculated
```

That is the MVP contradiction mechanism. No version trees, no prose-merging ritual, no pretending conflicting experience is impossible.

## Write path

The Evaluator / Learner writes memory only after all four actions have been replayed successfully and the chosen-action replay matches `PromotionOutcomeV1`.

Use xmemory REST:

```text
POST /instances/{instance_id}/read
POST /instances/{instance_id}/write
```

Known application objects are written using deterministic `structured_mutations`, not free-form extraction.

### PromotionCase checkpoint

1. Read existing SKU and `PromotionCase` by deterministic keys.
2. If the case exists, verify it is identical and continue as retry/repair.
3. If the case is new, write one structured mutation batch containing, as needed:
   - create/update SKU;
   - create PromotionCase;
   - create `case_sku`.

### Lesson update

For each of the two deterministic Lesson keys:

1. Read the current Lesson with `relations_scope=all_relations`.
2. Load its unique linked PromotionCases.
3. Add the current case locally when it is not yet linked.
4. Recompute all Lesson fields in application code.
5. Write one structured mutation batch that:
   - creates or updates Lesson;
   - creates the missing `lesson_evidence` relation;
   - creates `lesson_sku_scope` when required and missing.

The Lesson fields and the new evidence relation are changed in the same xmemory write call.

If case write succeeds but Lesson update fails, Kafka redelivery finds the same immutable case and resumes recomputation. No operational retry state is stored in xmemory.

Detailed arithmetic, idempotency, and failure handling live in [`../evaluator-learner/`](../evaluator-learner/).

## Read path

Before choosing a discount, the Promotion Agent asks xmemory for a very small amount of relevant experience.

Recommended order:

1. exact SKU Lessons matching context;
2. category Lessons matching context;
3. store-specific Lessons only if they are introduced later;
4. optionally a few supporting cases for explanation mode.

For the MVP, retrieve at most `3` Lessons and optionally `2` supporting PromotionCases per Lesson.

The Promotion Agent uses xmemory `/instances/{instance_id}/read`, requests structured `xresponse` candidates, then applies deterministic local eligibility/ranking before putting Lessons into the model prompt. Exact runtime rules live in [`../promotion-agent/README.md`](../promotion-agent/README.md).

The retrieved Lesson is evidence, not a mandatory instruction.

## Forgetting

No deletion or time decay in v1. The benchmark simulator is stationary, so forgetting mostly manufactures complexity.

A later version can recompute from the most recent `N` evidence cases or a scenario-date window without changing the object model.

## Benchmark isolation

Training:

```text
LEARNING_ENABLED=true
XMEM_INSTANCE_ID=trained-memory
```

Benchmark measurement:

```text
LEARNING_ENABLED=false
```

Use separate `clean-memory` and `trained-memory` instances. The Promotion Agent reads the selected instance; Evaluator still calculates oracle/regret but writes nothing during benchmark runs.

See [`../benchmark/README.md`](../benchmark/README.md).

## What stays outside xmemory

Do not store:

- full prompts or conversations;
- chain-of-thought or hidden reasoning;
- simulator coefficients / hidden ground truth;
- counterfactual actions as separate memory objects;
- random noise values;
- embeddings or hand-maintained vector indexes;
- raw source-dataset rows after scenario creation;
- Kafka offsets, duplicate markers, or Promotion Agent operational journal state;
- evaluator retry state;
- dozens of product/store attributes the system does not use.

The four evaluated gross-profit numbers do belong on PromotionCase because they are observed training evidence. Hidden coefficients that generated them do not. Otherwise the agent is not learning from experience; it is reading the answer key, which somewhat cheapens the scientific triumph.

## Minimal implementation for two developers

1. Create the xmemory instance from [`schema.xmd.yaml`](schema.xmd.yaml).
2. Implement immutable structured `PromotionCase` write.
3. Implement the two deterministic `lesson_key` builders.
4. Implement pure Lesson aggregation/confidence tests.
5. Implement structured Lesson + evidence relation writes.
6. Implement `read_relevant_lessons()` in the Promotion Agent.
7. Log Lesson IDs read and changed with every decision/evaluation.
8. Train on 200-300 scenarios.
9. Run the same fixed benchmark scenarios with clean and trained memory and learning disabled.

## Demo trace

```text
CASE-0018
Ice Cream 500ml · hot weekend · high stock
Agent chose: 10%
Gross profit: £252
Replay: 0=£240 · 10=£252 · 20=£281 · 30=£263
Oracle: 20% / £281
Regret: £29
        |
        v
LESSON
category:ice_cream|store:any|weekend|hot|event:any|stock:high
recommend: 20%
evidence: 7 cases
confidence: 0.82
avg advantage: +9.3%
        |
        v
CASE-0051 retrieves this Lesson
Agent chooses: 20%
Oracle chooses: 20%
Regret: £0
```

That is the hackathon story in one screen: evaluated experience was written, aggregated knowledge survived restart, the Lesson was read later, and the same agent changed its behavior because persistent memory existed.
