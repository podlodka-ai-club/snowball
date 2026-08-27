# xmemory Design

xmemory is the durable learning layer of the FMCG promotion agent. The MVP stores only information that can change a later promotion decision or explain why that decision changed.

![xmemory schema](../../assets/xmemory-schema.svg)

Editable diagram source: [`schema.mmd`](schema.mmd)  
XMD v1 schema: [`schema.xmd.yaml`](schema.xmd.yaml)

## MVP goal

Close one visible and reproducible loop:

`scenario -> agent decision -> simulator outcome -> counterfactual oracle -> case -> lesson -> later retrieval -> changed decision`

The memory schema deliberately contains only three objects:

1. **SKU** — stable product identity and basic economics.
2. **PromotionCase** — immutable evaluated evidence from one autonomous decision.
3. **Lesson** — compact reusable knowledge retrieved before future decisions.

There is no separate Store, SimulatorResult, Counterfactual, Feedback, or RuleVersion object in the MVP. Those concepts either fit naturally inside a PromotionCase or are implementation details outside durable product memory.

## Why this shape

The earlier design had `Product`, `StoreCluster`, `PromotionCase`, and `PromotionRule`. The current design keeps the useful evidence-versus-knowledge split but removes structure that does not earn its implementation cost yet.

- `StoreCluster` becomes the scalar `store` field on PromotionCase. We do not currently need store metadata or store-to-store relations.
- `PromotionRule` becomes **Lesson**, matching the learning loop terminology.
- The Promotion Agent has one action: `0%`, `10%`, `20%`, or `30%` discount. That chosen action is applied directly to the simulator.
- Context is stored as explicit scalar buckets instead of generic tag arrays, which makes retrieval and debugging predictable.
- Lessons update in place by deterministic `lesson_key`; revision history can wait until there is evidence that it is useful.

## Objects

### SKU

Stable product identity shared by many promotion cases.

Important fields:

- `sku_id` — stable primary key.
- `name` — demo-friendly product name.
- `category` — normalised category used for lesson generalisation.
- `base_price` — baseline shelf price.
- `cost` — unit cost used for gross-profit simulation.

For the demo, six understandable categories are enough: ice cream, beer, soft drinks, chips, meat, and yogurt. There is no prize for reproducing a supermarket master-data system by Friday night.

### PromotionCase

One completed scenario after the Promotion Agent acts, the simulator returns an outcome, and the evaluator replays all four actions.

A case contains four groups of facts.

#### Scenario

- `scenario_date`
- `store`
- `price`
- `baseline_sales`
- `stock`
- `stock_level`: `normal | high`
- `day_type`: `weekday | weekend`
- `weather`: `normal | hot | rain`
- `temperature_c`
- `event_type`: `none | local_event`
- `event_note`, for example `concert_nearby`

The raw scenario can still contain richer fields in application code. Only memory-relevant context belongs here.

#### Decision

- `chosen_discount`: `0 | 10 | 20 | 30`

There is exactly one applied action. The Promotion Agent chooses it and the simulator evaluates it.

#### Outcome

- `units_sold`
- `gross_profit`

Gross profit is the primary KPI for the MVP.

#### Counterfactual evaluation

- `best_discount`
- `best_gross_profit`
- `regret = best_gross_profit - gross_profit`
- `regret_pct`

Example:

```text
Agent chose:     10%
Gross profit:    £252
Oracle best:     20% / £281
Regret:          £29
```

This is the feedback signal that drives learning. The simulator, not another model, provides the objective comparison.

### Lesson

A Lesson is compact reusable knowledge derived from multiple evaluated cases and retrieved before the next decision.

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
  "rationale": "On hot weekends with high stock, 20% produced better gross profit than lighter discounts; 30% usually lost too much margin."
}
```

Important fields:

- `lesson_key` — deterministic identity for one scope/context bucket.
- `scope` — `sku:<id>` or `category:<category>`.
- optional `store_scope` — only when evidence is deliberately store-specific.
- optional conditions: `day_type`, `weather`, `event_type`, `stock_level`.
- `recommended_discount` — one of the four allowed actions.
- `rationale` — short evidence-grounded explanation.
- `evidence_count` — linked cases used by the lesson.
- `confidence` — agreement/strength of evidence, computed or validated in code.
- `avg_profit_advantage_pct` — business-readable strength of the recommendation versus the best alternative.

## Lesson identity

Do not let the LLM invent a fresh lesson every time it discovers the same thing using slightly different English.

Build `lesson_key` deterministically in application code:

```text
<scope>|store:<store-or-any>|<day-or-any>|<weather-or-any>|event:<event-or-any>|stock:<stock-or-any>
```

Examples:

```text
category:ice_cream|store:any|weekend|hot|event:any|stock:high
sku:ICE500|store:London Central|weekend|hot|event:local_event|stock:any
```

Repeated evidence updates the same Lesson.

For the MVP, a Lesson represents current aggregated knowledge. We intentionally do not create `active/superseded` versions. If revision history becomes useful later, add it as a separate observability feature.

## Relations

The schema has only three relations.

### `case_sku`

Each PromotionCase belongs to exactly one SKU. One SKU can have many cases.

### `lesson_evidence`

Many-to-many relation between Lesson and PromotionCase.

This is the most important relation in the project: every lesson can show exactly which evaluated cases produced or reinforced it.

```text
Lesson #17
  <- CASE-0004
  <- CASE-0011
  <- CASE-0018
  <- CASE-0023
```

### `lesson_sku_scope`

Optional direct link for SKU-specific lessons. Category-level lessons can rely on `scope=category:<name>` and do not need to be linked to every SKU in the category.

## Write path

The Evaluator / Learner writes memory only after all four actions have been replayed by the deterministic simulator.

1. Upsert the SKU.
2. Write one PromotionCase containing scenario, chosen action, realised outcome, oracle optimum, and regret.
3. Select the coarse lesson scope/context bucket.
4. Read the existing Lesson for the deterministic `lesson_key`, if any.
5. Recalculate supporting evidence using linked PromotionCases.
6. Create or update the Lesson.
7. Link every case actually used as evidence through `lesson_evidence`.

For the first implementation, code should calculate:

- regret and regret percentage;
- evidence count;
- profit advantage;
- lesson key;
- and preferably validate the recommended discount against supporting simulator outcomes.

The LLM is useful for deciding whether evidence deserves a more general scope and for writing the concise rationale. Arithmetic and action comparison should stay deterministic in code, because giving prose models custody of accounting is how legends begin.

## Read path

Before choosing a discount, the Promotion Agent asks xmemory for a very small amount of relevant experience.

Recommended order:

1. exact SKU lessons matching context;
2. category lessons matching context;
3. store-specific lessons when relevant;
4. at most a few strongest supporting cases when explanation is needed.

For the MVP, retrieve at most `3` Lessons and optionally `2` supporting PromotionCases per Lesson.

Example request intent:

```text
Find the strongest lessons relevant to Ice Cream 500ml,
London Central, hot weekend, local event, high stock.
Include evidence only when needed to explain the recommendation.
```

The retrieved lesson is evidence, not a mandatory instruction. The Promotion Agent can still choose another action when the current scenario differs materially.

## What stays outside xmemory

Do not store:

- full prompts or conversations;
- chain-of-thought or hidden reasoning;
- simulator coefficients / hidden ground truth;
- all four counterfactual outcomes as separate objects;
- random noise values;
- embeddings or hand-maintained vector indexes;
- raw source-dataset rows after they have been converted into scenarios;
- dozens of product/store attributes that the simulator does not use.

The hidden simulator coefficients must remain outside memory. Otherwise the agent is not learning from experience; it is reading the answer key, which rather ruins the point of the experiment.

## Minimal implementation for two developers

1. Create the xmemory instance from [`schema.xmd.yaml`](schema.xmd.yaml).
2. Implement `write_promotion_case()`.
3. Implement deterministic `build_lesson_key()`.
4. Implement `update_lesson_from_cases()`.
5. Implement `read_relevant_lessons()` in the Promotion Agent.
6. Log retrieved Lesson IDs with every recommendation.
7. Run the same fixed benchmark scenarios with clean and trained memory.

Only after that consider richer scope generalisation, lesson decay, conflicting evidence, or revision history.

## Demo trace

The final demo should make the entire write -> read -> changed behaviour chain visible:

```text
CASE-0018
Ice Cream 500ml · hot weekend · high stock
Agent chose: 10%
Gross profit: £252
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

That is the hackathon story in one screen: the case was written, evidence became a Lesson, the Lesson was read later, and the same agent changed its behaviour because persistent memory existed.
