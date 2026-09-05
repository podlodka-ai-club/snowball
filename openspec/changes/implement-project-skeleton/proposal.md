## Why

The repository holds architecture, versioned JSON contracts, and five component changes, but no build and no code. Two of those changes - `implement-scenario-generator` and `implement-market-simulator` - are independent of each other, yet both start by asking for "typed models mapped to the committed contract". Executed in parallel they would produce two incompatible DTO families for one and the same schema, and the divergence would only surface at the first integration. Producing the shared foundation once, before component work starts, removes that conflict instead of merging it later.

## What Changes

- Add a single-module Gradle build with the Kotlin JVM plugin, a `jvmToolchain(21)` toolchain, a version catalog, and a committed Gradle wrapper.
- Add Spotless/ktlint formatting, `.editorconfig`, and `gradle.properties` so that every later change is checked against the same style.
- Add hand-written Kotlin models for the three committed v1 contracts: `PromotionScenarioEvent`, `PromotionDecisionEvent`, `PromotionOutcomeEvent`.
- Add round-trip tests binding each model to its committed `*.example.json`.
- Add the transport-neutral internal ports `SimulationPort`, `OutcomeSink`, and `ScenarioPublisher` with trivial in-process implementations usable as test doubles.
- Add one CI workflow running `./gradlew spotlessCheck build` on push and pull request.
- Add `.gitignore` and `.env.example` with an empty `XMEM_API_KEY`, named as `docs/promotion-agent/README.md` names it.

Non-goals, stated explicitly because this change touches files every component will later own:

- No business logic: no simulator formula or coefficients, no oracle selection, no regret, no Lesson derivation, no prompt or model invocation.
- No messaging infrastructure. Whether the MVP needs Kafka at all is an open team question (see the discussion on PR #1); this change keeps the ports transport-neutral so either answer stays cheap, and decides nothing.
- No Spring Boot, no database, no HTTP layer, no xmemory client.
- No code generation from JSON Schema, and no schema validation on the runtime path; whether a component validates an incoming document at its own boundary is that component's decision. The committed schemas are compiled and enforced in the *tests*, which is what keeps the hand-written models honest.
- No change to any committed schema or to anything under `docs/`, `assets/`, `todo/`, or another change directory. The single exception is `openspec/README.md`, which gains this change in its implementation order.

## Capabilities

- `project-skeleton`: a buildable, formatted, CI-verified module that exposes the committed v1 contracts as typed Kotlin models behind neutral internal ports.

## Impact

There is no upstream component; the upstream is the committed contract material under `docs/`. Downstream is every one of the five component changes: they inherit the build, the style configuration, the contract models, and the port interfaces instead of each defining their own. The change adds no runtime behavior, so nothing downstream is constrained beyond the shape of the contracts that are already committed.
