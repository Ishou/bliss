# Survey training_weight export (Spec C) — Design

**Status:** Approved (design), pending spec review

**Context:** Third of the four-part gold-weighting rollout.

- **Spec A** (shipped): identity emits `UserRoleChanged` on `wordsparrow.user.role-changed`.
- **Spec B** (shipped): survey consumes the event, caches maintainer role in
  `maintainer_roles`, and stamps a frozen `training_weight` (NUMERIC, default
  `1.0`, `CHECK > 0`) on rater-proposed items authored by a maintainer created
  on/after the 2026-05-30 cutoff. The weight is **written and frozen** in the
  `survey_items.training_weight` column (migration `V8`), via
  `SurveyItemRepository.updateTrainingWeight`. It is **never read back** today.
- **Spec C** (this spec): `ExportDatasetUseCase` emits the frozen
  `training_weight` as a column in the §8.1 CSV export consumed by
  `bliss-clue-ai`'s training pipeline.
- **Spec D** (future): wire the column into
  `scripts/clue_generation/modal/build_modal_corpus.py` so the training run
  honours the per-row weight.

## Problem

Spec B freezes a per-item `training_weight` in the database, but nothing reads
it. The §8.1 CSV export — the contract `bliss-clue-ai` consumes — has no column
for it, so the gold-weighting signal never reaches the training pipeline. Spec C
closes that gap: read the frozen weight and emit it as a dedicated export
column.

This is deliberately a **narrow read-and-emit change**. No new computation, no
new ports, no schema-event work. The weight already exists in the column; we
surface it on the domain model and write it to the CSV.

## Scope

In scope:

1. Add `trainingWeight` to the `SurveyItem` domain model.
2. Read the column in the Postgres mapper used by the export path.
3. Read the column in the in-memory test repository.
4. Emit a `training_weight` column from `StyleGuideCsvWriter`.
5. Keep `StyleGuideCsvParser` coherent with the new writer column.
6. Update the §8.1 written contract in `docs/clue-style-guide-v2.md`.
7. Update the byte-equal golden fixture.

Out of scope:

- `build_modal_corpus.py` consumption (Spec D).
- Any change to how the weight is computed or frozen (Spec B owns that).
- Anchor-pair routing (anchor items are never exported — see §"Pg mapper").

## The §8.1 contract is written, not just a fixture

Answering the open question from brainstorming: the §8.1 format **is** a written
column spec, at `docs/clue-style-guide-v2.md` §8.1 ("Schéma du dataset final").
It currently documents **8 main columns plus an optional `meta` field** (column
9). It must be amended in the same PR — the byte-equal fixture is not the sole
contract.

Current documented format:

```
mot;definition;pos;categorie;style;force;longueur;source[;meta]
```

Amended format (this spec):

```
mot;definition;pos;categorie;style;force;longueur;source;training_weight[;meta]
```

`training_weight` becomes column 9 (a fixed-value structural column); the
optional free-form `meta` bag moves to column 10. Rationale for placing it
**before** `meta`: all single-valued structural columns stay contiguous, with
the variable key-value blob trailing last — matching §8.1's existing intent for
`meta`. Column order is irrelevant to the downstream consumer: `build_modal_corpus.py`
reads via `csv.DictReader` (column-name keyed), so Spec D is unaffected by
placement.

## Design

### Domain — `SurveyItem`

`survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyItem.kt`

Add one field with a default and an invariant:

```kotlin
val trainingWeight: Double = 1.0,
```

```kotlin
require(trainingWeight > 0) { "training_weight must be positive (was $trainingWeight)" }
```

The default `= 1.0` mirrors the DB column default and means the ~33 existing
construction sites (tests, parser, ingest) compile unchanged — only the export
read-path and the writer need to set/consume it. The `> 0` invariant mirrors the
`CHECK (training_weight > 0)` constraint from migration `V8`, so the domain
model cannot represent a weight the database would reject.

### Persistence — `PgSurveyItemRepository`

`survey/infrastructure/src/main/kotlin/.../persistence/PgSurveyItemRepository.kt`

Only the export read-path mapper changes:

- `toSurveyItem()` (used by `findById`, the export path): the query is
  `SELECT * FROM survey_items WHERE item_id = ?`, so `training_weight` is
  already in the `ResultSet`. Add one line:

  ```kotlin
  trainingWeight = getDouble("training_weight"),
  ```

- `toAnchorItem(prefix)` (used by pair-routing only) is **left unchanged**. Its
  query uses an explicit column projection that does not select
  `training_weight`, and anchor items are never exported. Relying on the domain
  default (`1.0`) is correct here — adding the column to the anchor projection
  would be dead weight.

### Test repository — `InMemorySurveyItemRepository`

`survey/application/src/test/kotlin/.../usecases/InMemoryRepositories.kt`

The in-memory repo already has a `trainingWeights: MutableMap<ItemId, Double>`
populated by `updateTrainingWeight` (asserted by Spec B's B4/B6 tests — keep the
map). `findById` must stitch the stored weight onto the returned item so the
export use case sees it:

```kotlin
override suspend fun findById(id: ItemId): SurveyItem? =
    items[id]?.let { item ->
        trainingWeights[id]?.let { w -> item.copy(trainingWeight = w) } ?: item
    }
```

Items never assigned a weight fall through to the domain default (`1.0`),
matching production where the column defaults to `1.0`.

### Writer — `StyleGuideCsvWriter`

`survey/application/src/main/kotlin/.../csv/StyleGuideCsvWriter.kt`

Header gains the column between `source` and `meta`:

```kotlin
fun header(): String =
    "mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta"
```

`toRow` appends the weight after `source`, before the meta blob:

```kotlin
append(item.source.name.lowercase())
append(';')
append(item.trainingWeight)
append(';')
append(quote(meta.entries.joinToString("|") { "${it.key}:${it.value}" }))
```

`Double` renders via Kotlin's default `toString()` → `1.0`, `3.0`. That is a
stable, deterministic representation; the byte-equal fixture asserts it exactly.

### Export use case — `ExportDatasetUseCase` (no change)

`ExportDatasetUseCase` already calls `items.findById(agg.itemId)` and passes the
full `SurveyItem` to `writer.toRow(item, meta)`. Once `findById` returns an item
carrying `trainingWeight` and the writer emits it, the use case needs **no
change**. This is why the change stays small.

### Parser — `StyleGuideCsvParser`

`survey/application/src/main/kotlin/.../csv/StyleGuideCsvParser.kt`

The parser feeds `IngestBatchUseCase`, which ingests **candidate batches from
`bliss-clue-ai`** — an *input* contract that does not carry `training_weight`
(the survey system assigns it; `bliss-clue-ai` does not). So the writer (export)
and parser (ingest) are genuinely different contracts, which is exactly why the
existing round-trip test asserts only the 8 shared structural columns.

However, the test suite couples them: `StyleGuideCsvRoundTripTest` and
`IngestBatchUseCaseTest` both build rows via `writer.toRow(...)`. With the writer
emitting `training_weight` at index 8, the parser's current `cells[8]`
(previously a loose `sourceBatch` fallback) would silently capture the weight
string. To keep the parser coherent:

- Parse `training_weight` from index 8 when present, defaulting to `1.0` when
  absent (8-column input) or unparseable:

  ```kotlin
  trainingWeight = cells.getOrNull(8)?.toDoubleOrNull() ?: 1.0,
  ```

- Drop the `cells[8] → sourceBatch` fallback; `sourceBatch` stays `"unknown"`
  (the ingest pipeline stamps the real batch downstream — `IngestBatchUseCase`
  overwrites it, as asserted by its "stamped with sourceBatch and tier" test).

The default-to-`1.0` (rather than fail-fast) is deliberate: this is a file
ingest of upstream candidate batches that legitimately omit the column, not an
environment-config boundary. Tolerating absence keeps old 8-column batches
ingestable.

## Testing (TDD)

All new behaviour is exercised before implementation.

1. **Writer header** — update `StyleGuideCsvRoundTripTest.header is fixed schema`
   to assert the new 10-column header string.

2. **Writer emits weight** — new test: an item with `trainingWeight = 3.0`
   produces a row whose 9th column is `3.0`; an item with the default produces
   `1.0`.

3. **Round-trip** — extend `parse of write equals original on structural fields`
   to also assert `parsed.trainingWeight == original.trainingWeight`, and add
   `trainingWeight` to the `arbItem()` generator (e.g.
   `Arb.of(1.0, 2.0, 3.0, 5.0)`).

4. **Export use case** — new/extended test on `ExportDatasetUseCase`: an item
   stamped (via the in-memory repo's `updateTrainingWeight`) with `3.0` exports a
   row carrying `3.0` in the `training_weight` column; an unstamped item exports
   `1.0`.

5. **Domain invariant** — new test: `SurveyItem(..., trainingWeight = 0.0)`
   throws; `trainingWeight = -1.0` throws.

6. **Byte-equal fixture** — update
   `survey/worker/src/test/resources/byteequal/expected.csv` to the 10-column
   header and add `training_weight` values to each row. The existing two fixture
   rows (`PAIN`, `POULE`) are `gold` source seeded pre-cutoff, so their weight is
   the default `1.0`. The `ExportByteEqualTest` then asserts byte-identity against
   the new fixture.

## Files touched

| File | Change |
|---|---|
| `survey/domain/.../model/SurveyItem.kt` | add `trainingWeight` field + invariant |
| `survey/infrastructure/.../persistence/PgSurveyItemRepository.kt` | `toSurveyItem()` reads `training_weight` |
| `survey/application/.../csv/StyleGuideCsvWriter.kt` | header + row column |
| `survey/application/.../csv/StyleGuideCsvParser.kt` | parse `training_weight`, drop sourceBatch hack |
| `survey/application/.../usecases/InMemoryRepositories.kt` (test) | `findById` stitches weight |
| `survey/application/.../csv/StyleGuideCsvRoundTripTest.kt` (test) | header + round-trip assertions |
| `survey/application/.../usecases/ExportDatasetUseCaseTest.kt` (test) | weight-emission assertions |
| `survey/worker/src/test/resources/byteequal/expected.csv` (test) | 10-column fixture |
| `docs/clue-style-guide-v2.md` | §8.1 column spec + examples amended |

`ExportDatasetUseCase.kt` is intentionally **not** in the list.

## Constraints / ADRs

- **ADR-0056** (survey bounded context) — owns the §8.1 export contract.
- **ADR-0001 §4** — one workstream, 400-line soft cap. This change is small;
  the §8.1 doc edit and fixture are the bulk. Should fit comfortably.
- **ADR-0001 §1** — no cross-context imports; nothing here crosses contexts.
- Hexagonal layering: domain field is framework-free; mapper change is
  infrastructure; writer/parser are application. No layer violations.

## Risks

- **Byte-equal fragility:** `Double.toString()` formatting must match the
  fixture exactly. Mitigated by asserting it directly in the writer test (item 2)
  before the fixture test runs.
- **Parser tolerance vs. correctness:** defaulting a malformed weight to `1.0`
  could mask a genuinely corrupt export. Accepted because the parser's job is
  ingesting upstream candidate batches (no weight column), not validating the
  survey's own exports; the byte-equal test guards the export side.
