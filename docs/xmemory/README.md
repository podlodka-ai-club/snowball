# xmemory Design

xmemory is the durable learning layer of the FMCG promotion agent. The MVP stores only information that can change a later promotion decision or explain why that decision changed.

![xmemory schema](../../assets/xmemory-schema.svg)

Editable diagram source: [`schema.mmd`](schema.mmd)  
XMD v1 schema: [`schema.xmd.yaml`](schema.xmd.yaml)

## MVP goal

Close one visible and reproducible loop:

`scenario -> agent recommendation -> human decision -> simulator oracle -> case -> lesson -> later retrieval -> changed decision`

The memory schema deliberately contains only three objects:

1. **SKU** — stable product identity and basic economics.
2. **PromotionCase** — immutable evaluated evidence, including human correction.
3. **Lesson** — compact reusable knowledge retrieved before future decisions.

There is no separate Store, HumanFeedback, SimulatorResult, Counterfactual, or RuleVersion object in the MVP. Those concepts either fit naturally inside a PromotionCase or are implementation details outside durable product memory.

## Why this is better than the previous schema

The previous design had `Product`, `StoreCluster`, `PromotionCase`, and `PromotionRule`. The new design keeps the useful evidence-versus-knowledge split but removes structure that does not earn its implementation cost yet.

- `StoreCluster` becomes the scalar `store` field on PromotionCase. We do not currently need store metadata or store-to-store relations.
- `PromotionRule` becomes **Lesson**, matching the product language and the hackathon learning loop.
- Human accept/change feedback lives directly in PromotionCase rather than becoming a separate feedback object or agent.
- Context is stored as explicit scalar buckets instead of `context_tags` arrays. XMD v1 fields are scalar, and explicit fields make retrieval and debugging much easier.
- Lessons update in place by deterministic `lesson_key`; we do not build rule versioning or superseding machinery until there is evidence that we need it.

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

One completed scenario after the human decision, simulator run, and counterfactual evaluation.

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

#### Agent and human decision

- `agent_discount`: `0 | 10 | 20 | 30`
- `human_discount`: final approved action
- `human_changed`: whether the manager overrode the recommendation
- `human_reason`: short explanation for an override

When the manager accepts the recommendation, `human_discount == agent_discount` and `human_changed == false`.

The human reason is useful evidence, but it is not truth. A manager can say "concert nearby means 30% will win" and still be wrong. The simulator oracle decides whether that correction actually helped.

#### Outcome

- `units_sold`
- `gross_profit` for the human-approved action
- `agent_gross_profit` for the agent's original action

If the human accepts the recommendation, those two profit values are equal. If the human overrides it, the evaluator still replays the original agent action so we can measure whether the correction added value.

#### Counterfactual evaluation

- `best_discount`
- `best_gross_profit`
- `agent_regret = best_gross_profit - agent_gross_profit`
- `applied_regret = best_gross_profit - gross_profit`
- `human_profit_delta = gross_profit - agent_gross_profit`

This distinction matters. A human correction should not automatically become a lesson just because a human typed it. We can objectively see whether the override improved gross profit.

Example:

```text
Agent:          10%
Human:          20%
Agent profit:   £252
Human profit:   £281
Oracle best:    20% / £281

agent_regret:       £29
applied_regret:      £0
human_profit_delta: +£29
```

That is much stronger feedback than `manager_changed=true`.

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

For the MVP, a Lesson represents current aggregated knowledge. We intentionally do not create `active/superseded` versions. If revision history becomes useful later, add it as a separate observability feature rather than pretending one primary key can simultaneously identify both the old and replacement records.

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
2. Write one PromotionCase containing scenario, agent recommendation, human decision, actual outcome, oracle optimum, and regrets.
3. Select the coarse lesson scope/context bucket.
4. Read the existing Lesson for the deterministic `lesson_key`, if any.
5. Recalculate supporting evidence using linked PromotionCases.
6. Create or update the Lesson.
7. Link every case actually used as evidence through `lesson_evidence`.

For the first implementation, code should calculate:

- regret;
- evidence count;
- profit advantage;
- lesson key;
- and preferably validate the recommended discount against supporting simulator outcomes.

The LLM is useful for deciding whether evidence deserves a more general scope and for writing the concise rationale. It should not be allowed to creatively calculate £29 as £47 because language models occasionally treat arithmetic as interpretive dance.

## Human correction policy

Human feedback is valuable, but only evaluated feedback should influence durable lessons.

Recommended MVP rule:

- store every accept/change decision in PromotionCase;
- treat `human_reason` as candidate context;
- only strengthen a Lesson when counterfactual evaluation supports the conclusion;
- if the human override loses profit, preserve the case but do not promote the manager's intuition as truth.

This gives the demo a genuine human-in-the-loop story without letting human authority poison memory automatically.

## Read path

Before recommending a discount, the Promotion Agent asks xmemory for a very small amount of relevant experience.

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

The hidden simulator coefficients must remain outside memory. Otherwise the agent is not learning from experience; it is reading the answer key, a beloved human educational tradition that rather defeats the experiment.

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
Agent: 10%
Human: 20% — "concert nearby"
Agent GP: £252
Applied GP: £281
Oracle: 20% / £281
Human delta: +£29
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