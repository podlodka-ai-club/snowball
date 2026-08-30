# Promotion Agent Runtime Flow

This is the primary "what does the agent actually do?" view.

![Promotion Agent runtime flow](../../assets/promotion-agent-flow.svg)

PlantUML source: [`flow.puml`](flow.puml)

The flow is intentionally explicit about the parts that change behavior and the parts that only make delivery reliable:

1. consume and validate one `PromotionScenario`;
2. check durable `scenario_id` idempotency state in the H2 `DecisionJournal`;
3. retrieve relevant xmemory Lessons and deterministically keep at most three;
4. build the stable model input from scenario + allowed discounts + Lessons;
5. ask the model for exactly one discount from `0 | 10 | 20 | 30`;
6. validate the structured result, retry once, then fall back to `0%` if necessary;
7. persist the exact decision event as `DECIDED` before publishing;
8. publish to `promotion.decisions.v1`;
9. mark `COMPLETED` and acknowledge the source Kafka offset only after publish succeeds.

The restart behavior matters:

- `COMPLETED` means the scenario is a harmless duplicate and the agent skips memory/model calls;
- `DECIDED` means the exact decision was already made, so the agent republishes that stored event rather than asking the model again;
- a new/`STARTED` scenario goes through memory retrieval and model decision normally.

The model never receives simulator coefficients, oracle answers, future outcomes, or an unrestricted xmemory dump. The benchmark difference is therefore concentrated where it belongs: the same decision process sees either no durable Lessons or useful accumulated Lessons.

The older [`architecture.mmd`](architecture.mmd) remains a component/topology view. This PlantUML flow is the better view for explaining runtime behavior.
