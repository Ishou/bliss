# Fly Postgres — documented absence.
#
# ADR-0007 §1 commits to "Fly Postgres co-located in the same Fly app
# and attached over Fly's internal `.flycast` private network".
#
# Provider deviation: the active community provider `andrewbaxter/fly`
# (v0.1.18, see `versions.tf`) does not expose a `fly_postgres`
# resource. The archived `fly-apps/fly` provider's roadmap had Postgres
# at "todo" when the repo was archived (2024-03-01); no fork has
# shipped it.
#
# This file exists so the Postgres cluster's *intent* is discoverable
# from `terraform/` even when the provider can't declare it
# (CLAUDE.md "All infrastructure defined in code, in the repo").
# Reviewers grepping for "postgres" find the rationale + bootstrap
# commands here, not on a Fly dashboard screenshot.
#
# Bootstrap (one-time, post-merge — see `docs/deploy.md` checklist):
#
#   flyctl postgres create \
#     --name        ${var.fly_postgres_app_name}     # wordsparrow-api-db
#     --region      ${var.fly_region}                # cdg
#     --vm-size     shared-cpu-1x \
#     --volume-size ${var.fly_postgres_volume_size_gb} \
#     --initial-cluster-size 1 \
#     --org         ${var.fly_org}
#
#   flyctl postgres attach ${var.fly_postgres_app_name} \
#     --app ${var.fly_app_name}
#
# The `attach` step injects `DATABASE_URL` into the API app secrets,
# pointing at the `.flycast` private hostname (no public IP,
# ADR-0007 §1).
#
# Migration path: when `andrewbaxter/fly` ships a `fly_postgres`
# resource, this file is replaced with real `resource` blocks and
# `tofu import` brings the existing cluster under management. No data
# migration needed; only Terraform state changes.
#
# Variables consumed by the bootstrap commands (parameterised here so
# they have a `terraform/` home and don't drift):
#   - var.fly_postgres_app_name       (default: wordsparrow-api-db)
#   - var.fly_postgres_volume_size_gb (default: 1)
#   - var.fly_region                  (default: cdg)
#   - var.fly_org                     (default: personal)
#
# This file declares no Terraform resources. `terraform fmt` and
# `terraform validate` exercise nothing here, and `tofu plan` shows no
# diff. The deviation is bounded: the moment `fly_postgres` lands
# upstream, this file becomes real HCL.
