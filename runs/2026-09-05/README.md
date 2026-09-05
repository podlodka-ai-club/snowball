# Schema evolution, 2026-09-05

The memory changed its own structure, and the change was driven by its own evidence.

## What happened, in order

1. A third instance, `snowball-evolving`, created on the committed schema. Seeded from the fixture
   with the 250 training cases - each carrying the discount the real training run actually chose,
   read from `../2026-09-04/training-250.log`, not the oracle's answer - plus 190 lessons and 1500
   evidence links. Writing into an empty instance took under two minutes; the same seed into the
   trained instance had taken ninety, because every batch collided with existing rows.
2. The memory's own lessons were checked for internal disagreement: the share of a bucket's cases
   whose best action matches the bucket's recommendation. Six buckets disagreed with themselves
   and separated cleanly along one observable feature - stock relative to baseline demand:

   | bucket | agreement before | after |
   |---|---|---|
   | chips (product and category) | 0.41 | 0.97 |
   | soft drinks (product and category) | 0.53 | 0.76 |
   | ice cream (product and category) | 0.66 | 0.74 |

   The feature was chosen by measurement against three others; it is computed from two numbers
   the agent sees in every scenario and nothing read from the simulator.
3. A schema migration adding `Lesson.stock_cover` was dry-run (`add_field: 1`) and applied:
   schema version 0 -> 1, migration `63bd84d1`. Existing lessons survived with the new column
   empty.
4. Twelve child lessons were written under the new dimension, with 272 evidence links back to the
   cases each aggregates. Every product split the same way: tight cover recommends 10%, ample
   cover recommends 20%. The parents stay as the fallback level.

## What it demonstrates, and what it does not

The engine's schema-evolution protocol works end to end: dry-run, additive versus non-additive
detection (a broken block was refused with the field named and a plan demanded), versioned apply,
migration history. The memory used it to acquire a condition its designers had not given it.

It does not improve the benchmark. Split buckets are the general levels, which the read cascade
reaches in six held-out scenarios out of fifty; offline this policy scores 4.15 against 4.15.
The value is the mechanism and the confidence gain inside the split buckets, not the regret line.

## Gotchas found on the way

- `instance_schema` is `{"yml": {"value": "<yaml text>"}}`. Thirty-two guessed variant names were
  rejected before this was read out of the official CLI's source (`xmemcli` on PyPI).
- `migrations/dry_run` and `PUT /schema` take the same body; `GET /migrations` lists history.
- A YAML description containing a colon must be quoted or folded, or the whole schema is refused
  as "not valid YAML" with no line number.
