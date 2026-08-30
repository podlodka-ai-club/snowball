## Context

`docs/market-simulator/README.md` contains the complete simulator v1 formula, coefficient tables, deterministic noise algorithm, rounding rules, and strict scope boundary. Market Simulator, Evaluator, and Learner may share one deployable JVM for the MVP, but they remain logical sibling components.

## Goals / Non-Goals

**Goals:**
- Pure simulation engine with golden deterministic tests.
- Hidden immutable simulator v1 configuration.
- Kafka decision input and `OutcomeSink` output boundary.
- Reusable simulation capability for evaluator counterfactuals.

**Non-Goals:**
- Oracle selection, regret, cases, lessons, xmemory.
- Separate outcomes Kafka topic in v1.
- Hot-reloaded coefficients.
- Random nondeterministic market behavior.

## Decisions

1. Load simulator v1 configuration once at startup and fail startup if any supported category/context/action coefficient is missing or invalid.
2. Implement `SimulationEngine` as a pure function with no Kafka/xmemory dependencies.
3. Follow the exact calculation and rounding order from `docs/market-simulator/README.md`; use BigDecimal where specified.
4. Derive scenario noise from first eight SHA-256 bytes interpreted unsigned big-endian and reuse it across all actions for a scenario.
5. Keep deterministic `outcome_id=OUT-<decision_id>` and preserve the scenario snapshot from the decision event.
6. The Kafka adapter performs validation and invokes the engine, then calls `OutcomeSink`. Only successful handoff permits acknowledgement.
7. Treat invalid input/config as permanent unhealthy failures. Use only bounded retries for transient downstream handoff failures.
8. Prevent hidden simulator internals from being serialized into the business outcome, xmemory, or Promotion Agent-accessible logs/interfaces.

## Risks / Trade-offs

- Hidden handcrafted coefficients are not real market science; they are controlled ground truth for demonstrating learning behavior.
- Determinism reduces realism but makes clean-vs-trained comparison defensible.
- In-process `OutcomeSink` couples deployment but not domain ownership; a future transport can wrap the same outcome contract.

## References

- `docs/market-simulator/README.md`
- `docs/market-simulator/promotion-outcome-v1.schema.json`
- `docs/market-simulator/promotion-outcome-v1.example.json`
- `docs/promotion-agent/promotion-decision-v1.schema.json`
