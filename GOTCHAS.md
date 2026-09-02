# Gotchas

One line per finding, written when it was hit rather than reconstructed later. Rule 13 of
`AGENTS.md`: by the final day nobody remembers these, and the retrospective questionnaire asks
for them.

## xmemory

- Quota is metered in **tokens**, not requests - so what matters is whether an operation goes
  through the model, not how many calls you make (`402 QUOTA_EXCEEDED`, `daily`/`monthly`).
- `structured_mutations` on `/write` bypasses extraction entirely: no model, deterministic,
  repeatable. This is the write path for `PromotionCase` and `Lesson`.
- A natural-language `/read` is slow **and** unreliable for fetching known records: three queries
  of the form "return cases for SKU X" answered "no matching case" while the data was demonstrably
  present. Use it for generalising questions, not for retrieval by key.
- `/read` with `mode: raw-tables` or `xresponse` does return the stored rows, but still costs a
  model call.
- Reads are cached: the same query costs 20+ seconds cold and about a second warm. Do not benchmark
  a read twice and believe the second number.
- Server-side validation is strict and atomic - an unknown field or a duplicated `create` for one
  primary key rejects the whole request and writes nothing.
- Two `create` mutations for the same primary key in one request are refused, so deduplicate before
  sending.
- The committed `docs/xmemory/schema.xmd.yaml` is accepted as-is by instance creation. Field names
  matter: `SKU.cost`, not `unit_cost`.

### Measured on 2026-09-02, one instance, five PromotionCase and four SKU

| operation | wall clock | through the model |
|---|---|---|
| create instance from the committed XMD | 1.4 s | no |
| structured write, 9 mutations | 5.0 s | no |
| read, `raw-tables`, all cases | 8.7 s | yes |
| read, `xresponse`, all SKU | 8.4 s | yes |
| read, natural language, cold | 20-26 s | yes |
| read, natural language, repeated | 1.2 s | cached |
| read with `scope` (rejected on format) | 0.7 s | no |

What this means for the plan: training 200-300 scenarios is affordable if cases and lessons are
written with `structured_mutations`. The expensive half is the lesson read before each decision -
at 20-26 seconds and a model call each, doing that per scenario is both the wall-clock and the
quota problem. A keyed read answers in well under a second without the model, so the agent's
retrieval path has to be keyed rather than conversational. The exact `scope` request shape was not
settled here - the API wants a different key form than the one documented inline - and belongs to
the change that implements the client.
