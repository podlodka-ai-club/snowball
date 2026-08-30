# AGENTS.md

Instructions for coding agents working in this repository, and for the people running them.

The team is three people with almost no overlapping calendar time, and most of the code is written by agents. These rules exist so that work can be split without talking it through first.

Style, versions, and formatting are **not** described here. They are machine-checked by `.editorconfig`, Spotless/ktlint, and `gradle/libs.versions.toml`. Restating them in prose would create a second source of truth that drifts. This file covers only what no tool can check.

## Before writing code

- **Contracts come before code.** The boundary of a component - what goes in, what comes out, what it explicitly does not do - is written down and agreed as text before it is implemented.
- **Go through OpenSpec.** `openspec/` is the implementation contract. A change gets its `proposal.md`, `specs/`, `design.md`, and `tasks.md` before it gets code, following `openspec/config.yaml`. Commit the change on its own so the plan can be read separately from the code.
- **Claim the task publicly.** Say in the chat or in an issue what you are taking before you start. Three agents duplicate work far more eagerly than three people - each will cheerfully build the whole thing.

## Contracts you must not change quietly

- The versioned JSON Schemas under `docs/` are external contracts. Do not modify one while implementing a component. If a contract has to change, that is its own OpenSpec change with its own discussion.
- Anything touching the memory schema goes through a written plan first. The schema is the most expensive artifact to roll back; a migration mid-sprint costs a day.
- Do not modify another component's OpenSpec change directory.

## Branches and review

- One task, one branch, one PR. A branch should not outlive a day - over a two-week sprint a long branch will not survive a single contract change.
- **A human owns the diff, not the agent.** Everything merged into `main` has been read in full by its author. If you do not understand a hunk, you do not merge it.
- Agent instructions live in the repository - this file, skills, prompts - as files, not in someone's private chat history. Otherwise three people run three different agents and behavior stops being reproducible.

## Tests and evals are different artifacts

- Unit tests check that the code does what it says. They must be green at all times and they block merge.
- Evals measure whether the agent gets better as it accumulates experience. They do not block merge and they have no passing threshold - they produce numbers.
- **Never edit a test or an eval to make it go green.** Fix the cause, or change the criterion deliberately and write down that you changed it. Evals are the only evidence of the before/after delta; weakening them destroys the thing being demonstrated.
- Do not add a threshold for evals in CI. A threshold is something a team eventually tunes, which is exactly the failure above.

## Experiments must be repeatable

- A run that cannot be repeated is not a result. Pin the model version, the seed, and the data slice, and store the run artifacts alongside the code.
- The simulator is deterministic: its random generator takes an explicit seed, the seed is supplied from outside and recorded in the run output. Without that no simulator test is stable and no eval is comparable to the previous one.
- **The simulator's hidden structure must not reach the agent** - not through the simulator API, not through logs the agent reads, not through memory. This is a technical requirement, not a preference: the agent is supposed to learn the ground truth, not read it.
- Training data and measurement data do not overlap, and the split is by time, not at random.

## Order of work

Build the thin end-to-end path first - task, result, evaluation, lesson, changed behavior - and only then deepen any single part. An unfinished plan is survivable; a missing demo is not.

## Hygiene

- **No keys or tokens in the repository.** `.env` stays local, only `.env.example` is committed.
- The model and memory quotas are shared. Announce bulk runs in the chat before starting them - one overnight run can exhaust the quota for everyone.
- Write down every gotcha at the moment you hit it, one line per finding. Reconstructing them from memory on the last day is the worst possible time.

## Build commands

```bash
./gradlew spotlessApply        # fix formatting locally, before committing
./gradlew spotlessCheck build  # what CI runs
```
