# Survey training_weight Export (Spec C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit the frozen per-item `training_weight` as a dedicated column in the §8.1 CSV export consumed by `bliss-clue-ai`.

**Architecture:** Surface the existing `survey_items.training_weight` column (written/frozen by Spec B) on the `SurveyItem` domain model, read it in the export-path Postgres mapper, and emit it from `StyleGuideCsvWriter` between `source` and `meta`. `ExportDatasetUseCase` is unchanged — it already passes the full item to the writer. The §8.1 written contract and the byte-equal golden fixture are updated in lockstep.

**Tech Stack:** Kotlin 2.3.21, Ktor, Postgres (CNPG + Flyway V8 already shipped), JUnit 5, AssertK, Kotest property tests, Testcontainers.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyItem.kt` | Domain model | Add `trainingWeight: Double = 1.0` + `> 0` invariant |
| `survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriter.kt` | Export serialization | Header + row gain `training_weight` column |
| `survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvParser.kt` | Ingest deserialization | Parse `training_weight` (default `1.0`), drop sourceBatch hack |
| `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgSurveyItemRepository.kt` | Persistence adapter | `toSurveyItem()` reads `training_weight` |
| `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemoryRepositories.kt` | Test double | `findById` stitches stored weight |
| `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt` | Writer/parser tests | New header + round-trip weight assertion |
| `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/ExportDatasetUseCaseTest.kt` | Export tests | New header assertions + stamped-weight test |
| `survey/worker/src/test/resources/byteequal/seed.sql` | Byte-equal seed | Stamp one row with weight `3.0` |
| `survey/worker/src/test/resources/byteequal/expected.csv` | Golden fixture | 10-column header + weight values |
| `docs/clue-style-guide-v2.md` | §8.1 written contract | Add `training_weight` column spec + examples |

Branch already in use: `docs/survey-training-weight-export-spec` carries the spec. Implementation lands on a fresh `feat/survey-training-weight-export` branch off `main` (the spec PR merges independently).

---

### Task 1: Domain field + invariant on `SurveyItem`

**Files:**
- Modify: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyItem.kt`
- Test: `survey/domain/src/test/kotlin/com/bliss/survey/domain/model/SurveyItemTest.kt`

- [ ] **Step 1: Write the failing test**

Append these two tests to the existing `SurveyItemTest` class (create the file with the class skeleton below if it does not exist):

```kotlin
package com.bliss.survey.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class SurveyItemTrainingWeightTest {
    private fun item(weight: Double) =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "PAIN",
            definition = "Aliment de boulangerie",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ALIMENTS,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 1,
            longueur = 4,
            source = Source.GOLD,
            sourceBatch = "gold_v1",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.parse("2026-05-25T12:00:00Z"),
            trainingWeight = weight,
        )

    @Test
    fun `defaults to 1_0 when omitted`() {
        val withoutWeight =
            SurveyItem(
                id = ItemId(UUID.randomUUID()),
                mot = "PAIN",
                definition = "Aliment de boulangerie",
                pos = Pos.NOM_COMMUN,
                categorie = Categorie.ALIMENTS,
                style = Style.DEFINITION_DIRECTE,
                forceClaimed = 1,
                longueur = 4,
                source = Source.GOLD,
                sourceBatch = "gold_v1",
                tier = Tier.MID,
                isCalibration = false,
                expected = null,
                retiredAt = null,
                createdAt = Instant.parse("2026-05-25T12:00:00Z"),
            )
        assertThat(withoutWeight.trainingWeight).isEqualTo(1.0)
    }

    @Test
    fun `rejects zero weight`() {
        assertThrows<IllegalArgumentException> { item(0.0) }
    }

    @Test
    fun `rejects negative weight`() {
        assertThrows<IllegalArgumentException> { item(-1.0) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :survey:domain:test --tests "com.bliss.survey.domain.model.SurveyItemTrainingWeightTest"`
Expected: COMPILE FAILURE — `SurveyItem` has no `trainingWeight` parameter.

- [ ] **Step 3: Add the field and invariant**

In `SurveyItem.kt`, add the field as the last constructor parameter (after `createdAt`):

```kotlin
    val createdAt: Instant,
    val trainingWeight: Double = 1.0,
) {
    init {
        require(mot.isNotBlank()) { "mot must not be blank" }
        require(definition.isNotBlank()) { "definition must not be blank" }
        require(forceClaimed in 1..5) { "force_claimed must be in 1..5 (was $forceClaimed)" }
        require(longueur > 0) { "longueur must be positive (was $longueur)" }
        require(sourceBatch.isNotBlank()) { "source_batch must not be blank" }
        require(trainingWeight > 0) { "training_weight must be positive (was $trainingWeight)" }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :survey:domain:test --tests "com.bliss.survey.domain.model.SurveyItemTrainingWeightTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyItem.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/model/SurveyItemTest.kt
git commit -s -m "feat(survey-domain): add training_weight field to SurveyItem"
```

(If you created a new file named `SurveyItemTrainingWeightTest.kt`, stage that path instead.)

---

### Task 2: In-memory repository stitches the stored weight

**Files:**
- Modify: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemoryRepositories.kt:34` (the `findById` override)
- Test: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemorySurveyItemRepositoryTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemorySurveyItemRepositoryTest.kt`:

```kotlin
package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemorySurveyItemRepositoryTest {
    private fun item() =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "PAIN",
            definition = "Aliment de boulangerie",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ALIMENTS,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 1,
            longueur = 4,
            source = Source.GOLD,
            sourceBatch = "gold_v1",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.parse("2026-05-25T12:00:00Z"),
        )

    @Test
    fun `findById returns stamped weight`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val it = item()
            repo.insert(it)
            repo.updateTrainingWeight(it.id, 3.0)
            assertThat(repo.findById(it.id)?.trainingWeight).isEqualTo(3.0)
        }

    @Test
    fun `findById returns default weight when unstamped`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val it = item()
            repo.insert(it)
            assertThat(repo.findById(it.id)?.trainingWeight).isEqualTo(1.0)
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.InMemorySurveyItemRepositoryTest"`
Expected: FAIL on `findById returns stamped weight` — `findById` ignores `trainingWeights`, returns `1.0`.

- [ ] **Step 3: Update `findById` to stitch the weight**

In `InMemoryRepositories.kt`, replace the `findById` override (line 34):

```kotlin
    override suspend fun findById(id: ItemId): SurveyItem? =
        items[id]?.let { item ->
            trainingWeights[id]?.let { w -> item.copy(trainingWeight = w) } ?: item
        }
```

Leave the `trainingWeights` map and `updateTrainingWeight` (lines 148–155) exactly as they are — Spec B's B4/B6 tests assert against the map directly.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.InMemorySurveyItemRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemoryRepositories.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemorySurveyItemRepositoryTest.kt
git commit -s -m "test(survey-application): in-memory findById stitches training_weight"
```

---

### Task 3: Writer emits the `training_weight` column

**Files:**
- Modify: `survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriter.kt`
- Test: `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriterTest.kt` (create)
- Modify (fix broken assertions): `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt:84`
- Modify (fix broken assertions): `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/ExportDatasetUseCaseTest.kt:84,142`

- [ ] **Step 1: Write the failing test**

Create `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriterTest.kt`:

```kotlin
package com.bliss.survey.application.csv

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StyleGuideCsvWriterTest {
    private val writer = StyleGuideCsvWriter()

    private fun item(weight: Double) =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "PAIN",
            definition = "Aliment de boulangerie",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ALIMENTS,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 1,
            longueur = 4,
            source = Source.GOLD,
            sourceBatch = "gold_v1",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.parse("2026-05-25T12:00:00Z"),
            trainingWeight = weight,
        )

    @Test
    fun `header includes training_weight before meta`() {
        assertThat(writer.header())
            .isEqualTo("mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta")
    }

    @Test
    fun `row emits weight between source and meta`() {
        val row = writer.toRow(item(3.0), meta = emptyMap())
        assertThat(row).isEqualTo("PAIN;Aliment de boulangerie;nom_commun;aliments;définition_directe;1;4;gold;3.0;")
    }

    @Test
    fun `row emits default weight as 1_0`() {
        val row = writer.toRow(item(1.0), meta = emptyMap())
        assertThat(row).isEqualTo("PAIN;Aliment de boulangerie;nom_commun;aliments;définition_directe;1;4;gold;1.0;")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.csv.StyleGuideCsvWriterTest"`
Expected: FAIL — header lacks `training_weight`; rows lack the weight column.

- [ ] **Step 3: Update the writer**

In `StyleGuideCsvWriter.kt`, change `header()`:

```kotlin
    fun header(): String =
        "mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta"
```

In `toRow`, insert the weight append between the `source` append and the `meta` append:

```kotlin
            append(item.source.name.lowercase())
            append(';')
            append(item.trainingWeight)
            append(';')
            append(quote(meta.entries.joinToString("|") { "${it.key}:${it.value}" }))
```

- [ ] **Step 4: Fix the two now-broken existing tests**

In `StyleGuideCsvRoundTripTest.kt`, the `header is fixed schema` test (line 84) asserts the old header. Update it:

```kotlin
    @Test
    fun `header is fixed schema`() {
        assertThat(writer.header())
            .isEqualTo("mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta")
    }
```

In `ExportDatasetUseCaseTest.kt`, both `startsWith(...)` assertions (lines 84 and 142) use the old header. Update both occurrences to:

```kotlin
            assertThat(csv).startsWith("mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta")
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.csv.StyleGuideCsvWriterTest" --tests "com.bliss.survey.application.csv.StyleGuideCsvRoundTripTest" --tests "com.bliss.survey.application.usecases.ExportDatasetUseCaseTest"`
Expected: PASS (the round-trip `parse of write equals original` test still passes — it only asserts the 8 structural columns; the parser change in Task 4 makes the weight round-trip too).

- [ ] **Step 6: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriter.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriterTest.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/ExportDatasetUseCaseTest.kt
git commit -s -m "feat(survey-application): emit training_weight column in CSV export"
```

---

### Task 4: Parser reads `training_weight` and round-trips it

**Files:**
- Modify: `survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvParser.kt`
- Modify: `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

In `StyleGuideCsvRoundTripTest.kt`, extend `arbItem()` to generate a weight and assert it round-trips. Add the import:

```kotlin
import io.kotest.property.arbitrary.of
```

Change the `Arb.bind` to add a weight generator. Replace the `arbItem()` body:

```kotlin
    private fun arbItem(): Arb<SurveyItem> =
        Arb.bind(
            Arb.string(2..12, Codepoint.alphanumeric()).map { it.uppercase() },
            Arb.string(2..50, Codepoint.alphanumeric()).map { raw ->
                raw.replaceFirstChar { c -> c.uppercase() }
            },
            Arb.enum<Pos>(),
            Arb.enum<Categorie>(),
            Arb.enum<Style>(),
            Arb.int(1..5),
            Arb.of(1.0, 2.0, 3.0, 5.0),
        ) { mot, def, pos, cat, style, force, weight ->
            SurveyItem(
                id = ItemId(UUID.randomUUID()),
                mot = mot,
                definition = def,
                pos = pos,
                categorie = cat,
                style = style,
                forceClaimed = force,
                longueur = mot.length,
                source = Source.SYNTHETIC_V1,
                sourceBatch = "test_batch",
                tier = Tier.MID,
                isCalibration = false,
                expected = null,
                retiredAt = null,
                createdAt = Instant.now(),
                trainingWeight = weight,
            )
        }
```

In the `parse of write equals original on structural fields` test, add a final assertion inside the `checkAll` block (after the `source` assertion):

```kotlin
                assertThat(parsed.trainingWeight).isEqualTo(original.trainingWeight)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.csv.StyleGuideCsvRoundTripTest"`
Expected: FAIL on `parse of write equals original` — parser always yields `trainingWeight = 1.0`, but originals are 1.0/2.0/3.0/5.0.

- [ ] **Step 3: Update the parser**

In `StyleGuideCsvParser.kt`, the writer now emits `training_weight` at column index 8 (before the meta blob at index 9). In `parseRow`, set `trainingWeight` from index 8 and stop reading index 8 as `sourceBatch`. Replace the `SurveyItem(...)` construction's `sourceBatch` line and add `trainingWeight`:

```kotlin
        return SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = definition,
            pos = pos,
            categorie = categorie,
            style = style,
            forceClaimed = force,
            longueur = longueur,
            source = source,
            sourceBatch = "unknown",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.now(),
            trainingWeight = cells.getOrNull(8)?.toDoubleOrNull() ?: 1.0,
        )
```

(The ingest pipeline stamps the real `sourceBatch` downstream — `IngestBatchUseCase` overwrites it, as its "stamped with sourceBatch and tier" test asserts. The previous `cells[8] → sourceBatch` read was a loose fallback that collided with the export's optional trailing column; index 8 now unambiguously carries the weight.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.csv.StyleGuideCsvRoundTripTest"`
Expected: PASS (all tests including the `polyvalent` 8-column row, which has no index-8 cell → weight defaults to `1.0`).

- [ ] **Step 5: Run the ingest test to confirm no regression**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.IngestBatchUseCaseTest"`
Expected: PASS — `IngestBatchUseCase` stamps `sourceBatch`/`tier` after parsing, so dropping the parser's `sourceBatch` fallback is invisible to it.

- [ ] **Step 6: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvParser.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt
git commit -s -m "feat(survey-application): parse training_weight column in CSV ingest"
```

---

### Task 5: Export use-case integration test for stamped weight

**Files:**
- Modify: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/ExportDatasetUseCaseTest.kt`

This verifies Tasks 2 + 3 wired through `ExportDatasetUseCase` (which itself is unchanged). It exercises the stamp → findById stitch → writer emission path end to end with the in-memory doubles.

- [ ] **Step 1: Write the test**

Add this test to `ExportDatasetUseCaseTest`:

```kotlin
    @Test
    fun `exports frozen training_weight for stamped item`() =
        runTest {
            val items = InMemorySurveyItemRepository()
            val ratings = InMemoryRatingRepository()
            val item =
                SurveyItem(
                    id = ItemId(UUID.randomUUID()),
                    mot = "POMME",
                    definition = "Fruit",
                    pos = Pos.NOM_COMMUN,
                    categorie = Categorie.ALIMENTS,
                    style = Style.DEFINITION_DIRECTE,
                    forceClaimed = 3,
                    longueur = 5,
                    source = Source.CURATED_V1,
                    sourceBatch = "batch1",
                    tier = Tier.MID,
                    isCalibration = false,
                    expected = null,
                    retiredAt = null,
                    createdAt = now,
                )
            items.insert(item)
            items.updateTrainingWeight(item.id, 3.0)
            ratings.aggregateOverride =
                listOf(
                    RatingAggregate(
                        itemId = item.id,
                        qualiteAuthSum = 10,
                        qualiteAuthN = 3,
                        qualiteAnonSum = 4,
                        qualiteAnonN = 2,
                        difficulteAuthSum = 8,
                        difficulteAuthN = 3,
                        difficulteAnonSum = 4,
                        difficulteAnonN = 2,
                        flagCount = 0,
                        qualiteSquaredAuthSum = 36,
                        qualiteSquaredAnonSum = 8,
                    ),
                )
            val uc = ExportDatasetUseCase(items, ratings, StyleGuideCsvWriter())
            val csv = uc.execute(minRatings = 3, since = null, authWeight = 2.0, anonWeight = 1.0)
            assertThat(csv).contains(";curated_v1;3.0;")
        }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.ExportDatasetUseCaseTest"`
Expected: PASS — the stamped `3.0` appears as the `training_weight` column. (Written after Tasks 2+3; had it been written before Task 3 it would fail on the old 9-column format.)

- [ ] **Step 3: Commit**

```bash
git add survey/application/src/test/kotlin/com/bliss/survey/application/usecases/ExportDatasetUseCaseTest.kt
git commit -s -m "test(survey-application): export emits frozen training_weight"
```

---

### Task 6: Postgres mapper reads `training_weight` + byte-equal fixture

**Files:**
- Modify: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgSurveyItemRepository.kt:412-429` (`toSurveyItem()`)
- Modify: `survey/worker/src/test/resources/byteequal/seed.sql`
- Modify: `survey/worker/src/test/resources/byteequal/expected.csv`
- Test: `survey/worker/src/test/kotlin/com/bliss/survey/worker/ExportByteEqualTest.kt` (no edit — exercises the changes)

- [ ] **Step 1: Update the seed to stamp a non-default weight**

In `byteequal/seed.sql`, after the two `INSERT INTO survey_items` rows, append a stamp so the test proves the mapper reads a real value (not just the default):

```sql
UPDATE survey_items SET training_weight = 3.0
 WHERE item_id = '00000000-0000-0000-0000-000000000002';
```

PAIN (item ...0001) keeps the column default `1.0`; POULE (item ...0002) becomes `3.0`.

- [ ] **Step 2: Update the golden fixture**

Replace the full contents of `byteequal/expected.csv` with the 10-column form (insert the weight between `source` and the meta blob):

```
mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta
PAIN;Aliment de boulangerie;nom_commun;aliments;définition_directe;1;4;gold;1.0;qualite_mean:3.67|qualite_n_auth:1|qualite_n_anon:1|qualite_stdev:0.50|difficulte_mean:2.00|difficulte_n_auth:1|difficulte_n_anon:1|flags:0|source_batch:gold_v1
POULE;Femelle du coq;nom_commun;animals;périphrase;2;5;gold;3.0;qualite_mean:4.50|qualite_n_auth:2|qualite_n_anon:0|qualite_stdev:0.50|difficulte_mean:3.50|difficulte_n_auth:2|difficulte_n_anon:0|flags:0|source_batch:gold_v1
```

- [ ] **Step 3: Run the byte-equal test to verify it fails**

Run: `./gradlew :survey:worker:test --tests "com.bliss.survey.worker.ExportByteEqualTest"`
Expected: FAIL — the export still emits the 9-column row (no `training_weight`), so it differs from the new fixture. (Requires Docker; if Docker is unavailable the test is skipped via `assumeTrue` and you must verify on a Docker-capable runner.)

- [ ] **Step 4: Update the Postgres mapper**

In `PgSurveyItemRepository.kt`, `toSurveyItem()` (line 412), add the weight read as the last argument (after `createdAt`). The query is `SELECT *`, so the column is already in the `ResultSet`:

```kotlin
    private fun ResultSet.toSurveyItem(): SurveyItem =
        SurveyItem(
            id = ItemId(getObject("item_id", UUID::class.java)),
            mot = getString("mot"),
            definition = getString("definition"),
            pos = Pos.valueOf(getString("pos").uppercase()),
            categorie = Categorie.valueOf(getString("categorie").uppercase()),
            style = Style.valueOf(getString("style").uppercase()),
            forceClaimed = getInt("force_claimed"),
            longueur = getInt("longueur"),
            source = Source.valueOf(getString("source").uppercase()),
            sourceBatch = getString("source_batch"),
            tier = Tier.valueOf(getString("tier").uppercase()),
            isCalibration = getBoolean("is_calibration"),
            expected = null,
            retiredAt = getTimestamp("retired_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
            trainingWeight = getDouble("training_weight"),
        )
```

Leave `toAnchorItem(prefix)` (line 160) unchanged — its explicit projection does not select `training_weight`, and anchor items are never exported, so the domain default (`1.0`) is correct there.

- [ ] **Step 5: Run the byte-equal test to verify it passes**

Run: `./gradlew :survey:worker:test --tests "com.bliss.survey.worker.ExportByteEqualTest"`
Expected: PASS — export now emits `gold;1.0;...` for PAIN and `gold;3.0;...` for POULE, byte-equal to the fixture.

- [ ] **Step 6: Commit**

```bash
git add survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgSurveyItemRepository.kt \
        survey/worker/src/test/resources/byteequal/seed.sql \
        survey/worker/src/test/resources/byteequal/expected.csv
git commit -s -m "feat(survey-infrastructure): read training_weight in export mapper"
```

---

### Task 7: Amend the §8.1 written contract

**Files:**
- Modify: `docs/clue-style-guide-v2.md` (§8.1, around lines 2414–2492)

No automated test — this is the human-readable contract. Keep it consistent with the writer header from Task 3.

- [ ] **Step 1: Update the column-count sentence**

Change line 2417 from:

```
**8 colonnes principales** plus un champ `meta` optionnel.
```

to:

```
**9 colonnes principales** plus un champ `meta` optionnel.
```

- [ ] **Step 2: Insert the `training_weight` row in the column table**

In the column-spec table (lines 2421–2431), insert a new row between row 8 (`source`) and the `meta` row, and renumber `meta` from 9 to 10:

```
| 9 | `training_weight` | float | Poids d'entraînement figé (frozen) au moment de la contribution | Réel > 0 ; défaut `1.0` ; pondération gold (ex. `3.0`) appliquée aux items proposés par un mainteneur après le cutoff 2026-05-30 (ADR-0056, Spec B) |
| 10 *(opt.)* | `meta` | str | Clés-valeurs au format `clé:valeur|clé:valeur|…` | Liaison clé-valeur par `:`, séparateur entre paires par `|`. Valeurs ne contiennent ni `|` ni `:` (échappement non géré). Toutes les clés sont optionnelles. Le champ peut être vide (`""`). Convention détaillée ci-dessous. |
```

- [ ] **Step 3: Update the header-line note**

Change the "En-tête" bullet (lines 2474–2475) from:

```
- **En-tête** : ligne 1 contenant les noms des 8 colonnes (ou 9 si
  `meta` est présent)
```

to:

```
- **En-tête** : ligne 1 contenant les noms des 9 colonnes (ou 10 si
  `meta` est présent)
```

- [ ] **Step 4: Update the example format line and rows**

Change the format line (line 2479) from:

```
> Format : `mot;definition;pos;categorie;style;force;longueur;source`
```

to:

```
> Format : `mot;definition;pos;categorie;style;force;longueur;source;training_weight`
```

Replace the example block (lines 2482–2488) — append `;1.0` to each row (all examples are non-gold, default weight):

```
COQ;Mâle de la basse-cour;nom_commun;animals;périphrase;2;3;curated_v1;1.0
KO;Hors combat;sigle_abreviation;abbreviations;définition_directe;1;2;curated_v1;1.0
NO;Refus anglais;sigle_abreviation;etranger;définition_directe;2;2;curated_v1;1.0
LOU;Loup sans p;nom_commun;animals;cryptique_morphologique;4;3;curated_v1;1.0
TIBIA;Os de la jambe avant;nom_commun;body_parts;technique;4;5;synthetic_v1;1.0
RIMBAUD;Auteur du Bateau ivre;nom_propre;first_names;culturel;5;7;synthetic_v1;1.0
```

- [ ] **Step 5: Commit**

```bash
git add docs/clue-style-guide-v2.md
git commit -s -m "docs(survey): document training_weight column in §8.1 schema"
```

---

### Task 8: Full-module verification + open the PR

**Files:** none (verification + PR)

- [ ] **Step 1: Run the full survey build**

Run: `./gradlew :survey:domain:test :survey:application:test :survey:infrastructure:test :survey:worker:test spotlessCheck`
Expected: PASS (worker byte-equal test requires Docker; ensure the runner has it).

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/survey-training-weight-export
```

Open the PR (base `main`). Body must name the workstream (Spec C, survey bounded context, application + infrastructure + domain layers), note that the §8.1 contract and byte-equal fixture were amended in lockstep, and cite ADR-0056. Confirm the diff stays within the ADR-0001 §4 soft target; the doc edit + fixture are the bulk.

---

## Self-Review

**1. Spec coverage:**
- Domain field `trainingWeight: Double = 1.0` + `> 0` invariant → Task 1. ✓
- Pg `toSurveyItem()` reads column; `toAnchorItem` untouched → Task 6. ✓
- In-memory `findById` stitch → Task 2. ✓
- Writer header + row (`training_weight` before `meta`, `toString` rendering) → Task 3. ✓
- Parser coherence (default `1.0`, drop sourceBatch hack) → Task 4. ✓
- `ExportDatasetUseCase` unchanged, verified end-to-end → Task 5. ✓
- §8.1 written contract amended → Task 7. ✓
- Byte-equal fixture (10 columns; gold rows + one stamped `3.0`) → Task 6. ✓
- All six spec test items (writer header, writer weight, round-trip, export use case, domain invariant, byte-equal) → Tasks 1,3,4,5,6. ✓

**2. Placeholder scan:** No TBD/TODO; every code step shows complete code and exact gradle commands.

**3. Type consistency:** `trainingWeight: Double` used identically in domain (Task 1), writer `append(item.trainingWeight)` (Task 3), parser `trainingWeight = ... ?: 1.0` (Task 4), in-memory `copy(trainingWeight = w)` (Task 2), Pg `getDouble("training_weight")` (Task 6). Header string `mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta` is identical in Tasks 3 (writer + both fixed test edits) and the fixture (Task 6). Column index 8 for the weight is consistent between writer emission order (Task 3) and parser read (Task 4).
