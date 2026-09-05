# Gotchas

One line per finding, written when it was hit rather than reconstructed later. Rule 13 of
`AGENTS.md`: by the final day nobody remembers these, and the retrospective questionnaire asks
for them.

## Decision model

- An empty answer usually means the token budget ran out inside the model's reasoning block, not a
  refusal: `finish_reason=length` with no content. Measured on the real agent prompt, 400 tokens
  yields nothing and 1200 is enough. Treating truncation as a failed decision would fall back to 0%
  and quietly turn the clean-memory arm into a fixed policy, inflating the delta being measured.
- Without lessons the model thinks noticeably longer - about 9 seconds against 2 with them - so a
  cold benchmark arm is slower than a trained one, not faster.
- `reasoning_effort` passed through `chat_template_kwargs` made no measurable difference on this
  server: 266 tokens against 269, same answer. Do not rely on it to control depth.
- The model follows a lesson literally: given a lesson recommending 0, 20 or 30 percent it returned
  exactly that, and chose differently with no memory at all. Good for demonstrating that memory
  drives behaviour - and a reminder that the lessons had better be right.

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
- A scoped read nests the key twice: `{"type": "Lesson", "key": {"key": {"lesson_key": "..."}}}`. Passing
  `{"key": {"lesson_key": "..."}}` is rejected as an extra input, which is why the first probe read
  nothing back.
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

## Writing and reading relations (measured 2026-09-03)

- In a `relation_mutation`, `object_name` on an endpoint is the participant **role** from the
  schema (`lesson`, `case`), not the object type. Passing the type parses fine and is then rejected
  with "missing participant role(s)", which reads like a key problem and is not one.
- An endpoint key is flat - `"key": {"lesson_key": "..."}` - while a read `scope` key is nested
  twice. The two shapes are not interchangeable.
- `create` on an existing object answers HTTP 400 `VALIDATION_ERROR` "already exists", and a read of
  an absent key answers 400 "No 'x' object matches the provided primary key". Both are ordinary
  outcomes on a resumable run, so the client treats those two message texts as results rather than
  failures, and falls back to `update`.
- **`xresponse` returns what the query text asks for, not what the scope contains.** The same scoped
  read returned one field for "Return the scoped records." and every field, plus the identifier, for
  "Return every stored field of the scoped Lesson record." A vague query silently yields a partial
  record, which then looks like missing data. `raw-tables` (note the hyphen; `raw_tables` is
  rejected) returns columns and rows verbatim and does not depend on the wording.
- **A `scope` narrows a read; it never widens one.** `all_relations` exposes the relations *among
  the objects the scope lists* - it does not pull in their neighbours. Scoping a read to a Lesson
  therefore hides the very cases linked to it, and the answer is an empty relation list rather than
  an error. Traversals run **unscoped**: an unscoped `raw-tables` read asking for "every
  PromotionCase linked to the Lesson whose lesson_key is X through lesson_evidence" returns exactly
  those rows. Cost: about 20 seconds for a key the service has not answered before, ~1 second when
  it repeats.
- `write` answers with a `changes` report of what it actually created, updated or deleted, grouped
  by operation. Reading it is the way to confirm a write; the REST envelope's `items` is not.

The read and write formats above are documented in the `tools` array of `GET /instances/<id>`,
which is worth reading in full before guessing at a shape - most of a day was spent inferring
things that were written down there.

## The inference server occasionally answers with invalid JSON (measured 2026-09-04)

Roughly once in a hundred completions the server returns HTTP 200 with a body that is not valid
JSON - observed twice: a body cut off inside a string, and an unescaped CR (code 13) inside one.
The model adapter must treat an unreadable body like any other failed answer. It did not, and one
such response ended a 250-scenario training run at scenario 50. Retrying does not always help: the
CR case reproduced on both attempts and the scenario fell back.

## A written record is not immediately readable by key (measured 2026-09-04)

Writing a few hundred Lesson records and then linking to them failed with "Participant 'lesson'
(Lesson) was not found" - while a `create` for that same key was refused as a CONFLICT, saying a
record with that `lesson_key` already exists. Both are true at once: the write is durable and the
uniqueness check sees it, but the primary-key resolution used by scoped reads and by relation
endpoints lags behind. The same key answered "No 'lesson' object matches the provided primary key"
for minutes and then resolved normally.

Consequences for anything that writes in bulk. A relation must not be created in the same breath as
the object it points at, unless both are in one batch - the batch itself is applied in dependency
order and does work. Reading a record back to confirm a write is unreliable by design here. And a
run that seeds objects, then links them, needs either a delay or a retry that treats "participant
not found" as transient rather than as a failure.

## A duplicate is reported three different ways (measured 2026-09-04)

Writing a record that already exists answers, depending on the path taken:

- HTTP 400 `VALIDATION_ERROR` - "A 'Lesson' with this primary key already exists."
- HTTP 409 `CONFLICT` - "Cannot save this write: an existing Lesson record already has the same
  primary key."
- HTTP 409 `CONFLICT` naming the value - "... already has lesson_key: sku:MEAT1|store:any|..."

A client that recognises one wording passes its tests and then aborts a bulk write partway through
on the second. Match on the condition rather than on a sentence, and treat the code as part of the
signal - which is why the client now carries the error code alongside the message.
