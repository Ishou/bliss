# Spec: `survey/` — authenticated clue-rating module for RLHF

**Date**: 2026-05-25
**Status**: Design — awaiting approval. Implementation starts only after ADR-0056 is accepted and the schema-only PR has merged.
**Workstream owner**: Ishou
**Companion ADR**: ADR-0056 (to be drafted; merges before any code).

---

## 1. Why

The clue-generation loop in [`bliss-clue-ai`](../../../../bliss-clue-ai) trains LoRA + DPO models on a French *mots fléchés* corpus. Today's human-feedback step is a Google-Sheets workflow (`bliss-clue-ai/scripts/campaign/`) that hand-distributes 500 stratified definitions to a small cohort of contributors. The Sheets-based campaign is a working prototype but it doesn't scale, can't reach the player base that already authenticates against WordSparrow, and produces ratings that join back to model training via manual Python glue.

The survey module replaces it: a public route in the WordSparrow frontend where **any visitor** — authenticated or anonymous — can rate model-produced candidate clues along the two dimensions the offline pipeline already consumes (`qualite`, `difficulte`), and where **signed-in raters** can additionally propose an alternative clue that automatically enters the corpus as an anonymous candidate. Each rating carries an `auth`/`anon` discriminator so downstream weighting can favour identified raters without losing the volume contributed by drive-by participants. Ratings feed the next training iteration; alternative clues seed the next ingest batch.

Rationale and constraints inherited from existing project artifacts (no separate justifications repeated here):

- **MANIFESTO.md**: DDD, hexagonal, API-first, TDD, mutation coverage on domain, no mocks of own code, property-based tests at boundaries, OpenTelemetry from day 1, threat-model on auth changes, accessibility AA, secrets never in code.
- **CLAUDE.md**: schema-first workflow, 400-line PR cap, `<type>(<bounded-context>-<layer>)` commit scope, ADR before non-trivial change, configure-in-cluster (Helm post-install Jobs, not CI scripts).
- **ADR-0001/0003**: schema-first cross-language contracts.
- **ADR-0023/0024**: dbnary as the lexical source of polysemic senses.
- **ADR-0044/0045**: identity bounded context, opaque `(provider, subject)` pair, no PII, RGPD-grade deletion fan-out via NATS JetStream.
- **ADR-0049**: NATS JetStream as the cross-context event transport; durable consumer naming `<ctx>-api-user-deleted`.
- **ADR-0050**: a11y baseline (axe-core via Playwright, `pnpm a11y`).
- **`bliss-clue-ai/docs/style_guide.md`**: 5-axis taxonomy (POS / catégorie / style / force / source) and §8.1 dataset schema. The survey module's data model is bound to this contract.

## 2. Decision summary

Add a top-level bounded context **`survey/`** peer to `grid/`, `game/`, `identity/`. Hexagonal layout (`domain/ application/ infrastructure/ api/ worker/`). Own Postgres cluster (CNPG), own Helm chart, own ingress host `survey.wordsparrow.io`. Sessions verified by calling `identity-api` when present (30 s cookie-verify cache, ADR-0044 pattern). On `wordsparrow.user.deleted` events from JetStream, ratings are heavily anonymised; proposed-clue contributions remain in the corpus as anonymous candidates unless the user opted out.

**Two participation modes**: authenticated and anonymous. The `/sondage` route is **open to anonymous visitors** (no sign-in required) to maximise rating volume; signed-in users unlock the contribution path (proposing alternative clues) and personal contribution tracking in `/compte`. Every rating carries a `submitted_as ∈ {auth, anon}` discriminator so the export job can apply distinct weights (default: `auth = 1.0`, `anon = 0.5`; configurable).

The frontend serves rating cards built from a stratified-by-tier, K-coverage-first sampler. Each card carries the 5-axis annotation (POS chip + catégorie chip + style + claimed force). The rater scores qualité (1–5), difficulté (1–5), optionally flags. Authenticated raters can additionally propose a correction; proposed corrections enter the candidate pool immediately via the §8.3 filter pipeline (filters 1–7 ported into Kotlin; LLM-juge filter 8 remains offline in `bliss-clue-ai`). The `correctif` field is hidden in anonymous mode — corpus contributions require an accountable identity even though the corpus row itself is anonymous.

Export is a worker subcommand emitting a CSV in `bliss-clue-ai/docs/style_guide.md §8.1` format, consumed verbatim by the offline training pipeline.

## 3. Architecture

```
survey/
  domain/          # SurveyItem, Rating, Choice, Tier, KCoveragePolicy — no framework deps
  application/     # ports + use cases; §8.3 filter pipeline (filters 1-7) lives here
  infrastructure/  # Postgres adapter (CNPG via Flyway), NATS consumer, identity-api client
  api/             # Ktor edge; openapi.yaml is the contract
  worker/          # CLI subcommands: --ingest-batch, --export-dataset, --retire-saturated
  deploy/
    chart/         # Helm chart for survey-api
    db-chart/      # Helm chart for CNPG cluster (sibling release, deployed first)
```

**Cross-context edges**

| Edge | Mechanism | Reference |
|---|---|---|
| Session validation (in) | HTTP call to `identity-api` with `__Host-ws_session` cookie; 30 s in-memory cache | ADR-0044 |
| User deleted (in) | NATS JetStream durable `survey-api-user-deleted` on subject `wordsparrow.user.deleted` | ADR-0049 |
| Training data (out) | CSV file at an agreed location in `bliss-clue-ai/data/campaign/` | §8.1 of style_guide.md |
| Ingest source (in) | CSV file at an agreed location in `bliss-clue-ai/data/campaign/` | §8.1 of style_guide.md |

No synchronous calls to or from `grid/` or `game/`.

**Naming conventions** (matched against existing prod values):

| Surface | Value |
|---|---|
| Ingress host | `survey.wordsparrow.io` |
| K8s fullname | `wordsparrow-survey-api` (via `fullnameOverride`) |
| TLS secret | `bliss-survey-api-tls` |
| Env secret | `bliss-survey-api-env` |
| CNPG release | `wordsparrow-survey-api-pg` (sibling chart release) |
| Ingress floating-IP target | `116.202.180.82` (ADR-0012; required for cert-manager HTTP-01) |
| Ingress rate limit | `limit-rps: "5"`, `limit-burst-multiplier: "5"`, `limit-connections: "30"` (parity with identity/game) |
| JetStream durable | `survey-api-user-deleted` |
| Commit scope | `feat(survey-<layer>): ...` (one of: `domain`, `application`, `infrastructure`, `api`, `worker`, `deploy`) |

## 4. Data model

### 4.1 Tables

```sql
-- A candidate clue for a (mot, sens) pair. Polymorphic source — model-generated,
-- gold-curated, or rater-proposed. The (pos, categorie) tuple disambiguates sense
-- per style_guide.md §7.5. Same (mot, definition, pos, categorie) from a different
-- source_batch is a NEW item_id (deliberate; raters can re-rate across iterations).
CREATE TABLE survey_items (
  item_id        UUID PRIMARY KEY,                    -- UUIDv7 per ADR-0021
  mot            TEXT NOT NULL,
  definition     TEXT NOT NULL,
  pos            TEXT NOT NULL,                       -- enum: 12 values §7.1
  categorie      TEXT NOT NULL,                       -- enum: 43 values §7.2
  style          TEXT NOT NULL,                       -- enum: 9 values §4
  force_claimed  SMALLINT NOT NULL CHECK (force_claimed BETWEEN 1 AND 5),
  longueur       SMALLINT NOT NULL,
  source         TEXT NOT NULL,                       -- enum §8.1: gold|curated_v1|synthetic_v1|manual|rater_proposed
  source_batch   TEXT NOT NULL,                       -- 'iter18_dpo_pairs_20260525', 'rater_2026-05', 'gold_v1', ...
  tier           TEXT NOT NULL DEFAULT 'mid'          -- routing strata
                 CHECK (tier IN ('high','mid','low','excluded')),
  is_calibration BOOLEAN NOT NULL DEFAULT FALSE,
  expected       JSONB,                                -- gold answer for calibration items
  retired_at     TIMESTAMPTZ,                          -- soft-stop once K-coverage met
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON survey_items (tier) WHERE retired_at IS NULL;
CREATE INDEX ON survey_items (mot);

-- A rating is either auth (linked to a user_id) or anon (user_id NULL from
-- inception, submitted_as='anon'). The submitted_as discriminator is immutable
-- through the user.deleted flow so anonymised auth-ratings remain
-- distinguishable from anon-from-inception ratings at export-weighting time.
-- proposed_item_id is reserved for auth ratings only; the corpus contribution
-- path requires accountable identity even though the corpus row is anonymous.
-- No correctif text column here — the text lives in survey_items.definition
-- (single source of truth).
CREATE TABLE ratings (
  rating_id        UUID PRIMARY KEY,                  -- UUIDv7
  item_id          UUID NOT NULL REFERENCES survey_items(item_id),
  user_id          UUID,                              -- nullable: null for anon and after anonymisation
  submitted_as     TEXT NOT NULL                      -- discriminator; immutable through anonymisation
                   CHECK (submitted_as IN ('auth','anon')),
  qualite          SMALLINT NOT NULL CHECK (qualite BETWEEN 1 AND 5),
  difficulte       SMALLINT NOT NULL CHECK (difficulte BETWEEN 1 AND 5),
  flag             TEXT CHECK (flag IS NULL OR flag IN
                     ('hors_sujet','auto_reference','erreur_sens','autre')),
  proposed_item_id UUID REFERENCES survey_items(item_id),
  latency_ms       INTEGER,                            -- nullable after anonymisation
  client_meta      JSONB,                              -- nullable after anonymisation
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Per-user uniqueness is enforced only for auth ratings. Anon ratings have
  -- no server-side dedup (no stable identity to dedupe against); the frontend
  -- tracks rated item_ids in localStorage for best-effort client-side dedup.
  CHECK (submitted_as = 'auth' OR (user_id IS NULL AND proposed_item_id IS NULL))
);
CREATE UNIQUE INDEX ratings_auth_uniq ON ratings (item_id, user_id)
  WHERE user_id IS NOT NULL;
CREATE INDEX ON ratings (item_id);
CREATE INDEX ON ratings (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ON ratings (submitted_as);

-- Authorship link for proposed clues. Carries the opt-out flag.
-- Fully deleted on user.deleted.
CREATE TABLE proposed_by (
  proposed_item_id UUID PRIMARY KEY REFERENCES survey_items(item_id),
  user_id          UUID NOT NULL,
  opted_out        BOOLEAN NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON proposed_by (user_id);

-- Per-user serving cursor + rolling calibration agreement.
CREATE TABLE user_progress (
  user_id              UUID PRIMARY KEY,
  items_rated          INTEGER NOT NULL DEFAULT 0,
  calibration_agreement NUMERIC(4,3),
  last_rated_at        TIMESTAMPTZ
);
```

### 4.2 Domain invariants

- `survey_items` is immutable except for `retired_at`. A new model run produces new `item_id`s.
- A `Rating` cannot exist without its `SurveyItem`. For auth ratings, the partial unique index `(item_id, user_id) WHERE user_id IS NOT NULL` prevents duplicates; idempotent retry returns 409.
- Anon ratings have no server-side dedup. The frontend keeps a localStorage list of rated `item_id`s for best-effort client-side dedup; clearing localStorage allows re-rating from the same browser, which is accepted as a low-cost trade for not persisting an anon session identifier server-side (see Section 10.1).
- `proposed_item_id` is **null for all anon ratings** (DB-level CHECK constraint). Domain enforces this at construction.
- `proposed_item_id` always references a `survey_items` row with `source = 'rater_proposed'`. Domain enforces this at construction.
- `submitted_as` is immutable: an auth rating that gets anonymised on `user.deleted` keeps `submitted_as = 'auth'` (its `user_id` goes to NULL, but the discriminator preserves the original participation tier for export weighting).
- Polysemy is first-class (style_guide §7.5): `(mot, definition, pos, categorie)` is the corpus-level uniqueness key — a single `mot` legitimately has multiple rows with different `categorie` (POULE-animals vs. POULE-expressions), each with its own clue candidates and ratings.
- Calibration agreement is computed on a rolling window of the user's last N=20 calibration ratings (auth only — anon has no stable identity to track across requests).

### 4.3 Domain model (Kotlin shape — names only)

```
@JvmInline value class ItemId(val value: UUID)
@JvmInline value class RatingId(val value: UUID)
@JvmInline value class UserId(val value: UUID)

enum class Pos { /* 12 values from §7.1 */ }
enum class Categorie { /* 43 values from §7.2 */ }
enum class Style { /* 9 values from §4 */ }
enum class Tier { HIGH, MID, LOW, EXCLUDED }
enum class FlagReason { HORS_SUJET, AUTO_REFERENCE, ERREUR_SENS, AUTRE }
enum class Source { GOLD, CURATED_V1, SYNTHETIC_V1, MANUAL, RATER_PROPOSED }

data class SurveyItem(
  val id: ItemId, val mot: String, val definition: String,
  val pos: Pos, val categorie: Categorie, val style: Style,
  val forceClaimed: Int, val source: Source, val sourceBatch: String,
  val tier: Tier, val isCalibration: Boolean, val expected: CalibrationAnswer?,
  val retiredAt: Instant?
) {
  init { require(forceClaimed in 1..5) }
}

enum class SubmittedAs { AUTH, ANON }

data class Rating(
  val id: RatingId, val itemId: ItemId, val userId: UserId?,
  val submittedAs: SubmittedAs,
  val qualite: Int, val difficulte: Int,
  val flag: FlagReason?, val proposedItemId: ItemId?,
  val latencyMs: Int?, val createdAt: Instant
) {
  init {
    require(submittedAs == SubmittedAs.AUTH || (userId == null && proposedItemId == null)) {
      "anon ratings cannot carry user_id or proposed_item_id"
    }
  }
}
```

## 5. Item routing — tier-stratified + K-coverage

`GET /v1/items/next` returns a single item the user hasn't rated, selected by:

1. **Tier-stratified weighted draw**: configurable per-tier weights (default `mid=0.55, high=0.20, low=0.15, excluded=0.05, calibration=0.05`).
2. **Within the chosen tier-pool**, prioritise by K-coverage: K=0 (never rated) → K=1 → K≥2.
3. **Retire** an item when its rating count reaches the target K (default K=3; configurable per tier). Sets `retired_at`; the item no longer surfaces in `next` but its history is preserved for export.

The weights and the retirement target live in the chart's `values.yaml`; no code change required to tune them.

## 6. API surface

`survey.wordsparrow.io` — OpenAPI 3 contract at `survey/api/openapi.yaml`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/v1/items/next` | optional | Pull next unrated item; tier-stratified + K-coverage routing. Anonymous callers may pass `?excluded=<comma-sep-uuids>` for best-effort client-side dedup. Returns 204 if pool exhausted. |
| `POST` | `/v1/items/{itemId}/rating` | optional | Submit `{qualite, difficulte, flag?, correctif?, latency_ms}`. Server sets `submitted_as` based on session presence. `correctif` is **rejected with 401** for anonymous callers (corpus contributions require an account). For auth, idempotent via partial unique index; returns 201 or 409 with the existing rating. For anon, always 201 (no dedup). |
| `GET` | `/v1/me/progress` | session required | `{items_rated, calibration_agreement, last_rated_at}` |
| `GET` | `/v1/me/contributions` | session required | List of `survey_items` rows where the caller is the `proposed_by` author. |
| `PATCH` | `/v1/me/preferences` | session required | `{delete_proposed_on_erasure: bool}` — sets `proposed_by.opted_out` for the caller's existing contributions. |
| `GET` | `/v1/health` | none | Liveness/readiness |

Error responses follow RFC 7807 (consistent with grid-api). No admin HTTP endpoints — all admin operations are CLI/Job-only (see Section 7).

**Auth resolution**: the API middleware attempts to read `__Host-ws_session` and verifies via identity-api (30 s cache). If a valid session is present, the rating is submitted as `auth` with the resolved `user_id`. If no session or an invalid one, the rating is submitted as `anon` with `user_id=NULL`. The middleware never rejects requests for being anonymous; it only rejects when an explicitly-auth-required path (`/v1/me/*`) is hit without a session.

**`correctif` handling on submit**: if the request includes `correctif` and the caller is **authenticated**, the application layer runs filters 1–7 from style_guide §8.3 against it. If accepted, a new `survey_items` row with `source='rater_proposed'` is minted in the same transaction as the rating; `proposed_item_id` is set; a `proposed_by` row is inserted with `opted_out=false`. If filters reject, the API returns 422 with the failing filter id and message; the rating itself is NOT submitted (the rater must either fix or drop the correctif). If the caller is **anonymous** and submits `correctif`, the API returns 401 with a message inviting sign-in.

## 7. Worker subcommands

`survey-worker` is a single Kotlin executable with subcommands. Same shape as `grid/worker` (ADR-0013, ADR-0042).

```sh
survey-worker --ingest-batch     --csv=<path> --source-batch=<id> --tier=<high|mid|low|excluded>
survey-worker --export-dataset   --output=<path> --min-ratings=2 [--since=<date>]
survey-worker --retire-saturated                                # idempotent
```

**Ingest** reads §8.1-formatted CSV, validates each row against the enums and through filters 1–7, mints `survey_items` rows. Rejects logged with line number and reason.

**Export** emits CSV in §8.1 format with `meta` carrying aggregated rating data, **separating auth and anon contributions** so the training pipeline can apply distinct weights:

```
mot;definition;pos;categorie;style;force;longueur;source;meta
POULE;Femelle du coq;nom_commun;animals;périphrase;2;5;synthetic_v1;qualite_mean:4.2|qualite_n_auth:3|qualite_n_anon:12|qualite_stdev:0.4|difficulte_mean:2.0|difficulte_n_auth:3|difficulte_n_anon:12|flags:0|source_batch:iter18_20260525
```

The aggregation uses configurable per-discriminator weights (defaults: `auth = 1.0`, `anon = 0.5`). Weighted means look like `qualite_mean = (1.0·Σauth + 0.5·Σanon) / (1.0·n_auth + 0.5·n_anon)`. Both counts are exported separately so `bliss-clue-ai` can re-weight downstream if needed.

The export is **deterministic and byte-stable** given identical inputs (sorted by `item_id`, fixed locale, NFC). Guarded by the `survey-export-csv-byteequal` CI gate.

**Retire-saturated** runs as a k8s CronJob hourly; idempotent.

**Initial calibration seeding** runs as a Helm `post-install,post-upgrade` Job (configure-in-cluster pattern per CLAUDE.md). Source: ~20 hand-validated rows from `bliss-clue-ai/data/seed/gold_pilot_v1.csv`, embedded as a ConfigMap.

## 8. Frontend route

New file `frontend/src/ui/routes/sondage.lazy.tsx` (lazy-loaded per convention; see `aide.lazy.tsx`, `mentions-legales.lazy.tsx`).

**Auth gate**: none. The route is **open**. The page renders for both signed-in and anonymous visitors. A persistent banner at the top invites sign-in: *Connectez-vous pour proposer vos propres indices et suivre vos contributions.* For anonymous visitors the `correctif` field and `style` dropdown are hidden; clicking the banner navigates to sign-in with `return_to=/sondage`. Anon raters track their rated `item_id`s in localStorage (`survey.anon.rated_ids`) and pass them to `/v1/items/next` as `excluded` for best-effort dedup. Clearing localStorage permits re-rating from the same browser — accepted trade-off per Section 10.1.

**UI surface per card**:
- `mot` heading
- Chip row: `[pos]` + `[catégorie]` (catégorie chip is visually prominent — it carries the sense disambiguation)
- Definition under evaluation
- Sub-line: `style: <style> · force annoncée: <force>`
- Likert: *Qualité* 1–5 (ARIA radiogroup; `Tab` to focus, `←`/`→` to change, `Space`/`Enter` to select)
- Likert: *Difficulté* 1–5 (same ARIA radiogroup pattern; `Tab` between groups)
- Optional flag dropdown
- Optional `correctif` text field + small `style` dropdown for the proposed alternative
- Transparency notice under the correctif field: *Les corrections proposées rejoignent notre corpus comme indices anonymes, sans lien avec votre compte.*
- Submit button (`Enter` when focused on submit) → next card prefetched and swapped

Number-row shortcuts are deliberately avoided: on French AZERTY layouts the digit keys require `Shift`, so `1`–`5` shortcuts would be inconsistent across keyboard layouts. Standard ARIA radiogroup interaction works across layouts and is the documented accessible default.

**Accessibility (ADR-0050)**:
- Keyboard navigation as above
- ARIA: `role="radiogroup"` + `aria-label` on each Likert; `aria-checked` per radio; `aria-live="polite"` on the card region for screen-reader feedback when the next card loads
- Focus management: on next-card load, focus moves to the first Likert
- High-contrast token usage from the existing Panda theme
- Axe-core test under `pnpm a11y` extended to cover `/sondage`

**Analytics (Matomo, ADR-0025)**: events `survey_session_start`, `survey_rating_submitted` (with `tier` and `submitted_as` dimensions), `survey_correctif_proposed`, `survey_flag_raised`, `survey_signin_prompt_shown`, `survey_signin_prompt_clicked`. No PII added; the `submitted_as` dimension lets us measure the auth-vs-anon mix and the conversion rate from anon → signed-in.

**Account integration (`/compte`)**:
- New section *Mes contributions* listing user's proposed clues with their current K-coverage.
- New section *Confidentialité du sondage* with the `delete_proposed_on_erasure` checkbox.

## 9. Cross-context contracts

### 9.1 Identity (in)

`survey-api` validates the `__Host-ws_session` cookie by calling `GET /v1/me` on `identity-api`. Result is cached for 30 s per session id (ADR-0044 §Consequences). The identity-context `UserId` is reused verbatim as `survey.ratings.user_id`.

### 9.2 NATS JetStream — user deleted (in)

Durable consumer `survey-api-user-deleted` on subject `wordsparrow.user.deleted`. Payload schema is identity-api's existing event (`identity/api/events/`). Explicit-ack with retry. On receipt, executes:

```sql
-- 1. Remove corpus contributions where the user opted out
DELETE FROM survey_items
 WHERE item_id IN (
   SELECT proposed_item_id FROM proposed_by
    WHERE user_id = $1 AND opted_out = TRUE
 );

-- 2. Anonymise ratings (no text operation: there is no correctif column on ratings)
UPDATE ratings
   SET user_id = NULL,
       client_meta = NULL,
       latency_ms = NULL,
       created_at = date_trunc('month', created_at)
 WHERE user_id = $1;

-- 3. Drop the authorship link entirely
DELETE FROM proposed_by WHERE user_id = $1;

-- 4. Drop the progress row
DELETE FROM user_progress WHERE user_id = $1;
```

The remaining `ratings` rows carry `(item_id, qualite, difficulte, flag, proposed_item_id, month)`. All retained columns are low-cardinality enums or numerics; many users produce identical tuples; singling-out and linkability are structurally defeated. Survives WP216 anonymisation criteria — see Section 10.

### 9.3 `bliss-clue-ai` CSV (in/out)

`bliss-clue-ai` and the survey module are two separate repos. The contract between them is the §8.1 CSV format and an agreed location convention:

| Direction | Path (in `bliss-clue-ai`) | Producer | Consumer |
|---|---|---|---|
| Ingest | `data/campaign/v<N>_candidates.csv` | `bliss-clue-ai` production pipeline | `survey-worker --ingest-batch` |
| Export | `data/campaign/v<N>_ratings.csv` | `survey-worker --export-dataset` | `bliss-clue-ai` training pipeline |

Both sides commit their files for reproducibility. No automated transfer — the maintainer manually copies CSVs between the two repos at training boundaries.

## 10. RGPD posture

### 10.1 Anonymisation rationale (WP216)

**Anon ratings** are anonymous from inception: no `user_id`, no session identifier persisted server-side, no IP stored on the row. The `ratings` row carries only `(item_id, qualite, difficulte, flag, latency_ms, client_meta, created_at, submitted_as='anon')`. Because no stable identifier links two anon ratings together at storage time, they cannot be clustered into per-individual sessions by an attacker reading the dataset — singling out and linkability are both defeated by construction. The frontend's localStorage dedup token never reaches the server.

**Auth ratings** become anonymous after `user.deleted`. The rows retain only `(item_id, qualite, difficulte, flag, proposed_item_id, month, submitted_as='auth')`. None of these columns is identifying on its own:

- `item_id` is a corpus-internal UUID with no identity link.
- `qualite`, `difficulte` are 1–5 enums (high collision rate across users).
- `flag` is a 4-value enum.
- `proposed_item_id` points to a corpus row that has no remaining author link (the `proposed_by` row was deleted in step 3).
- `created_at` is coarsened to month-precision; the typical rating volume per month per item makes timestamp-based clustering ineffective.

Tested against the three WP216 criteria:
- **Singling out**: a row identifies no individual; tuples are highly repeated across the rater population.
- **Linkability**: with `user_id`, `latency_ms`, `client_meta` stripped and timestamps coarsened, behavioural linkage across rows is defeated.
- **Inference**: no per-individual facts can be derived from any subset.

Proposed clues (`survey_items` with `source='rater_proposed'`) were **never personal data** — they entered the corpus as candidate dictionary definitions, not as records about a person. The `proposed_by` join table is the only place authorship was recorded; that table is fully deleted on `user.deleted`.

### 10.2 Opt-out

`/compte` exposes a single checkbox: *Supprimer aussi mes corrections proposées en cas de suppression de mon compte.* Default unchecked (i.e., contributions persist anonymously). Toggling on sets `proposed_by.opted_out = TRUE` for all existing contributions and is applied to future ones. On `user.deleted`, opted-out contributions are deleted alongside the join table.

### 10.3 Transparency at the point of collection (Article 13)

The transparency line under the `correctif` field (*Les corrections proposées rejoignent notre corpus comme indices anonymes, sans lien avec votre compte.*) satisfies the in-context information requirement. A longer treatment lands in `docs/privacy/` as part of PR8.

### 10.4 Threat model summary (full STRIDE table in ADR-0056)

| Threat | Mitigation |
|---|---|
| Spoofing | `__Host-ws_session` cookie + identity-api verification (ADR-0044) |
| Tampering | Per-user uniqueness on ratings; ingress rate limiting; signed session cookie |
| Repudiation | No PII to repudiate; rating bound to opaque `user_id` for the lifetime of the user record |
| Information disclosure | No admin HTTP endpoints; aggregates only in CSV export; `proposed_by` is per-user-readable only via the caller's own contributions |
| Denial of service | Ingress rate limits; tier sampling is O(log n); K-coverage retirement keeps the pool bounded |
| Elevation of privilege | No admin path; worker subcommands run as Kubernetes Jobs with dedicated service account and minimal Postgres role |
| Rater abuse (data poisoning) | Calibration items, rolling agreement scoring, export-time de-weighting |

## 11. Testing strategy

Per MANIFESTO §Quality and §Testing.

| Layer | Type | Tooling |
|---|---|---|
| `survey/domain` | TDD unit + property-based | JUnit5 + Kotest property |
| `survey/application` | Integration with in-memory adapters | JUnit5 |
| `survey/infrastructure` | Contract against real Postgres | Testcontainers (Postgres 18) |
| `survey/api` | HTTP contract | Ktor test engine + JSON Schema validation against `openapi.yaml` |
| Cross-context | JetStream consumer contract | In-process NATS test server + real event payload from `identity/api/events/` |
| `survey/worker` | E2E with real Postgres + real NATS | Testcontainers |
| Frontend `/sondage` | Vitest unit + Playwright E2E | Same as existing routes |
| Frontend a11y | axe-core via Playwright (ADR-0050) | `pnpm a11y` |
| Konsist arch | Module dependency rules | Konsist |

**Mandatory property-based tests** (MANIFESTO §PBT):

1. §8.1 row ↔ `SurveyItem` parse/serialize round-trip
2. Export aggregator: identical inputs produce byte-identical CSVs
3. Tier-stratified sampler: over N=10000 draws against a fixed pool, observed tier frequencies are within ε=0.02 of configured weights
4. K-coverage retirement idempotence: `retire(retire(s)) == retire(s)`
5. Anonymisation idempotence: applying the user.deleted SQL twice has no additional effect
6. Anonymisation preserves `submitted_as`: for any auth rating, post-anonymisation row still reports `submitted_as = 'auth'`
7. Anon rating invariants: every persisted anon rating has `user_id IS NULL`, `proposed_item_id IS NULL`, `submitted_as = 'anon'` (the DB CHECK enforces this; the PBT verifies the application layer never tries to violate it)
8. Filter-1-through-7 pipeline: pre-NFC normalisation invariant, plus the 27 negative cases from `bliss-clue-ai/scripts/pipeline/test_negative_cases.py` ported as the test fixture

**CI gates touched or added**:

- Extended: `ci` (Gradle, Konsist, Spotless), `openapi-lint`, `openapi-typescript-drift`, `helm-lint`, `api-chart-lint`.
- Added: `survey-export-csv-byteequal` — runs `survey-worker --export-dataset` against a checked-in fixture DB dump and asserts byte-equality with a golden CSV.
- Unchanged: `commitlint`, `branch-name`, `dco`, `secret-scan`, `codeql`, `dependency-review`, `claude-code-review`.

## 12. Rollout (PR sequence)

Schema-first per ADR-0001 §3 / ADR-0003. Each PR stays under the 400-line cap; if any single PR breaks the cap, it splits further or invokes the standing cap-override with justification.

| PR | Scope | Approx. size |
|---|---|---|
| **PR1** | ADR-0056 (survey bounded context) + threat model | docs only |
| **PR2** | Schema-only: `survey/api/openapi.yaml` + AsyncAPI consumer entry | schema only |
| **PR3** | `survey/domain` — types, invariants, KCoveragePolicy. TDD with failing tests first. | ≤400 |
| **PR4** | `survey/application` — use cases, ports, §8.3 filters 1–7 port | ≤400 |
| **PR5** | `survey/infrastructure` — Postgres adapters via testcontainers, identity-api client | ≤400 |
| **PR6** | `survey/api` — Ktor edge, RFC 7807 errors | ≤400 |
| **PR7** | `survey/worker` — subcommands + Helm chart + db-chart + post-install calibration seed Job | likely cap-override (chart + worker together) |
| **PR8** | Frontend `/sondage` route + `/compte` sections + Matomo events + axe-core extension | ≤400 |
| **PR9** | NATS consumer (`survey-api-user-deleted` durable) + integration test | ≤400 |

Flag-gating: `survey.enabled` (frontend), `survey.api.enabled` (backend route gate). Both default false; ship dark, release bright. Flag expiry date: 2026-08-25 (per MANIFESTO §"Deploy ≠ Release").

After PR9 merges:
1. Helm post-install Job seeds calibration items.
2. Manual ingest of a small batch (~50 items) for smoke-test.
3. Flip flags ON for maintainer only.
4. Onboard the existing Sheets cohort.
5. Public ramp: flag ON for all authenticated users.
6. Sheets workflow documented as superseded in `bliss-clue-ai/scripts/campaign/README`.

## 13. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Sense disambiguation fails — raters score against the wrong sense | M | H | `categorie` chip is visually prominent; calibration items include polysemy traps; calibration de-weighting at export |
| `excluded`-tier flood — most clues are filter-rejected; weight 0.05 still floods | M | M | Per-batch cap on `excluded` rows at ingest, configurable |
| K=3 too aggressive — items retire while ratings still disagree | L | M | Configurable per tier; high-stdev items get K bumped (v2) |
| NATS consumer lag — ratings linger un-anonymised | L | M | SigNoz alert on consumer lag > 5 min (ADR-0040 pattern) |
| §8.1 export drift breaks `bliss-clue-ai` training | M | H | `survey-export-csv-byteequal` CI gate + golden CSV fixture |
| Frontend bundle bloat | L | L | Lazy-loaded route |
| Identity verify traffic spike | L | L | 30 s cache (ADR-0044); identity scales horizontally |
| Rater abuse (low-effort farming) | L | M | Calibration de-weighting; no live block (avoids UX cliff) |
| `correctif` injection of disallowed content | L | M | Filters 1–7 run synchronously on submit; rejection returns 422 with reason |
| Anon abuse — drive-by spam ratings | M | M | Ingress per-IP rate limit (5 rps/30 connections); export weight 0.5 for anon; calibration items mixed into the pool catch noise at the aggregate level (anon group's rolling agreement vs. expected); auth ratings remain dominant in weighted means even at unequal counts |
| Anon volume drowns auth signal | L | M | Per-discriminator counts in export `meta` let `bliss-clue-ai` re-weight downstream; a SigNoz dashboard panel surfaces the auth/anon ratio per export |

## 14. Open items (v2 candidates, NOT in scope for v1)

1. **Adaptive routing weights** based on per-rater calibration agreement
2. **Pairwise comparison task type** — schema accommodates via `item_type` polymorphism if DPO loop demands explicit preference data again
3. **Disagreement triage UI** — currently surfaced via export stdev only
4. **Cross-language extension** — `pos` and `categorie` enums are FR-specific; would need a `language` column or a sister context
5. **Filter 8 (LLM-juge) in-band** — currently offline in `bliss-clue-ai`; not worth bringing online until rater volume justifies it
6. **Bumping K for high-stdev items** — keeps disagreed-on items in the pool longer
7. **Difficulty histogram per category** in the maintainer's view — current export is enough
8. **Captcha or proof-of-work for anon** — only if anon abuse signals (calibration disagreement spikes, abusive flag floods) appear in production. v1 relies on ingress rate limiting + downweighting.
9. **Anon → auth rating attribution** — currently an anon visitor who later signs in loses their prior ratings (they remain in the table as anon). A localStorage-backed merge endpoint could attribute them on sign-in; deferred until we see whether the conversion rate justifies the complexity.

## 15. What this spec deliberately does NOT do

- No pairwise rating in v1.
- No admin web UI (CLI/Job only).
- No real-time aggregation API for the maintainer.
- No live blocking of low-calibration raters.
- No gamification, leaderboard, or rewards.
- No editing of past ratings; re-rating only happens on a new `source_batch`.
- No import of historical Sheets ratings (treat as frozen v0 corpus, already consumed offline).
- No public scoring statistics.
- No automated CSV transfer between `bliss-clue-ai` and `bliss` (manual copy at training boundaries).
- No server-side persistence of an anon session identifier — keeps the "anonymous by construction" property of anon ratings clean. Client-side dedup only.
- No anon contribution path (proposing alternative clues requires sign-in).
- No anon → auth attribution of prior ratings on sign-in (deferred to v2 per Section 14).

---

End of spec.
