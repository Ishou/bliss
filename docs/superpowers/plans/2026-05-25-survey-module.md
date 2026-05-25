# Survey Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the `survey/` bounded context per [the approved design spec](../specs/2026-05-25-survey-module-design.md) — an auth-optional `/sondage` route that collects qualité/difficulté ratings (and proposed clue corrections from signed-in users) on candidate clues produced by `bliss-clue-ai`, with a §8.1-formatted CSV export consumed back into training.

**Architecture:** New top-level Kotlin bounded context peer to `grid/`, `game/`, `identity/`. Hexagonal layers (`domain/ application/ infrastructure/ api/ worker/`). Own CNPG Postgres cluster, own Helm chart, own ingress at `survey.wordsparrow.io`. Sessions optional via `__Host-ws_session` (identity-api verification with 30 s cache). NATS JetStream consumer `survey-api-user-deleted` performs RGPD-compliant anonymisation. Frontend gets `/sondage` (open) + `/compte` integration. Worker subcommands handle ingest/export/retire.

**Tech Stack:** Kotlin 2.3.21 + JDK 21 + Ktor 3.4 (REST), Postgres 18 via CNPG + Flyway 12.6, NATS JetStream (jnats 2.20.6), Helm/k3s, Vite + React 19 + TypeScript + Panda CSS + TanStack Router (existing frontend). Testing: JUnit5, Kotest property, Konsist, testcontainers, Vitest, Playwright, axe-core.

**Plan size:** ~9 PRs as documented in spec §12. Each PR aims to stay under the 400-line cap with the standing override available for chart-heavy PRs.

---

## File Structure (top-level inventory)

**Created**:

```
docs/adr/0056-survey-bounded-context.md
survey/
  domain/
    build.gradle.kts
    src/main/kotlin/com/bliss/survey/domain/
      model/{ItemId,RatingId,UserId,SurveyItem,Rating,Pos,Categorie,Style,Tier,FlagReason,Source,SubmittedAs}.kt
      routing/{KCoveragePolicy,TierWeights,StratifiedSampler}.kt
      calibration/CalibrationAgreement.kt
    src/test/kotlin/com/bliss/survey/domain/   # mirrors above, plus architecture/DomainArchitectureTest.kt
  application/
    build.gradle.kts
    src/main/kotlin/com/bliss/survey/application/
      ports/{SurveyItemRepository,RatingRepository,ProposedByRepository,UserProgressRepository,IdGenerator,Clock,RandomFactory}.kt
      usecases/{GetNextItemUseCase,SubmitRatingUseCase,IngestBatchUseCase,ExportDatasetUseCase,RetireSaturatedItemsUseCase,AnonymizeUserRatingsUseCase}.kt
      filters/{Filter1Typographiques,Filter2CaracteresInterdits,Filter3Longueur,Filter4StereotypesIa,Filter5AutoReference,Filter6LangueFr,Filter7Tautologie,FilterPipeline,FilterResult}.kt
      csv/{StyleGuideCsvParser,StyleGuideCsvWriter,RatingAggregator}.kt
    src/test/kotlin/...                          # mirrors above + architecture/
  infrastructure/
    build.gradle.kts
    src/main/kotlin/com/bliss/survey/infrastructure/
      persistence/{PgSurveyItemRepository,PgRatingRepository,PgProposedByRepository,PgUserProgressRepository,Datasource}.kt
      identity/{IdentityClient,CachedSessionVerifier}.kt
      nats/{UserDeletedConsumer,JsonEventDecoder}.kt
    src/main/resources/db/migration/{V1__survey_items.sql,V2__ratings.sql,V3__proposed_by.sql,V4__user_progress.sql}
    src/test/kotlin/...                          # uses testcontainers
  api/
    build.gradle.kts
    openapi.yaml
    src/main/kotlin/com/bliss/survey/api/
      Main.kt
      Module.kt
      Wiring.kt
      auth/SessionMiddleware.kt
      routes/{NextItemRoute,SubmitRatingRoute,MeProgressRoute,MeContributionsRoute,MePreferencesRoute,HealthRoute}.kt
      dto/{ItemDto,RatingRequest,RatingResponse,ProgressResponse,ContributionItemDto,PreferencesPatch,ProblemDetails}.kt
    src/test/kotlin/...                          # Ktor test engine
  worker/
    build.gradle.kts
    src/main/kotlin/com/bliss/survey/worker/Main.kt
    src/test/kotlin/...
  deploy/
    chart/{Chart.yaml,values.yaml,values-local.yaml,values-prod.yaml,templates/{_helpers.tpl,deployment.yaml,service.yaml,ingress.yaml,serviceaccount.yaml,configmap-calibration.yaml,job-seed-calibration.yaml,cronjob-retire.yaml}}
    db-chart/{Chart.yaml,values.yaml,values-prod.yaml,templates/{_helpers.tpl,cluster.yaml}}
frontend/src/
  ui/routes/sondage.lazy.tsx
  ui/routes/sondage.tsx
  application/survey/{client.ts,types.ts,localStorage.ts}
  ui/components/sondage/{RatingCard.tsx,Likert.tsx,FlagPicker.tsx,CorrectifField.tsx,SignInBanner.tsx}
  ui/components/compte/{MyContributions.tsx,SurveyPreferences.tsx}
```

**Modified**:

- `settings.gradle.kts` — register the 5 new modules.
- `frontend/src/ui/routes/__root.tsx` — add `/sondage` route.
- `frontend/src/ui/routes/compte.tsx` — embed new sections.
- `frontend/src/application/auth/index.ts` — expose `useOptionalAuth` if missing.
- `frontend/playwright.config.ts` — add `/sondage` to a11y coverage targets.
- `infra/nats/` — register the new durable consumer.
- `.github/workflows/ci.yml` — add `survey-export-csv-byteequal` job.
- `Makefile` — extend `deploy-local` to install survey charts.

---

## PR1 — ADR-0056 (survey bounded context)

**Goal:** Land the ADR with full threat model so PRs 2–9 have a referenceable design decision. Docs-only PR.

**Files:**
- Create: `docs/adr/0056-survey-bounded-context.md`

### Task 1.1: Write ADR-0056

- [ ] **Step 1: Create the ADR**

```bash
test -f docs/adr/0056-survey-bounded-context.md && echo "EXISTS — abort" || echo "OK to create"
```

Expected: `OK to create`

- [ ] **Step 2: Write the ADR body**

Create `docs/adr/0056-survey-bounded-context.md` with this content:

```markdown
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
```

- [ ] **Step 3: Verify markdown is well-formed**

Run: `npx --yes markdownlint-cli2 docs/adr/0056-survey-bounded-context.md 2>&1 | head -20`
Expected: no errors (or only project-level rule warnings consistent with neighbouring ADRs).

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0056-survey-bounded-context.md
git commit -s -m "$(cat <<'EOF'
docs(adr): add ADR-0056 for survey bounded context

ADR captures the decision to introduce a top-level `survey/` context for
the in-app clue-rating loop replacing the Sheets-based campaign. Includes
the full STRIDE threat model required by CLAUDE.md for auth/authz
changes; defers data-model and API specifics to the design spec at
docs/superpowers/specs/2026-05-25-survey-module-design.md.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Open PR**

```bash
git push -u origin HEAD:feat/survey-adr
gh pr create --title "feat(survey-docs): ADR-0056 — survey bounded context" --body "$(cat <<'EOF'
## Summary

- Adds ADR-0056 introducing the `survey/` top-level bounded context.
- Includes the STRIDE threat model required by CLAUDE.md for auth changes.
- Defers data model and API specifics to the design spec at `docs/superpowers/specs/2026-05-25-survey-module-design.md`.

## Test plan

- [x] markdownlint clean
- [ ] Maintainer review: threat model coverage, ADR style consistent with 0044/0045

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR2.

---

## PR2 — Schema-only: `survey/api/openapi.yaml`

**Goal:** Land the OpenAPI 3.1 contract. No implementation. Gates `openapi-lint`, `openapi-typescript-drift` (which will start failing once frontend regenerates types — that catch-up happens in PR8).

**Files:**
- Create: `survey/api/openapi.yaml`
- Modify: `settings.gradle.kts` (placeholder include for `:survey:api` — actually defer to PR6 to avoid an empty module).

Actually we hold off on `settings.gradle.kts` here — adding a module with no `build.gradle.kts` would break `./gradlew build`. The OpenAPI YAML lives at `survey/api/openapi.yaml` (path mirroring `grid/api/openapi.yaml`); the module wiring lands in PR6.

### Task 2.1: Create the OpenAPI 3.1 contract

- [ ] **Step 1: Create the directory**

```bash
mkdir -p survey/api
test -d survey/api && echo "OK"
```

Expected: `OK`

- [ ] **Step 2: Write `survey/api/openapi.yaml`**

Create `survey/api/openapi.yaml` with:

```yaml
openapi: 3.1.0
info:
  title: WordSparrow Survey API
  version: 0.1.0
  summary: Auth-optional rating + corpus-contribution surface for clue RLHF.
  description: |
    Backend for the `/sondage` route. Anonymous and authenticated visitors
    rate candidate clues (qualité, difficulté, optional flag). Authenticated
    visitors can additionally submit a `correctif` (alternative clue) which
    enters the corpus as an anonymous candidate.

    Auth resolution: the API attempts to read `__Host-ws_session` and
    verifies via identity-api (30 s cache, ADR-0044 pattern). Auth is
    optional on `/v1/items/*`; required on `/v1/me/*`. Anonymous callers
    receive 401 if they include a `correctif` (corpus contributions require
    sign-in).

    See ADR-0056 and `docs/superpowers/specs/2026-05-25-survey-module-design.md`.
  contact:
    name: Bliss maintainers
    url: https://github.com/Ishou/bliss
    email: noreply@bliss.example
  license:
    name: FSL-1.1-MIT
    url: https://github.com/Ishou/bliss/blob/main/LICENSE

servers:
  - url: https://survey.wordsparrow.io
    description: Production
  - url: http://localhost:7780
    description: Local k3d

tags:
  - name: items
    description: Rating-pool reads and rating submissions.
  - name: me
    description: Per-user progress, contributions, and preferences.

paths:
  /v1/items/next:
    get:
      operationId: getNextItem
      summary: Get the next unrated item for the caller.
      description: |
        Pulls one survey item the caller has not yet rated, applying tier-
        stratified weighted sampling and K-coverage prioritisation. Auth
        optional: signed-in callers are dedup'd server-side; anonymous
        callers may provide `excluded` to perform client-side dedup.
      tags: [items]
      parameters:
        - in: query
          name: excluded
          required: false
          description: Comma-separated list of `item_id`s the anonymous caller has already rated locally. Ignored when the caller is authenticated.
          schema:
            type: string
      responses:
        '200':
          description: Next item.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Item' }
        '204':
          description: Pool exhausted — no unrated items remain for this caller.
        '503':
          $ref: '#/components/responses/ProblemDetails'

  /v1/items/{itemId}/rating:
    post:
      operationId: submitRating
      summary: Submit a rating for an item.
      description: |
        Server assigns `submitted_as` based on session presence. For auth
        callers, the partial unique index on (item_id, user_id) makes this
        idempotent — a repeat submission returns 409 with the existing
        rating. For anon callers, every submit creates a new row (no
        server-side dedup). Anonymous callers including a `correctif` are
        rejected with 401.
      tags: [items]
      parameters:
        - in: path
          name: itemId
          required: true
          schema: { type: string, format: uuid }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RatingRequest' }
      responses:
        '201':
          description: Rating accepted.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RatingResponse' }
        '400': { $ref: '#/components/responses/ProblemDetails' }
        '401': { $ref: '#/components/responses/ProblemDetails' }
        '404': { $ref: '#/components/responses/ProblemDetails' }
        '409':
          description: Auth caller already rated this item.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RatingResponse' }
        '422':
          description: Correctif rejected by style-guide filters (1–7).
          content:
            application/json:
              schema: { $ref: '#/components/schemas/CorrectifRejection' }

  /v1/me/progress:
    get:
      operationId: getMyProgress
      summary: Caller's rating progress.
      tags: [me]
      security:
        - sessionCookie: []
      responses:
        '200':
          description: Progress snapshot.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProgressResponse' }
        '401': { $ref: '#/components/responses/ProblemDetails' }

  /v1/me/contributions:
    get:
      operationId: getMyContributions
      summary: Caller's proposed-clue contributions.
      tags: [me]
      security:
        - sessionCookie: []
      responses:
        '200':
          description: List of items the caller proposed.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/ContributionItem' }
        '401': { $ref: '#/components/responses/ProblemDetails' }

  /v1/me/preferences:
    patch:
      operationId: patchMyPreferences
      summary: Update caller's survey preferences.
      description: Currently only `delete_proposed_on_erasure`. Updates `proposed_by.opted_out` for all existing contributions and applies to future ones.
      tags: [me]
      security:
        - sessionCookie: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/PreferencesPatch' }
      responses:
        '204':
          description: Preferences updated.
        '400': { $ref: '#/components/responses/ProblemDetails' }
        '401': { $ref: '#/components/responses/ProblemDetails' }

  /v1/health:
    get:
      operationId: getHealth
      summary: Liveness + readiness.
      responses:
        '200': { description: OK }
        '503': { description: Database or NATS unavailable }

components:
  securitySchemes:
    sessionCookie:
      type: apiKey
      in: cookie
      name: __Host-ws_session

  parameters: {}

  schemas:
    Item:
      type: object
      required: [item_id, mot, definition, pos, categorie, style, force_claimed, longueur, tier, is_calibration]
      properties:
        item_id:        { type: string, format: uuid }
        mot:            { type: string }
        definition:     { type: string }
        pos:            { $ref: '#/components/schemas/Pos' }
        categorie:      { $ref: '#/components/schemas/Categorie' }
        style:          { $ref: '#/components/schemas/Style' }
        force_claimed:  { type: integer, minimum: 1, maximum: 5 }
        longueur:       { type: integer, minimum: 1 }
        tier:
          type: string
          enum: [high, mid, low, excluded]
        is_calibration: { type: boolean }

    Pos:
      type: string
      enum:
        - verbe_infinitif
        - verbe_conjugue
        - participe_passe
        - participe_present
        - nom_commun
        - nom_propre
        - adjectif
        - adverbe
        - interjection
        - mot_outil
        - sigle_abreviation
        - autre

    Categorie:
      type: string
      enum:
        # Group A — Sciences, measures, sky
        - chemical_symbols
        - units
        - celestial_objects
        - nombres
        - roman_numerals
        # Group B — Geography and places
        - cardinal_points
        - cities
        - countries
        - country_codes
        - geography
        # Group C — People and myths
        - first_names
        - titles
        - mythology
        # Group D — Language and utterance
        - abbreviations
        - etranger
        - expressions
        - grammar
        - interjections
        - orthographe
        # Group E — Life and perception
        - animals
        - body_parts
        - senses
        # Group F — Economy and institutions
        - currencies
        - organizations
        # Group G — Games and music
        - card_game
        - games
        - music_notes
        # Group H — Residual
        - autre
        # Macro-domain I — Daily material
        - aliments
        - vetements
        - mobilier_objet
        - outils
        - transports
        - materiaux
        # Macro-domain J — Human and social
        - professions
        - famille_relations
        - sentiments_etats
        # Macro-domain K — Nature
        - nature_paysage
        - flore
        - meteo_climat
        # Macro-domain L — Abstractions and arts
        - temps_duree
        - couleurs
        - arts

    Style:
      type: string
      enum:
        - definition_directe
        - periphrase
        - metonymie
        - fonction_role
        - calembour
        - culturel
        - cryptique
        - cryptique_morphologique
        - technique

    RatingRequest:
      type: object
      required: [qualite, difficulte, latency_ms]
      properties:
        qualite:    { type: integer, minimum: 1, maximum: 5 }
        difficulte: { type: integer, minimum: 1, maximum: 5 }
        flag:
          type: string
          enum: [hors_sujet, auto_reference, erreur_sens, autre]
          nullable: true
        correctif:
          type: object
          nullable: true
          description: Alternative clue text + claimed style. Authenticated callers only.
          required: [text, style]
          properties:
            text:   { type: string, minLength: 2, maxLength: 60 }
            style:  { $ref: '#/components/schemas/Style' }
        latency_ms: { type: integer, minimum: 0 }

    RatingResponse:
      type: object
      required: [rating_id, item_id, submitted_as]
      properties:
        rating_id:        { type: string, format: uuid }
        item_id:          { type: string, format: uuid }
        submitted_as:     { type: string, enum: [auth, anon] }
        proposed_item_id: { type: string, format: uuid, nullable: true }

    CorrectifRejection:
      type: object
      required: [type, title, status, filter_id, reason]
      properties:
        type:      { type: string, format: uri }
        title:     { type: string }
        status:    { type: integer }
        filter_id: { type: integer, minimum: 1, maximum: 7 }
        reason:    { type: string }

    ProgressResponse:
      type: object
      required: [items_rated]
      properties:
        items_rated:          { type: integer, minimum: 0 }
        calibration_agreement: { type: number, format: float, nullable: true, minimum: 0, maximum: 1 }
        last_rated_at:        { type: string, format: date-time, nullable: true }

    ContributionItem:
      type: object
      required: [item_id, mot, definition, pos, categorie, style, opted_out, k_coverage, created_at]
      properties:
        item_id:    { type: string, format: uuid }
        mot:        { type: string }
        definition: { type: string }
        pos:        { $ref: '#/components/schemas/Pos' }
        categorie:  { $ref: '#/components/schemas/Categorie' }
        style:      { $ref: '#/components/schemas/Style' }
        opted_out:  { type: boolean }
        k_coverage: { type: integer, minimum: 0 }
        created_at: { type: string, format: date-time }

    PreferencesPatch:
      type: object
      required: [delete_proposed_on_erasure]
      properties:
        delete_proposed_on_erasure: { type: boolean }

    ProblemDetails:
      type: object
      required: [type, title, status]
      properties:
        type:   { type: string, format: uri }
        title:  { type: string }
        status: { type: integer }
        detail: { type: string }

  responses:
    ProblemDetails:
      description: RFC 7807 problem details.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/ProblemDetails' }
```

- [ ] **Step 3: Lint the OpenAPI**

Run: `npx --yes @stoplight/spectral-cli@6 lint survey/api/openapi.yaml`
Expected: no errors. Warnings on missing example/example-summary are acceptable and consistent with `identity/api/openapi.yaml`.

- [ ] **Step 4: Commit**

```bash
git add survey/api/openapi.yaml
git commit -s -m "$(cat <<'EOF'
feat(survey-api): schema-only PR — OpenAPI 3.1 contract

Defines the full survey API surface: GET /v1/items/next, POST /v1/items/
{itemId}/rating, GET /v1/me/progress, GET /v1/me/contributions, PATCH
/v1/me/preferences, GET /v1/health. Auth optional on /v1/items/*,
required on /v1/me/*. Includes the 12 POS values, 43 catégorie values, 9
style values from bliss-clue-ai/docs/style_guide.md §4 + §7. No
implementation — ADR-0056 (merged) is the design referent.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Open PR**

```bash
git push -u origin HEAD:feat/survey-openapi
gh pr create --title "feat(survey-api): OpenAPI 3.1 schema-only contract" --body "$(cat <<'EOF'
## Summary

- Defines `survey/api/openapi.yaml` per ADR-0056 and the design spec.
- Auth is **optional** on `/v1/items/*` and **required** on `/v1/me/*`.
- Includes the §4/§7 enums verbatim from `bliss-clue-ai/docs/style_guide.md`.

## Test plan

- [x] `spectral lint survey/api/openapi.yaml` clean
- [ ] CI: `openapi-lint` job green
- [ ] Note: `openapi-typescript-drift` may start failing until PR8 regenerates frontend types — acceptable per schema-first workflow

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR3.

---

## PR3 — `survey/domain`

**Goal:** Land the domain layer via strict TDD (failing test first, then minimal impl). No framework deps. Mutation-coverage target on invariants.

**Files:**
- Modify: `settings.gradle.kts` — add `include(":survey:domain")`
- Create: `survey/domain/build.gradle.kts`
- Create: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/*.kt`
- Create: `survey/domain/src/main/kotlin/com/bliss/survey/domain/routing/*.kt`
- Create: `survey/domain/src/main/kotlin/com/bliss/survey/domain/calibration/*.kt`
- Create: `survey/domain/src/test/kotlin/com/bliss/survey/domain/**/*Test.kt`

### Task 3.1: Wire `:survey:domain` into Gradle

- [ ] **Step 1: Add the include**

Edit `settings.gradle.kts` to append a new `include`:

```kotlin
include(":identity:domain")
include(":identity:application")
include(":identity:infrastructure")
include(":identity:api")
include(":survey:domain")        // <-- add this line
```

- [ ] **Step 2: Create the build file**

Create `survey/domain/build.gradle.kts` with:

```kotlin
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Verify Gradle resolves the module**

Run: `./gradlew :survey:domain:compileKotlin`
Expected: PASS with no sources yet (empty module compiles).

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts survey/domain/build.gradle.kts
git commit -s -m "chore(survey-domain): scaffold empty :survey:domain module"
```

### Task 3.2: Konsist architecture test (failing first)

- [ ] **Step 1: Write the failing arch test**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/architecture/DomainArchitectureTest.kt`:

```kotlin
package com.bliss.survey.domain.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class DomainArchitectureTest {
    private val domainScope = Konsist.scopeFromModule("survey/domain")

    @Test
    fun `domain has no infrastructure imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.contains("infrastructure") }
        }
    }

    @Test
    fun `domain has no application imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.survey.application") }
        }
    }

    @Test
    fun `domain has no framework imports`() {
        val forbiddenPrefixes =
            listOf(
                "org.springframework",
                "jakarta.",
                "javax.",
                "io.ktor",
                "org.http4k",
                "org.jetbrains.exposed",
                "io.micronaut",
            )
        domainScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `domain has no cross-context imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.grid") ||
                    import.name.startsWith("com.bliss.game") ||
                    import.name.startsWith("com.bliss.identity")
            }
        }
    }
}
```

- [ ] **Step 2: Run — should PASS (no files yet, so no violations)**

Run: `./gradlew :survey:domain:test`
Expected: PASS. Konsist with no source files trivially passes assertFalse.

- [ ] **Step 3: Commit**

```bash
git add survey/domain/src/test/kotlin/com/bliss/survey/domain/architecture/DomainArchitectureTest.kt
git commit -s -m "test(survey-domain): Konsist guards (no framework/cross-context imports)"
```

### Task 3.3: Value-class ID types (TDD)

- [ ] **Step 1: Write the failing test**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/model/IdsTest.kt`:

```kotlin
package com.bliss.survey.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.util.UUID

class IdsTest {
    @Test
    fun `ItemId wraps a UUID`() {
        val u = UUID.fromString("01234567-89ab-7cde-89ab-0123456789ab")
        assertThat(ItemId(u).value).isEqualTo(u)
    }

    @Test
    fun `RatingId wraps a UUID`() {
        val u = UUID.randomUUID()
        assertThat(RatingId(u).value).isEqualTo(u)
    }

    @Test
    fun `UserId wraps a UUID`() {
        val u = UUID.randomUUID()
        assertThat(UserId(u).value).isEqualTo(u)
    }
}
```

- [ ] **Step 2: Run — should FAIL (types not defined)**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.IdsTest`
Expected: FAIL with `unresolved reference: ItemId` (etc.).

- [ ] **Step 3: Write the minimal implementation**

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Ids.kt`:

```kotlin
package com.bliss.survey.domain.model

import java.util.UUID

@JvmInline value class ItemId(val value: UUID)
@JvmInline value class RatingId(val value: UUID)
@JvmInline value class UserId(val value: UUID)
```

- [ ] **Step 4: Run — should PASS**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.IdsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Ids.kt survey/domain/src/test/kotlin/com/bliss/survey/domain/model/IdsTest.kt
git commit -s -m "feat(survey-domain): value-class ids (ItemId, RatingId, UserId)"
```

### Task 3.4: Enum types (Pos, Categorie, Style, Tier, FlagReason, Source, SubmittedAs)

- [ ] **Step 1: Write the failing enum test**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/model/EnumsTest.kt`:

```kotlin
package com.bliss.survey.domain.model

import assertk.assertThat
import assertk.assertions.hasSize
import org.junit.jupiter.api.Test

class EnumsTest {
    @Test fun `Pos has 12 values (§7-1)`()                        { assertThat(Pos.values().toList()).hasSize(12) }
    @Test fun `Categorie has 43 values (§7-2)`()                 { assertThat(Categorie.values().toList()).hasSize(43) }
    @Test fun `Style has 9 values (§4)`()                         { assertThat(Style.values().toList()).hasSize(9) }
    @Test fun `Tier has 4 values`()                               { assertThat(Tier.values().toList()).hasSize(4) }
    @Test fun `FlagReason has 4 values`()                         { assertThat(FlagReason.values().toList()).hasSize(4) }
    @Test fun `Source has 5 values`()                             { assertThat(Source.values().toList()).hasSize(5) }
    @Test fun `SubmittedAs has 2 values`()                        { assertThat(SubmittedAs.values().toList()).hasSize(2) }
}
```

- [ ] **Step 2: Run — should FAIL (enums not defined)**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.EnumsTest`
Expected: FAIL.

- [ ] **Step 3: Write the enum files**

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Pos.kt`:

```kotlin
package com.bliss.survey.domain.model

enum class Pos {
    VERBE_INFINITIF, VERBE_CONJUGUE,
    PARTICIPE_PASSE, PARTICIPE_PRESENT,
    NOM_COMMUN, NOM_PROPRE,
    ADJECTIF, ADVERBE, INTERJECTION,
    MOT_OUTIL, SIGLE_ABREVIATION, AUTRE
}
```

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Categorie.kt`:

```kotlin
package com.bliss.survey.domain.model

enum class Categorie {
    // Group A — Sciences, measures, sky (5)
    CHEMICAL_SYMBOLS, UNITS, CELESTIAL_OBJECTS, NOMBRES, ROMAN_NUMERALS,
    // Group B — Geography and places (5)
    CARDINAL_POINTS, CITIES, COUNTRIES, COUNTRY_CODES, GEOGRAPHY,
    // Group C — People and myths (3)
    FIRST_NAMES, TITLES, MYTHOLOGY,
    // Group D — Language and utterance (6)
    ABBREVIATIONS, ETRANGER, EXPRESSIONS, GRAMMAR, INTERJECTIONS, ORTHOGRAPHE,
    // Group E — Life and perception (3)
    ANIMALS, BODY_PARTS, SENSES,
    // Group F — Economy and institutions (2)
    CURRENCIES, ORGANIZATIONS,
    // Group G — Games and music (3)
    CARD_GAME, GAMES, MUSIC_NOTES,
    // Group H — Residual (1)
    AUTRE,
    // Macro I — Daily material (6)
    ALIMENTS, VETEMENTS, MOBILIER_OBJET, OUTILS, TRANSPORTS, MATERIAUX,
    // Macro J — Human and social (3)
    PROFESSIONS, FAMILLE_RELATIONS, SENTIMENTS_ETATS,
    // Macro K — Nature (3)
    NATURE_PAYSAGE, FLORE, METEO_CLIMAT,
    // Macro L — Abstractions and arts (3)
    TEMPS_DUREE, COULEURS, ARTS
}
```

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Style.kt`:

```kotlin
package com.bliss.survey.domain.model

enum class Style {
    DEFINITION_DIRECTE, PERIPHRASE, METONYMIE, FONCTION_ROLE,
    CALEMBOUR, CULTUREL, CRYPTIQUE, CRYPTIQUE_MORPHOLOGIQUE, TECHNIQUE
}
```

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Tier.kt`:

```kotlin
package com.bliss.survey.domain.model

enum class Tier { HIGH, MID, LOW, EXCLUDED }
```

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/FlagReason.kt`:

```kotlin
package com.bliss.survey.domain.model

enum class FlagReason { HORS_SUJET, AUTO_REFERENCE, ERREUR_SENS, AUTRE }
```

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Source.kt`:

```kotlin
package com.bliss.survey.domain.model

enum class Source { GOLD, CURATED_V1, SYNTHETIC_V1, MANUAL, RATER_PROPOSED }
```

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SubmittedAs.kt`:

```kotlin
package com.bliss.survey.domain.model

enum class SubmittedAs { AUTH, ANON }
```

- [ ] **Step 4: Run — should PASS**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.EnumsTest`
Expected: PASS, all 7 cardinality assertions green.

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/model/{Pos,Categorie,Style,Tier,FlagReason,Source,SubmittedAs}.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/model/EnumsTest.kt
git commit -s -m "feat(survey-domain): the 5 axes — Pos, Categorie, Style, Tier, plus FlagReason, Source, SubmittedAs"
```

### Task 3.5: `SurveyItem` data class with init invariants

- [ ] **Step 1: Write the failing test**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/model/SurveyItemTest.kt`:

```kotlin
package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasClass
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SurveyItemTest {
    private fun base() = SurveyItem(
        id = ItemId(UUID.randomUUID()),
        mot = "POULE",
        definition = "Femelle du coq",
        pos = Pos.NOM_COMMUN,
        categorie = Categorie.ANIMALS,
        style = Style.PERIPHRASE,
        forceClaimed = 2,
        longueur = 5,
        source = Source.SYNTHETIC_V1,
        sourceBatch = "iter18_20260525",
        tier = Tier.MID,
        isCalibration = false,
        expected = null,
        retiredAt = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `valid item constructs`() {
        base()  // throws if invariant fails
    }

    @Test
    fun `force_claimed must be in 1-5`() {
        assertFailure { base().copy(forceClaimed = 0) }
            .hasClass(IllegalArgumentException::class)
            .messageContains("force")
        assertFailure { base().copy(forceClaimed = 6) }
            .hasClass(IllegalArgumentException::class)
    }

    @Test
    fun `mot must not be blank`() {
        assertFailure { base().copy(mot = "  ") }
            .hasClass(IllegalArgumentException::class)
            .messageContains("mot")
    }

    @Test
    fun `definition must not be blank`() {
        assertFailure { base().copy(definition = "") }
            .hasClass(IllegalArgumentException::class)
            .messageContains("definition")
    }

    @Test
    fun `longueur must be positive`() {
        assertFailure { base().copy(longueur = 0) }
            .hasClass(IllegalArgumentException::class)
    }
}
```

- [ ] **Step 2: Run — should FAIL**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.SurveyItemTest`
Expected: FAIL with `unresolved reference: SurveyItem`.

- [ ] **Step 3: Write the implementation**

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyItem.kt`:

```kotlin
package com.bliss.survey.domain.model

import java.time.Instant

data class SurveyItem(
    val id: ItemId,
    val mot: String,
    val definition: String,
    val pos: Pos,
    val categorie: Categorie,
    val style: Style,
    val forceClaimed: Int,
    val longueur: Int,
    val source: Source,
    val sourceBatch: String,
    val tier: Tier,
    val isCalibration: Boolean,
    val expected: CalibrationAnswer?,
    val retiredAt: Instant?,
    val createdAt: Instant,
) {
    init {
        require(mot.isNotBlank()) { "mot must not be blank" }
        require(definition.isNotBlank()) { "definition must not be blank" }
        require(forceClaimed in 1..5) { "force_claimed must be in 1..5 (was $forceClaimed)" }
        require(longueur > 0) { "longueur must be positive (was $longueur)" }
        require(sourceBatch.isNotBlank()) { "source_batch must not be blank" }
    }
}

data class CalibrationAnswer(
    val expectedQualiteMin: Int,
    val expectedQualiteMax: Int,
    val expectedDifficulteMin: Int,
    val expectedDifficulteMax: Int,
) {
    init {
        require(expectedQualiteMin in 1..5 && expectedQualiteMax in 1..5 && expectedQualiteMin <= expectedQualiteMax)
        require(expectedDifficulteMin in 1..5 && expectedDifficulteMax in 1..5 && expectedDifficulteMin <= expectedDifficulteMax)
    }
}
```

- [ ] **Step 4: Run — should PASS**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.SurveyItemTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyItem.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/model/SurveyItemTest.kt
git commit -s -m "feat(survey-domain): SurveyItem with init invariants + CalibrationAnswer"
```

### Task 3.6: `Rating` data class with anon invariants

- [ ] **Step 1: Write the failing test**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/model/RatingTest.kt`:

```kotlin
package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertions.hasClass
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RatingTest {
    private val itemId = ItemId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val now = Instant.now()

    @Test
    fun `auth rating with user id is valid`() {
        Rating(
            id = RatingId(UUID.randomUUID()),
            itemId = itemId, userId = userId,
            submittedAs = SubmittedAs.AUTH,
            qualite = 3, difficulte = 3,
            flag = null, proposedItemId = null,
            latencyMs = 1000, createdAt = now,
        )
    }

    @Test
    fun `anon rating must have null user id`() {
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId, userId = userId,  // <-- not null
                submittedAs = SubmittedAs.ANON,
                qualite = 3, difficulte = 3,
                flag = null, proposedItemId = null,
                latencyMs = 1000, createdAt = now,
            )
        }.hasClass(IllegalArgumentException::class).messageContains("anon")
    }

    @Test
    fun `anon rating must have null proposed item id`() {
        val proposed = ItemId(UUID.randomUUID())
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId, userId = null,
                submittedAs = SubmittedAs.ANON,
                qualite = 3, difficulte = 3,
                flag = null, proposedItemId = proposed,
                latencyMs = 1000, createdAt = now,
            )
        }.hasClass(IllegalArgumentException::class).messageContains("anon")
    }

    @Test
    fun `qualite must be in 1-5`() {
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId, userId = userId,
                submittedAs = SubmittedAs.AUTH,
                qualite = 0, difficulte = 3,
                flag = null, proposedItemId = null,
                latencyMs = 1000, createdAt = now,
            )
        }.hasClass(IllegalArgumentException::class)
    }

    @Test
    fun `difficulte must be in 1-5`() {
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId, userId = userId,
                submittedAs = SubmittedAs.AUTH,
                qualite = 3, difficulte = 9,
                flag = null, proposedItemId = null,
                latencyMs = 1000, createdAt = now,
            )
        }.hasClass(IllegalArgumentException::class)
    }
}
```

- [ ] **Step 2: Run — should FAIL**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.RatingTest`
Expected: FAIL with `unresolved reference: Rating`.

- [ ] **Step 3: Write the implementation**

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Rating.kt`:

```kotlin
package com.bliss.survey.domain.model

import java.time.Instant

data class Rating(
    val id: RatingId,
    val itemId: ItemId,
    val userId: UserId?,
    val submittedAs: SubmittedAs,
    val qualite: Int,
    val difficulte: Int,
    val flag: FlagReason?,
    val proposedItemId: ItemId?,
    val latencyMs: Int?,
    val createdAt: Instant,
) {
    init {
        require(qualite in 1..5) { "qualite must be in 1..5 (was $qualite)" }
        require(difficulte in 1..5) { "difficulte must be in 1..5 (was $difficulte)" }
        if (submittedAs == SubmittedAs.ANON) {
            require(userId == null) { "anon rating must have null user_id" }
            require(proposedItemId == null) { "anon rating must have null proposed_item_id (contributions require auth)" }
        }
    }
}
```

- [ ] **Step 4: Run — should PASS**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.model.RatingTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Rating.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/model/RatingTest.kt
git commit -s -m "feat(survey-domain): Rating with anon and value-range invariants"
```

### Task 3.7: `TierWeights` value object + `KCoveragePolicy`

- [ ] **Step 1: Write failing tests**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/routing/TierWeightsTest.kt`:

```kotlin
package com.bliss.survey.domain.routing

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isCloseTo
import com.bliss.survey.domain.model.Tier
import org.junit.jupiter.api.Test

class TierWeightsTest {
    @Test
    fun `defaults sum to one`() {
        val w = TierWeights.DEFAULT
        val sum = w.weights.values.sum()
        assertThat(sum.toDouble()).isCloseTo(1.0, 0.0001)
    }

    @Test
    fun `weights must be non-negative`() {
        assertFailure { TierWeights(mapOf(Tier.HIGH to -0.1, Tier.MID to 1.1, Tier.LOW to 0.0, Tier.EXCLUDED to 0.0)) }
    }

    @Test
    fun `weights must cover all four tiers`() {
        assertFailure { TierWeights(mapOf(Tier.HIGH to 0.5, Tier.MID to 0.5)) }
    }
}
```

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/routing/KCoveragePolicyTest.kt`:

```kotlin
package com.bliss.survey.domain.routing

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.bliss.survey.domain.model.Tier
import org.junit.jupiter.api.Test

class KCoveragePolicyTest {
    private val policy = KCoveragePolicy(targetK = mapOf(
        Tier.HIGH to 3, Tier.MID to 3, Tier.LOW to 3, Tier.EXCLUDED to 2
    ))

    @Test fun `item below target K is not retired`() {
        assertThat(policy.shouldRetire(Tier.MID, ratingCount = 2)).isFalse()
    }

    @Test fun `item at target K is retired`() {
        assertThat(policy.shouldRetire(Tier.MID, ratingCount = 3)).isTrue()
    }

    @Test fun `item past target K stays retired (idempotence)`() {
        assertThat(policy.shouldRetire(Tier.MID, ratingCount = 4)).isTrue()
    }

    @Test fun `excluded tier uses its own threshold`() {
        assertThat(policy.shouldRetire(Tier.EXCLUDED, ratingCount = 2)).isTrue()
        assertThat(policy.shouldRetire(Tier.EXCLUDED, ratingCount = 1)).isFalse()
    }
}
```

- [ ] **Step 2: Run — should FAIL**

Run: `./gradlew :survey:domain:test --tests "com.bliss.survey.domain.routing.*"`
Expected: FAIL.

- [ ] **Step 3: Write implementations**

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/routing/TierWeights.kt`:

```kotlin
package com.bliss.survey.domain.routing

import com.bliss.survey.domain.model.Tier

data class TierWeights(val weights: Map<Tier, Double>) {
    init {
        require(weights.keys == Tier.values().toSet()) {
            "weights must cover all 4 tiers (missing: ${Tier.values().toSet() - weights.keys})"
        }
        require(weights.values.all { it >= 0.0 }) { "weights must be non-negative" }
        val sum = weights.values.sum()
        require(sum > 0.0) { "weights must have positive sum" }
    }

    companion object {
        val DEFAULT = TierWeights(mapOf(
            Tier.HIGH     to 0.20,
            Tier.MID      to 0.55,
            Tier.LOW      to 0.15,
            Tier.EXCLUDED to 0.10,
        ))
    }
}
```

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/routing/KCoveragePolicy.kt`:

```kotlin
package com.bliss.survey.domain.routing

import com.bliss.survey.domain.model.Tier

data class KCoveragePolicy(val targetK: Map<Tier, Int>) {
    init {
        require(targetK.keys == Tier.values().toSet()) { "targetK must cover all 4 tiers" }
        require(targetK.values.all { it >= 1 }) { "targetK values must be ≥ 1" }
    }

    fun shouldRetire(tier: Tier, ratingCount: Int): Boolean =
        ratingCount >= (targetK[tier] ?: error("missing tier $tier"))

    companion object {
        val DEFAULT = KCoveragePolicy(mapOf(
            Tier.HIGH to 3, Tier.MID to 3, Tier.LOW to 3, Tier.EXCLUDED to 2
        ))
    }
}
```

- [ ] **Step 4: Run — should PASS**

Run: `./gradlew :survey:domain:test --tests "com.bliss.survey.domain.routing.*"`
Expected: PASS (both test classes green).

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/routing/{TierWeights,KCoveragePolicy}.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/routing/{TierWeightsTest,KCoveragePolicyTest}.kt
git commit -s -m "feat(survey-domain): TierWeights + KCoveragePolicy with retirement idempotence"
```

### Task 3.8: `StratifiedSampler` with property-based distribution test

- [ ] **Step 1: Write the failing property test**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/routing/StratifiedSamplerTest.kt`:

```kotlin
package com.bliss.survey.domain.routing

import assertk.assertThat
import assertk.assertions.isCloseTo
import com.bliss.survey.domain.model.Tier
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.next
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StratifiedSamplerTest {
    @Test
    fun `defaults — distribution converges to weights over 10k draws (ε = 0_02)`() {
        val weights = TierWeights.DEFAULT
        val sampler = StratifiedSampler(weights)
        val rng = Random(42L)  // deterministic seed

        val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
        val n = 10_000
        repeat(n) {
            val tier = sampler.pickTier(rng)
            counts[tier] = counts.getValue(tier) + 1
        }

        for ((tier, expected) in weights.weights) {
            val observed = counts.getValue(tier).toDouble() / n
            assertThat(observed, "tier=$tier observed=$observed expected=$expected").isCloseTo(expected, 0.02)
        }
    }

    @Test
    fun `randomised weights — distribution converges`() {
        val weightsArb = Arb.bind(
            Arb.double(0.1, 1.0), Arb.double(0.1, 1.0), Arb.double(0.1, 1.0), Arb.double(0.1, 1.0)
        ) { h, m, l, e ->
            val sum = h + m + l + e
            TierWeights(mapOf(
                Tier.HIGH to h / sum, Tier.MID to m / sum, Tier.LOW to l / sum, Tier.EXCLUDED to e / sum
            ))
        }
        val rng = Random(123L)
        repeat(10) {
            val w = weightsArb.next(io.kotest.property.RandomSource.seeded(it.toLong()))
            val sampler = StratifiedSampler(w)
            val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
            val n = 20_000
            repeat(n) {
                val tier = sampler.pickTier(rng)
                counts[tier] = counts.getValue(tier) + 1
            }
            for ((tier, expected) in w.weights) {
                val observed = counts.getValue(tier).toDouble() / n
                assertThat(observed, "weights=$w tier=$tier observed=$observed expected=$expected").isCloseTo(expected, 0.025)
            }
        }
    }
}
```

- [ ] **Step 2: Run — should FAIL**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.routing.StratifiedSamplerTest`
Expected: FAIL with `unresolved reference: StratifiedSampler`.

- [ ] **Step 3: Write the implementation**

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/routing/StratifiedSampler.kt`:

```kotlin
package com.bliss.survey.domain.routing

import com.bliss.survey.domain.model.Tier
import kotlin.random.Random

/**
 * Picks a Tier per call using the configured weights. The sampler is stateless;
 * the caller controls determinism via the supplied Random.
 *
 * Implementation: cumulative-weight binary search. O(log n) per draw where
 * n is the number of tiers (4 here, so effectively constant). The Tier order
 * is sorted alphabetically for stable enumeration in tests.
 */
class StratifiedSampler(weights: TierWeights) {
    private data class Bucket(val tier: Tier, val cumulative: Double)

    private val buckets: List<Bucket>
    private val total: Double

    init {
        val sorted = weights.weights.entries.sortedBy { it.key.name }
        var running = 0.0
        buckets = sorted.map { (tier, w) ->
            running += w
            Bucket(tier, running)
        }
        total = running
    }

    fun pickTier(rng: Random): Tier {
        val r = rng.nextDouble() * total
        // small N; linear scan beats binary search overhead in practice
        for (b in buckets) if (r < b.cumulative) return b.tier
        return buckets.last().tier
    }
}
```

- [ ] **Step 4: Run — should PASS**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.routing.StratifiedSamplerTest`
Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/routing/StratifiedSampler.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/routing/StratifiedSamplerTest.kt
git commit -s -m "feat(survey-domain): StratifiedSampler with PBT for distribution convergence"
```

### Task 3.9: `CalibrationAgreement` with monotonicity property

- [ ] **Step 1: Write the failing property test**

Create `survey/domain/src/test/kotlin/com/bliss/survey/domain/calibration/CalibrationAgreementTest.kt`:

```kotlin
package com.bliss.survey.domain.calibration

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CalibrationAgreementTest {
    @Test
    fun `empty window yields null agreement`() {
        assertThat(CalibrationAgreement.rollingAgreement(emptyList(), windowSize = 20)).isEqualTo(null)
    }

    @Test
    fun `all-agree window yields 1_0`() {
        val results = List(20) { true }
        assertThat(CalibrationAgreement.rollingAgreement(results, 20)).isEqualTo(1.0)
    }

    @Test
    fun `all-disagree window yields 0_0`() {
        val results = List(20) { false }
        assertThat(CalibrationAgreement.rollingAgreement(results, 20)).isEqualTo(0.0)
    }

    @Test
    fun `monotonicity — adding a matching rating never decreases agreement`() = runTest {
        checkAll(Arb.list(Arb.boolean(), 1..50)) { history ->
            val before = CalibrationAgreement.rollingAgreement(history, 20) ?: 0.0
            val after = CalibrationAgreement.rollingAgreement(history + true, 20) ?: 0.0
            assertThat(after, "before=$before after=$after history=$history").isGreaterThanOrEqualTo(before)
        }
    }

    @Test
    fun `monotonicity — adding a non-matching rating never increases agreement`() = runTest {
        checkAll(Arb.list(Arb.boolean(), 1..50)) { history ->
            val before = CalibrationAgreement.rollingAgreement(history, 20) ?: 1.0
            val after = CalibrationAgreement.rollingAgreement(history + false, 20) ?: 1.0
            assertThat(after, "before=$before after=$after history=$history").isLessThanOrEqualTo(before)
        }
    }
}
```

> **Note** the monotonicity properties assume `windowSize` is large enough that adding one element doesn't fall off the front when the history is shorter than the window. The PBTs cap history at 50, so for the +1 case we may exceed 20 — that's the case the property must still hold, which it does because the trailing window slides one element at a time and the *new* element is the dominant force. The arrangement is right; the impl needs to handle it.

- [ ] **Step 2: Run — should FAIL**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.calibration.CalibrationAgreementTest`
Expected: FAIL.

- [ ] **Step 3: Write the implementation**

Create `survey/domain/src/main/kotlin/com/bliss/survey/domain/calibration/CalibrationAgreement.kt`:

```kotlin
package com.bliss.survey.domain.calibration

/**
 * Stateless helper for computing the rolling agreement rate of a user against
 * calibration-item answers. Caller provides chronological history of booleans
 * (true = matched expected, false = did not). Returns the rate over the last
 * `windowSize` entries, or null when history is empty.
 *
 * Monotonicity invariants enforced via PBT (see CalibrationAgreementTest):
 *   - Adding a matching result never decreases agreement.
 *   - Adding a non-matching result never increases agreement.
 *
 * Proof sketch: the windowed mean over a sliding window of width W moves by
 * at most (newElement - droppedElement)/W ; since the new element is
 * {0, 1} and the dropped is {0, 1}, the change has the sign of
 * (new - dropped) which has the same sign as (new - mean) when |old window|
 * < W (no drop happens). When |old window| ≥ W, the dropped element is
 * the oldest; the property still holds because the new element's
 * contribution dominates over a single step.
 */
object CalibrationAgreement {
    fun rollingAgreement(history: List<Boolean>, windowSize: Int): Double? {
        require(windowSize >= 1) { "windowSize must be ≥ 1" }
        if (history.isEmpty()) return null
        val tail = if (history.size <= windowSize) history else history.takeLast(windowSize)
        return tail.count { it }.toDouble() / tail.size
    }
}
```

- [ ] **Step 4: Run — should PASS**

Run: `./gradlew :survey:domain:test --tests com.bliss.survey.domain.calibration.CalibrationAgreementTest`
Expected: PASS, all 5 tests green including both PBTs.

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/calibration/CalibrationAgreement.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/calibration/CalibrationAgreementTest.kt
git commit -s -m "feat(survey-domain): CalibrationAgreement with PBT-verified monotonicity"
```

### Task 3.10: Final domain test sweep + PR

- [ ] **Step 1: Run the whole module**

Run: `./gradlew :survey:domain:check`
Expected: PASS (Konsist + all unit tests).

- [ ] **Step 2: Run Spotless**

Run: `./gradlew :survey:domain:spotlessApply`
Expected: re-formats any drift; succeeds.

- [ ] **Step 3: Verify Spotless is now clean**

Run: `./gradlew :survey:domain:spotlessCheck`
Expected: PASS.

- [ ] **Step 4: Run full build to confirm no broken neighbours**

Run: `./gradlew build --parallel`
Expected: PASS overall.

- [ ] **Step 5: Push and open PR**

```bash
git push -u origin HEAD:feat/survey-domain
gh pr create --title "feat(survey-domain): domain layer — types, invariants, sampler, calibration" --body "$(cat <<'EOF'
## Summary

- `:survey:domain` module per ADR-0056 and the design spec.
- TDD throughout: failing test → minimal impl, one commit each.
- 5-axis enums (Pos/Categorie/Style + Tier/FlagReason/Source/SubmittedAs) sourced verbatim from `bliss-clue-ai/docs/style_guide.md` §4 + §7.
- Init invariants on `SurveyItem` and `Rating` (anon ratings reject user_id and proposed_item_id).
- `StratifiedSampler` + `KCoveragePolicy` for the routing algorithm in spec §5.
- `CalibrationAgreement` with PBT-verified monotonicity invariants.

## Test plan

- [x] `:survey:domain:check` green
- [x] PBT: stratified sampler converges within ε=0.02 over 10k draws (deterministic seed)
- [x] PBT: calibration agreement is monotone in matching/non-matching adds
- [x] Konsist arch test guards: no infrastructure/framework/cross-context imports
- [x] Spotless clean
- [ ] CI green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR4.

---

## PR4 — `survey/application`

**Goal:** Land use cases, ports, the filters-1-through-7 pipeline ported from `bliss-clue-ai`, and the §8.1 CSV parser/writer. No infrastructure adapters yet (those land in PR5).

> **Size note:** This PR is likely to exceed the 400-line cap because the filter port adds ~300 lines on its own. Invoke the standing cap-override in the PR body with the justification *"filters port + use cases + CSV codec form one coherent application layer that can't ship usefully without all three pieces; splitting would create three PRs each blocked on the next."*

**Files:**
- Modify: `settings.gradle.kts` — add `include(":survey:application")`
- Create: `survey/application/build.gradle.kts`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/*.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/*.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/filters/*.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/csv/*.kt`
- Create: `survey/application/src/test/kotlin/...`

### Task 4.1: Wire `:survey:application` into Gradle

- [ ] **Step 1: Add include**

Edit `settings.gradle.kts`:

```kotlin
include(":survey:domain")
include(":survey:application")   // <-- add
```

- [ ] **Step 2: Create build file**

Create `survey/application/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":survey:domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :survey:application:compileKotlin`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts survey/application/build.gradle.kts
git commit -s -m "chore(survey-application): scaffold empty :survey:application module"
```

### Task 4.2: Konsist architecture test for application

- [ ] **Step 1: Write the arch test**

Create `survey/application/src/test/kotlin/com/bliss/survey/application/architecture/ApplicationArchitectureTest.kt`:

```kotlin
package com.bliss.survey.application.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApplicationArchitectureTest {
    private val appScope = Konsist.scopeFromModule("survey/application")

    @Test fun `application has no infrastructure imports`() {
        appScope.files.assertFalse { it.hasImport { import -> import.name.contains(".infrastructure.") } }
    }

    @Test fun `application has no framework imports`() {
        val forbidden = listOf("io.ktor", "org.springframework", "jakarta.", "javax.")
        appScope.files.assertFalse { it.hasImport { import -> forbidden.any { p -> import.name.startsWith(p) } } }
    }

    @Test fun `application has no cross-context imports`() {
        appScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.grid") ||
                    import.name.startsWith("com.bliss.game") ||
                    import.name.startsWith("com.bliss.identity")
            }
        }
    }
}
```

- [ ] **Step 2: Run, commit**

```bash
./gradlew :survey:application:test --tests "*ApplicationArchitectureTest"
git add survey/application/src/test/kotlin/com/bliss/survey/application/architecture/ApplicationArchitectureTest.kt
git commit -s -m "test(survey-application): Konsist arch guards"
```

### Task 4.3: Ports (interfaces only — TDD doesn't apply to pure interfaces)

- [ ] **Step 1: Write all port interfaces in one commit**

Create the following files. Each is small and interface-only; they exist to be implemented by the infrastructure layer in PR5.

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/Clock.kt`:

```kotlin
package com.bliss.survey.application.ports

import java.time.Instant

fun interface Clock { fun now(): Instant }
```

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/IdGenerator.kt`:

```kotlin
package com.bliss.survey.application.ports

import java.util.UUID

fun interface IdGenerator { fun next(): UUID }
```

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/RandomFactory.kt`:

```kotlin
package com.bliss.survey.application.ports

import kotlin.random.Random

fun interface RandomFactory { fun create(): Random }
```

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/SurveyItemRepository.kt`:

```kotlin
package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.*

interface SurveyItemRepository {
    suspend fun findById(id: ItemId): SurveyItem?
    suspend fun insert(item: SurveyItem)
    suspend fun retire(id: ItemId, at: java.time.Instant)
    suspend fun pickUnratedForUser(userId: UserId?, tier: Tier, exclude: Set<ItemId>): SurveyItem?
    suspend fun countUnretiredByTier(): Map<Tier, Int>
    suspend fun listSaturated(policy: com.bliss.survey.domain.routing.KCoveragePolicy): List<ItemId>
    suspend fun listProposedByUser(userId: UserId): List<ProposedContribution>
    suspend fun deleteByIds(ids: Collection<ItemId>)
}

data class ProposedContribution(
    val item: SurveyItem,
    val optedOut: Boolean,
    val kCoverage: Int,
)
```

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/RatingRepository.kt`:

```kotlin
package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.*

interface RatingRepository {
    suspend fun findAuthRating(itemId: ItemId, userId: UserId): Rating?
    suspend fun insert(rating: Rating)
    suspend fun countByItem(itemId: ItemId): Int
    suspend fun anonymiseForUser(userId: UserId)
    suspend fun aggregateForExport(since: java.time.Instant?): List<RatingAggregate>
}

data class RatingAggregate(
    val itemId: ItemId,
    val qualiteAuthSum: Int, val qualiteAuthN: Int,
    val qualiteAnonSum: Int, val qualiteAnonN: Int,
    val difficulteAuthSum: Int, val difficulteAuthN: Int,
    val difficulteAnonSum: Int, val difficulteAnonN: Int,
    val flagCount: Int,
    val qualiteSquaredAuthSum: Int, val qualiteSquaredAnonSum: Int,
)
```

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/ProposedByRepository.kt`:

```kotlin
package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.*

interface ProposedByRepository {
    suspend fun insert(itemId: ItemId, userId: UserId, optedOut: Boolean)
    suspend fun setOptOut(userId: UserId, optedOut: Boolean)
    suspend fun listOptedOutByUser(userId: UserId): List<ItemId>
    suspend fun deleteByUser(userId: UserId)
}
```

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/UserProgressRepository.kt`:

```kotlin
package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.UserId
import java.time.Instant

interface UserProgressRepository {
    suspend fun incrementItemsRated(userId: UserId, at: Instant)
    suspend fun updateCalibrationAgreement(userId: UserId, agreement: Double)
    suspend fun get(userId: UserId): UserProgress?
    suspend fun deleteByUser(userId: UserId)
}

data class UserProgress(
    val userId: UserId,
    val itemsRated: Int,
    val calibrationAgreement: Double?,
    val lastRatedAt: Instant?,
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :survey:application:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/ports/
git commit -s -m "feat(survey-application): ports — Clock, IdGenerator, RandomFactory, repositories"
```

### Task 4.4: `Filter1Typographiques` through `Filter7Tautologie` — port from bliss-clue-ai

Source reference: `bliss-clue-ai/scripts/pipeline/` Python implementations. Behaviour is documented in `bliss-clue-ai/docs/style_guide.md` §8.3. The 27 negative-case test fixtures live in `bliss-clue-ai/scripts/pipeline/test_negative_cases.py` — we port their *expectations* (not the Python source) as the test fixture.

- [ ] **Step 1: Define the shared types**

Create `survey/application/src/main/kotlin/com/bliss/survey/application/filters/FilterResult.kt`:

```kotlin
package com.bliss.survey.application.filters

sealed interface FilterResult {
    val filterId: Int
    val reason: String

    data class Accept(override val filterId: Int) : FilterResult { override val reason = "ok" }
    data class Warning(override val filterId: Int, override val reason: String) : FilterResult
    data class Reject(override val filterId: Int, override val reason: String) : FilterResult
}
```

Create `survey/application/src/main/kotlin/com/bliss/survey/application/filters/Filter.kt`:

```kotlin
package com.bliss.survey.application.filters

import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Style

data class FilterInput(
    val mot: String,
    val definition: String,
    val pos: Pos? = null,
    val style: Style? = null,
)

interface Filter {
    val id: Int
    fun apply(input: FilterInput): FilterResult
}
```

- [ ] **Step 2: Filter1Typographiques — failing test**

Create `survey/application/src/test/kotlin/com/bliss/survey/application/filters/Filter1TypographiquesTest.kt`:

```kotlin
package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter1TypographiquesTest {
    private val f = Filter1Typographiques()
    private fun input(def: String) = FilterInput(mot = "PAIN", definition = def)

    @Test fun `clean definition accepts`() {
        assertThat(f.apply(input("Aliment de boulangerie"))).isInstanceOf(FilterResult.Accept::class)
    }
    @Test fun `emoji rejects`() {
        assertThat(f.apply(input("Aliment 🍞 de boulangerie"))).isInstanceOf(FilterResult.Reject::class)
    }
    @Test fun `markdown bold rejects`() {
        assertThat(f.apply(input("**Aliment** de boulangerie"))).isInstanceOf(FilterResult.Reject::class)
    }
    @Test fun `html tag rejects`() {
        assertThat(f.apply(input("<b>Aliment</b> de boulangerie"))).isInstanceOf(FilterResult.Reject::class)
    }
    @Test fun `non-printable rejects`() {
        assertThat(f.apply(input("Alimentde boulangerie"))).isInstanceOf(FilterResult.Reject::class)
    }
}
```

- [ ] **Step 3: Run, expect FAIL, then implement**

Run: `./gradlew :survey:application:test --tests "*Filter1Typographiques*"`
Expected: FAIL (Filter1Typographiques not defined).

Create `survey/application/src/main/kotlin/com/bliss/survey/application/filters/Filter1Typographiques.kt`:

```kotlin
package com.bliss.survey.application.filters

class Filter1Typographiques : Filter {
    override val id = 1

    // Emojis (surrogate-pair detected as supplementary codepoint) and other forbidden marks.
    private val markdownBold = Regex("\\*\\*")
    private val htmlTag = Regex("<[^>]+>")

    override fun apply(input: FilterInput): FilterResult {
        val def = input.definition
        if (def.codePoints().anyMatch { it >= 0x1F000 }) return FilterResult.Reject(id, "emoji or supplementary codepoint")
        if (markdownBold.containsMatchIn(def))         return FilterResult.Reject(id, "markdown bold (** **)")
        if (htmlTag.containsMatchIn(def))              return FilterResult.Reject(id, "html tag")
        for (ch in def) {
            // ASCII control chars except tab/LF/CR — but those will be normalised in §8.4 so we still reject here as a defensive stop
            if (ch.code in 0..31 && ch != '\t' && ch != '\n' && ch != '\r') {
                return FilterResult.Reject(id, "non-printable control character")
            }
            if (ch.code == 0x7F) return FilterResult.Reject(id, "DEL character")
        }
        return FilterResult.Accept(id)
    }
}
```

- [ ] **Step 4: Run, expect PASS, commit**

Run: `./gradlew :survey:application:test --tests "*Filter1Typographiques*"`
Expected: PASS.

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/filters/{Filter,FilterResult,Filter1Typographiques}.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/filters/Filter1TypographiquesTest.kt
git commit -s -m "feat(survey-application): port Filter1Typographiques (§8.3 step 1)"
```

### Task 4.5: Filters 2 through 7 — port each as its own commit

Follow the same red-green pattern for each. Each filter is small (≤30 lines); each test class mirrors `Filter1TypographiquesTest`'s shape.

> **Note**: I'll provide implementations and key test cases inline. For brevity, each step here represents a full TDD cycle (failing test → implement → commit). Pattern in step 4.4 stays the same.

**Filter2CaracteresInterdits** — `survey/application/src/main/kotlin/com/bliss/survey/application/filters/Filter2CaracteresInterdits.kt`:

```kotlin
package com.bliss.survey.application.filters

import java.text.Normalizer

class Filter2CaracteresInterdits : Filter {
    override val id = 2
    private val allowed = Regex("^[\\p{L}\\p{N}\\p{P}\\s]+$")

    override fun apply(input: FilterInput): FilterResult {
        // Pre-NFC defensively (per pipeline_test_pilot_v1.md §5.1 — combining marks need decomposed)
        val nfc = Normalizer.normalize(input.definition, Normalizer.Form.NFC)
        return if (allowed.matches(nfc)) FilterResult.Accept(id)
               else FilterResult.Reject(id, "contains characters outside [letters, digits, punctuation, whitespace]")
    }
}
```

Test (key cases): NFC `Épaule` accepts, NFD-decomposed `Épaule` accepts (post-normalisation), arbitrary symbol `Aliment§` rejects.

**Filter3Longueur** — `survey/application/src/main/kotlin/com/bliss/survey/application/filters/Filter3Longueur.kt`:

```kotlin
package com.bliss.survey.application.filters

class Filter3Longueur : Filter {
    override val id = 3
    private val splitWords = Regex("[\\s'’\\-]+")

    override fun apply(input: FilterInput): FilterResult {
        val words = input.definition.trim().split(splitWords).filter { it.isNotEmpty() }.size
        val chars = input.definition.length
        return when {
            words > 12 || chars > 60 -> FilterResult.Reject(id, "too long ($words words / $chars chars)")
            words > 8                -> FilterResult.Warning(id, "long ($words words; cap is 8)")
            else                     -> FilterResult.Accept(id)
        }
    }
}
```

Test: 4-word accept; 9-word warning; 13-word reject; >60 char reject.

**Filter4StereotypesIa** — proscribed prefixes (style_guide §6.5):

```kotlin
package com.bliss.survey.application.filters

class Filter4StereotypesIa : Filter {
    override val id = 4
    private val proscribed = listOf(
        "quelqu'un qui ", "quelqu’un qui ",
        "personne qui ",
        "action de ", "fait de ",
        "chose qui ",
    )
    override fun apply(input: FilterInput): FilterResult {
        val lc = input.definition.lowercase()
        proscribed.firstOrNull { lc.startsWith(it) }?.let {
            return FilterResult.Reject(id, "starts with AI stereotype prefix '$it'")
        }
        return FilterResult.Accept(id)
    }
}
```

Test: "Quelqu'un qui mange" rejects, "Aliment de boulangerie" accepts.

**Filter5AutoReference** — boundary match on accent-stripped word:

```kotlin
package com.bliss.survey.application.filters

import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Style
import java.text.Normalizer

class Filter5AutoReference : Filter {
    override val id = 5
    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    override fun apply(input: FilterInput): FilterResult {
        if (input.pos == Pos.SIGLE_ABREVIATION) return FilterResult.Accept(id)
        if (input.style == Style.CRYPTIQUE_MORPHOLOGIQUE) return FilterResult.Accept(id)
        val motStem = stripAccents(input.mot.lowercase())
        val defStripped = stripAccents(input.definition.lowercase())
        if (Regex("\\b${Regex.escape(motStem)}\\b").containsMatchIn(defStripped)) {
            return FilterResult.Reject(id, "self-reference: '${input.mot}' appears in definition")
        }
        return FilterResult.Accept(id)
    }
}
```

Test: POMME/"Pommé" rejects (accent-strip match); RIO/"Carioca" accepts (substring not at boundary); KO/"Kilooctet" with `pos=SIGLE_ABREVIATION` accepts.

**Filter6LangueFr** — lingua-language-detector based. Library not in current deps — add to `build.gradle.kts`:

In `survey/application/build.gradle.kts`, append to `dependencies`:

```kotlin
    implementation("com.github.pemistahl:lingua:1.2.2")
```

Implementation:

```kotlin
package com.bliss.survey.application.filters

import com.github.pemistahl.lingua.api.IsoCode639_1
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

class Filter6LangueFr : Filter {
    override val id = 6
    private val detector = LanguageDetectorBuilder
        .fromIsoCodes639_1(IsoCode639_1.FR, IsoCode639_1.EN)
        .build()

    override fun apply(input: FilterInput): FilterResult {
        val confidences = detector.computeLanguageConfidenceValues(input.definition)
        val fr = confidences.entries.firstOrNull { it.key.isoCode639_1 == IsoCode639_1.FR }?.value ?: 0.0
        val en = confidences.entries.firstOrNull { it.key.isoCode639_1 == IsoCode639_1.EN }?.value ?: 0.0
        return if (fr < 0.3 && en > 0.7) FilterResult.Reject(id, "looks English (FR=$fr, EN=$en)")
               else FilterResult.Accept(id)
    }
}
```

Test: "The friendly cat at home" rejects, "Aliment de boulangerie" accepts, "Salut du gentleman" accepts (borderline gold case from `pipeline_test_pilot_v1.md` §5.2).

**Filter7Tautologie** — closed list of generic category labels:

```kotlin
package com.bliss.survey.application.filters

class Filter7Tautologie : Filter {
    override val id = 7
    private val generic = setOf("animal", "prénom", "plante", "objet", "fruit", "outil", "couleur", "mot")

    override fun apply(input: FilterInput): FilterResult {
        val trimmed = input.definition.trim().lowercase().trimEnd('.', ',', '!', '?')
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            tokens.size == 1 && tokens[0] in generic                  -> FilterResult.Reject(id, "bare generic label '${tokens[0]}'")
            tokens.size == 2 && tokens[0] in generic                  -> FilterResult.Warning(id, "thin label + qualifier")
            else                                                      -> FilterResult.Accept(id)
        }
    }
}
```

Test: "Animal" reject, "Animal commun" warning, "Animal de basse-cour" accept.

- [ ] **Step 1: For each filter (2–7), write failing test → impl → commit**

Use the exact pattern from Task 4.4. Each filter has its own test class and its own commit. Suggested commit messages:

```
feat(survey-application): port Filter2CaracteresInterdits (§8.3 step 2)
feat(survey-application): port Filter3Longueur (§8.3 step 3)
feat(survey-application): port Filter4StereotypesIa (§8.3 step 4)
feat(survey-application): port Filter5AutoReference (§8.3 step 5)
feat(survey-application): port Filter6LangueFr (§8.3 step 6, adds lingua dep)
feat(survey-application): port Filter7Tautologie (§8.3 step 7)
```

- [ ] **Step 2: Run the full filter suite**

Run: `./gradlew :survey:application:test --tests "com.bliss.survey.application.filters.*"`
Expected: all green.

### Task 4.6: `FilterPipeline` — chains filters 1–7

- [ ] **Step 1: Failing test**

Create `survey/application/src/test/kotlin/com/bliss/survey/application/filters/FilterPipelineTest.kt`:

```kotlin
package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import com.bliss.survey.domain.model.Pos
import org.junit.jupiter.api.Test

class FilterPipelineTest {
    private val pipeline = FilterPipeline.default()

    @Test fun `clean definition accepts`() {
        val r = pipeline.run(FilterInput("POULE", "Femelle du coq", Pos.NOM_COMMUN))
        assertThat(r).isInstanceOf(FilterResult.Accept::class)
    }

    @Test fun `first rejecting filter short-circuits`() {
        val r = pipeline.run(FilterInput("POULE", "Quelqu'un qui pond", Pos.NOM_COMMUN))
        assertThat(r).isInstanceOf(FilterResult.Reject::class)
        // Filter 4 (stereotypes) should be the cause, not the auto-reference filter 5
        check(r is FilterResult.Reject); assertThat(r.filterId).let { /* equals 4 */ }
    }
}
```

(adjust the last assertion to use `assertk.assertions.isEqualTo` on `r.filterId`)

- [ ] **Step 2: Implement**

`survey/application/src/main/kotlin/com/bliss/survey/application/filters/FilterPipeline.kt`:

```kotlin
package com.bliss.survey.application.filters

class FilterPipeline(private val filters: List<Filter>) {
    fun run(input: FilterInput): FilterResult {
        for (f in filters) {
            val r = f.apply(input)
            if (r is FilterResult.Reject) return r
        }
        return FilterResult.Accept(filterId = 0)  // 0 = pipeline-level
    }

    companion object {
        fun default(): FilterPipeline = FilterPipeline(listOf(
            Filter1Typographiques(), Filter2CaracteresInterdits(),
            Filter3Longueur(), Filter4StereotypesIa(),
            Filter5AutoReference(), Filter6LangueFr(), Filter7Tautologie()
        ))
    }
}
```

- [ ] **Step 3: Run, commit**

```bash
./gradlew :survey:application:test --tests "*FilterPipelineTest"
git add survey/application/src/main/kotlin/com/bliss/survey/application/filters/FilterPipeline.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/filters/FilterPipelineTest.kt
git commit -s -m "feat(survey-application): FilterPipeline chains filters 1-7, short-circuits on reject"
```

### Task 4.7: §8.1 CSV parser + writer + property-based round-trip

- [ ] **Step 1: Write the round-trip property test**

Create `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt`:

```kotlin
package com.bliss.survey.application.csv

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.*
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StyleGuideCsvRoundTripTest {
    private val parser = StyleGuideCsvParser()
    private val writer = StyleGuideCsvWriter()

    private fun arbItem(): Arb<SurveyItem> = Arb.bind(
        Arb.string(2..12, Codepoint.alphanumeric()).map { it.uppercase() },
        Arb.string(2..50, Codepoint.alphanumeric()).map { it.replaceFirstChar { c -> c.uppercase() } },
        Arb.enum<Pos>(), Arb.enum<Categorie>(), Arb.enum<Style>(),
        Arb.int(1..5),
    ) { mot, def, pos, cat, style, force ->
        SurveyItem(
            id = ItemId(UUID.randomUUID()), mot = mot, definition = def,
            pos = pos, categorie = cat, style = style,
            forceClaimed = force, longueur = mot.length,
            source = Source.SYNTHETIC_V1, sourceBatch = "test_batch",
            tier = Tier.MID, isCalibration = false, expected = null,
            retiredAt = null, createdAt = Instant.now(),
        )
    }

    @Test
    fun `round-trip property — parse(write(item)) equals item`() = runTest {
        checkAll(arbItem()) { original ->
            val csvRow = writer.toRow(original, meta = emptyMap())
            val parsed = parser.parseRow(csvRow)
            // Compare structural fields (id, source_batch, createdAt are not in §8.1 — excluded from equality)
            assertThat(parsed.mot).isEqualTo(original.mot)
            assertThat(parsed.definition).isEqualTo(original.definition)
            assertThat(parsed.pos).isEqualTo(original.pos)
            assertThat(parsed.categorie).isEqualTo(original.categorie)
            assertThat(parsed.style).isEqualTo(original.style)
            assertThat(parsed.forceClaimed).isEqualTo(original.forceClaimed)
            assertThat(parsed.longueur).isEqualTo(original.longueur)
        }
    }
}
```

- [ ] **Step 2: Implement parser + writer**

`survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvParser.kt`:

```kotlin
package com.bliss.survey.application.csv

import com.bliss.survey.domain.model.*
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/**
 * Parses one row in the §8.1 schema:
 *   mot;definition;pos;categorie;style;force;longueur;source[;meta]
 *
 * Returns a SurveyItem with synthetic id (caller may overwrite), tier=MID,
 * createdAt=now. The meta field (column 9) is returned alongside as a map
 * for the caller to project into rating state, since SurveyItem itself has
 * no meta surface.
 */
class StyleGuideCsvParser {
    data class ParseError(val message: String, val line: Int, val column: String?)

    fun parseRow(row: String, lineNumber: Int = 1): SurveyItem {
        val cells = splitSemicolons(row)
        require(cells.size >= 8) { "row must have ≥ 8 columns (got ${cells.size}) at line $lineNumber" }
        val mot = nfc(cells[0])
        val definition = nfc(cells[1])
        val pos = parsePos(cells[2])
        val categorie = parseCategorie(cells[3])
        val style = parseStyle(cells[4])
        val force = cells[5].toInt()
        val longueur = cells[6].toInt()
        val source = parseSource(cells[7])
        return SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot, definition = definition,
            pos = pos, categorie = categorie, style = style,
            forceClaimed = force, longueur = longueur,
            source = source, sourceBatch = if (cells.size > 8) cells[8] else "unknown",
            tier = Tier.MID, isCalibration = false, expected = null,
            retiredAt = null, createdAt = Instant.now(),
        )
    }

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun splitSemicolons(row: String): List<String> {
        // RFC 4180-ish: quotes wrap cells with embedded semicolons / newlines / quotes
        val out = mutableListOf<String>()
        val cur = StringBuilder(); var inQuote = false; var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                inQuote && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> { cur.append('"'); i += 2 }
                c == '"'                                                        -> { inQuote = !inQuote; i++ }
                c == ';' && !inQuote                                            -> { out += cur.toString(); cur.clear(); i++ }
                else                                                            -> { cur.append(c); i++ }
            }
        }
        out += cur.toString()
        return out
    }

    private fun parsePos(s: String) = Pos.valueOf(s.uppercase())
    private fun parseCategorie(s: String) = Categorie.valueOf(s.uppercase())
    private fun parseStyle(s: String) = Style.valueOf(s.replace("é", "e").replace("ô", "o").uppercase())
    private fun parseSource(s: String) = Source.valueOf(s.uppercase())
}
```

`survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriter.kt`:

```kotlin
package com.bliss.survey.application.csv

import com.bliss.survey.domain.model.*
import java.text.Normalizer

/**
 * Emits one row in the §8.1 schema. Stable serialisation for the export
 * byte-equality CI gate: cells in fixed order, NFC, no trailing whitespace.
 *
 * Style names use the accented French form from §4 (definition_directe →
 * "définition_directe") for parity with the bliss-clue-ai dataset.
 */
class StyleGuideCsvWriter {
    fun header(): String = "mot;definition;pos;categorie;style;force;longueur;source;meta"

    fun toRow(item: SurveyItem, meta: Map<String, String>): String = buildString {
        append(quote(nfc(item.mot)));      append(';')
        append(quote(nfc(item.definition))); append(';')
        append(item.pos.name.lowercase()); append(';')
        append(item.categorie.name.lowercase()); append(';')
        append(styleName(item.style));     append(';')
        append(item.forceClaimed);         append(';')
        append(item.longueur);             append(';')
        append(item.source.name.lowercase()); append(';')
        append(quote(meta.entries.joinToString("|") { "${it.key}:${it.value}" }))
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
    private fun quote(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"${s.replace("\"", "\"\"")}\""
        else s

    private fun styleName(s: Style): String = when (s) {
        Style.DEFINITION_DIRECTE     -> "définition_directe"
        Style.PERIPHRASE             -> "périphrase"
        Style.METONYMIE              -> "métonymie"
        Style.FONCTION_ROLE          -> "fonction_rôle"
        Style.CALEMBOUR              -> "calembour"
        Style.CULTUREL               -> "culturel"
        Style.CRYPTIQUE              -> "cryptique"
        Style.CRYPTIQUE_MORPHOLOGIQUE-> "cryptique_morphologique"
        Style.TECHNIQUE              -> "technique"
    }
}
```

Note that the parser also has to accept accented style names — update `parseStyle`:

```kotlin
    private fun parseStyle(s: String): Style {
        val canonical = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase()
        return Style.valueOf(canonical)
    }
```

- [ ] **Step 3: Run, commit**

```bash
./gradlew :survey:application:test --tests "*StyleGuideCsvRoundTripTest"
# Expected: PASS, the property holds across 1000 random samples.
git add survey/application/src/main/kotlin/com/bliss/survey/application/csv/ \
        survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt
git commit -s -m "feat(survey-application): §8.1 CSV codec with PBT-verified round-trip"
```

### Task 4.8: Use cases

These compose ports + domain. Each gets a small test using in-memory fake repositories (no mocks of own code per MANIFESTO).

- [ ] **Step 1: Write `GetNextItemUseCase` + test**

`survey/application/src/main/kotlin/com/bliss/survey/application/usecases/GetNextItemUseCase.kt`:

```kotlin
package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.RandomFactory
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.*
import com.bliss.survey.domain.routing.StratifiedSampler
import com.bliss.survey.domain.routing.TierWeights

class GetNextItemUseCase(
    private val itemRepo: SurveyItemRepository,
    private val sampler: StratifiedSampler,
    private val randomFactory: RandomFactory,
) {
    suspend fun execute(forUser: UserId?, locallyExcluded: Set<ItemId>): SurveyItem? {
        val rng = randomFactory.create()
        // Try up to 4 tiers, skipping empty ones
        repeat(4) {
            val tier = sampler.pickTier(rng)
            val pick = itemRepo.pickUnratedForUser(forUser, tier, locallyExcluded)
            if (pick != null) return pick
        }
        return null
    }
}
```

Test (using `InMemorySurveyItemRepository` — define as a test helper in the same package). Skipping full code here for brevity; pattern is straightforward: stub repo returns N items by tier, assert sampler+anti-join picks one not in `locallyExcluded`.

- [ ] **Step 2: `SubmitRatingUseCase`**

`survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt`:

```kotlin
package com.bliss.survey.application.usecases

import com.bliss.survey.application.filters.FilterInput
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.filters.FilterResult
import com.bliss.survey.application.ports.*
import com.bliss.survey.domain.model.*

sealed interface SubmitRatingResult {
    data class Accepted(val rating: Rating) : SubmitRatingResult
    data class AlreadyExists(val existing: Rating) : SubmitRatingResult
    data class CorrectifRejected(val filterId: Int, val reason: String) : SubmitRatingResult
    data object AnonCorrectifForbidden : SubmitRatingResult
    data object ItemNotFound : SubmitRatingResult
}

data class SubmitRatingCommand(
    val itemId: ItemId,
    val userId: UserId?,
    val qualite: Int,
    val difficulte: Int,
    val flag: FlagReason?,
    val correctif: Pair<String, Style>?,    // (text, claimed style); auth-only
    val latencyMs: Int,
)

class SubmitRatingUseCase(
    private val items: SurveyItemRepository,
    private val ratings: RatingRepository,
    private val proposedBy: ProposedByRepository,
    private val progress: UserProgressRepository,
    private val filters: FilterPipeline,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun execute(cmd: SubmitRatingCommand): SubmitRatingResult {
        val parent = items.findById(cmd.itemId) ?: return SubmitRatingResult.ItemNotFound

        // Anon callers cannot submit a correctif
        if (cmd.userId == null && cmd.correctif != null) return SubmitRatingResult.AnonCorrectifForbidden

        // Idempotency for auth callers
        if (cmd.userId != null) {
            ratings.findAuthRating(cmd.itemId, cmd.userId)?.let { return SubmitRatingResult.AlreadyExists(it) }
        }

        // Mint the proposed-clue corpus row if present
        var proposedItemId: ItemId? = null
        if (cmd.correctif != null) {
            val (text, claimed) = cmd.correctif
            val proposedInput = FilterInput(mot = parent.mot, definition = text, pos = parent.pos, style = claimed)
            val r = filters.run(proposedInput)
            if (r is FilterResult.Reject) return SubmitRatingResult.CorrectifRejected(r.filterId, r.reason)
            val newItem = SurveyItem(
                id = ItemId(ids.next()),
                mot = parent.mot,
                definition = text,
                pos = parent.pos,
                categorie = parent.categorie,
                style = claimed,
                forceClaimed = 3,
                longueur = parent.mot.length,
                source = Source.RATER_PROPOSED,
                sourceBatch = "rater_${monthKey(clock.now())}",
                tier = Tier.MID,
                isCalibration = false, expected = null, retiredAt = null,
                createdAt = clock.now(),
            )
            items.insert(newItem)
            proposedItemId = newItem.id
            // userId is guaranteed non-null here (we returned earlier if anon+correctif)
            proposedBy.insert(newItem.id, cmd.userId!!, optedOut = false)
        }

        val rating = Rating(
            id = RatingId(ids.next()),
            itemId = cmd.itemId,
            userId = cmd.userId,
            submittedAs = if (cmd.userId != null) SubmittedAs.AUTH else SubmittedAs.ANON,
            qualite = cmd.qualite,
            difficulte = cmd.difficulte,
            flag = cmd.flag,
            proposedItemId = proposedItemId,
            latencyMs = cmd.latencyMs,
            createdAt = clock.now(),
        )
        ratings.insert(rating)
        if (cmd.userId != null) progress.incrementItemsRated(cmd.userId, clock.now())
        return SubmitRatingResult.Accepted(rating)
    }

    private fun monthKey(t: java.time.Instant): String {
        val zdt = t.atZone(java.time.ZoneOffset.UTC)
        return "%04d-%02d".format(zdt.year, zdt.monthValue)
    }
}
```

Test: write at least 6 scenarios — anon happy path, auth happy path, auth dup → AlreadyExists, anon+correctif → forbidden, correctif rejected by filter, item not found.

- [ ] **Step 3: `IngestBatchUseCase`, `ExportDatasetUseCase`, `RetireSaturatedItemsUseCase`, `AnonymizeUserRatingsUseCase`**

These follow the same pattern. Implementation sketches:

```kotlin
// IngestBatchUseCase: parse §8.1 CSV → filter (pipeline) → repo.insert; gather rejects + accepted counts
class IngestBatchUseCase(
    private val parser: com.bliss.survey.application.csv.StyleGuideCsvParser,
    private val filters: com.bliss.survey.application.filters.FilterPipeline,
    private val items: com.bliss.survey.application.ports.SurveyItemRepository,
    private val ids: com.bliss.survey.application.ports.IdGenerator,
    private val clock: com.bliss.survey.application.ports.Clock,
) {
    data class Report(val accepted: Int, val rejected: List<Pair<Int, String>>)
    suspend fun execute(csvLines: List<String>, sourceBatch: String, tier: com.bliss.survey.domain.model.Tier): Report {
        val rejected = mutableListOf<Pair<Int, String>>()
        var ok = 0
        for ((i, raw) in csvLines.drop(1).withIndex()) {   // skip header
            try {
                val parsed = parser.parseRow(raw, lineNumber = i + 2)
                val r = filters.run(com.bliss.survey.application.filters.FilterInput(
                    mot = parsed.mot, definition = parsed.definition, pos = parsed.pos, style = parsed.style
                ))
                if (r is com.bliss.survey.application.filters.FilterResult.Reject) {
                    rejected += i + 2 to "filter ${r.filterId}: ${r.reason}"; continue
                }
                items.insert(parsed.copy(
                    id = com.bliss.survey.domain.model.ItemId(ids.next()),
                    sourceBatch = sourceBatch, tier = tier, createdAt = clock.now(),
                ))
                ok++
            } catch (e: Exception) {
                rejected += i + 2 to "parse: ${e.message}"
            }
        }
        return Report(ok, rejected)
    }
}
```

```kotlin
// ExportDatasetUseCase: read aggregates → write §8.1 CSV with meta
class ExportDatasetUseCase(
    private val items: com.bliss.survey.application.ports.SurveyItemRepository,
    private val ratings: com.bliss.survey.application.ports.RatingRepository,
    private val writer: com.bliss.survey.application.csv.StyleGuideCsvWriter,
) {
    suspend fun execute(minRatings: Int, since: java.time.Instant?, authWeight: Double, anonWeight: Double): String {
        val aggs = ratings.aggregateForExport(since)
                          .filter { (it.qualiteAuthN + it.qualiteAnonN) >= minRatings }
                          .sortedBy { it.itemId.value }
        val rows = mutableListOf(writer.header())
        for (agg in aggs) {
            val item = items.findById(agg.itemId) ?: continue
            val nAuth = agg.qualiteAuthN; val nAnon = agg.qualiteAnonN
            val qMean = (authWeight * agg.qualiteAuthSum + anonWeight * agg.qualiteAnonSum) / (authWeight * nAuth + anonWeight * nAnon)
            val dMean = (authWeight * agg.difficulteAuthSum + anonWeight * agg.difficulteAnonSum) / (authWeight * nAuth + anonWeight * nAnon)
            val qStd = stdev(agg.qualiteAuthSum, agg.qualiteAnonSum, agg.qualiteSquaredAuthSum, agg.qualiteSquaredAnonSum, nAuth, nAnon)
            val meta = linkedMapOf(
                "qualite_mean" to "%.2f".format(qMean),
                "qualite_n_auth" to nAuth.toString(),
                "qualite_n_anon" to nAnon.toString(),
                "qualite_stdev" to "%.2f".format(qStd),
                "difficulte_mean" to "%.2f".format(dMean),
                "difficulte_n_auth" to nAuth.toString(),
                "difficulte_n_anon" to nAnon.toString(),
                "flags" to agg.flagCount.toString(),
                "source_batch" to item.sourceBatch,
            )
            rows += writer.toRow(item, meta)
        }
        return rows.joinToString("\n")
    }

    private fun stdev(authSum: Int, anonSum: Int, authSq: Int, anonSq: Int, nAuth: Int, nAnon: Int): Double {
        val n = nAuth + nAnon; if (n < 2) return 0.0
        val mean = (authSum + anonSum).toDouble() / n
        val variance = ((authSq + anonSq).toDouble() / n) - mean * mean
        return kotlin.math.sqrt(kotlin.math.max(0.0, variance))
    }
}
```

```kotlin
// RetireSaturatedItemsUseCase: idempotent sweep
class RetireSaturatedItemsUseCase(
    private val items: com.bliss.survey.application.ports.SurveyItemRepository,
    private val policy: com.bliss.survey.domain.routing.KCoveragePolicy,
    private val clock: com.bliss.survey.application.ports.Clock,
) {
    suspend fun execute(): Int {
        val ids = items.listSaturated(policy)
        val now = clock.now()
        for (id in ids) items.retire(id, now)
        return ids.size
    }
}
```

```kotlin
// AnonymizeUserRatingsUseCase: called by NATS consumer
class AnonymizeUserRatingsUseCase(
    private val ratings: com.bliss.survey.application.ports.RatingRepository,
    private val proposedBy: com.bliss.survey.application.ports.ProposedByRepository,
    private val items: com.bliss.survey.application.ports.SurveyItemRepository,
    private val progress: com.bliss.survey.application.ports.UserProgressRepository,
) {
    suspend fun execute(userId: com.bliss.survey.domain.model.UserId) {
        // 1. Delete opted-out contributions
        val optedOut = proposedBy.listOptedOutByUser(userId)
        if (optedOut.isNotEmpty()) items.deleteByIds(optedOut)
        // 2. Anonymise ratings
        ratings.anonymiseForUser(userId)
        // 3. Drop authorship link entirely
        proposedBy.deleteByUser(userId)
        // 4. Drop progress
        progress.deleteByUser(userId)
    }
}
```

Each gets a test using in-memory fake repos. Commit each separately with messages like `feat(survey-application): <UseCaseName>`.

- [ ] **Step 4: Final sweep + PR**

Run: `./gradlew :survey:application:check`
Expected: all green.

```bash
git push -u origin HEAD:feat/survey-application
gh pr create --title "feat(survey-application): use cases, filters 1-7 port, §8.1 codec" --body "$(cat <<'EOF'
## Summary

- `:survey:application` module per ADR-0056 and the design spec.
- Ports for repositories, Clock, IdGenerator, RandomFactory.
- 7 use cases: GetNextItem, SubmitRating, IngestBatch, ExportDataset, RetireSaturatedItems, AnonymizeUserRatings.
- Filters 1-7 ported verbatim from `bliss-clue-ai/scripts/pipeline/`; LLM-juge filter 8 deliberately omitted (stays offline per spec §3).
- §8.1 CSV parser + writer with PBT-verified round-trip property.

## Cap override

This PR exceeds the 400-line cap. Filters port + use cases + CSV codec form one coherent application layer that can't ship usefully without all three pieces; splitting would create three PRs each blocked on the next. Invoking the standing cap-override.

## Test plan

- [x] `:survey:application:check` green (Konsist + unit + PBT)
- [x] Filter 6 calibrated at FR<0.3 ∧ EN>0.7 per `pipeline_test_pilot_v1.md` §6.1
- [x] CSV round-trip PBT (1000 samples)
- [x] In-memory fake repositories used for use-case tests (no mocks of own code per MANIFESTO)
- [x] Lingua dependency added (com.github.pemistahl:lingua:1.2.2)
- [ ] CI green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR5.

---

## PR5 — `survey/infrastructure`

**Goal:** Postgres adapters (CNPG-backed) via Flyway migrations and testcontainers; identity-api session-verify client with 30 s cache; NATS JetStream consumer (the consumer wiring is here but the live deployment happens in PR9).

**Files:**
- Modify: `settings.gradle.kts` — add `include(":survey:infrastructure")`
- Create: `survey/infrastructure/build.gradle.kts`
- Create: `survey/infrastructure/src/main/resources/db/migration/V{1..4}__*.sql`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/*.kt`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/identity/*.kt`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/*.kt`
- Create: tests under `survey/infrastructure/src/test/kotlin/...` using testcontainers

### Task 5.1: Wire `:survey:infrastructure`

- [ ] **Step 1: Update settings + build file**

Edit `settings.gradle.kts`:

```kotlin
include(":survey:application")
include(":survey:infrastructure")   // <-- add
```

Create `survey/infrastructure/build.gradle.kts` modeled on `identity/infrastructure/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

val ktorVersion = "3.4.3"
val testcontainersVersion = "1.21.4"

dependencies {
    implementation(project(":survey:domain"))
    implementation(project(":survey:application"))

    // UUIDv7
    implementation("com.fasterxml.uuid:java-uuid-generator:5.2.0")

    // HTTP client to call identity-api
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("org.postgresql:postgresql:42.7.11")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.flywaydb:flyway-core:12.6.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.6.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // NATS JetStream client
    implementation("io.nats:jnats:2.20.6")

    testFixturesImplementation(project(":survey:domain"))
    testFixturesImplementation(project(":survey:application"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :survey:infrastructure:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add settings.gradle.kts survey/infrastructure/build.gradle.kts
git commit -s -m "chore(survey-infrastructure): scaffold :survey:infrastructure module"
```

### Task 5.2: Flyway migrations (V1–V4)

Create `survey/infrastructure/src/main/resources/db/migration/V1__survey_items.sql`:

```sql
-- survey_items: candidate clues with the 5-axis annotation (style_guide §4 + §7).
-- Polymorphic by `source`; rater-proposed clues carry source='rater_proposed'.
-- Same (mot, definition, pos, categorie) from a new source_batch = a new item_id
-- (raters can re-rate across model iterations per spec §4.1).

CREATE TABLE survey_items (
    item_id        UUID PRIMARY KEY,
    mot            TEXT NOT NULL,
    definition     TEXT NOT NULL,
    pos            TEXT NOT NULL,
    categorie      TEXT NOT NULL,
    style          TEXT NOT NULL,
    force_claimed  SMALLINT NOT NULL CHECK (force_claimed BETWEEN 1 AND 5),
    longueur       SMALLINT NOT NULL CHECK (longueur > 0),
    source         TEXT NOT NULL,
    source_batch   TEXT NOT NULL,
    tier           TEXT NOT NULL DEFAULT 'mid'
                   CHECK (tier IN ('high','mid','low','excluded')),
    is_calibration BOOLEAN NOT NULL DEFAULT FALSE,
    expected       JSONB,
    retired_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX survey_items_tier_unretired_idx
    ON survey_items (tier) WHERE retired_at IS NULL;
CREATE INDEX survey_items_mot_idx ON survey_items (mot);
CREATE INDEX survey_items_source_idx ON survey_items (source);
```

Create `survey/infrastructure/src/main/resources/db/migration/V2__ratings.sql`:

```sql
-- ratings: one row per (user, item) for auth callers; no DB-level dedup for anon.
-- submitted_as is immutable through anonymisation (auth-ratings remain
-- distinguishable from anon-from-inception at export-weighting time, spec §4.1).
-- No correctif text column — the text lives in survey_items.definition (single
-- source of truth, spec §4.1 schema discussion).

CREATE TABLE ratings (
    rating_id        UUID PRIMARY KEY,
    item_id          UUID NOT NULL REFERENCES survey_items(item_id),
    user_id          UUID,
    submitted_as     TEXT NOT NULL CHECK (submitted_as IN ('auth','anon')),
    qualite          SMALLINT NOT NULL CHECK (qualite BETWEEN 1 AND 5),
    difficulte       SMALLINT NOT NULL CHECK (difficulte BETWEEN 1 AND 5),
    flag             TEXT CHECK (flag IS NULL OR flag IN
                       ('hors_sujet','auto_reference','erreur_sens','autre')),
    proposed_item_id UUID REFERENCES survey_items(item_id),
    latency_ms       INTEGER,
    client_meta      JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (submitted_as = 'auth' OR (user_id IS NULL AND proposed_item_id IS NULL))
);

-- Partial unique index — auth dedup only; anon may repeat
CREATE UNIQUE INDEX ratings_auth_uniq
    ON ratings (item_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ratings_item_idx ON ratings (item_id);
CREATE INDEX ratings_user_idx ON ratings (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ratings_submitted_as_idx ON ratings (submitted_as);
```

Create `survey/infrastructure/src/main/resources/db/migration/V3__proposed_by.sql`:

```sql
-- proposed_by: authorship link for rater-proposed corpus rows. opted_out
-- toggles whether the contribution is deleted from the corpus on
-- user.deleted (spec §10.2). Fully deleted on user.deleted regardless of
-- opted_out (the row itself was the only place authorship was recorded).

CREATE TABLE proposed_by (
    proposed_item_id UUID PRIMARY KEY REFERENCES survey_items(item_id),
    user_id          UUID NOT NULL,
    opted_out        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX proposed_by_user_idx ON proposed_by (user_id);
```

Create `survey/infrastructure/src/main/resources/db/migration/V4__user_progress.sql`:

```sql
-- user_progress: per-user rating cursor + rolling calibration agreement.
-- Auth-only; anon raters have no row.

CREATE TABLE user_progress (
    user_id              UUID PRIMARY KEY,
    items_rated          INTEGER NOT NULL DEFAULT 0,
    calibration_agreement NUMERIC(4,3),
    last_rated_at        TIMESTAMPTZ
);
```

- [ ] **Step 1: Commit migrations**

```bash
git add survey/infrastructure/src/main/resources/db/migration/V*.sql
git commit -s -m "feat(survey-infrastructure): Flyway V1-V4 — survey_items, ratings, proposed_by, user_progress"
```

### Task 5.3: Postgres adapters (testcontainers)

This task implements `PgSurveyItemRepository`, `PgRatingRepository`, `PgProposedByRepository`, `PgUserProgressRepository`. Each gets a testcontainers-backed integration test.

- [ ] **Step 1: Datasource bootstrap**

Create `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/Datasource.kt`:

```kotlin
package com.bliss.survey.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Datasource {
    fun create(jdbcUrl: String, user: String, password: String): DataSource {
        val cfg = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = user
            this.password = password
            maximumPoolSize = 10
            isAutoCommit = false
        }
        val ds = HikariDataSource(cfg)
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return ds
    }
}
```

- [ ] **Step 2: For each repository, write a testcontainers-backed test FIRST, then the impl**

The pattern is:

```kotlin
// PgSurveyItemRepositoryTest.kt
package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.domain.model.*
import org.junit.jupiter.api.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.*
import java.time.Instant
import java.util.UUID

@Testcontainers
class PgSurveyItemRepositoryTest {
    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer<Nothing>("postgres:18-alpine")
    }

    private lateinit var repo: PgSurveyItemRepository

    @BeforeEach
    fun setup() {
        val ds = Datasource.create(pg.jdbcUrl, pg.username, pg.password)
        repo = PgSurveyItemRepository(ds)
    }

    @Test
    fun `insert and findById round-trips`() = kotlinx.coroutines.runBlocking {
        val item = sampleItem()
        repo.insert(item)
        val back = repo.findById(item.id)
        assertk.assertThat(back).isEqualTo(item)
    }

    @Test
    fun `pickUnratedForUser returns null when nothing matches`() = kotlinx.coroutines.runBlocking {
        val pick = repo.pickUnratedForUser(null, Tier.MID, exclude = emptySet())
        assertk.assertThat(pick).isEqualTo(null)
    }

    private fun sampleItem() = SurveyItem(
        id = ItemId(UUID.randomUUID()),
        mot = "POULE", definition = "Femelle du coq",
        pos = Pos.NOM_COMMUN, categorie = Categorie.ANIMALS, style = Style.PERIPHRASE,
        forceClaimed = 2, longueur = 5, source = Source.SYNTHETIC_V1,
        sourceBatch = "test", tier = Tier.MID,
        isCalibration = false, expected = null, retiredAt = null,
        createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
    )
}
```

Implementation `PgSurveyItemRepository.kt` uses raw JDBC (matches identity/grid patterns; no ORM). Key SQL bits:

```kotlin
package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.*
import com.bliss.survey.domain.model.*
import com.bliss.survey.domain.routing.KCoveragePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PgSurveyItemRepository(private val ds: DataSource) : SurveyItemRepository {

    override suspend fun insert(item: SurveyItem): Unit = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO survey_items
                  (item_id, mot, definition, pos, categorie, style, force_claimed, longueur,
                   source, source_batch, tier, is_calibration, expected, retired_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """).use { ps ->
                ps.setObject(1, item.id.value)
                ps.setString(2, item.mot)
                ps.setString(3, item.definition)
                ps.setString(4, item.pos.name.lowercase())
                ps.setString(5, item.categorie.name.lowercase())
                ps.setString(6, item.style.name.lowercase())
                ps.setInt(7, item.forceClaimed)
                ps.setInt(8, item.longueur)
                ps.setString(9, item.source.name.lowercase())
                ps.setString(10, item.sourceBatch)
                ps.setString(11, item.tier.name.lowercase())
                ps.setBoolean(12, item.isCalibration)
                ps.setString(13, null)  // expected JSONB — populated only for calibration items
                ps.setTimestamp(14, item.retiredAt?.let { Timestamp.from(it) })
                ps.setTimestamp(15, Timestamp.from(item.createdAt))
                ps.executeUpdate()
                c.commit()
            }
        }
    }

    override suspend fun findById(id: ItemId): SurveyItem? = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement("SELECT * FROM survey_items WHERE item_id = ?").use { ps ->
                ps.setObject(1, id.value)
                ps.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun pickUnratedForUser(userId: UserId?, tier: Tier, exclude: Set<ItemId>): SurveyItem? = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            // K=0 first, then K=1, then K>=2 — three queries, return first hit.
            // For anon callers (userId == null), the anti-join collapses; we filter
            // only by the exclude set + retired_at IS NULL + tier.
            val baseSql = """
                SELECT si.* FROM survey_items si
                  ${if (userId != null) "LEFT JOIN ratings r ON r.item_id = si.item_id AND r.user_id = ?" else ""}
                 WHERE si.tier = ? AND si.retired_at IS NULL
                   ${if (exclude.isNotEmpty()) "AND si.item_id NOT IN (${exclude.joinToString(",") { "?" }})" else ""}
                   ${if (userId != null) "AND r.rating_id IS NULL" else ""}
                   AND (SELECT count(*) FROM ratings r2 WHERE r2.item_id = si.item_id) = ?
                 ORDER BY random() LIMIT 1
            """.trimIndent()
            for (k in 0..2) {
                c.prepareStatement(baseSql).use { ps ->
                    var idx = 1
                    if (userId != null) { ps.setObject(idx++, userId.value) }
                    ps.setString(idx++, tier.name.lowercase())
                    for (id in exclude) ps.setObject(idx++, id.value)
                    ps.setInt(idx, k)
                    ps.executeQuery().use { rs -> if (rs.next()) return@withContext mapRow(rs) }
                }
            }
            null
        }
    }

    override suspend fun retire(id: ItemId, at: Instant): Unit = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement("UPDATE survey_items SET retired_at = ? WHERE item_id = ? AND retired_at IS NULL").use { ps ->
                ps.setTimestamp(1, Timestamp.from(at))
                ps.setObject(2, id.value)
                ps.executeUpdate(); c.commit()
            }
        }
    }

    override suspend fun countUnretiredByTier(): Map<Tier, Int> = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            val out = Tier.values().associateWith { 0 }.toMutableMap()
            c.prepareStatement("SELECT tier, count(*) FROM survey_items WHERE retired_at IS NULL GROUP BY tier").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) out[Tier.valueOf(rs.getString(1).uppercase())] = rs.getInt(2)
                }
            }
            out.toMap()
        }
    }

    override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            val results = mutableListOf<ItemId>()
            for ((tier, k) in policy.targetK) {
                c.prepareStatement("""
                    SELECT si.item_id FROM survey_items si
                     WHERE si.tier = ? AND si.retired_at IS NULL
                       AND (SELECT count(*) FROM ratings r WHERE r.item_id = si.item_id) >= ?
                """).use { ps ->
                    ps.setString(1, tier.name.lowercase())
                    ps.setInt(2, k)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) results += ItemId(rs.getObject(1, java.util.UUID::class.java))
                    }
                }
            }
            results
        }
    }

    override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            val out = mutableListOf<ProposedContribution>()
            c.prepareStatement("""
                SELECT si.*, pb.opted_out,
                       (SELECT count(*) FROM ratings r WHERE r.item_id = si.item_id) AS k_cov
                  FROM survey_items si
                  JOIN proposed_by pb ON pb.proposed_item_id = si.item_id
                 WHERE pb.user_id = ?
                 ORDER BY si.created_at DESC
            """).use { ps ->
                ps.setObject(1, userId.value)
                ps.executeQuery().use { rs ->
                    while (rs.next()) out += ProposedContribution(
                        item = mapRow(rs),
                        optedOut = rs.getBoolean("opted_out"),
                        kCoverage = rs.getInt("k_cov"),
                    )
                }
            }
            out
        }
    }

    override suspend fun deleteByIds(ids: Collection<ItemId>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        ds.connection.use { c ->
            c.prepareStatement("DELETE FROM survey_items WHERE item_id = ANY (?)").use { ps ->
                ps.setArray(1, c.createArrayOf("UUID", ids.map { it.value }.toTypedArray()))
                ps.executeUpdate(); c.commit()
            }
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): SurveyItem = SurveyItem(
        id = ItemId(rs.getObject("item_id", java.util.UUID::class.java)),
        mot = rs.getString("mot"),
        definition = rs.getString("definition"),
        pos = Pos.valueOf(rs.getString("pos").uppercase()),
        categorie = Categorie.valueOf(rs.getString("categorie").uppercase()),
        style = Style.valueOf(rs.getString("style").uppercase()),
        forceClaimed = rs.getInt("force_claimed"),
        longueur = rs.getInt("longueur"),
        source = Source.valueOf(rs.getString("source").uppercase()),
        sourceBatch = rs.getString("source_batch"),
        tier = Tier.valueOf(rs.getString("tier").uppercase()),
        isCalibration = rs.getBoolean("is_calibration"),
        expected = null,  // calibration row deserialisation is its own task; skipped for v1
        retiredAt = rs.getTimestamp("retired_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
```

- [ ] **Step 3: Repeat for `PgRatingRepository`, `PgProposedByRepository`, `PgUserProgressRepository`**

Each one follows the same red-green pattern. Key behaviours:

`PgRatingRepository.anonymiseForUser` performs the spec §9.2 SQL:

```kotlin
override suspend fun anonymiseForUser(userId: UserId): Unit = withContext(Dispatchers.IO) {
    ds.connection.use { c ->
        c.prepareStatement("""
            UPDATE ratings
               SET user_id = NULL,
                   client_meta = NULL,
                   latency_ms = NULL,
                   created_at = date_trunc('month', created_at)
             WHERE user_id = ?
        """).use { ps ->
            ps.setObject(1, userId.value); ps.executeUpdate(); c.commit()
        }
    }
}
```

Test it by inserting an auth rating + verifying anonymisation removes the personal-data columns while leaving `submitted_as = 'auth'` and `qualite/difficulte/proposed_item_id` intact. Add a property-based test for the **idempotence** invariant (running it twice = same final state).

Commit each repository as its own commit:

```
feat(survey-infrastructure): PgSurveyItemRepository + testcontainers test
feat(survey-infrastructure): PgRatingRepository with anonymise SQL + idempotence PBT
feat(survey-infrastructure): PgProposedByRepository
feat(survey-infrastructure): PgUserProgressRepository
```

### Task 5.4: `IdentityClient` + `CachedSessionVerifier`

- [ ] **Step 1: Define the port and verifier**

`survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/identity/IdentityClient.kt`:

```kotlin
package com.bliss.survey.infrastructure.identity

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class MeDto(val user_id: String, val display_name: String? = null)

class IdentityClient(private val baseUrl: String) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }

    /** Returns null when the session is invalid (401/403) or absent. */
    suspend fun verifySession(cookie: String?): UUID? {
        if (cookie.isNullOrBlank()) return null
        val resp = client.get("$baseUrl/v1/me") { header(HttpHeaders.Cookie, "__Host-ws_session=$cookie") }
        return when (resp.status) {
            HttpStatusCode.OK -> resp.body<MeDto>().user_id.let(UUID::fromString)
            else -> null
        }
    }
}
```

`survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/identity/CachedSessionVerifier.kt`:

```kotlin
package com.bliss.survey.infrastructure.identity

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CachedSessionVerifier(
    private val client: IdentityClient,
    private val ttl: Duration = Duration.ofSeconds(30),
    private val clock: () -> Instant = Instant::now,
) {
    private data class Entry(val userId: UUID?, val expiresAt: Instant)
    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun verify(cookieValue: String?): UUID? {
        if (cookieValue.isNullOrBlank()) return null
        val now = clock()
        cache[cookieValue]?.let { if (it.expiresAt.isAfter(now)) return it.userId else cache.remove(cookieValue) }
        val resolved = client.verifySession(cookieValue)
        cache[cookieValue] = Entry(resolved, now.plus(ttl))
        return resolved
    }
}
```

- [ ] **Step 2: Test with `ktor-client-mock`**

Use `MockEngine` to simulate identity-api. Verify (a) cache hit returns same result without HTTP call, (b) cache miss triggers HTTP, (c) entry expires after TTL.

- [ ] **Step 3: Commit**

```bash
git add survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/identity/ \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/identity/
git commit -s -m "feat(survey-infrastructure): IdentityClient + 30s-TTL CachedSessionVerifier"
```

### Task 5.5: NATS user-deleted consumer (wiring only; no live runtime yet)

- [ ] **Step 1: Implement the consumer**

`survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserDeletedConsumer.kt`:

```kotlin
package com.bliss.survey.infrastructure.nats

import com.bliss.survey.application.usecases.AnonymizeUserRatingsUseCase
import com.bliss.survey.domain.model.UserId
import io.nats.client.*
import io.nats.client.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

@Serializable data class UserDeletedEvent(val user_id: String, val occurred_at: String)

class UserDeletedConsumer(
    private val nats: Connection,
    private val anonymise: AnonymizeUserRatingsUseCase,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun start() {
        val jsm = nats.jetStreamManagement()
        val cfg = ConsumerConfiguration.builder()
            .durable("survey-api-user-deleted")
            .filterSubject("wordsparrow.user.deleted")
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofSeconds(30))
            .build()
        jsm.createOrUpdateConsumer("WORDSPARROW_USER_EVENTS", cfg)
        val sub = nats.jetStream().subscribe(
            "wordsparrow.user.deleted",
            PushSubscribeOptions.builder().durable("survey-api-user-deleted").build(),
        )
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val msg = sub.nextMessage(Duration.ofSeconds(1)) ?: continue
                try {
                    val event = Json.decodeFromString<UserDeletedEvent>(msg.data.decodeToString())
                    anonymise.execute(UserId(UUID.fromString(event.user_id)))
                    msg.ack()
                } catch (e: Exception) {
                    log.error("survey-api-user-deleted consumer failed for ${msg.subject}", e)
                    msg.nak()
                }
            }
        }
    }
}
```

- [ ] **Step 2: Test against an in-process NATS test server**

Use `io.nats.client.impl.NatsServerProtocol` — pattern lives in `identity/infrastructure/src/test/kotlin/com/bliss/identity/infrastructure/events/` (mirror it). Verify a published `wordsparrow.user.deleted` event triggers the use case exactly once.

- [ ] **Step 3: Commit**

```bash
git add survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/ \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/nats/
git commit -s -m "feat(survey-infrastructure): NATS UserDeletedConsumer (durable: survey-api-user-deleted)"
```

### Task 5.6: Final infrastructure sweep + PR

- [ ] **Step 1: Run the module**

Run: `./gradlew :survey:infrastructure:check`
Expected: PASS (testcontainers spins up Postgres and an in-process NATS).

- [ ] **Step 2: Spotless**

Run: `./gradlew :survey:infrastructure:spotlessApply :survey:infrastructure:spotlessCheck`
Expected: PASS.

- [ ] **Step 3: Push and open PR**

```bash
git push -u origin HEAD:feat/survey-infrastructure
gh pr create --title "feat(survey-infrastructure): Postgres adapters, identity client, NATS consumer" --body "$(cat <<'EOF'
## Summary

- Flyway V1-V4 migrations matching the design spec §4.1 schema.
- Postgres adapters for SurveyItem/Rating/ProposedBy/UserProgress; raw JDBC, no ORM (matches identity/grid pattern).
- IdentityClient with 30 s-TTL CachedSessionVerifier (ADR-0044 pattern).
- NATS UserDeletedConsumer on durable `survey-api-user-deleted` per ADR-0049.
- All tests testcontainers-backed; no mocks of own code.

## Test plan

- [x] `:survey:infrastructure:check` green
- [x] Anonymise SQL idempotence PBT
- [x] Cache TTL test with MockEngine
- [x] NATS consumer round-trip test with in-process server
- [ ] CI green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR6.

---

## PR6 — `survey/api`

**Goal:** Ktor HTTP edge implementing the OpenAPI from PR2. Auth-optional middleware on `/v1/items/*`, required on `/v1/me/*`. RFC 7807 errors.

**Files:**
- Modify: `settings.gradle.kts` — add `include(":survey:api")`
- Create: `survey/api/build.gradle.kts`
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/{Main,Module,Wiring}.kt`
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/auth/SessionMiddleware.kt`
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/*.kt`
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/dto/*.kt`
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/config/SurveyApiConfig.kt`
- Create: tests under `survey/api/src/test/kotlin/...`

### Task 6.1: Wire `:survey:api`

- [ ] **Step 1: Update settings + build**

Edit `settings.gradle.kts`:

```kotlin
include(":survey:infrastructure")
include(":survey:api")              // <-- add
```

Create `survey/api/build.gradle.kts` modelled on `identity/api/build.gradle.kts`:

```kotlin
plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
    id("com.gradleup.shadow") version "8.3.6"
}

kotlin { jvmToolchain(21) }
application { mainClass.set("com.bliss.survey.api.MainKt") }

val ktorVersion = "3.4.3"

dependencies {
    implementation(project(":survey:domain"))
    implementation(project(":survey:application"))
    implementation(project(":survey:infrastructure"))

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :survey:api:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add settings.gradle.kts survey/api/build.gradle.kts
git commit -s -m "chore(survey-api): scaffold :survey:api module"
```

### Task 6.2: DTOs + ProblemDetails

- [ ] **Step 1: Create DTOs**

Create `survey/api/src/main/kotlin/com/bliss/survey/api/dto/ProblemDetails.kt`:

```kotlin
package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
)
```

Create `survey/api/src/main/kotlin/com/bliss/survey/api/dto/RatingDtos.kt`:

```kotlin
package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class CorrectifPayload(val text: String, val style: String)

@Serializable
data class RatingRequest(
    val qualite: Int,
    val difficulte: Int,
    val flag: String? = null,
    val correctif: CorrectifPayload? = null,
    val latency_ms: Int,
)

@Serializable
data class RatingResponse(
    val rating_id: String,
    val item_id: String,
    val submitted_as: String,
    val proposed_item_id: String? = null,
)

@Serializable
data class CorrectifRejection(
    val type: String,
    val title: String,
    val status: Int,
    val filter_id: Int,
    val reason: String,
)
```

Create `survey/api/src/main/kotlin/com/bliss/survey/api/dto/ItemDtos.kt`:

```kotlin
package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ItemDto(
    val item_id: String,
    val mot: String,
    val definition: String,
    val pos: String,
    val categorie: String,
    val style: String,
    val force_claimed: Int,
    val longueur: Int,
    val tier: String,
    val is_calibration: Boolean,
)

@Serializable
data class ProgressResponse(
    val items_rated: Int,
    val calibration_agreement: Double? = null,
    val last_rated_at: String? = null,
)

@Serializable
data class ContributionItemDto(
    val item_id: String,
    val mot: String,
    val definition: String,
    val pos: String,
    val categorie: String,
    val style: String,
    val opted_out: Boolean,
    val k_coverage: Int,
    val created_at: String,
)

@Serializable
data class PreferencesPatch(val delete_proposed_on_erasure: Boolean)
```

- [ ] **Step 2: Commit**

```bash
git add survey/api/src/main/kotlin/com/bliss/survey/api/dto/
git commit -s -m "feat(survey-api): DTOs matching openapi.yaml schemas"
```

### Task 6.3: Auth-optional middleware (`SessionMiddleware`)

- [ ] **Step 1: Failing test**

Create `survey/api/src/test/kotlin/com/bliss/survey/api/auth/SessionMiddlewareTest.kt`:

```kotlin
package com.bliss.survey.api.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SessionMiddlewareTest {

    @Test
    fun `no cookie — userId attribute is null and request proceeds`() = testApplication {
        application {
            install(SessionMiddleware) {
                verifyCookie = { _ -> null }
            }
            routing {
                get("/probe") {
                    val u = call.attributes.getOrNull(UserIdKey)
                    call.respond(HttpStatusCode.OK, mapOf("user_id" to (u?.toString() ?: "anon")))
                }
            }
        }
        val resp = client.get("/probe")
        assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
        assertThat(resp.bodyAsText()).isEqualTo("""{"user_id":"anon"}""")
    }

    @Test
    fun `valid cookie — userId attribute is set`() = testApplication {
        val fixedUser = UUID.fromString("01234567-89ab-7cde-89ab-0123456789ab")
        application {
            install(SessionMiddleware) {
                verifyCookie = { c -> if (c == "valid-token") fixedUser else null }
            }
            routing {
                get("/probe") {
                    val u = call.attributes.getOrNull(UserIdKey)
                    call.respond(HttpStatusCode.OK, mapOf("user_id" to (u?.toString() ?: "anon")))
                }
            }
        }
        val resp = client.get("/probe") { cookie("__Host-ws_session", "valid-token") }
        assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
        assertThat(resp.bodyAsText()).isEqualTo("""{"user_id":"$fixedUser"}""")
    }

    @Test
    fun `invalid cookie — userId attribute null (no 401)`() = testApplication {
        application {
            install(SessionMiddleware) {
                verifyCookie = { _ -> null }
            }
            routing {
                get("/probe") {
                    val u = call.attributes.getOrNull(UserIdKey)
                    call.respond(HttpStatusCode.OK, mapOf("user_id" to (u?.toString() ?: "anon")))
                }
            }
        }
        val resp = client.get("/probe") { cookie("__Host-ws_session", "tampered") }
        assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
        assertThat(resp.bodyAsText()).isEqualTo("""{"user_id":"anon"}""")
    }
}
```

- [ ] **Step 2: Implement**

Create `survey/api/src/main/kotlin/com/bliss/survey/api/auth/SessionMiddleware.kt`:

```kotlin
package com.bliss.survey.api.auth

import io.ktor.server.application.*
import io.ktor.util.*
import java.util.UUID

val UserIdKey = AttributeKey<UUID>("survey.userId")

class SessionMiddlewareConfig {
    var verifyCookie: suspend (String) -> UUID? = { null }
}

val SessionMiddleware = createApplicationPlugin(
    name = "SurveySessionMiddleware",
    createConfiguration = ::SessionMiddlewareConfig,
) {
    val verify = pluginConfig.verifyCookie
    onCall { call ->
        val cookieValue = call.request.cookies["__Host-ws_session"]
        if (!cookieValue.isNullOrBlank()) {
            verify(cookieValue)?.let { call.attributes.put(UserIdKey, it) }
        }
    }
}
```

- [ ] **Step 3: Run, commit**

```bash
./gradlew :survey:api:test --tests "*SessionMiddlewareTest"
git add survey/api/src/main/kotlin/com/bliss/survey/api/auth/SessionMiddleware.kt \
        survey/api/src/test/kotlin/com/bliss/survey/api/auth/SessionMiddlewareTest.kt
git commit -s -m "feat(survey-api): auth-optional session middleware (UserIdKey attribute)"
```

### Task 6.4: Routes (NextItem, SubmitRating, MeProgress, MeContributions, MePreferences, Health)

Each route gets its own file + its own test. Test-first, full Ktor test-engine roundtrip. Commit one route per task.

- [ ] **Step 1: HealthRoute**

`survey/api/src/main/kotlin/com/bliss/survey/api/routes/HealthRoute.kt`:

```kotlin
package com.bliss.survey.api.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.healthRoute() = get("/v1/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
```

Test asserts 200 + body shape.

Commit: `feat(survey-api): /v1/health route`

- [ ] **Step 2: NextItemRoute**

`survey/api/src/main/kotlin/com/bliss/survey/api/routes/NextItemRoute.kt`:

```kotlin
package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.ItemDto
import com.bliss.survey.application.usecases.GetNextItemUseCase
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.UserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.nextItemRoute(useCase: GetNextItemUseCase) = get("/v1/items/next") {
    val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }
    val excluded = call.request.queryParameters["excluded"]?.split(",")
        ?.mapNotNull { runCatching { ItemId(UUID.fromString(it.trim())) }.getOrNull() }
        ?.toSet()
        ?: emptySet()
    val item = useCase.execute(forUser = userId, locallyExcluded = excluded)
    if (item == null) {
        call.respond(HttpStatusCode.NoContent)
    } else {
        call.respond(HttpStatusCode.OK, ItemDto(
            item_id = item.id.value.toString(),
            mot = item.mot, definition = item.definition,
            pos = item.pos.name.lowercase(),
            categorie = item.categorie.name.lowercase(),
            style = item.style.name.lowercase(),
            force_claimed = item.forceClaimed,
            longueur = item.longueur,
            tier = item.tier.name.lowercase(),
            is_calibration = item.isCalibration,
        ))
    }
}
```

Test: stub use case returning a fixed item; assert 200 + JSON shape. Empty pool → 204. With excluded param.

Commit: `feat(survey-api): GET /v1/items/next with auth-optional user resolution`

- [ ] **Step 3: SubmitRatingRoute**

`survey/api/src/main/kotlin/com/bliss/survey/api/routes/SubmitRatingRoute.kt`:

```kotlin
package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.*
import com.bliss.survey.application.usecases.*
import com.bliss.survey.domain.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.submitRatingRoute(useCase: SubmitRatingUseCase) = post("/v1/items/{itemId}/rating") {
    val itemIdParam = call.parameters["itemId"]
    val itemId = runCatching { UUID.fromString(itemIdParam) }.getOrNull()
        ?: return@post call.respond(HttpStatusCode.BadRequest,
            ProblemDetails("about:blank", "invalid item id", 400, "itemId must be a UUID"))
    val body = call.receive<RatingRequest>()
    val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }

    if (userId == null && body.correctif != null) {
        return@post call.respond(HttpStatusCode.Unauthorized,
            ProblemDetails("about:blank", "sign-in required",
                401, "Proposing a clue correction requires signing in."))
    }

    val cmd = SubmitRatingCommand(
        itemId = ItemId(itemId), userId = userId,
        qualite = body.qualite, difficulte = body.difficulte,
        flag = body.flag?.let { FlagReason.valueOf(it.uppercase()) },
        correctif = body.correctif?.let { it.text to Style.valueOf(it.style.uppercase()) },
        latencyMs = body.latency_ms,
    )

    when (val r = useCase.execute(cmd)) {
        is SubmitRatingResult.Accepted -> call.respond(HttpStatusCode.Created, r.rating.toResponse())
        is SubmitRatingResult.AlreadyExists -> call.respond(HttpStatusCode.Conflict, r.existing.toResponse())
        is SubmitRatingResult.AnonCorrectifForbidden -> call.respond(HttpStatusCode.Unauthorized,
            ProblemDetails("about:blank", "sign-in required", 401))
        is SubmitRatingResult.CorrectifRejected -> call.respond(HttpStatusCode.UnprocessableEntity,
            CorrectifRejection("about:blank", "correctif rejected", 422, r.filterId, r.reason))
        SubmitRatingResult.ItemNotFound -> call.respond(HttpStatusCode.NotFound,
            ProblemDetails("about:blank", "item not found", 404))
    }
}

private fun Rating.toResponse() = RatingResponse(
    rating_id = id.value.toString(),
    item_id = itemId.value.toString(),
    submitted_as = submittedAs.name.lowercase(),
    proposed_item_id = proposedItemId?.value?.toString(),
)
```

Test scenarios: anon happy path, auth happy path, anon+correctif → 401, auth dup → 409, item missing → 404, correctif filtered → 422. Six tests.

Commit: `feat(survey-api): POST /v1/items/{itemId}/rating with auth resolution and RFC 7807 errors`

- [ ] **Step 4: MeProgressRoute, MeContributionsRoute, MePreferencesRoute**

Each is short. Pattern: read `UserIdKey`; if null, 401 with ProblemDetails; else read/write via use case.

```kotlin
// MeProgressRoute
fun Route.meProgressRoute(repo: com.bliss.survey.application.ports.UserProgressRepository) = get("/v1/me/progress") {
    val userId = call.attributes.getOrNull(com.bliss.survey.api.auth.UserIdKey)
        ?: return@get call.respond(HttpStatusCode.Unauthorized,
            com.bliss.survey.api.dto.ProblemDetails("about:blank", "sign-in required", 401))
    val p = repo.get(com.bliss.survey.domain.model.UserId(userId))
    call.respond(HttpStatusCode.OK, com.bliss.survey.api.dto.ProgressResponse(
        items_rated = p?.itemsRated ?: 0,
        calibration_agreement = p?.calibrationAgreement,
        last_rated_at = p?.lastRatedAt?.toString(),
    ))
}
```

```kotlin
// MeContributionsRoute
fun Route.meContributionsRoute(items: com.bliss.survey.application.ports.SurveyItemRepository) = get("/v1/me/contributions") {
    val userId = call.attributes.getOrNull(com.bliss.survey.api.auth.UserIdKey)
        ?: return@get call.respond(HttpStatusCode.Unauthorized,
            com.bliss.survey.api.dto.ProblemDetails("about:blank", "sign-in required", 401))
    val list = items.listProposedByUser(com.bliss.survey.domain.model.UserId(userId))
    call.respond(HttpStatusCode.OK, list.map { c ->
        com.bliss.survey.api.dto.ContributionItemDto(
            item_id = c.item.id.value.toString(),
            mot = c.item.mot, definition = c.item.definition,
            pos = c.item.pos.name.lowercase(), categorie = c.item.categorie.name.lowercase(),
            style = c.item.style.name.lowercase(), opted_out = c.optedOut,
            k_coverage = c.kCoverage, created_at = c.item.createdAt.toString(),
        )
    })
}
```

```kotlin
// MePreferencesRoute
fun Route.mePreferencesRoute(proposedBy: com.bliss.survey.application.ports.ProposedByRepository) = patch("/v1/me/preferences") {
    val userId = call.attributes.getOrNull(com.bliss.survey.api.auth.UserIdKey)
        ?: return@patch call.respond(HttpStatusCode.Unauthorized,
            com.bliss.survey.api.dto.ProblemDetails("about:blank", "sign-in required", 401))
    val body = call.receive<com.bliss.survey.api.dto.PreferencesPatch>()
    proposedBy.setOptOut(com.bliss.survey.domain.model.UserId(userId), body.delete_proposed_on_erasure)
    call.respond(HttpStatusCode.NoContent)
}
```

Each route is one commit with its test.

### Task 6.5: `Module.kt`, `Wiring.kt`, `Main.kt`

- [ ] **Step 1: Module + Wiring + Main**

`survey/api/src/main/kotlin/com/bliss/survey/api/Module.kt`:

```kotlin
package com.bliss.survey.api

import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.routes.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.surveyApiModule(deps: Wiring) {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(CORS) {
        // Permissive within the wordsparrow.io origin set; mirror grid/game patterns.
        anyHost()  // dev — tightened to the frontend origin via values.yaml in prod
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Cookie)
        allowCredentials = true
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError,
                ProblemDetails("about:blank", "internal error", 500, cause.message))
        }
    }
    install(SessionMiddleware) {
        verifyCookie = { c -> deps.sessionVerifier.verify(c) }
    }
    routing {
        healthRoute()
        nextItemRoute(deps.getNextItem)
        submitRatingRoute(deps.submitRating)
        meProgressRoute(deps.userProgress)
        meContributionsRoute(deps.items)
        mePreferencesRoute(deps.proposedBy)
    }
}
```

`survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt`:

```kotlin
package com.bliss.survey.api

data class Wiring(
    val sessionVerifier: com.bliss.survey.infrastructure.identity.CachedSessionVerifier,
    val getNextItem: com.bliss.survey.application.usecases.GetNextItemUseCase,
    val submitRating: com.bliss.survey.application.usecases.SubmitRatingUseCase,
    val items: com.bliss.survey.application.ports.SurveyItemRepository,
    val proposedBy: com.bliss.survey.application.ports.ProposedByRepository,
    val userProgress: com.bliss.survey.application.ports.UserProgressRepository,
)
```

`survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt`:

```kotlin
package com.bliss.survey.api

import com.bliss.survey.api.config.SurveyApiConfig
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.RandomFactory
import com.bliss.survey.application.usecases.*
import com.bliss.survey.domain.routing.KCoveragePolicy
import com.bliss.survey.domain.routing.StratifiedSampler
import com.bliss.survey.domain.routing.TierWeights
import com.bliss.survey.infrastructure.identity.CachedSessionVerifier
import com.bliss.survey.infrastructure.identity.IdentityClient
import com.bliss.survey.infrastructure.persistence.*
import com.fasterxml.uuid.Generators
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import java.time.Instant
import kotlin.random.Random

fun main() {
    val cfg = SurveyApiConfig.load()
    val ds = Datasource.create(cfg.jdbcUrl, cfg.dbUser, cfg.dbPassword)

    val items = PgSurveyItemRepository(ds)
    val ratings = PgRatingRepository(ds)
    val proposedBy = PgProposedByRepository(ds)
    val progress = PgUserProgressRepository(ds)

    val clock = Clock { Instant.now() }
    val ids = IdGenerator { Generators.timeBasedEpochGenerator().generate() }
    val rng = RandomFactory { Random.Default }

    val getNextItem = GetNextItemUseCase(items, StratifiedSampler(TierWeights.DEFAULT), rng)
    val submitRating = SubmitRatingUseCase(items, ratings, proposedBy, progress, FilterPipeline.default(), ids, clock)

    val wiring = Wiring(
        sessionVerifier = CachedSessionVerifier(IdentityClient(cfg.identityBaseUrl)),
        getNextItem = getNextItem,
        submitRating = submitRating,
        items = items,
        proposedBy = proposedBy,
        userProgress = progress,
    )

    embeddedServer(CIO, port = cfg.port, host = "0.0.0.0") {
        surveyApiModule(wiring)
    }.start(wait = true)
}
```

`survey/api/src/main/kotlin/com/bliss/survey/api/config/SurveyApiConfig.kt`:

```kotlin
package com.bliss.survey.api.config

data class SurveyApiConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val identityBaseUrl: String,
) {
    companion object {
        fun load(): SurveyApiConfig = SurveyApiConfig(
            port = System.getenv("SURVEY_PORT")?.toInt() ?: 7780,
            jdbcUrl = required("JDBC_URL"),
            dbUser = required("DB_USER"),
            dbPassword = required("DB_PASSWORD"),
            identityBaseUrl = required("IDENTITY_BASE_URL"),
        )
        private fun required(k: String): String = System.getenv(k)
            ?: error("missing env $k")
    }
}
```

- [ ] **Step 2: Konsist arch test**

Mirror identity's `ApiArchitectureTest` to ensure api doesn't import other contexts.

- [ ] **Step 3: Commit**

```bash
git add survey/api/src/main/kotlin/com/bliss/survey/api/{Module,Wiring,Main}.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/config/ \
        survey/api/src/main/kotlin/com/bliss/survey/api/routes/ \
        survey/api/src/test/kotlin/com/bliss/survey/api/
git commit -s -m "feat(survey-api): Ktor module, wiring, main, route handlers, arch tests"
```

### Task 6.6: Final api sweep + PR

- [ ] **Step 1: Module check**

Run: `./gradlew :survey:api:check`
Expected: PASS.

- [ ] **Step 2: Build full app**

Run: `./gradlew :survey:api:shadowJar`
Expected: fat JAR builds.

- [ ] **Step 3: Push and PR**

```bash
git push -u origin HEAD:feat/survey-api
gh pr create --title "feat(survey-api): Ktor HTTP edge with auth-optional middleware" --body "$(cat <<'EOF'
## Summary

- :survey:api Ktor module implementing PR2's openapi.yaml.
- Auth-optional SessionMiddleware: sets UserIdKey attribute when cookie verifies, leaves it absent otherwise.
- Routes: /v1/items/next, /v1/items/{itemId}/rating, /v1/me/{progress,contributions,preferences}, /v1/health.
- RFC 7807 problem responses.
- 401 returned for anon callers attempting correctif.

## Test plan

- [x] `:survey:api:check` green
- [x] Each route has a Ktor test-engine test covering happy + error paths
- [x] SessionMiddleware test covers null/valid/invalid cookie
- [x] Shadow jar builds
- [ ] CI green
- [ ] Manual: `JDBC_URL=... ./gradlew :survey:api:run` and curl /v1/health

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR7.

---

## PR7 — `survey/worker` + Helm chart + db-chart + bootstrap Job

**Goal:** Worker executable with three subcommands; Helm chart for the API; sibling Helm chart for the CNPG cluster; Helm `post-install,post-upgrade` Job that seeds calibration items from `bliss-clue-ai/data/seed/gold_pilot_v1.csv`; hourly CronJob for `--retire-saturated`.

> **Cap override expected**: chart + worker together exceed 400 lines. Same reasoning as PR4's override.

**Files:**
- Modify: `settings.gradle.kts` — add `include(":survey:worker")`
- Create: `survey/worker/build.gradle.kts`
- Create: `survey/worker/src/main/kotlin/com/bliss/survey/worker/Main.kt`
- Create: `survey/api/deploy/chart/{Chart.yaml,values{,-local,-prod}.yaml,templates/*}`
- Create: `survey/api/deploy/db-chart/{Chart.yaml,values{,-prod}.yaml,templates/*}`
- Modify: `Makefile` — extend `deploy-local` to install survey charts
- Modify: `.github/workflows/deploy-api-k8s.yml` — add survey job

### Task 7.1: Wire `:survey:worker`

- [ ] **Step 1: Update settings + build**

Edit `settings.gradle.kts`: `include(":survey:worker")`

Create `survey/worker/build.gradle.kts`:

```kotlin
plugins {
    application
    kotlin("jvm")
    id("com.gradleup.shadow") version "8.3.6"
}
kotlin { jvmToolchain(21) }
application { mainClass.set("com.bliss.survey.worker.MainKt") }
dependencies {
    implementation(project(":survey:domain"))
    implementation(project(":survey:application"))
    implementation(project(":survey:infrastructure"))
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.testcontainers:postgresql:1.21.4")
}
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Commit scaffold**

```bash
./gradlew :survey:worker:compileKotlin
git add settings.gradle.kts survey/worker/build.gradle.kts
git commit -s -m "chore(survey-worker): scaffold :survey:worker module"
```

### Task 7.2: Worker `Main.kt` with three subcommands

- [ ] **Step 1: Implement**

`survey/worker/src/main/kotlin/com/bliss/survey/worker/Main.kt`:

```kotlin
package com.bliss.survey.worker

import com.bliss.survey.application.csv.StyleGuideCsvParser
import com.bliss.survey.application.csv.StyleGuideCsvWriter
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.usecases.*
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.routing.KCoveragePolicy
import com.bliss.survey.infrastructure.persistence.*
import com.fasterxml.uuid.Generators
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("survey-worker")

fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: run {
        System.err.println("usage: survey-worker --ingest-batch | --export-dataset | --retire-saturated [options]")
        exitProcess(2)
    }
    val opts = args.drop(1).chunked(2).associate { it[0].trimStart('-') to it[1] }
    val ds = Datasource.create(
        System.getenv("JDBC_URL") ?: error("missing JDBC_URL"),
        System.getenv("DB_USER") ?: error("missing DB_USER"),
        System.getenv("DB_PASSWORD") ?: error("missing DB_PASSWORD"),
    )
    val items = PgSurveyItemRepository(ds); val ratings = PgRatingRepository(ds)
    val clock = Clock { Instant.now() }
    val ids = IdGenerator { Generators.timeBasedEpochGenerator().generate() }

    runBlocking {
        when (cmd) {
            "--ingest-batch" -> {
                val csv = Path.of(opts["csv"] ?: error("missing --csv"))
                val sourceBatch = opts["source-batch"] ?: error("missing --source-batch")
                val tier = Tier.valueOf((opts["tier"] ?: "mid").uppercase())
                val uc = IngestBatchUseCase(StyleGuideCsvParser(), FilterPipeline.default(), items, ids, clock)
                val report = uc.execute(Files.readAllLines(csv), sourceBatch, tier)
                log.info("ingest done: accepted={}, rejected={}", report.accepted, report.rejected.size)
                if (report.rejected.isNotEmpty()) {
                    report.rejected.forEach { log.warn("line {}: {}", it.first, it.second) }
                }
            }
            "--export-dataset" -> {
                val out = Path.of(opts["output"] ?: error("missing --output"))
                val minRatings = opts["min-ratings"]?.toInt() ?: 2
                val since = opts["since"]?.let { Instant.parse(it) }
                val authW = opts["auth-weight"]?.toDouble() ?: 1.0
                val anonW = opts["anon-weight"]?.toDouble() ?: 0.5
                val uc = ExportDatasetUseCase(items, ratings, StyleGuideCsvWriter())
                val csv = uc.execute(minRatings, since, authW, anonW)
                Files.writeString(out, csv)
                log.info("export written: {}", out)
            }
            "--retire-saturated" -> {
                val uc = RetireSaturatedItemsUseCase(items, KCoveragePolicy.DEFAULT, clock)
                val n = uc.execute()
                log.info("retired {} items", n)
            }
            else -> { System.err.println("unknown subcommand: $cmd"); exitProcess(2) }
        }
    }
}
```

- [ ] **Step 2: Write a testcontainers-backed end-to-end test for `--ingest-batch`**

Test fixture: small CSV with 3 valid + 1 invalid row → assert 3 ok / 1 reject and 3 `survey_items` rows inserted.

- [ ] **Step 3: Commit**

```bash
git add survey/worker/src/main/kotlin/com/bliss/survey/worker/Main.kt \
        survey/worker/src/test/kotlin/com/bliss/survey/worker/
git commit -s -m "feat(survey-worker): three subcommands — ingest-batch, export-dataset, retire-saturated"
```

### Task 7.3: Survey-export-csv-byteequal CI gate

- [ ] **Step 1: Create a golden CSV fixture + DB seed script**

Create `survey/worker/src/test/resources/byteequal/seed.sql` — inserts 5 deterministic items + their ratings.

Create `survey/worker/src/test/resources/byteequal/expected.csv` — the byte-exact expected export output.

- [ ] **Step 2: Test**

```kotlin
// ExportByteEqualTest
@Testcontainers
class ExportByteEqualTest {
    companion object {
        @Container @JvmStatic val pg = PostgreSQLContainer<Nothing>("postgres:18-alpine")
    }
    @Test
    fun `export is byte-equal to golden fixture`() = runBlocking {
        val ds = Datasource.create(pg.jdbcUrl, pg.username, pg.password)
        // seed
        ds.connection.use { c ->
            val seed = javaClass.getResourceAsStream("/byteequal/seed.sql")!!.bufferedReader().readText()
            c.createStatement().execute(seed); c.commit()
        }
        val out = Files.createTempFile("export", ".csv")
        // run the worker main via direct call, NOT a subprocess (faster)
        com.bliss.survey.worker.runExport(ds, out, minRatings = 1, authWeight = 1.0, anonWeight = 0.5)
        val produced = Files.readString(out)
        val expected = javaClass.getResourceAsStream("/byteequal/expected.csv")!!.bufferedReader().readText()
        assertThat(produced).isEqualTo(expected)
    }
}
```

(Refactor: extract a `runExport(ds, ...)` helper in Main so the test doesn't need a subprocess.)

- [ ] **Step 3: Add CI job**

Edit `.github/workflows/ci.yml` to add a job named `survey-export-csv-byteequal`. Pattern: run `./gradlew :survey:worker:test --tests ExportByteEqualTest` after the main test job.

- [ ] **Step 4: Commit**

```bash
git add survey/worker/src/test/ .github/workflows/ci.yml
git commit -s -m "ci(survey-worker): export-csv-byteequal gate guards §8.1 export contract"
```

### Task 7.4: Helm db-chart (`bliss-survey-api-pg`)

Mirror `identity/api/deploy/db-chart/` exactly. The db-chart is a thin wrapper around a CNPG `Cluster` resource.

- [ ] **Step 1: Create chart skeleton**

```bash
mkdir -p survey/api/deploy/db-chart/templates
```

`survey/api/deploy/db-chart/Chart.yaml`:

```yaml
apiVersion: v2
name: bliss-survey-api-pg
description: "Bliss survey-api Postgres cluster (CNPG)"
type: application
version: 0.1.0
appVersion: "0.1.0"
```

`survey/api/deploy/db-chart/values.yaml` — copy verbatim from `identity/api/deploy/db-chart/values.yaml`, changing `identity` → `survey` throughout.

`survey/api/deploy/db-chart/templates/_helpers.tpl` — copy from identity's helpers, changing names.

`survey/api/deploy/db-chart/templates/cluster.yaml` — copy `identity/api/deploy/db-chart/templates/cluster.yaml`, renaming the cluster to `wordsparrow-survey-api-pg`.

`survey/api/deploy/db-chart/values-prod.yaml` — set storage size to 5Gi initially (smaller than identity; surveys are smaller than auth attempts).

- [ ] **Step 2: Helm lint**

Run: `helm lint survey/api/deploy/db-chart/ -f survey/api/deploy/db-chart/values-prod.yaml`
Expected: 0 errors.

- [ ] **Step 3: Commit**

```bash
git add survey/api/deploy/db-chart/
git commit -s -m "feat(survey-deploy): bliss-survey-api-pg db-chart (CNPG cluster sibling release)"
```

### Task 7.5: Helm chart for the API

- [ ] **Step 1: Skeleton**

```bash
mkdir -p survey/api/deploy/chart/templates
```

`survey/api/deploy/chart/Chart.yaml`:

```yaml
apiVersion: v2
name: bliss-survey-api
description: "Bliss survey API (Ktor REST, anon+auth ratings, RLHF export)"
type: application
version: 0.1.0
appVersion: "0.1.0"
dependencies: []
```

`survey/api/deploy/chart/values.yaml` — sensible neutral defaults: replicaCount 1, image.repository empty (filled by CD), service.port 7780.

`survey/api/deploy/chart/values-prod.yaml` — copy `identity/api/deploy/chart/values-prod.yaml` and adjust:

```yaml
fullnameOverride: "wordsparrow-survey-api"

image:
  requireDigest: true
  digest: ""

database:
  enabled: true   # CNPG cluster sibling release

ingress:
  host: "survey.wordsparrow.io"
  tlsSecretName: "bliss-survey-api-tls"
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    external-dns.alpha.kubernetes.io/hostname: "survey.wordsparrow.io"
    external-dns.alpha.kubernetes.io/target: "116.202.180.82"
    nginx.ingress.kubernetes.io/limit-rps: "5"
    nginx.ingress.kubernetes.io/limit-burst-multiplier: "5"
    nginx.ingress.kubernetes.io/limit-connections: "30"

envFromSecret: "bliss-survey-api-env"

calibration:
  # Seed gold items at install time (Helm post-install Job, ADR-pattern: configure-in-cluster)
  seedEnabled: true
```

- [ ] **Step 2: Templates**

Copy each template from `identity/api/deploy/chart/templates/` and adjust:

- `_helpers.tpl` — rename to `bliss-survey-api` throughout.
- `deployment.yaml` — same shape; container port 7780; env from secret `bliss-survey-api-env` + the CNPG-injected `JDBC_URL` from the db-chart.
- `service.yaml`, `ingress.yaml`, `serviceaccount.yaml` — standard adjustments.

Add a new template `survey/api/deploy/chart/templates/configmap-calibration.yaml`:

```yaml
{{- if .Values.calibration.seedEnabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "bliss-survey-api.fullname" . }}-calibration
  labels: {{- include "bliss-survey-api.labels" . | nindent 4 }}
data:
  gold_pilot_v1.csv: |
    mot;definition;pos;categorie;style;force;longueur;source
    PAIN;Aliment de boulangerie;nom_commun;aliments;définition_directe;1;4;gold
    POULE;Femelle du coq;nom_commun;animals;périphrase;2;5;gold
    # ... (paste ~20 hand-validated rows from bliss-clue-ai/data/seed/gold_pilot_v1.csv)
{{- end }}
```

The actual gold rows are pasted at chart-build time. Keep the chart self-contained — the CSV lives here, not via a network fetch.

Add `survey/api/deploy/chart/templates/job-seed-calibration.yaml`:

```yaml
{{- if .Values.calibration.seedEnabled }}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "bliss-survey-api.fullname" . }}-seed
  annotations:
    "helm.sh/hook": "post-install,post-upgrade"
    "helm.sh/hook-delete-policy": before-hook-creation
  labels: {{- include "bliss-survey-api.labels" . | nindent 4 }}
spec:
  ttlSecondsAfterFinished: 600
  template:
    spec:
      restartPolicy: OnFailure
      serviceAccountName: {{ include "bliss-survey-api.serviceAccountName" . }}
      containers:
        - name: seed
          image: "{{ .Values.image.repository }}@{{ .Values.image.digest }}"
          command: ["java", "-cp", "/app/lib/*", "com.bliss.survey.worker.MainKt"]
          args: ["--ingest-batch", "--csv", "/seed/gold_pilot_v1.csv", "--source-batch", "gold_v1", "--tier", "mid"]
          envFrom:
            - secretRef: { name: {{ .Values.envFromSecret }} }
          volumeMounts:
            - name: seed-csv
              mountPath: /seed
      volumes:
        - name: seed-csv
          configMap:
            name: {{ include "bliss-survey-api.fullname" . }}-calibration
{{- end }}
```

Add `survey/api/deploy/chart/templates/cronjob-retire.yaml`:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: {{ include "bliss-survey-api.fullname" . }}-retire
  labels: {{- include "bliss-survey-api.labels" . | nindent 4 }}
spec:
  schedule: "0 * * * *"   # hourly
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 1
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      ttlSecondsAfterFinished: 600
      template:
        spec:
          restartPolicy: OnFailure
          serviceAccountName: {{ include "bliss-survey-api.serviceAccountName" . }}
          containers:
            - name: retire
              image: "{{ .Values.image.repository }}@{{ .Values.image.digest }}"
              command: ["java", "-cp", "/app/lib/*", "com.bliss.survey.worker.MainKt"]
              args: ["--retire-saturated"]
              envFrom:
                - secretRef: { name: {{ .Values.envFromSecret }} }
```

- [ ] **Step 3: Helm lint**

Run: `helm lint survey/api/deploy/chart/ -f survey/api/deploy/chart/values-prod.yaml`
Expected: 0 errors.

- [ ] **Step 4: Commit**

```bash
git add survey/api/deploy/chart/
git commit -s -m "feat(survey-deploy): Helm chart with post-install calibration seed Job + hourly retire CronJob"
```

### Task 7.6: Makefile + deploy workflow integration

- [ ] **Step 1: Extend `Makefile`**

Append to the local-cluster deploy target (mirror the identity entries):

```makefile
deploy-local:
	# ... existing entries
	helm upgrade --install wordsparrow-survey-api-pg ./survey/api/deploy/db-chart/ -f ./survey/api/deploy/db-chart/values-prod.yaml
	helm upgrade --install wordsparrow-survey-api ./survey/api/deploy/chart/ -f ./survey/api/deploy/chart/values-local.yaml
```

- [ ] **Step 2: Extend `.github/workflows/deploy-api-k8s.yml`**

Add a survey job parallel to identity. Pattern: build → push → `helm upgrade --install`. The CD workflow already handles the digest-pinning required by `image.requireDigest`.

- [ ] **Step 3: Commit**

```bash
git add Makefile .github/workflows/deploy-api-k8s.yml
git commit -s -m "ci(survey-deploy): wire survey-api into local-cluster make target + CD workflow"
```

### Task 7.7: Final PR7 sweep + open PR

- [ ] **Step 1: Build everything**

Run: `./gradlew build --parallel`
Expected: PASS.

- [ ] **Step 2: Push and open PR**

```bash
git push -u origin HEAD:feat/survey-worker-and-charts
gh pr create --title "feat(survey-worker): worker subcommands + Helm chart + db-chart + bootstrap" --body "$(cat <<'EOF'
## Summary

- :survey:worker executable with --ingest-batch, --export-dataset, --retire-saturated.
- byte-equal export CI gate guards §8.1 contract.
- Helm chart `bliss-survey-api` (deployment + ingress + service + ServiceAccount).
- Sibling Helm chart `bliss-survey-api-pg` (CNPG cluster).
- Post-install Job seeds calibration items from `gold_pilot_v1.csv`.
- Hourly CronJob runs `--retire-saturated`.
- Makefile + CD workflow wired.

## Cap override

Chart + worker + CI together exceed 400 lines. Same reasoning as PR4: these pieces ship together usefully or not at all. Invoking the standing override.

## Test plan

- [x] `./gradlew build --parallel` green
- [x] `helm lint` both charts
- [x] Export byte-equal test green
- [x] Ingest end-to-end against testcontainers
- [ ] CI green
- [ ] Manual `make deploy-local` smoke

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR8.

---

## PR8 — Frontend `/sondage` route + `/compte` integration

**Goal:** Add the auth-optional `/sondage` route with rating cards (qualité + difficulté + flag + correctif), Matomo events, axe-core a11y coverage, and `/compte` sections (*Mes contributions*, *Préférences*). Regenerate OpenAPI types so the drift gate clears.

**Files:**
- Modify: `frontend/openapi-typescript.config.ts` (or equivalent) — add survey OpenAPI source.
- Create: `frontend/src/infrastructure/api/survey/types.ts` (generated; do not hand-edit).
- Create: `frontend/src/infrastructure/api/survey/client.ts`.
- Create: `frontend/src/infrastructure/session/localStorageSurveyAnon.ts`.
- Create: `frontend/src/application/survey/{SurveyClient.ts,types.ts,index.ts}`.
- Create: `frontend/src/ui/routes/sondage.tsx` (eager — route def + head).
- Create: `frontend/src/ui/routes/sondage.lazy.tsx` (lazy — UI).
- Create: `frontend/src/ui/components/sondage/{RatingCard,Likert,FlagPicker,CorrectifField,SignInBanner}.tsx`.
- Modify: `frontend/src/ui/routes/compte.tsx` — embed *Mes contributions* and *Préférences du sondage* sections.
- Modify: `frontend/src/ui/routes/__root.tsx` — register the new route.
- Modify: `frontend/playwright.config.ts` (or e2e config) — include `/sondage` in a11y target set.

### Task 8.1: Regenerate OpenAPI types

- [ ] **Step 1: Add survey to the OpenAPI generator config**

The project uses `pnpm api:check` to regenerate `frontend/src/infrastructure/api/<ctx>/types.ts`. Locate the existing config (likely `frontend/openapi-typescript.config.ts` or similar). Add an entry:

```ts
{
  input: '../survey/api/openapi.yaml',
  output: 'src/infrastructure/api/survey/types.ts',
}
```

- [ ] **Step 2: Run the generator**

Run: `cd frontend && pnpm api:check`
Expected: `src/infrastructure/api/survey/types.ts` created. The `openapi-typescript-drift` CI gate should now clear once this lands in main.

- [ ] **Step 3: Commit**

```bash
cd /Users/isho/IdeaProjects/bliss
git add frontend/openapi-typescript.config.ts frontend/src/infrastructure/api/survey/types.ts
git commit -s -m "chore(survey-frontend): regenerate OpenAPI types from survey/api/openapi.yaml"
```

### Task 8.2: HTTP client + localStorage anon dedup

- [ ] **Step 1: Survey HTTP client**

Create `frontend/src/infrastructure/api/survey/client.ts`:

```ts
import type { paths } from './types';

type NextItemResponse = paths['/v1/items/next']['get']['responses']['200']['content']['application/json'];
type RatingRequest = paths['/v1/items/{itemId}/rating']['post']['requestBody']['content']['application/json'];
type RatingResponse = paths['/v1/items/{itemId}/rating']['post']['responses']['201']['content']['application/json'];
type ProgressResponse = paths['/v1/me/progress']['get']['responses']['200']['content']['application/json'];
type ContributionItem = paths['/v1/me/contributions']['get']['responses']['200']['content']['application/json'][number];

const BASE = import.meta.env.VITE_SURVEY_API_BASE ?? 'https://survey.wordsparrow.io';

export class HttpSurveyClient {
  async getNextItem(opts: { excludedItemIds?: readonly string[] } = {}): Promise<NextItemResponse | null> {
    const params = new URLSearchParams();
    if (opts.excludedItemIds && opts.excludedItemIds.length > 0) {
      params.set('excluded', opts.excludedItemIds.join(','));
    }
    const url = `${BASE}/v1/items/next${params.size > 0 ? `?${params}` : ''}`;
    const res = await fetch(url, { credentials: 'include' });
    if (res.status === 204) return null;
    if (!res.ok) throw new Error(`getNextItem failed: ${res.status}`);
    return res.json();
  }

  async submitRating(itemId: string, body: RatingRequest): Promise<RatingResponse> {
    const res = await fetch(`${BASE}/v1/items/${itemId}/rating`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 401) throw new SignInRequiredError();
    if (res.status === 422) throw new CorrectifRejectedError(await res.json());
    if (!res.ok && res.status !== 409) throw new Error(`submitRating failed: ${res.status}`);
    return res.json();
  }

  async getProgress(): Promise<ProgressResponse> {
    const res = await fetch(`${BASE}/v1/me/progress`, { credentials: 'include' });
    if (!res.ok) throw new Error(`getProgress failed: ${res.status}`);
    return res.json();
  }

  async getContributions(): Promise<ContributionItem[]> {
    const res = await fetch(`${BASE}/v1/me/contributions`, { credentials: 'include' });
    if (!res.ok) throw new Error(`getContributions failed: ${res.status}`);
    return res.json();
  }

  async patchPreferences(body: { delete_proposed_on_erasure: boolean }): Promise<void> {
    const res = await fetch(`${BASE}/v1/me/preferences`, {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`patchPreferences failed: ${res.status}`);
  }
}

export class SignInRequiredError extends Error { constructor() { super('sign in required'); } }
export class CorrectifRejectedError extends Error {
  constructor(public detail: { filter_id: number; reason: string }) { super(`correctif rejected: ${detail.reason}`); }
}
```

- [ ] **Step 2: localStorage anon dedup**

Create `frontend/src/infrastructure/session/localStorageSurveyAnon.ts`:

```ts
const KEY = 'survey.anon.rated_ids';
const MAX_ITEMS = 500;   // cap to keep localStorage small

export const surveyAnonRatedStore = {
  list(): string[] {
    try { const raw = localStorage.getItem(KEY); return raw ? JSON.parse(raw) as string[] : []; }
    catch { return []; }
  },
  add(itemId: string): void {
    const list = surveyAnonRatedStore.list();
    if (list.includes(itemId)) return;
    list.push(itemId);
    while (list.length > MAX_ITEMS) list.shift();   // FIFO drop
    localStorage.setItem(KEY, JSON.stringify(list));
  },
  clear(): void { localStorage.removeItem(KEY); },
};
```

- [ ] **Step 3: Vitest unit tests for both**

Use MSW for the HTTP client (existing pattern, see `frontend/src/infrastructure/mocks/handlers/`). For the localStorage store, vitest with `jsdom` is enough.

- [ ] **Step 4: Commit**

```bash
cd /Users/isho/IdeaProjects/bliss
git add frontend/src/infrastructure/api/survey/client.ts \
        frontend/src/infrastructure/session/localStorageSurveyAnon.ts \
        frontend/src/infrastructure/api/survey/__tests__/ \
        frontend/src/infrastructure/session/__tests__/
git commit -s -m "feat(survey-frontend): HTTP client + anon localStorage dedup store"
```

### Task 8.3: Application layer types + index

- [ ] **Step 1: Domain-ish types**

Create `frontend/src/application/survey/types.ts`:

```ts
import type { paths } from '@/infrastructure/api/survey/types';

export type SurveyItem = paths['/v1/items/next']['get']['responses']['200']['content']['application/json'];
export type Pos = SurveyItem['pos'];
export type Categorie = SurveyItem['categorie'];
export type Style = SurveyItem['style'];

export type FlagReason = 'hors_sujet' | 'auto_reference' | 'erreur_sens' | 'autre';

export interface RatingSubmission {
  qualite: 1 | 2 | 3 | 4 | 5;
  difficulte: 1 | 2 | 3 | 4 | 5;
  flag?: FlagReason;
  correctif?: { text: string; style: Style };
  latency_ms: number;
}
```

Create `frontend/src/application/survey/index.ts`:

```ts
export type { SurveyItem, Pos, Categorie, Style, FlagReason, RatingSubmission } from './types';
export { HttpSurveyClient, SignInRequiredError, CorrectifRejectedError } from '@/infrastructure/api/survey/client';
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/application/survey/
git commit -s -m "feat(survey-frontend): application layer shape — domain-ish types + re-exports"
```

### Task 8.4: Likert + FlagPicker + CorrectifField + RatingCard + SignInBanner

These are small focused components. Each goes in its own commit with a vitest test.

- [ ] **Step 1: Likert with ARIA radiogroup**

`frontend/src/ui/components/sondage/Likert.tsx`:

```tsx
import { useId } from 'react';
import { css } from 'styled-system/css';

interface LikertProps {
  label: string;
  ariaLabel: string;
  value: number | null;
  onChange: (v: 1 | 2 | 3 | 4 | 5) => void;
  leftHint?: string;
  rightHint?: string;
}

const groupStyles = css({
  display: 'flex', gap: 'sm', alignItems: 'center',
});

const itemStyles = css({
  width: '40px', height: '40px',
  borderRadius: 'sm', border: '1px solid token(colors.border)',
  background: 'transparent', cursor: 'pointer',
  _focusVisible: { outline: '2px solid token(colors.accent)' },
});

const itemSelectedStyles = css({
  background: 'accent', color: 'fg',
});

export function Likert({ label, ariaLabel, value, onChange, leftHint, rightHint }: LikertProps) {
  const groupId = useId();
  return (
    <fieldset aria-labelledby={`${groupId}-label`}>
      <legend id={`${groupId}-label`}>{label}</legend>
      <div className={groupStyles} role="radiogroup" aria-label={ariaLabel}>
        {leftHint && <span>{leftHint}</span>}
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={value === n}
            tabIndex={value === n || (value === null && n === 1) ? 0 : -1}
            className={value === n ? `${itemStyles} ${itemSelectedStyles}` : itemStyles}
            onClick={() => onChange(n as 1 | 2 | 3 | 4 | 5)}
            onKeyDown={(e) => {
              if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
                e.preventDefault(); if (n > 1) onChange((n - 1) as 1 | 2 | 3 | 4 | 5);
              }
              if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
                e.preventDefault(); if (n < 5) onChange((n + 1) as 1 | 2 | 3 | 4 | 5);
              }
            }}
          >
            {n}
          </button>
        ))}
        {rightHint && <span>{rightHint}</span>}
      </div>
    </fieldset>
  );
}
```

Test (`__tests__/Likert.test.tsx`): renders 5 radios, arrow keys move selection, click selects, `aria-checked` set correctly.

- [ ] **Step 2: FlagPicker, CorrectifField, SignInBanner**

These are similar in shape: small, focused, ARIA-correct, unit-tested. Each gets its own commit:

```
feat(survey-frontend): Likert with ARIA radiogroup + arrow-key nav
feat(survey-frontend): FlagPicker (4-option dropdown)
feat(survey-frontend): CorrectifField (text + style dropdown, auth-only)
feat(survey-frontend): SignInBanner (anon mode invitation)
```

- [ ] **Step 3: RatingCard composes them**

`frontend/src/ui/components/sondage/RatingCard.tsx`:

```tsx
import { useState, useEffect, useRef } from 'react';
import type { SurveyItem, FlagReason, Style } from '@/application/survey';
import { Likert } from './Likert';
import { FlagPicker } from './FlagPicker';
import { CorrectifField } from './CorrectifField';
import { Button } from '@/ui/components/primitives';

export interface RatingCardProps {
  item: SurveyItem;
  isAuthenticated: boolean;
  onSubmit: (payload: {
    qualite: 1|2|3|4|5; difficulte: 1|2|3|4|5;
    flag?: FlagReason;
    correctif?: { text: string; style: Style };
    latency_ms: number;
  }) => Promise<void>;
}

export function RatingCard({ item, isAuthenticated, onSubmit }: RatingCardProps) {
  const [q, setQ] = useState<1|2|3|4|5 | null>(null);
  const [d, setD] = useState<1|2|3|4|5 | null>(null);
  const [flag, setFlag] = useState<FlagReason | undefined>();
  const [correctif, setCorrectif] = useState<{ text: string; style: Style } | undefined>();
  const startedAt = useRef(performance.now());

  // Auto-focus the qualité likert when a new item loads
  useEffect(() => { startedAt.current = performance.now(); }, [item.item_id]);

  const submit = async () => {
    if (q === null || d === null) return;
    await onSubmit({
      qualite: q, difficulte: d, flag, correctif: isAuthenticated ? correctif : undefined,
      latency_ms: Math.round(performance.now() - startedAt.current),
    });
  };

  return (
    <article aria-live="polite">
      <h2>{item.mot}</h2>
      <p>
        <span data-chip="pos">{item.pos}</span>
        <span data-chip="categorie">{item.categorie}</span>
      </p>
      <blockquote>« {item.definition} »</blockquote>
      <p>style: {item.style} · force annoncée: {item.force_claimed}</p>

      <Likert label="Cette définition est :" ariaLabel="Qualité"
        value={q} onChange={setQ} leftHint="Mauvaise" rightHint="Excellente" />

      <Likert label="Difficulté réelle :" ariaLabel="Difficulté"
        value={d} onChange={setD} leftHint="Très facile" rightHint="Expert" />

      <FlagPicker value={flag} onChange={setFlag} />

      {isAuthenticated && (
        <CorrectifField value={correctif} onChange={setCorrectif} />
      )}

      <Button onClick={submit} disabled={q === null || d === null}>Suivant</Button>
    </article>
  );
}
```

Test: renders all four sub-components for auth, hides CorrectifField for anon, submit calls `onSubmit` with computed `latency_ms`.

- [ ] **Step 4: Commit**

```
feat(survey-frontend): RatingCard composes likerts + flag + correctif
```

### Task 8.5: Route `/sondage`

- [ ] **Step 1: Eager half**

Create `frontend/src/ui/routes/sondage.tsx`:

```tsx
import { createRoute } from '@tanstack/react-router';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/sondage',
  head: () => buildHead({
    title: 'Sondage — WordSparrow',
    description: 'Aidez à améliorer les indices de mots fléchés en notant les définitions générées.',
    canonical: `${SITE_BASE_URL}/sondage`,
  }),
}).lazy(() => import('./sondage.lazy').then((m) => m.Route));
```

- [ ] **Step 2: Lazy half**

Create `frontend/src/ui/routes/sondage.lazy.tsx`:

```tsx
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { ContentPage } from '@/ui/components/layout';
import { RatingCard } from '@/ui/components/sondage/RatingCard';
import { SignInBanner } from '@/ui/components/sondage/SignInBanner';
import { useAuth } from '@/ui/components/auth';
import { HttpSurveyClient, type SurveyItem, SignInRequiredError, CorrectifRejectedError } from '@/application/survey';
import { surveyAnonRatedStore } from '@/infrastructure/session/localStorageSurveyAnon';
import { matomoTrack } from '@/infrastructure/analytics/matomoTracker';
import { Route as ParentRoute } from './sondage';

const client = new HttpSurveyClient();

export const Route = createLazyRoute(ParentRoute.id)({
  component: SondagePage,
});

function SondagePage() {
  const { me } = useAuth();
  const isAuth = me !== null;
  const [item, setItem] = useState<SurveyItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadNext = async () => {
    setLoading(true); setError(null);
    try {
      const excluded = isAuth ? undefined : surveyAnonRatedStore.list();
      const next = await client.getNextItem({ excludedItemIds: excluded });
      setItem(next);
    } catch (e) { setError(String(e)); }
    finally { setLoading(false); }
  };

  useEffect(() => {
    loadNext();
    matomoTrack('survey_session_start', { submitted_as: isAuth ? 'auth' : 'anon' });
  }, [isAuth]);

  const onSubmit = async (payload: Parameters<typeof RatingCard>[0]['onSubmit'] extends (p: infer P) => unknown ? P : never) => {
    if (!item) return;
    try {
      await client.submitRating(item.item_id, payload);
      matomoTrack('survey_rating_submitted', {
        submitted_as: isAuth ? 'auth' : 'anon',
        tier: item.tier,
      });
      if (payload.correctif) matomoTrack('survey_correctif_proposed', {});
      if (payload.flag) matomoTrack('survey_flag_raised', { flag: payload.flag });
      if (!isAuth) surveyAnonRatedStore.add(item.item_id);
      await loadNext();
    } catch (e) {
      if (e instanceof SignInRequiredError) {
        matomoTrack('survey_signin_prompt_shown', { reason: 'correctif_anon' });
        // The button should be hidden for anon already; this is a defensive branch.
      } else if (e instanceof CorrectifRejectedError) {
        setError(`Correction rejetée par le filtre ${e.detail.filter_id}: ${e.detail.reason}`);
      } else {
        setError(String(e));
      }
    }
  };

  return (
    <ContentPage>
      {!isAuth && <SignInBanner onClick={() => matomoTrack('survey_signin_prompt_clicked', {})} />}
      {loading && <p>Chargement…</p>}
      {error && <p role="alert">{error}</p>}
      {!loading && item === null && <p>Plus d'indices à noter pour l'instant. Merci !</p>}
      {item && <RatingCard item={item} isAuthenticated={isAuth} onSubmit={onSubmit} />}
    </ContentPage>
  );
}
```

- [ ] **Step 3: Register the route**

Edit `frontend/src/ui/routes/__root.tsx` and add `Route as SondageRoute` to the routeTree (mirror existing route registrations).

- [ ] **Step 4: Run unit + e2e**

Run: `cd frontend && pnpm test`
Expected: PASS.

Run: `cd frontend && pnpm e2e -- --grep sondage`
Expected: PASS (write at least one smoke test: page loads, card renders, submit triggers next card).

Run: `cd frontend && pnpm a11y`
Expected: PASS, including `/sondage`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/routes/sondage.tsx frontend/src/ui/routes/sondage.lazy.tsx \
        frontend/src/ui/routes/__root.tsx \
        frontend/playwright.config.ts \
        frontend/src/ui/routes/__tests__/sondage.test.tsx \
        frontend/e2e/sondage.spec.ts
git commit -s -m "feat(survey-frontend): /sondage route with Matomo events and a11y coverage"
```

### Task 8.6: `/compte` integration — Mes contributions + Préférences

- [ ] **Step 1: Component for *Mes contributions***

Create `frontend/src/ui/components/compte/MyContributions.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { HttpSurveyClient } from '@/application/survey';

const client = new HttpSurveyClient();

export function MyContributions() {
  const [items, setItems] = useState<Awaited<ReturnType<typeof client.getContributions>>>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    client.getContributions().then((list) => { setItems(list); setLoading(false); }).catch(() => setLoading(false));
  }, []);

  if (loading) return <p>Chargement…</p>;
  if (items.length === 0) return <p>Vous n'avez encore proposé aucune correction.</p>;
  return (
    <ul>
      {items.map((c) => (
        <li key={c.item_id}>
          <strong>{c.mot}</strong> — « {c.definition} » ({c.categorie}, {c.style}) — noté {c.k_coverage} fois
          {c.opted_out && <em> (sera supprimé en cas de suppression du compte)</em>}
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 2: Component for *Préférences***

`frontend/src/ui/components/compte/SurveyPreferences.tsx`:

```tsx
import { useState } from 'react';
import { HttpSurveyClient } from '@/application/survey';

const client = new HttpSurveyClient();

export function SurveyPreferences() {
  const [deleteOnErasure, setDeleteOnErasure] = useState(false);
  const [saving, setSaving] = useState(false);

  const save = async (next: boolean) => {
    setSaving(true); setDeleteOnErasure(next);
    try { await client.patchPreferences({ delete_proposed_on_erasure: next }); }
    finally { setSaving(false); }
  };

  return (
    <fieldset>
      <legend>Confidentialité du sondage</legend>
      <label>
        <input
          type="checkbox"
          checked={deleteOnErasure}
          disabled={saving}
          onChange={(e) => save(e.target.checked)}
        />
        Supprimer aussi mes corrections proposées en cas de suppression de mon compte.
      </label>
    </fieldset>
  );
}
```

- [ ] **Step 3: Embed in `compte.tsx`**

Add two sections to `frontend/src/ui/routes/compte.tsx` (eager half — already imports `useAuth`):

```tsx
import { MyContributions } from '@/ui/components/compte/MyContributions';
import { SurveyPreferences } from '@/ui/components/compte/SurveyPreferences';

// In the page body, inside the auth-required render branch:
<section><h2>Mes contributions</h2><MyContributions /></section>
<section><h2>Préférences du sondage</h2><SurveyPreferences /></section>
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/components/compte/ frontend/src/ui/routes/compte.tsx
git commit -s -m "feat(survey-frontend): /compte sections — Mes contributions + Préférences du sondage"
```

### Task 8.7: Final frontend sweep + PR

- [ ] **Step 1: All checks**

```bash
cd frontend
pnpm typecheck
pnpm test
pnpm e2e
pnpm a11y
pnpm api:check    # should be clean now
```

Expected: all PASS.

- [ ] **Step 2: Push and PR**

```bash
cd /Users/isho/IdeaProjects/bliss
git push -u origin HEAD:feat/survey-frontend
gh pr create --title "feat(survey-frontend): /sondage route + /compte integration" --body "$(cat <<'EOF'
## Summary

- `/sondage` route — open to anon, full features for auth.
- ARIA radiogroup Likerts (qualité + difficulté) with arrow-key nav; AZERTY-safe (no digit shortcuts).
- Sign-in banner for anon visitors; correctif field hidden in anon mode.
- Matomo events with `submitted_as` dimension.
- `/compte` gains *Mes contributions* + *Préférences du sondage* sections.
- localStorage anon dedup (`survey.anon.rated_ids`).
- axe-core coverage extended to `/sondage`.
- OpenAPI types regenerated.

## Test plan

- [x] `pnpm typecheck` clean
- [x] `pnpm test` green
- [x] `pnpm e2e` green (sondage smoke test)
- [x] `pnpm a11y` green (sondage + compte)
- [x] `pnpm api:check` clean (drift gate)
- [ ] CI green
- [ ] Manual: anon and auth submit flows end-to-end

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, proceed to PR9.

---

## PR9 — NATS consumer wired live + flag flip

**Goal:** Wire the `UserDeletedConsumer` (already implemented in PR5) into `Main.kt` and start it at boot; register the durable on the JetStream stream via the chart-Job pattern (`infra/nats/templates/stream-bootstrap-job.yaml` is the precedent). Then flip the feature flag for the maintainer first, then onboard the existing Sheets cohort, then ramp public.

**Files:**
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt` — start the consumer.
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt` — carry the consumer.
- Modify: `survey/api/build.gradle.kts` — `implementation("io.nats:jnats:2.20.6")` (might already be on infrastructure).
- Modify: `infra/nats/templates/stream-bootstrap-job.yaml` — extend bootstrap to register the survey durable.
- Modify: `survey/api/deploy/chart/values-prod.yaml` — add `NATS_URL` env, NetworkPolicy clause.
- Modify: `frontend/src/ui/routes/sondage.tsx` and `__root.tsx` — flip flag to enabled (already lazy-loaded, but route registration is gated).

### Task 9.1: Wire `UserDeletedConsumer` into Main

- [ ] **Step 1: Update Wiring + Main**

Edit `survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt`:

```kotlin
data class Wiring(
    val sessionVerifier: com.bliss.survey.infrastructure.identity.CachedSessionVerifier,
    val getNextItem: com.bliss.survey.application.usecases.GetNextItemUseCase,
    val submitRating: com.bliss.survey.application.usecases.SubmitRatingUseCase,
    val items: com.bliss.survey.application.ports.SurveyItemRepository,
    val proposedBy: com.bliss.survey.application.ports.ProposedByRepository,
    val userProgress: com.bliss.survey.application.ports.UserProgressRepository,
    val userDeletedConsumer: com.bliss.survey.infrastructure.nats.UserDeletedConsumer,
)
```

Edit `survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt` to instantiate the consumer and start it before `embeddedServer` begins serving:

```kotlin
import com.bliss.survey.application.usecases.AnonymizeUserRatingsUseCase
import com.bliss.survey.infrastructure.nats.UserDeletedConsumer
import io.nats.client.Nats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

// ... existing setup ...

val anonymise = AnonymizeUserRatingsUseCase(ratings, proposedBy, items, progress)
val natsConn = Nats.connect(cfg.natsUrl)
val consumerScope = CoroutineScope(SupervisorJob())
val consumer = UserDeletedConsumer(natsConn, anonymise, consumerScope)
consumer.start()

val wiring = Wiring(
    sessionVerifier = CachedSessionVerifier(IdentityClient(cfg.identityBaseUrl)),
    getNextItem = getNextItem,
    submitRating = submitRating,
    items = items,
    proposedBy = proposedBy,
    userProgress = progress,
    userDeletedConsumer = consumer,
)
```

Extend `SurveyApiConfig`:

```kotlin
val natsUrl: String,
// ...
natsUrl = System.getenv("NATS_URL") ?: "nats://nats.wordsparrow.svc.cluster.local:4222",
```

- [ ] **Step 2: Test the start path**

The pattern lives in `identity/api/src/test/kotlin/com/bliss/identity/api/...` — find the bootstrap test and mirror it. The consumer's start path is already tested at the infrastructure layer (PR5); here we just need a smoke test that Main doesn't blow up.

- [ ] **Step 3: Commit**

```bash
git add survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/config/SurveyApiConfig.kt
git commit -s -m "feat(survey-api): wire UserDeletedConsumer at boot"
```

### Task 9.2: Register the durable consumer via chart-Job

The `WORDSPARROW_USER_EVENTS` JetStream stream is already created by `infra/nats/`. The consumer is created by the `UserDeletedConsumer` itself on `start()` (it calls `jsm.createOrUpdateConsumer(...)`), so no additional Job is strictly required — the consumer is **its own** declaration mechanism.

However, per CLAUDE.md "Configure-in-cluster, not push-from-CI", we want the durable's existence to be observable from `kubectl` before the survey-api pod is healthy. Add a tiny pre-flight Job to the chart that just asserts the stream exists and prints a message if the survey-api consumer is missing (informational).

- [ ] **Step 1: Add a NetworkPolicy template so survey-api can reach NATS**

Create `survey/api/deploy/chart/templates/networkpolicy.yaml`:

```yaml
{{- if .Values.nats.networkPolicyEnabled }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "bliss-survey-api.fullname" . }}-to-nats
  labels: {{- include "bliss-survey-api.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels: {{- include "bliss-survey-api.selectorLabels" . | nindent 6 }}
  policyTypes: [Egress]
  egress:
    - to:
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: nats
      ports:
        - port: 4222
          protocol: TCP
{{- end }}
```

In `values-prod.yaml`:

```yaml
nats:
  url: "nats://nats.wordsparrow.svc.cluster.local:4222"
  networkPolicyEnabled: true
```

- [ ] **Step 2: Pass `NATS_URL` env to the Deployment**

Edit `survey/api/deploy/chart/templates/deployment.yaml` env block:

```yaml
            - name: NATS_URL
              value: {{ .Values.nats.url | quote }}
```

- [ ] **Step 3: Helm lint and commit**

```bash
helm lint survey/api/deploy/chart/ -f survey/api/deploy/chart/values-prod.yaml
git add survey/api/deploy/chart/templates/networkpolicy.yaml \
        survey/api/deploy/chart/templates/deployment.yaml \
        survey/api/deploy/chart/values-prod.yaml
git commit -s -m "feat(survey-deploy): NATS env + NetworkPolicy egress to JetStream"
```

### Task 9.3: Final consumer integration test

- [ ] **Step 1: End-to-end test**

Write an integration test that exercises the full flow:
1. Start survey-api against testcontainers Postgres + in-process NATS.
2. POST an auth rating with a correctif → 201, item exists in `survey_items` with `source='rater_proposed'`.
3. Publish `wordsparrow.user.deleted` for that user.
4. Wait for ack (≤5 s).
5. Verify: `ratings.user_id IS NULL`, `latency_ms IS NULL`, `client_meta IS NULL`, `created_at` truncated to month-start, `submitted_as = 'auth'`.
6. Verify: the `survey_items` row with `source='rater_proposed'` still exists (no opt-out).
7. Verify: `proposed_by` table empty for that user.
8. Verify: `user_progress` row deleted.

This is the most consequential test in the project — the RGPD posture rests on it. Commit:

```
test(survey): end-to-end user.deleted anonymisation flow
```

### Task 9.4: Flip the feature flag, ramp

The flag is gated at the route-registration level in `__root.tsx`. PR8 left it conditional on an env var (default false). PR9 flips the env var.

- [ ] **Step 1: Local smoke**

Run: `make deploy-local`

Verify:
- `kubectl get pods -n wordsparrow` shows `wordsparrow-survey-api-*` and `wordsparrow-survey-api-pg-*` healthy.
- `curl http://survey.localhost/v1/health` → `200 OK`.
- The post-install Job ran and inserted calibration items (`SELECT count(*) FROM survey_items WHERE is_calibration = true` ≥ 20).
- Open `http://localhost:5173/sondage` in a browser; rate one item anon; verify a `ratings` row with `submitted_as = 'anon'` appears.
- Sign in; rate one item with a `correctif`; verify `survey_items` gains a `rater_proposed` row and the rating links it.
- Delete the account via `/compte`; verify the `wordsparrow.user.deleted` event triggers the anonymisation SQL.

- [ ] **Step 2: Push, open the final PR**

```bash
git push -u origin HEAD:feat/survey-nats-and-launch
gh pr create --title "feat(survey): NATS consumer live + launch (maintainer first)" --body "$(cat <<'EOF'
## Summary

- UserDeletedConsumer started at boot; durable consumer registered against `WORDSPARROW_USER_EVENTS` stream.
- NetworkPolicy egress for NATS.
- End-to-end test of the user.deleted → anonymisation pipeline (the critical RGPD path).
- After merge: smoke test via `make deploy-local`, then flip the feature flag for the maintainer, then ramp to the existing Sheets cohort, then public.

## Test plan

- [x] :survey:api:check green
- [x] :survey:infrastructure:check green (consumer round-trip)
- [x] End-to-end user.deleted flow
- [ ] Local smoke per Task 9.4 step 1
- [ ] Production smoke after flag flip

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After this PR merges, the survey module is live. Run the rollout sequence from spec §12:

1. **Flip flags ON for maintainer only**: set `SURVEY_FLAG_USER_IDS=<your-user-id>` in `bliss-survey-api-env` and redeploy. Verify rater UX end-to-end.
2. **Onboard the Sheets cohort**: email each existing contributor with the `/sondage` link and a `return_to=/sondage` sign-in deep link.
3. **Public ramp**: remove the user-id allowlist; flag ON for all visitors.
4. **Sunset the Sheets workflow**: open a `docs` PR in `bliss-clue-ai` marking `scripts/campaign/` as superseded.

---

## Closing — what this plan covers vs. the spec

The plan implements the spec's nine PRs in sequence:

| Spec § | Plan PR | Status |
|---|---|---|
| §3 architecture / layout | PR1 (ADR) + PR3–7 (modules) | ✓ |
| §4 schema | PR3 (domain types) + PR5 (Flyway) | ✓ |
| §5 routing | PR3 (StratifiedSampler, KCoveragePolicy) + PR5 (Pg query) | ✓ |
| §6 API surface | PR2 (openapi.yaml) + PR6 (Ktor) | ✓ |
| §7 worker subcommands | PR7 | ✓ |
| §8 frontend route + /compte | PR8 | ✓ |
| §9 cross-context (identity + NATS + bliss-clue-ai) | PR5 (impl) + PR9 (live) + PR7 (CSV worker) | ✓ |
| §10 RGPD posture | PR3 (Rating invariants) + PR5 (anonymise SQL) + PR9 (e2e) | ✓ |
| §11 testing strategy | every PR has its own tests; PBTs in PR3/PR4/PR5 | ✓ |
| §12 rollout sequence | this plan's PR1–PR9 ordering | ✓ |
| §13 risks | mitigations baked into each PR | ✓ |
| §14 v2 deferrals | called out in spec; no v1 tasks | ✓ |
| §15 deliberately-not | nothing in this plan implements them | ✓ |

Feature-flag expiry (2026-08-25 per spec §12) lands in PR8's flag definition; CI fails after that date if the flag still exists.

The plan deliberately uses the standing 400-line cap-override on PR4 and PR7. Every other PR aims to stay under the cap.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-05-25-survey-module.md`. The plan is intentionally long (9 PRs across the full stack); each PR is independently mergeable after the schema-only PR2 lands, and each PR's task list is bite-sized.

Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task; review between tasks; fast iteration. Best for a plan this size where you want to spot-check work without re-reading each PR end to end.

**2. Inline Execution** — execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints. Best if you want to drive the pacing yourself.

Which approach?
