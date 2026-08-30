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

1. **Single Gradle module, packages laid out as the component docs prescribe.** `docs/scenario-generator/README.md`, `docs/promotion-agent/README.md`, and `docs/market-simulator/README.md` all sketch the same tree - `domain/`, `application/`, `port/`, `adapter/`, `config/` - so the skeleton uses it rather than inventing its own: the contract models go in `domain`, the port interfaces in `port`, and their in-process implementations in `adapter/inprocess` because an implementation of a port is an adapter. `application` and `config` stay empty until a component needs them. Component changes add packages inside this same tree. Splitting into Gradle modules later is mechanical, splitting too early is not.
2. **Hand-written models, not generated ones.** Three small schemas do not repay a generator plugin in the build, and generated names would not match the ports the components already reference by name in their specs. The binding is pinned by the round-trip tests instead.
3. **Jackson as the JSON codec.** `openspec/config.yaml` names Jackson in the primary stack, and a round-trip test needs some codec. `jackson-module-kotlin` is added as a library only; no Spring, no auto-configuration. Serialization is configured to omit nothing that the schema requires and to emit no property the schema does not allow.
4. **Optional schema fields are nullable Kotlin properties with a `null` default, required fields are non-nullable without a default.** Kotlin's type system then carries the schema's `required` list, and a missing required field fails at parse time rather than downstream.
5. **Closed enumerations become Kotlin enums with explicit wire values.** `stock_level`, `day_type`, `weather`, the scenario-level `event_type`, and the `0 | 10 | 20 | 30` discount are fixed in the schema, so an unknown value is a contract violation and should fail parsing.
6. **Constant-valued envelope fields (`event_type`, `schema_version`, `simulator_version`) are single-value enumerations and ordinary required properties.** They are part of the contract identity, must survive a round trip unchanged, and a wrong value must fail to parse. They deliberately carry no Kotlin default: a default would make a document that omits them parse as v1 instead of failing, even though every schema lists them as required. A secondary constructor fills them for code that builds an event rather than reading one.
7. **Unbounded JSON numbers are carried as `BigDecimal`.** `price`, `cost`, `gross_profit`, and `temperature_c` are declared as JSON numbers with no precision bound, so they are `BigDecimal`. `Double` would silently round values the schema permits, and would additionally admit `NaN` and the infinities, which JSON cannot represent at all and which Jackson emits as bare strings. This does not pre-empt the Market Simulator's rounding policy - `docs/market-simulator/README.md` still owns scale-2 `HALF_UP` - it only avoids destroying the value before the simulator can apply it.
8. **The models enforce the schema's own invariants, not business rules.** `minLength: 1`, `exclusiveMinimum: 0` on `price`, and `minimum: 0` on `cost`, `stock`, `baseline_sales`, and `units_sold` are part of the contract, not of any component's logic, so they are checked in `init` blocks and hold for direct construction as well as for parsing. Anything beyond the schema - a plausible price range, a category whitelist - belongs to the component that owns the meaning.

   Jackson's defaults are much weaker than JSON Schema, and the gaps are not guessable from the documentation - each one below was found by feeding a mutated committed example through the mapper and watching it succeed. All are closed in `ContractJson`, and each has a test: a number or boolean read into a `string` field, a number read as an enum *ordinal* so that `"stock_level": 1` becomes `HIGH`, a number or array read as a date, an explicit `null` defaulted to `0` in a required numeric field before the constructor ever runs, an explicit `null` in an optional field, a non-UTC offset normalised to `Z` on the way out, and a JSON number narrowed to `Double` inside the parser. The two temporal types need a small strict deserializer, because java-time reads a number as an epoch before any coercion configuration is consulted.

   Two deviations from the schemas remain, both narrowing and both deliberate:

   - **Integers are accepted only in canonical form.** JSON Schema defines `integer` by mathematical value, so `320.0` is a valid `stock`; this mapper rejects it. The alternative, Jackson's `ACCEPT_FLOAT_AS_INT`, also silently truncates `1.5` to `1`. Rejecting an odd-but-valid spelling is a loud failure; truncating a fractional count is a quiet corruption.
   - **`stock`, `baseline_sales`, and `units_sold` are `Int`**, which is narrower than an unbounded JSON `integer`. Nothing an FMCG scenario produces comes close to 2^31, and widening the type later is a one-line change in one file.
9. **Three ports, no more.** `SimulationPort` maps a scenario identity, a scenario payload, and an allowed discount to an outcome; `OutcomeSink` accepts a completed outcome; `ScenarioPublisher` accepts a generated scenario. Each gets a trivial in-process implementation - a recording sink, a recording publisher, a fixed-answer simulation - used by tests and by nothing else. Naming follows the component specs so no rename is needed when they land. `SimulationPort` takes `scenarioId` separately because `docs/market-simulator/README.md` types its engine as `simulate(scenarioId, scenario, discount)` and derives the deterministic noise from `v1|<scenario_id>`, while the committed `scenario` object does not carry the id; a port without it could not be implemented without being changed first, which is exactly the conflict this change exists to prevent.
10. **CI runs exactly the acceptance command.** One workflow, one job, `spotlessCheck` before `build`, so a formatting failure is reported as such instead of as a compile error.

## Team build and style guideline

The team has a drafted build and style guideline covering the stack, the code style, the lint setup, and the test layout. This change takes it as the source of truth for everything it specifies rather than re-deciding any of it: Gradle 9.7.1 through a committed wrapper, Kotlin 2.4.10, `jvmToolchain(21)`, Kotlin DSL, the version catalog, `application`, the Spotless/ktlint block, `gradle.properties`, the `.editorconfig` file verbatim, the JUnit/MockK/AssertJ test stack, backtick test names written as full sentences, and the CI workflow. The guideline also says explicitly that the project adds no code-style rules of its own on top of the official Kotlin style, so `.editorconfig` is copied exactly and not extended.

Two deliberate deviations, both additive:

- **Jackson is added as a dependency.** The guideline lists the test stack but no JSON codec, and a round-trip test against the committed examples needs one. `openspec/config.yaml` already names Jackson in the primary stack, and the guideline's own exclusion list rules out heavyweight frameworks rather than libraries pulled in for a concrete need. `jackson-datatype-jsr310` comes with it so `date` and `date-time` fields are `LocalDate`/`OffsetDateTime` rather than strings.
- **`PromotionOutcomeEvent` keeps its name.** The Market Simulator package sketch calls the file `PromotionOutcomeV1.kt`, but the other two contracts are sketched as `PromotionScenarioEvent` and `PromotionDecisionEvent`. One naming scheme across three sibling event types is worth more here than matching each sketch letter by letter; the version is already carried by `schema_version` and `simulator_version` inside the type.
- **The `src/evals/kotlin/` source set is not created.** The guideline reserves it, and it matters a great deal that evals stay separate from tests, but this change produces no eval and an empty source set would only be a place for one to be misfiled. It belongs to the change that writes the first eval.

## Ground-truth leakage

`SimulationPort` is the one port that touches simulator territory, so its shape is a boundary decision, not a convenience. Its return type is the committed `PromotionOutcomeV1` payload: units sold and gross profit. It exposes no coefficient table, no noise factor, no counterfactual set, and no oracle answer, and the contract models contain no field for any of them because the committed schemas contain none. The in-process implementation shipped here returns a caller-supplied fixed value and computes nothing, so it cannot become an accidental source of ground truth.

The narrow return type is necessary but **not sufficient**, and it would be a mistake to read it as the whole defence. The port is callable, the action space has four members, and anything holding a real implementation can call it once per discount and reconstruct the counterfactual profits for the current scenario - precisely the ground truth `docs/market-simulator/README.md` requires stay hidden. No field-level whitelist can prevent that. The actual boundary is a wiring rule: **a real `SimulationPort` must never be reachable from the Promotion Agent.** Replaying all four actions is the Evaluator's job, and the Evaluator is a different component with a different dependency graph. The skeleton can only state the rule and keep the interface honest; the change that supplies a real implementation is the one that has to respect it.

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
