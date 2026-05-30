# ADR Path Registry

> **Purpose.** Map source-tree paths to the ADRs that govern them, so any
> agent (human or AI) editing those paths knows which decisions are binding
> *before* writing code. Skip this and you ship the next recurrence of an
> already-decided incident (see the 2026-05-26 5th-CORS bug — ADR-0048 was
> canonical, the survey-api scaffolding agent never read it).
>
> **How to use.** Run `scripts/adr-context.sh <path>...` — it greps this
> file for matching globs and emits the bodies of the matching ADRs to
> stdout. Inline that output into the implementer prompt at dispatch time
> (the dispatch skill does this automatically). For one-off edits, read
> the ADRs in full before touching the file.
>
> **How to maintain.** This file is a registry, not a memo. Every new ADR
> with operational bite goes here in the same PR that adds the ADR; CI's
> `registry-coherence` workflow fails any PR that touches `docs/adr/NNNN-*.md`
> without updating this file. Globs are bash-style (`*` does not cross `/`,
> `**` does). One ADR can appear under multiple globs.

## Format

```
<ADR-id>  <path glob>                              <one-line rule reminder>
```

Lines are matched literally by `scripts/adr-context.sh`. Keep the glob
column aligned for grep-ability; the one-liner is for humans skimming this
file, not for the helper script.

## Registry

```
ADR-0001  .claude/skills/dispatch/**               Parallel-agent workflow: branch naming, cap, §6a review/fix cycle
ADR-0001  docs/superpowers/plans/**                Orchestration procedures follow ADR-0001 §2-§7
ADR-0001  docs/superpowers/specs/**                Specs precede plans; both follow ADR-0001 §3
ADR-0003  */api/openapi.yaml                       Schema-first contract; merge schema-only PRs first
ADR-0003  */api/asyncapi.yaml                      Same as openapi but for event channels (ADR-0019)
ADR-0003  frontend/src/infrastructure/api/**/types.ts  Generated; never hand-edit (drift gate)
ADR-0007  */api/src/**/config/*.kt                 Runtime config from env vars; fail-fast at boot
ADR-0009  infra/platform/charts/**                 Self-managed k3s on Hetzner; helm chart layout
ADR-0009  .github/workflows/deploy-api-k8s.yml     Deploy pattern: configure-in-cluster, not push-from-CI
ADR-0010  terraform/**                             OpenTofu remote state on Hetzner
ADR-0011  terraform/k8s/**                         k8s subtree provisioned by OpenTofu
ADR-0013  */worker/src/**                          Batch worker pattern (words/clues)
ADR-0018  game/**                                  Game bounded context: HTTP + WebSocket
ADR-0019  */api/asyncapi.yaml                      AsyncAPI 2.6, not 3.x
ADR-0025  frontend/src/**/analytics/**             Matomo + RGPD posture
ADR-0026  frontend/**/sw.*                         PWA offline cache via Workbox
ADR-0027  infra/observability/**                   SigNoz on ClickHouse
ADR-0033  frontend/src/**/otel/**                  Frontend OTel public ingest; emits traceparent/tracestate
ADR-0033  frontend/src/infrastructure/api/**       Browser SDK adds traceparent to every cross-origin fetch
ADR-0034  */api/src/**/Module.kt                   CORS: allowHeaders { true } (wildcard predicate)
ADR-0042  */worker/src/**/pre*generation/**        Daily puzzle pre-gen worker (k8s CronJob)
ADR-0044  identity/**                              Identity bounded context for player OIDC
ADR-0044  */api/src/**/persistence/*Database.kt    CNPG libpq URI → toJdbcUrl(); never pass raw uri to Hikari
ADR-0044  */api/src/**/SessionMiddleware.kt        Session cookie verification via identity-api
ADR-0045  identity/**                              Player-identity data minimization (RGPD)
ADR-0046  identity/api/build.gradle.kts            Nimbus JOSE JWT pinned dependency
ADR-0047  identity/api/src/**/token/**            Token endpoint exchange threat model
ADR-0048  */api/src/**/Module.kt                   CORS wildcard for credentialed contexts (mirrors identity)
ADR-0048  */api/src/test/**/CorsTest.kt            Mandatory CORS regression test (traceparent/tracestate)
ADR-0048  */api/src/test/**/architecture/CorsWildcardArchitectureTest.kt  Konsist guard: credentialed-CORS wildcard predicate
ADR-0049  */api/src/**/nats/**                     JetStream cross-context events (must start before Ktor serves)
ADR-0049  */infrastructure/src/**/nats/**          JetStream consumer pattern
ADR-0050  frontend/**                              A11y baseline: WCAG AA, axe-core via Playwright
ADR-0053  frontend/src/**/prerender/**             Build-time SEO prerender
ADR-0054  frontend/src/ui/**                       Page-shell primitive
ADR-0055  game/**/persistence/**                   Multiplayer game persistence
ADR-0056  survey/**                                Survey bounded context (RLHF clue rating; pairwise comparison task pulled from v2 deferral)
ADR-0057  modal_jobs/**                            Cloud-GPU finetune lane (Modal)
ADR-0058  data/external/**                         Licensed-data posture (commercial intent); per-source verdict matrix
ADR-0058  data/dbnary/**                           DBnary SA-acceptance + distribution discipline
ADR-0058  scripts/clue_generation/**               Training/filter paths must classify per ADR-0058 matrix
ADR-0058  scripts/eval/**                          Same — eval paths that feed training must classify
ADR-0058  modal_jobs/**                            Training/inference on Modal must classify per ADR-0058 (incl. the Command-R base model)
ADR-0059  survey/**/persistence/**                 Campaign lifecycle: campaigns table, partial-unique open invariant
ADR-0059  survey/**/usecases/SubmitRatingUseCase.kt           Locked arm + campaign_id stamping
ADR-0059  survey/**/usecases/SubmitPairRatingUseCase.kt       Locked arm + campaign_id stamping
ADR-0059  survey/api/openapi.yaml                  /v1/campaign/current + 423 on rating POSTs
ADR-0059  frontend/src/ui/components/sondage/**    LockBanner + useCampaignStatus + disabled cards
ADR-0059  scripts/survey/backfill_campaigns.py     Historical campaign attribution from Modal logs
ADR-0060  identity/**                              Identity user roles + UserRoleChanged event (gold-weighting Spec A)
```

## Adding entries

When adding a new ADR or making an existing one operationally binding for a
new path, append to the registry above. The CI gate enforces that any change
to `docs/adr/NNNN-*.md` is paired with a touch of this file in the same PR.
That doesn't mean the touch has to add a line — sometimes an ADR is purely
contextual and doesn't govern a specific path — but if the gate trips on an
ADR that genuinely doesn't bind any path, add an "# ADR-NNNN: contextual, no
binding paths" comment line below the table so the diff is explicit.
