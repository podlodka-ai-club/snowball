## Context

`docs/architecture/README.md` describes five components connected by three versioned JSON contracts, and `docs/` already commits the schemas plus valid examples for all three. The OpenSpec changes for the components assume those contracts exist as typed models, but nothing in the repository builds yet. This change is the foundation layer only: build, style, contract types, ports, CI.

The transport question is deliberately left open. The architecture documents describe Kafka topics, and the team discussion on PR #1 has not settled whether the MVP needs a broker at all. A skeleton that hard-codes either answer would force a rewrite of the component adapters; a skeleton that names the boundary as a plain Kotlin interface costs nothing either way.

## Goals / Non-Goals

**Goals:**
- One reproducible build command, `./gradlew spotlessCheck build`, green from a clean clone on JDK 21.
- Typed Kotlin models that are a faithful, lossless projection of the three committed v1 schemas.
- Round-trip evidence against the committed examples, so a later contract drift fails a test rather than an integration.
- Transport-neutral port interfaces that component changes can implement without renegotiating names.
- Identical formatting rules for every contributor from the first commit.

**Non-Goals:**
- Any domain behavior: simulator arithmetic, oracle, regret, Lessons, prompts, model calls.
- Kafka, Spring Boot, a database, an HTTP layer, or an xmemory client.
- Multi-module layout. Five components in one hackathon repository do not justify module wiring; the package boundary carries the ownership.
- Schema code generation and runtime JSON Schema validation.
- Runtime configuration or profiles beyond `.env.example`.

## Decisions

1. **Single Gradle module, packages as component boundaries.** Source lives under `contracts` and `ports` packages; component changes add sibling packages. Splitting into Gradle modules later is mechanical, splitting too early is not.
2. **Hand-written models, not generated ones.** Three small schemas do not repay a generator plugin in the build, and generated names would not match the ports the components already reference by name in their specs. The binding is pinned by the round-trip tests instead.
3. **Jackson as the JSON codec.** `openspec/config.yaml` names Jackson in the primary stack, and a round-trip test needs some codec. `jackson-module-kotlin` is added as a library only; no Spring, no auto-configuration. Serialization is configured to omit nothing that the schema requires and to emit no property the schema does not allow.
4. **Optional schema fields are nullable Kotlin properties with a `null` default, required fields are non-nullable without a default.** Kotlin's type system then carries the schema's `required` list, and a missing required field fails at parse time rather than downstream.
5. **Closed enumerations become Kotlin enums with explicit wire values.** `stock_level`, `day_type`, `weather`, the scenario-level `event_type`, and the `0 | 10 | 20 | 30` discount are fixed in the schema, so an unknown value is a contract violation and should fail parsing.
6. **Constant-valued envelope fields (`event_type`, `schema_version`, `simulator_version`) are modelled with fixed defaults and validated on construction.** They are part of the contract identity, not of the payload, and must survive a round trip unchanged.
7. **Money stays as the schema types it.** The schemas declare `price`, `cost`, and `gross_profit` as JSON numbers, so the models carry them as such and leave `BigDecimal` rounding policy to the Market Simulator, which is where `docs/market-simulator/README.md` specifies it. The skeleton must not pre-empt that rounding decision.
8. **Three ports, no more.** `SimulationPort` maps a scenario plus an allowed discount to an outcome; `OutcomeSink` accepts a completed outcome; `ScenarioPublisher` accepts a generated scenario. Each gets a trivial in-process implementation - a recording sink, a recording publisher, a fixed-answer simulation - used by tests and by nothing else. Naming follows the component specs so no rename is needed when they land.
9. **CI runs exactly the acceptance command.** One workflow, one job, `spotlessCheck` before `build`, so a formatting failure is reported as such instead of as a compile error.

## Ground-truth leakage

`SimulationPort` is the one port that touches simulator territory, so its shape is a boundary decision, not a convenience. Its return type is the committed `PromotionOutcomeV1` payload: units sold and gross profit. It exposes no coefficient table, no noise factor, no counterfactual set, and no oracle answer, and the contract models contain no field for any of them because the committed schemas contain none. The in-process implementation shipped here returns a caller-supplied fixed value and computes nothing, so it cannot become an accidental source of ground truth. Anything richer that the Evaluator needs - replaying all four discounts, comparing them - is orchestration on top of this port and stays outside the Promotion Agent's reachable surface, as `docs/market-simulator/README.md` requires.

## Risks / Trade-offs

- Hand-written models can drift from the schemas. The round-trip tests over the committed examples are the guard; they fail on a renamed or dropped field, though not on a constraint the examples do not exercise.
- Fixing the codec to Jackson is a small, early commitment. It is reversible - it appears only in the contract package - and it matches the stack already declared in `openspec/config.yaml`.
- Port names are chosen from the component specs before those components exist. If a spec later needs a different signature, the interface changes in one place instead of in two parallel DTO families.
- A single module means package discipline is a convention rather than a compiler-enforced rule.

## References

- `docs/architecture/README.md`
- `docs/scenario-generator/promotion-scenario-v1.schema.json`, `docs/scenario-generator/promotion-scenario-v1.example.json`
- `docs/promotion-agent/promotion-decision-v1.schema.json`, `docs/promotion-agent/promotion-decision-v1.example.json`
- `docs/market-simulator/promotion-outcome-v1.schema.json`, `docs/market-simulator/promotion-outcome-v1.example.json`
- `openspec/config.yaml`
