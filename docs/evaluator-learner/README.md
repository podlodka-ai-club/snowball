# Evaluator / Learner

The whole job of this component is one sentence:

> **Take one finished promotion, check what would have happened with every allowed discount, save the result as experience, and update reusable Lessons.**

Nothing else belongs on this page.

![Evaluator / Learner overview](../../assets/evaluator-learner-architecture.svg)

Editable source: [`architecture.mmd`](architecture.mmd)

## The task

Input:

```text
One completed promotion
```

Example:

```text
SKU: Ice Cream 500ml
Context: hot weekend, high stock
Agent chose: 10%
Actual simulated gross profit: £252
```

Output:

```text
1 PromotionCase
2 updated Lessons
```

The component answers two questions:

1. **Was the agent's choice good?**
2. **What should the agent remember for similar situations next time?**

That is the entire purpose.

## Sequence

![Evaluator / Learner sequence](../../assets/evaluator-learner-sequence.svg)

Editable source: [`sequence.mmd`](sequence.mmd)

The sequence is deliberately small:

```text
1. Receive completed promotion
2. Replay 0%, 10%, 20%, 30%
3. Find the best discount
4. Calculate regret
5. Save one PromotionCase
6. Update one SKU Lesson
7. Update one category Lesson
8. Done
```

## Step 1: receive one completed promotion

The Evaluator receives the exact scenario, chosen discount, and chosen outcome.

Tiny example:

```text
scenario_id: SCN-18
sku: ICE500
category: ice_cream
day: weekend
weather: hot
stock: high
chosen discount: 10%
chosen gross profit: £252
```

At this point we know what happened for **10%**, but we do not yet know whether 10% was the best choice.

## Step 2: replay all four choices

The Evaluator asks the deterministic simulation capability the same question four times:

```text
What if discount = 0%?
What if discount = 10%?
What if discount = 20%?
What if discount = 30%?
```

Everything except the discount stays identical.

Example result:

| Discount | Gross profit |
| --- | ---: |
| 0% | £240 |
| 10% | £252 |
| 20% | £281 |
| 30% | £263 |

The Evaluator does not guess these numbers and an LLM does not calculate them.

## Step 3: find the best action

Choose the discount with the highest gross profit.

For the example:

```text
best discount = 20%
best gross profit = £281
```

If two discounts have exactly the same gross profit, choose the lower discount.

Example:

```text
10% -> £270
20% -> £270

winner -> 10%
```

## Step 4: calculate regret

Regret means: **how much gross profit did the chosen action leave on the table?**

```text
regret = best gross profit - chosen gross profit
```

Example:

```text
£281 - £252 = £29
```

So this decision lost £29 compared with the best available action.

## Step 5: save one PromotionCase

The evaluated result becomes one immutable `PromotionCase`.

Example:

```text
PromotionCase CASE-v1-SCN-18

context:
  ice_cream
  weekend
  hot
  high stock

agent chose:
  10%

profits:
  0%  -> £240
  10% -> £252
  20% -> £281
  30% -> £263

best:
  20% -> £281

regret:
  £29
```

This object is the durable evidence. After restart we can still see exactly what happened without replaying the scenario again.

`case_id` is deterministic:

```text
CASE-<simulator_version>-<scenario_id>
```

So the same completed promotion cannot accidentally create another case on retry.

## Step 6: choose which Lessons this case belongs to

For the MVP, every PromotionCase contributes to exactly **two** Lessons.

### SKU Lesson

Very specific:

```text
sku:ICE500 + weekend + hot + high stock
```

Meaning:

```text
What have we learned about this exact product in this kind of situation?
```

### Category Lesson

More general:

```text
category:ice_cream + weekend + hot + high stock
```

Meaning:

```text
What have we learned about ice cream in this kind of situation?
```

That is all. No combinations for every optional field. No lesson explosion worthy of a supermarket ontology committee.

## Step 7: recompute each Lesson from its cases

A Lesson is not generated from one clever sentence. It is calculated from all `PromotionCase` objects linked to it.

Imagine the category Lesson already has three cases:

| Case | 0% | 10% | 20% | 30% |
| --- | ---: | ---: | ---: | ---: |
| A | £240 | £255 | £280 | £260 |
| B | £220 | £250 | £275 | £268 |
| C | £250 | £270 | £290 | £271 |

Add the columns:

```text
0%  total = £710
10% total = £775
20% total = £845
30% total = £799
```

Therefore:

```text
recommended discount = 20%
```

The recommendation is simple: choose the action with the highest accumulated gross profit across the evidence for that Lesson.

The same data also produces:

```text
evidence_count
confidence
avg_profit_advantage_pct
rationale
```

All numeric values are calculated in deterministic application code.

## What a Lesson looks like

Tiny example:

```text
Lesson

scope:
  category:ice_cream

context:
  weekend
  hot
  high stock

recommend:
  20%

evidence:
  7 cases

confidence:
  0.82

average advantage:
  +8.6%

rationale:
  20% produced the highest average gross profit across 7 similar cases.
```

This is what the Promotion Agent can read before a later decision.

## Confidence

Keep confidence understandable.

It increases when:

- there are more cases;
- the same action keeps winning;
- the winning action is meaningfully better than alternatives.

It decreases when:

- there are only one or two cases;
- different actions win in different cases;
- profits are almost tied.

For v1:

```text
evidence_score  = min(case_count / 5, 1)
agreement_score = cases where recommendation was best / case_count
advantage_score = clamp(avg_profit_advantage_pct / 10, 0, 1)

confidence =
  0.60 * evidence_score
+ 0.25 * agreement_score
+ 0.15 * advantage_score
```

The exact formula matters less than two properties: it is deterministic and easy to explain.

## What happens when new evidence disagrees?

Nothing dramatic. Recalculate the same Lesson.

Before:

```text
Lesson recommends 20%
```

New cases arrive.

Now accumulated totals say:

```text
10% -> £1,420
20% -> £1,390
```

Then the same Lesson becomes:

```text
recommended discount = 10%
```

No new conflicting Lesson is created. No LLM debates its former self. The Lesson simply reflects the current accumulated evidence.

## xmemory writes

For one completed promotion, the useful durable writes are:

```text
PromotionCase
  -> linked to SKU

PromotionCase
  -> linked as evidence to SKU Lesson

PromotionCase
  -> linked as evidence to category Lesson

SKU Lesson
  -> recomputed

Category Lesson
  -> recomputed
```

The important relation is `lesson_evidence`:

```text
Lesson
  <- Case A
  <- Case B
  <- Case C
```

That relation explains exactly why a Lesson says what it says.

## Duplicate safety

If the same completed promotion is processed twice:

```text
same scenario
-> same case_id
-> same lesson_keys
-> same evidence relations
```

The second run verifies/recomputes existing state instead of increasing `evidence_count` twice.

## Failure rule

The rule is equally simple:

> **No complete four-action evaluation = no new learning.**

If one replay fails, or the replayed chosen action does not match the original chosen outcome, write no new Lesson evidence.

A completed `PromotionCase` can be safely retried into Lesson recomputation if a later memory write fails.

## Minimal implementation

The implementation only needs these logical functions:

```text
evaluate(outcome)
  -> replay 4 actions
  -> calculate oracle + regret
  -> PromotionCase

lessonKeys(case)
  -> SKU key
  -> category key

recomputeLesson(lessonKey, linkedCases)
  -> recommended discount
  -> evidence count
  -> confidence
  -> advantage
  -> rationale

persist(case, lessons)
```

If these four pieces work, the learning loop works.

## One-screen demo

```text
CASE-v1-SCN-18
Hot weekend · high stock · Ice Cream 500ml

Agent chose: 10%

0%  -> £240
10% -> £252   <- chosen
20% -> £281   <- best
30% -> £263

Regret: £29

        |
        v

CATEGORY LESSON
ice_cream · hot · weekend · high stock

20% is currently best
7 supporting cases
confidence 0.82
average advantage +8.6%

        |
        v

A later Promotion Agent can retrieve this Lesson
before choosing its next discount.
```

That is the component: **evaluate one experience, store the evidence, improve the reusable Lesson.**
