## 1. Build foundation

- [x] 1.1 Add the Gradle wrapper pinned to 9.7.1 and commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/`.
- [x] 1.2 Add `settings.gradle.kts`, a single-module `build.gradle.kts` with the Kotlin JVM plugin and `jvmToolchain(21)`, and declare every dependency and plugin version in `gradle/libs.versions.toml`.
- [x] 1.3 Add `gradle.properties` with the official Kotlin code style, build cache, and parallel execution enabled.
- [x] 1.4 Add `.editorconfig` covering encoding, line endings, final newline, trailing whitespace, indentation, and the ktlint code style and line length for Kotlin files.
- [x] 1.5 Apply Spotless with ktlint over Kotlin sources and Gradle Kotlin scripts, and confirm `./gradlew spotlessCheck` passes on the committed tree.

## 2. Contract models

- [x] 2.1 Add the scenario payload and `PromotionScenarioEvent` model mapped field for field to `docs/scenario-generator/promotion-scenario-v1.schema.json`, with required fields non-nullable, optional fields nullable, and closed enumerations as Kotlin enums carrying their wire values.
- [x] 2.2 Add `PromotionDecisionEvent` and the discount enumeration mapped to `docs/promotion-agent/promotion-decision-v1.schema.json`, reusing the scenario payload type rather than redeclaring it.
- [x] 2.3 Add `PromotionOutcomeEvent` and the outcome payload mapped to `docs/market-simulator/promotion-outcome-v1.schema.json`, reusing the scenario and decision payload types.
- [x] 2.4 Add a single shared JSON mapper configuration for the contract package, with no behavior beyond what the schemas require.

## 3. Contract tests

- [x] 3.1 Add a round-trip test for `promotion-scenario-v1.example.json`: parse into the model, serialize back, and compare as JSON trees for equality.
- [x] 3.2 Add the same round-trip test for `promotion-decision-v1.example.json`.
- [x] 3.3 Add the same round-trip test for `promotion-outcome-v1.example.json`.
- [x] 3.4 Add negative tests proving that a missing required field, an unknown property, and an out-of-enumeration value are all rejected at parse time.
- [x] 3.5 Add a test proving the constant envelope fields round-trip unchanged and that a conflicting constant is rejected.
- [x] 3.6 Add negative tests for the constraints Jackson's defaults would otherwise accept: a wrong JSON type, an explicit null in an optional field, an empty `minLength: 1` string, and a number outside its schema bounds.
- [x] 3.7 Probe the mapper with mutated examples rather than trusting its configuration, and add tests for every gap found: a number or boolean in a string field, an enum given as an ordinal, a temporal value given as a number or array, an explicit null in a required numeric field, a non-UTC offset surviving the round trip, and a number too precise for a 64-bit float.

- [x] 3.8 Compile the committed schemas into real validators in the tests, validate every example and every serialized model against them, and assert schema and model reject the same document in every negative case. Verify by loosening a committed enum and confirming the suite goes red.
- [x] 3.9 Add construction-side tests: a date year beyond four digits, an offset carrying seconds, the upstream-copying constructors, and the scale-sensitivity of `BigDecimal` equality.

## 4. Internal ports

- [x] 4.1 Add `SimulationPort`, `OutcomeSink`, and `ScenarioPublisher` as plain Kotlin interfaces over the contract models, with no transport, framework, or configuration types in their signatures. `SimulationPort` takes the scenario identity alongside the payload, as the documented simulator engine does.
- [x] 4.2 Add trivial in-process implementations - a fixed-answer simulation and recording sink/publisher - suitable as test doubles and containing no domain arithmetic.
- [x] 4.3 Add tests showing each port can be exercised in-process and that `SimulationPort` returns only the committed outcome payload, exposing no coefficients, noise, counterfactuals, or oracle result.

## 5. Repository hygiene and acceptance

- [x] 5.1 Add `.gitignore` for Gradle, JVM, and IDE artifacts, and `.env.example` with an empty `XMEM_API_KEY` and no other secret-bearing key.
- [x] 5.2 Add a CI workflow on push and pull request that checks out the repository, provisions Temurin JDK 21, sets up Gradle, and runs `./gradlew spotlessCheck build`.
- [x] 5.3 Acceptance: from a clean clone on JDK 21, `./gradlew spotlessCheck build` succeeds, all three round-trip tests pass, and the diff contains no secret value and no modification to any committed schema or to `docs/`, `assets/`, `todo/`, or another change directory.
