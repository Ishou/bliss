# ADR-0038: Replace cloudflare/pages-action with cloudflare/wrangler-action

## Status

Accepted

## Context

ADR-0004 §3 specifies `cloudflare/pages-action` as the deploy mechanism for the
frontend static bundle to Cloudflare Pages. ADR-0004's Notes section lists
"Cloudflare materially changes the Pages product, pricing, or terms in a way
that affects the project" as a revisit trigger.

That trigger has fired: Cloudflare deprecated `cloudflare/pages-action`.
Its bundled dependencies include `@miniflare/watcher@2.14.4` (Miniflare v2,
EOL upstream), surfacing as `npm warn deprecated` on every deploy run and
representing a growing supply-chain risk as Cloudflare's API shifts under a
frozen action. The officially-endorsed replacement is `cloudflare/wrangler-action`,
which ships current Wrangler + Miniflare 4 and is the documented forward path.

ADR-0001 §7 requires an ADR merged before any build-system or
deployment-mechanism change. This ADR records that decision for PR #355.

## Decision

Replace `cloudflare/pages-action` with `cloudflare/wrangler-action` (v3.15.0,
pinned to SHA `9acf94ace14e7dc412b076f2c5c20b8ce93c79cd`) in
`.github/workflows/deploy-frontend.yml`.

The structured `branch:` input from `pages-action` is replaced with a
`--branch=` argument inside the `command:` string. To prevent shell injection
via a crafted branch name, the user-controlled `github.head_ref` value is
exposed as an environment variable (`PAGES_BRANCH`) and referenced as
`$PAGES_BRANCH` in the command — not interpolated directly via `${{ }}`.

`pull-requests: write` is removed from the workflow permissions block. It was
exercised by `pages-action` to post preview-URL PR comments; `wrangler-action`
does not post PR comments, so the permission has no exercised use. Per the
manifesto least-privilege rule, retaining unused permissions widens the
`GITHUB_TOKEN` blast radius without delivering value. A follow-up workstream
that re-introduces the auto-comment step will re-add the permission alongside
the step that requires it.

## Consequences

### Easier

- No more `npm warn deprecated` noise from Miniflare v2 on every deploy run.
- `wrangler-action` is Cloudflare's officially-supported deploy path; future
  Wrangler features are accessible without another action swap.
- `GITHUB_TOKEN` blast radius reduced: `pull-requests: write` dropped.
- Shell-injection risk from a crafted branch name is eliminated by the env-var
  isolation pattern.

### Harder

- Preview URLs are no longer auto-posted as PR comments. The URL remains visible
  in the job log and on the Cloudflare Pages dashboard. A follow-up
  `actions/github-script` step can re-introduce the comment (re-adding
  `pull-requests: write` at that point).

### Different

- The deploy step uses a `command:` string rather than structured action inputs.
  This is the canonical `wrangler pages deploy` invocation per Cloudflare's
  documentation for `wrangler-action`.
