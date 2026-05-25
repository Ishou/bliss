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

Data model: see the design spec at `docs/superpowers/specs/2026-05-25-survey-module-design.md`.

## Threat Model

The following threats were considered for the survey rating and corpus-
contribution surface.

**Spoofing (forged user identity)** — auth ratings rely on the existing
`__Host-ws_session` cookie verified by `identity-api`. The 30 s cache means
a stolen cookie is usable for at most 30 s beyond the identity-api
revocation; this is the same posture as `grid-api` and `game-api` and is
considered acceptable for non-admin player traffic. Anonymous ratings carry
no identity claim and therefore have nothing to spoof.

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
contributed corpus rows deleted. See design spec §10 for the WP216
anonymisation argument in full.

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

**Future work (explicitly deferred to v2):**
- Pairwise comparison task type.
- Adaptive routing weights based on per-rater calibration agreement.
- Captcha for anon participants (only if abuse signals appear).
- Anon → auth attribution on sign-in.
- Disagreement triage UI for the maintainer.
- Cross-language extension (FR-specific today via the `pos`/`categorie`
  enums from `bliss-clue-ai/docs/style_guide.md §7`).
