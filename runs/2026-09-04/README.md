# Training run and benchmark, 2026-09-04

## What was run

| | training | benchmark |
|---|---|---|
| split | 250 scenarios up to 2011-10-21 | 50 scenarios after it |
| learning | on | off, in both arms |
| memory | `snowball-trained` | `snowball-clean` and `snowball-trained` |
| model | `muse-glimmer-30b-q3`, `temperature=0` | same |
| simulator | `v1`, seeded per scenario id | same |
| fixture | `src/test/resources/fixtures/baseline.csv` | same |

Reproduce with (the instance ids belong to this account; the key comes from the umbrella `.env`):

    ./gradlew classes
    java -cp <classpath> club.podlodka.snowball.adapter.cli.RunExperiment \
      --split=training --instance=$XMEM_INSTANCE_ID_TRAINED
    java -cp <classpath> club.podlodka.snowball.adapter.cli.RunExperiment \
      --split=benchmark --no-learning --instance=$XMEM_INSTANCE_ID_CLEAN

## Benchmark result

Both arms decided the same 50 held-out scenarios, neither of them learning from what it saw.

| | clean memory | trained memory |
|---|---|---|
| optimal decisions | 44.0% | 76.0% |
| mean regret | 0.8808 | 0.2434 |
| total regret | 44.04 | 12.17 |
| fallbacks | 0 | 0 |

Paired over the same scenarios: trained is better on 22, worse on 5, equal on 23 - of those, 17
are cases where both arms found the optimal action. An exact binomial test over the 27 disagreeing
pairs gives p = 1.5e-03.

Two caveats, both of which understate the trained arm rather than flatter it. Its last three
scenarios ran with no memory at all, because the shared xmemory quota ran out mid-run (HTTP 402).
And memory answered for 44 of the 50 scenarios: the training split does not cover every lesson key
the benchmark asks about.

## Looser keys: the cascade

A second measurement, same 50 scenarios, same trained memory, after the looser lesson buckets were
written into it. The agent falls back to a more general bucket when the exact one has no answer.

| | clean | strict key | cascade |
|---|---|---|---|
| optimal decisions | 44% | 76% | 80% |
| total regret | 44.04 | 12.17 | 7.18 |
| memory answered | - | 44/50 | 50/50 |
| fallbacks | 0 | 0 | 0 |

Against clean memory the cascade removes 84% of the lost profit. The offline estimate in
`AnalyzeLessonKeys` predicted 7.16 before the run; the run produced 7.18.

What this does **not** establish: that the cascade beats the strict key by a statistically
significant margin. The two disagree on six scenarios out of fifty - cascade better on four, worse
on two, p = 0.69. The gain in money is real but concentrated in a few expensive scenarios rather
than spread across the set. Only the difference against clean memory is significant.

The buckets were seeded by recomputing them offline rather than by retraining: the simulator is
deterministic, so every training case reproduces exactly without asking the model.
`seed-lesson-buckets.log` is the first attempt, which died partway through the evidence links -
see the read-after-write entry in `GOTCHAS.md`. `seed-evidence-links.log` is the run that finished
them: 1500 links in 15 minutes, waiting 157 times for a written record to become addressable.

The trained memory now holds all 190 buckets across three levels of generalisation, with every
case linked to every bucket it supports - so a later training run aggregates each lesson from the
whole history rather than from whatever single case it just saw.

## Training run

250 scenarios in 68 minutes, 16.5s each, 2 fallbacks. Mean regret 0.2195, 73.6% optimal.

Optimal rate per fifty, in order: 90%, 52%, 74%, 78%, 76%. The first fifty are not comparable -
an earlier run had already recorded lessons for them before it died. The learning signal is the
52% -> 74% -> 78% climb across scenarios the agent met for the first time, and the plateau after
it.

## What the runs found that the tests did not

- The model server returns HTTP 200 with invalid JSON roughly once in a hundred completions. The
  response parse was not guarded, so one such answer ended the first training run at scenario 50
  of 250. Retrying does not always help - one case reproduced on both attempts.
- Nothing checked that the configured memory instance exists. A mistyped instance id answers 404
  on every call, and the agent's own resilience then hides it: it logs "memory unavailable" per
  scenario and decides without lessons. A benchmark arm would have reported fifty ordinary-looking
  measurements of a memory that was never there.
