# TODO: Design Evaluator / Learner

Use this prompt in a future session to continue the HackerSprint2 project.

## Prompt

We are building a HackerSprint2 project: a self-learning FMCG Promotion Agent with persistent xmemory.

Repository:

`zu50052f/hacker-sprint-2`

Before making changes, inspect the current repository, especially:

- `README.md`
- `docs/architecture/`
- `docs/scenario-generator/`
- `docs/promotion-agent/`
- `docs/xmemory/`
- `docs/benchmark/`
- `docs/market-simulator/` if it already exists

The current autonomous loop is:

`Scenario -> Promotion Agent -> Market Simulator -> Evaluator/Learner -> PromotionCase + Lesson in xmemory -> next Scenario`

The Promotion Agent chooses exactly one action:

- 0% discount
- 10%
- 20%
- 30%

Promotion duration is fixed to one day.

Human is completely removed from the MVP loop.

The main hackathon claim is:

```text
same model
same prompt
same simulator
same benchmark
clean memory   -> worse decisions
trained memory -> better decisions
```

The Evaluator/Learner is the component that closes the self-learning loop.

## Existing xmemory model

xmemory intentionally contains only three domain objects:

1. SKU
2. PromotionCase
3. Lesson

PromotionCase contains scenario/context, chosen action outcome, all four replay profits, oracle result, and regret.

Important fields include:

- chosen_discount
- units_sold
- gross_profit
- profit_0
- profit_10
- profit_20
- profit_30
- best_discount
- best_gross_profit
- regret
- regret_pct

Lesson contains:

- deterministic `lesson_key`
- scope
- optional context conditions
- recommended_discount
- rationale
- evidence_count
- confidence
- avg_profit_advantage_pct

Core relations:

- SKU -> PromotionCase
- PromotionCase <-> Lesson through evidence relation
- Lesson <-> SKU optionally for SKU-specific scope

Hidden simulator coefficients must NEVER be stored in xmemory.

The Promotion Agent retrieves Lessons before later decisions.

## Goal of this task

Design the **Evaluator / Learner architecture and contracts only**. Do not implement the runtime yet.

Keep the design as small and concrete as the existing Scenario Generator and Promotion Agent designs.

The Evaluator/Learner must provide the objective feedback signal and convert repeated evaluated cases into durable reusable Lessons.

## Main design questions to resolve

### 1. Runtime boundary

Decide whether Evaluator/Learner should be:

- part of the same runtime/module as Market Simulator,
- a separate Kotlin/Spring Boot service,
- or another small runtime if xmemory integration materially benefits from it.

Prefer the smallest deployment design that keeps the learning responsibility clear.

Do not split evaluator and learner into separate deployables for the MVP unless there is a concrete reason.

### 2. Input boundary

Assuming Market Simulator publishes a chosen-action outcome event such as:

`promotion.outcomes.v1`

Evaluator/Learner should consume that result and have enough information to identify:

- scenario_id
- decision_id
- normalized scenario
- chosen discount
- chosen units_sold
- chosen gross_profit
- simulator version

Define the consumer group, validation, retries, offset policy, and duplicate behavior.

Avoid exactly-once machinery.

### 3. Counterfactual replay

For every completed chosen-action outcome, replay the exact same scenario with:

- 0%
- 10%
- 20%
- 30%

Use the exact same Market Simulator version and deterministic noise seed semantics.

The evaluator must calculate:

```text
profit_0
profit_10
profit_20
profit_30
best_discount
best_gross_profit
regret
regret_pct
```

Define tie-breaking explicitly. For example, when two discounts produce the same gross profit within a configured tolerance, prefer the lower discount.

This must be deterministic.

### 4. Arithmetic ownership

All business arithmetic should be deterministic application code, not LLM output.

Code should calculate:

- best discount
- best gross profit
- regret
- regret percentage
- action rankings
- evidence count
- average profit advantage
- confidence or at least the deterministic portion of confidence

Do not ask the model to perform arithmetic that can be computed exactly.

### 5. PromotionCase creation

Define exactly how one evaluated outcome becomes one immutable PromotionCase.

The case should contain enough evidence to survive process restart without rerunning the simulator:

- scenario context
- chosen action
- chosen outcome
- all four replay profits
- oracle best action/profit
- regret metrics

Use deterministic `case_id`, ideally derived from stable scenario/decision identity.

Prevent duplicate cases for duplicate Kafka deliveries.

### 6. xmemory write path

Define the exact sequence for durable writes.

A reasonable structure is:

```text
outcome event
  -> replay all actions
  -> build PromotionCase
  -> upsert SKU
  -> write PromotionCase
  -> link case_sku
  -> determine lesson candidates
  -> create/update Lessons
  -> link lesson_evidence
```

Decide what must happen atomically from the application's point of view and what can be safely retried idempotently.

Do not invent cross-system distributed transactions for the hackathon.

### 7. Lesson generation strategy

This is the most important part of the task.

Design a minimal but meaningful mechanism that turns evaluated cases into reusable Lessons.

The learner should not merely produce one free-form lesson per case.

Use deterministic scope/context buckets and deterministic `lesson_key` values.

Potential scopes:

1. exact SKU
2. category

Potential context dimensions:

- day_type
- weather
- event_type
- stock_level
- optional store_scope

For current MVP one store exists, so avoid needlessly making every Lesson store-specific.

### 8. Lesson candidate policy

Define when a case updates which Lesson buckets.

For example, one case might contribute to:

- `sku:ICE500|weekend|hot|stock:high`
- `category:ice_cream|weekend|hot|stock:high`

But creating every Cartesian combination of context dimensions would flood memory with garbage.

Design a deliberately small candidate policy.

A strong MVP approach could be:

- always update one exact-SKU coarse-context bucket;
- always update one category coarse-context bucket;
- optionally create a more specific event-aware bucket only after enough evidence exists.

Choose a simple rule and document it precisely.

### 9. Recommended discount

For each Lesson bucket, determine `recommended_discount` from supporting PromotionCases using stored counterfactual profits.

Possible deterministic approach:

For every allowed discount, compute mean gross profit across linked cases.

Then choose the action with the highest mean gross profit.

Define tie-breaking.

This is preferable to letting the LLM simply declare which discount feels persuasive.

### 10. Evidence count

`evidence_count` should equal the number of PromotionCases genuinely used to compute the Lesson.

Do not count cases merely because they look vaguely similar.

The `lesson_evidence` relation must remain the authoritative provenance trace.

### 11. Average profit advantage

Define `avg_profit_advantage_pct` deterministically.

A useful interpretation is the average percentage advantage of the Lesson's recommended discount over the best alternative across supporting cases.

Be precise about denominator and zero handling.

The metric should be explainable in the hackathon demo without requiring econometrics folklore.

### 12. Confidence

Design a compact deterministic confidence formula.

Confidence should increase when:

- evidence count grows;
- the same discount consistently wins;
- the profit advantage over alternatives is meaningful.

Confidence should decrease when:

- evidence conflicts;
- winners alternate frequently;
- action profits are nearly tied.

Keep it bounded 0.0 to 1.0.

Do not use model self-reported confidence.

A simple formula is preferable to a theoretically impressive formula nobody can explain during the demo.

### 13. Minimum evidence threshold

Decide whether Lessons should be exposed to Promotion Agent immediately after one case.

Consider a threshold such as:

- 1 case: Lesson exists but low confidence
- 2–3 cases: usable
- more evidence: confidence increases

Alternatively, suppress Lessons below a minimum evidence count.

Choose based on which gives the clearest learning curve for 200–300 training scenarios.

### 14. Lesson rationale

The rationale can be generated from deterministic evidence or optionally polished by an LLM.

If an LLM is used, its job should be limited to producing a short human-readable sentence from already computed facts.

It must not determine:

- recommended discount
- confidence
- evidence count
- profit advantage

Example input to rationale generation:

```text
scope: category:ice_cream
context: weekend + hot + high stock
recommended discount: 20
mean profit advantage: 8.6%
evidence: 7 cases
30% lost margin in 6/7 cases
```

Example output:

`On hot weekends with high stock, 20% usually produced the strongest gross profit; 30% increased volume but lost too much margin.`

Keep rationale short and evidence-grounded.

### 15. Lesson update and contradiction handling

Define what happens when new evidence disagrees with an existing Lesson.

For MVP, prefer recomputation from linked PromotionCases rather than incremental prose mutation.

Example:

```text
old recommendation: 20%
new accumulated evidence: 10% now has higher mean profit
-> same lesson_key
-> recompute fields
-> recommended_discount becomes 10%
-> confidence recalculated
```

This naturally handles contradiction without creating version trees.

Document this explicitly because it is a strong self-improvement story.

### 16. Forgetting / stale evidence

Decide whether to implement any forgetting behavior in the MVP design.

A minimal useful extension could be:

- no deletion in v1;
- but support a configurable evidence window later, based on most recent N cases or scenario date.

Do not add time decay unless it materially improves the hackathon demo.

The simulator is stationary for the benchmark, so aggressive forgetting would mostly manufacture complexity.

### 17. Idempotency

Duplicate outcome events must not:

- create duplicate PromotionCases;
- increment evidence_count twice;
- create duplicate lesson_evidence relations;

Use deterministic IDs and upsert semantics where possible.

Define retry-safe write behavior clearly.

### 18. Failure behavior

Define behavior when:

- replay fails for one action
- xmemory is unavailable
- PromotionCase write succeeds but Lesson update fails
- Lesson update succeeds but relation write fails
- duplicate outcome arrives
- malformed counterfactual result appears

Strong preference:

- never publish/update a Lesson from incomplete four-action evaluation;
- retry the learning write path safely;
- preserve enough local state/logging to resume without recomputing logically different evidence.

Do not silently convert partial evaluation into memory.

### 19. Observability

For every evaluated case make the full causal trace visible:

```text
scenario_id
  -> decision_id
  -> chosen discount
  -> chosen profit
  -> [profit_0, profit_10, profit_20, profit_30]
  -> oracle best
  -> regret
  -> case_id
  -> affected lesson_keys
```

For every Lesson update record/log:

- lesson_key
- previous recommendation
- new recommendation
- evidence_count
- confidence
- avg_profit_advantage_pct
- case_id that triggered recomputation

This is essential for the hackathon demo.

We need to be able to show something like:

```text
CASE-0018
  -> LESSON-X created/reinforced

later

CASE-0051
Promotion Agent retrieved LESSON-X
  -> chose 20%
  -> oracle also 20%
```

### 20. Clean-memory benchmark isolation

The benchmark must be able to run evaluation scenarios without contaminating training memory.

Define an explicit mode such as:

- `LEARNING_ENABLED=false` during benchmark evaluation

or use a separate memory instance.

The clean-memory and trained-memory benchmark runs must evaluate the same fixed scenarios without writing new Lessons during the comparison.

Document this boundary clearly.

### 21. Training flow

The expected training phase is roughly:

- 200–300 scenarios
- learning enabled
- PromotionCases + Lessons written to xmemory

Benchmark phase:

- 50 fixed scenarios
- learning disabled
- compare clean xmemory vs trained xmemory

Define how the evaluator behaves in both modes.

### 22. xmemory API usage

Use the current repository's xmemory design as the source of truth.

Define exact read/write operations needed by Evaluator/Learner:

- upsert SKU
- write/upsert PromotionCase
- get current Lesson by deterministic `lesson_key`
- retrieve linked PromotionCases needed for recomputation
- upsert Lesson
- create idempotent `lesson_evidence` relation
- optional SKU scope relation

Do not store operational retry state in xmemory unless it is genuinely product memory.

### 23. Contracts/docs to create

Follow the same repo pattern:

```text
docs/evaluator-learner/
  README.md
  architecture.mmd
```

If Evaluator/Learner publishes a useful event for observability or benchmark orchestration, define a versioned schema only if there is an actual consumer. Do not invent a Kafka topic merely to make the diagram more symmetrical.

Render:

`assets/evaluator-learner-architecture.svg`

Then update:

- root `README.md`
- `docs/architecture/README.md`
- `docs/architecture/high-level-architecture.mmd`
- `assets/high-level-architecture.svg`
- `docs/xmemory/README.md` if the finalized learning algorithm changes how Lessons are computed
- `docs/benchmark/README.md` if benchmark isolation needs clarification

## Important boundary with Market Simulator

Market Simulator owns:

- hidden market coefficients
- simulation of `(scenario, discount)`
- deterministic noise semantics
- chosen-action outcome generation

Evaluator/Learner owns:

- requesting/reusing four action simulations
- oracle calculation
- regret
- PromotionCase creation
- Lesson aggregation/recalculation
- xmemory writes

Evaluator/Learner must never directly read simulator coefficients.

It sees results, not the answer key.

## Important boundary with Promotion Agent

Promotion Agent only reads Lessons before deciding.

Evaluator/Learner should never directly mutate Promotion Agent prompt or code based on a case.

Behavior changes through durable memory:

```text
case
-> Lesson update in xmemory
-> later Lesson retrieval
-> same Promotion Agent prompt/model sees different evidence
-> decision may change
```

That write -> read loop is the core of the project.

## Guiding principle

Optimize for a convincing self-learning demo, not theoretical sophistication.

Prefer:

- deterministic arithmetic
- explicit evidence provenance
- small Lesson buckets
- recomputation from durable cases
- visible contradictions and recommendation changes
- retry-safe writes

Avoid:

- one free-form lesson per case
- LLM-calculated metrics
- embedding everything without structured filtering
- dozens of Lesson dimensions
- complex reinforcement learning
- online model fine-tuning
- distributed transactions
- human approval workflow

The final demo must make this chain obvious:

```text
experience was written
-> aggregated into a durable Lesson
-> Lesson survived restart
-> later decision retrieved it
-> behavior changed
-> benchmark improved
```

Complete this task by committing the Evaluator/Learner architecture and learning design to the repository. Do not implement the runtime yet.
