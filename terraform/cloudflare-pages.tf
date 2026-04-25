# Cloudflare Pages project for the Bliss frontend.
#
# Per ADR-0004 §3 ("schema in repo") and ADR-0004 Notes ("the Cloudflare
# Pages project declared as IaC in the repo") plus PR #10 finding 1 fix:
# the project itself is Terraform-managed so its existence is auditable
# from `git log` rather than from a screenshot of the Cloudflare dashboard.
#
# Direct Upload model (ADR-0004 §3): GitHub Actions builds the bundle and
# pushes it to Cloudflare via `cloudflare/pages-action`. Cloudflare itself
# does *not* clone the repo or run the build — the `build_config` block
# is metadata Cloudflare's dashboard echoes back, not an active build
# trigger. We populate it accurately so the dashboard reflects reality.
#
# Production branch is sourced from var.production_branch; preview
# deployments fire on every other branch automatically (Pages default).

resource "cloudflare_pages_project" "frontend" {
  account_id        = var.cloudflare_account_id
  name              = var.pages_project_name
  production_branch = var.production_branch

  build_config = {
    # These mirror the GitHub Actions deploy workflow. They are informational
    # under the Direct Upload model — the workflow is the source of truth
    # for what actually runs.
    build_command   = "pnpm --filter frontend build"
    destination_dir = "frontend/dist"
    root_dir        = "/"
    build_caching   = true
  }

  # No source binding: Direct Upload mode means no Git provider connection.
  # Pages auto-creates preview deployments for any non-production branch
  # we push to via the action; production fires when branch == main.

  deployment_configs = {
    preview    = {}
    production = {}
  }
}
