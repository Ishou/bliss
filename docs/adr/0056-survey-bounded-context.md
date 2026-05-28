# ADR-0056: Survey bounded context — auth-optional clue-rating module for RLHF

## Status
Accepted

## Context

The clue-generation loop in [`bliss-clue-ai`](https://github.com/Ishou/bliss-clue-ai)
trains LoRA + DPO models on a French *mots fléchés* corpus. Today's human-feedback
loop is a Google-Sheets workflow (`bliss-clue-ai/scripts/campaign/`) that
hand-distributes ~500 stratified definitions to a small cohort of contributors.
The workflow is a working prototype but it doesn't scale, can't reach the
authenticated WordSparrow player base, and joins ratings back to model training
via manual Python glue.

We want to replace it with an in-app rating loop where authenticated and
anonymous visitors can rate model-produced candidate clues, where signed-in
raters can propose alternative clues that enter the corpus as anonymous
candidates, and where the export job emits a CSV consumed back into training.

### Options considered

| Option | Pros | Cons |
|---|---|---|
| **New `survey/` top-level context** | Cleanest separation; cross-cutting concern; isolates user-facing auth-aware feature from puzzle generation | One more chart and DB to operate |
| Extend `grid/` | No new chart | Conflates puzzle generation (deterministic, hot-path) with auth-aware human-feedback collection; same layer pollution rejected by ADR-0044 |
| Static form sink (Pages Function → object storage) | Cheapest | No dedup, no calibration, no auth-tier weighting, no incremental export — offline pipeline still needs joining |

### Why the new context

The cost of one more chart is fixed and small (the precedent is ADR-0044's
`identity/` argument verbatim). The architectural cost of mixing
human-feedback collection into `grid/` compounds with every feature added to
either side. The static-sink option doesn't pay back: the offline pipeline
still needs to do the joining and de-duplication that the new context does
in-band.

## Decision

Add a top-level bounded context `survey/` peer to `grid/`, `game/`, `identity/`.
Standard hexagonal layout: `domain/`, `application/`, `infrastructure/`, `api/`,
`worker/`. Owns:

- Candidate clues received from `bliss-clue-ai` as ingest batches.
- Rater-proposed clues promoted into the corpus.
- Ratings (qualité 1–5, difficulté 1–5, optional flag) by authenticated and
  anonymous visitors.
- Pairwise comparison ratings (`pair_ratings` table): LEFT_WINS/RIGHT_WINS →
  one row in `pair_ratings` (consumed by DPO pair builder as chosen/rejected);
  BOTH_GOOD/BOTH_BAD → two rows in `ratings`; SKIP → no write.
- Authorship link for proposed clues (`proposed_by` table) and per-user
  opt-out preference.
- §8.1-formatted CSV export consumed by `bliss-clue-ai`'s training pipeline.

Schema-first per ADR-0003: `survey/api/openapi.yaml` is the contract that the
frontend consumes.

Ingress host: `survey.wordsparrow.io`. Sessions verified by calling
`identity-api` with a 30 s cache (ADR-0044 pattern). The route is open —
anonymous visitors can rate but cannot propose corrections.

Cross-context events: JetStream durable `survey-api-user-deleted` on subject
`wordsparrow.user.deleted` (ADR-0049 pattern).

Data model (tables owned by `survey/`):

| Table | Key columns | Notes |
|---|---|---|
| `candidate_clues` | `id`, `word`, `definition`, `pos`, `categorie`, `source_model`, `source_version`, `created_at` | Ingest batch rows from `bliss-clue-ai`. Immutable after insert. |
| `ratings` | `id`, `item_id` → `candidate_clues`, `user_id` (nullable), `qualite SMALLINT 1–5`, `difficulte SMALLINT 1–5`, `flag TEXT`, `weight NUMERIC`, `latency_ms INT`, `client_meta JSONB`, `created_at TIMESTAMPTZ` | Partial unique index `(item_id, user_id) WHERE user_id IS NOT NULL` prevents duplicate auth submissions. |
| `proposed_clues` | `id`, `word`, `definition`, `pos`, `categorie`, `proposed_at` | Corpus entries from rater proposals. No embedded identity. |
| `proposed_by` | `user_id`, `proposed_clue_id`, `opted_out BOOL` | Authorship join table; fully deleted on `user.deleted`. |
| `calibration_items` | `id` → `candidate_clues`, `gold_qualite`, `gold_difficulte`, `seeded_from` | Gold-truth items seeded from `bliss-clue-ai/data/seed/gold_pilot_v1.csv`. |
| `rater_calibration` | `user_id`, `agreement_score NUMERIC`, `rated_count INT`, `last_updated` | Per-user rolling calibration score for export-time weighting. |
| `pair_ratings` | `id`, `left_item_id` → `candidate_clues`, `right_item_id` → `candidate_clues`, `user_id` (nullable), `verdict` (LEFT_WINS \| RIGHT_WINS), `difficulte SMALLINT 1–5`, `latency_ms INT`, `created_at TIMESTAMPTZ` | Strict preference table: every row is a usable DPO pair. BOTH_GOOD/BOTH_BAD route to `ratings`; SKIP is not persisted. |

## Threat Model

The following threats were considered for the survey rating and corpus-
contribution surface.

**Spoofing (forged user identity)** — auth ratings rely on the existing
`__Secure-ws_session` cookie (renamed per ADR-0044 Amendment 2026-05-18;
`Secure; HttpOnly; SameSite=Lax; Domain=wordsparrow.io`) verified by
`identity-api`. The 30 s cache means a stolen cookie is usable for at most
30 s beyond the identity-api revocation; this is the same posture as
`grid-api` and `game-api` and is considered acceptable for non-admin player
traffic. Anonymous ratings carry no identity claim and therefore have
nothing to spoof.

**Tampering (ratings injection / dataset poisoning)** — per-user uniqueness
on auth ratings (partial unique index `(item_id, user_id) WHERE user_id IS
NOT NULL`) prevents duplicate submissions per signed-in user. Anonymous
ratings have no server-side dedup; the calibration items mixed into the
serving pool (~5%) provide an aggregate noise signal that the export job
de-weights against. Ingress rate limiting (`limit-rps: 5`,
`limit-connections: 30`) bounds per-IP abuse.

**Repudiation** — by design the survey context retains no PII beyond the
opaque `(provider, subject)` `user_id` it inherits from `identity-api`. There
is nothing to repudiate. Rating bound to the `user_id` for the lifetime of
the user record; anonymised on `user.deleted`.

**Information disclosure** — no admin HTTP endpoints. Aggregates are only
emitted via CSV export (a maintainer-triggered worker job). `/v1/me/*` paths
are session-required and scoped to the caller's own data. The `proposed_by`
table is readable only via `/v1/me/contributions` (caller's own entries).

**Denial of service** — ingress rate limits bound burst traffic. Tier-
stratified sampling is O(log n) over the unrated pool via Postgres indexes.
K-coverage retirement keeps the pool bounded.

**Elevation of privilege** — no admin path on the HTTP surface. Worker
subcommands run as Kubernetes Jobs with a dedicated ServiceAccount and a
Postgres role limited to the survey database. No cross-context Postgres
roles.

**Rater abuse (data poisoning by a determined contributor)** — calibration
items are seeded as gold-truth from `bliss-clue-ai/data/seed/gold_pilot_v1.csv`
and mixed into the serving pool at ~5%. Rolling calibration agreement is
tracked per authenticated user; export-time weighting de-trusts users whose
agreement drops below threshold. Anon ratings carry weight 0.5 by default
and have no per-user tracking — abuse is bounded by aggregate noise.

**RGPD Article 17 (right to erasure)** — `user.deleted` consumer
heavy-anonymises the rater's `ratings` rows (user_id → NULL, latency_ms →
NULL, client_meta → NULL, created_at coarsened to month). Proposed-clue
corpus rows are not personal data by construction (they were minted as
candidate dictionary entries with no embedded identity claim); the
`proposed_by` join table that recorded authorship is fully deleted. Users
who opted out (`proposed_by.opted_out = TRUE`) additionally have their
contributed corpus rows deleted.

WP216 anonymisation basis: after the four-field erasure (`user_id → NULL`,
`latency_ms → NULL`, `client_meta → NULL`, `created_at` coarsened to month)
the retained rating rows satisfy the three WP216 tests — (a) singling-out:
`user_id` is gone, the sole link to the individual; (b) linkability:
`latency_ms` and `client_meta` that could form a device fingerprint are
both nulled; (c) inference: `created_at` coarsened to month removes the
temporal precision that could combine with IP logs to re-identify. The
remaining fields (`item_id`, `qualite`, `difficulte`, `flag`, `weight`) are
content-only and not linkable to any individual within our data model. On
this basis the retained rows are no longer personal data within the meaning
of RGPD Article 4(1).

## Consequences

**Easier:**
- The Sheets-based campaign workflow is superseded; rating volume scales
  with the WordSparrow player base.
- `bliss-clue-ai`'s training pipeline reads ratings as a checked-in CSV, no
  manual aggregation step.
- New consumers of cross-context user events join via JetStream durable
  subscription (the same pattern game-api already uses).
- Anonymous participation maximises rating volume without compromising the
  RGPD posture (anonymous from inception).

**Harder:**
- One more Helm chart + CNPG cluster to operate. Mitigated by reusing the
  identity chart's shape verbatim.
- One more Postgres database backup target (covered by the existing CNPG
  backup pattern).
- A small amount of duplication of the §8.3 filter pipeline (filters 1–7
  ported to Kotlin from `bliss-clue-ai/scripts/pipeline/`). Filter 8
  (LLM-juge) stays offline.

**Pairwise comparison task type (added 2026-05-28, was deferred to v2):**
Pulled forward from v2 once binary GOOD/BAD ratings started bottoming out at "good" — as the model improves, more outputs land in the GOOD bucket and binary mode can't extract refinement signal between two acceptable clues. Pairwise also feeds DPO with cleaner preference pairs than the cross-product of same-mot good/bad ratings, and reduces rater fatigue (one click per pair vs two).

Design:
- New endpoints `GET /v1/items/pairs/next` and `POST /v1/ratings/pair`.
- Verdict enum: `LEFT_WINS` / `RIGHT_WINS` / `BOTH_GOOD` / `BOTH_BAD` / `SKIP`.
- Verdict routing keeps the absolute-quality stream clean:
  - `LEFT_WINS` / `RIGHT_WINS` → one row in a new `pair_ratings` table (preference-only; `CHECK (verdict IN ('left_wins', 'right_wins'))` enforces the invariant at the DB level).
  - `BOTH_GOOD` / `BOTH_BAD` → two rows in the existing `ratings` table (`qualite=5` or `qualite=1`). These are absolute judgments equivalent to two independent binary-mode ratings.
  - `SKIP` → no writes.
- DPO pair builder consumes `pair_ratings` directly (no cross-product needed).
- Binary RAFT pipeline continues unchanged on the `ratings` table.

**Future work (still deferred to v2):**
- Adaptive routing weights based on per-rater calibration agreement.
- Captcha for anon participants (only if abuse signals appear).
- Anon → auth attribution on sign-in.
- Disagreement triage UI for the maintainer.
- Cross-language extension (FR-specific today via the `pos`/`categorie`
  enums from `bliss-clue-ai/docs/style_guide.md §7`).
