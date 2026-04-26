# ADR-0011: OpenTofu for the Hetzner k8s subtree

## Status

Proposed.

## Context

ADR-0010 committed the `terraform/k8s/` subtree to the Hetzner Object
Storage S3 backend with `use_lockfile = true` and a `skip_s3_checksum`
workaround. Both the `required_version` line in §2 and the workaround
text in §Context name "Terraform 1.10+ / OpenTofu 1.10+" interchangeably
and pick neither. That hedge was honest at the time — the backend block
itself is identical across both — but it leaves CI, docs, and the
maintainer's local install ambiguous. This ADR resolves the hedge.

HashiCorp relicensed Terraform from MPL 2.0 to the Business Source
License (BSL) in August 2023, effective from Terraform 1.6 onward. BSL
is source-available, not open-source: non-commercial use is permitted,
but the additional-use grant and the four-year delayed-MPL clause add
license text we have to read every time the tool comes up in a
contributor conversation, a CI image rebuild, or a future "can we mirror
this provider" question. **OpenTofu** is the Linux Foundation fork
created in response, kept on **MPL 2.0**, and is syntax-compatible with
Terraform 1.x — `.tf` files, `required_version` constraints, the S3
backend block from ADR-0010 §2, and the `hetznercloud/hcloud` provider
all work unchanged. As of OpenTofu 1.10 the CLI surface
(`tofu init/plan/apply/fmt/validate`) maps 1:1 to `terraform`.

The `skip_s3_checksum` workaround ADR-0010 §Context calls out is
referenced via **OpenTofu issue #2605 and PR #2606** — the upstream
tracking is in the OpenTofu repository, not the Terraform one.
Continuing to invoke `terraform` while waiting on a fix that lands in
`tofu` releases is a small but real friction: the version we read about
in the issue tracker is not the version we run. Picking OpenTofu aligns
the tool we run with the upstream we follow.

The scope of this ADR is **the `terraform/k8s/` subtree only**. The root
`terraform/` directory (Fly app + Cloudflare Pages + the
`flyctl postgres create` carve-out, owned by ADR-0007) is on the
ADR-0009 migration's teardown list — step 7, "Fly teardown (and
ADR-0007 amendment)". Switching that subtree from `terraform` to `tofu`
weeks before it is deleted is churn for nothing: every line touched is
a line that disappears. The root stays on Terraform until step 7
removes it.

## Decision

### 1. Tooling pick: OpenTofu, pinned to `~> 1.10`

The `terraform/k8s/` subtree is built and operated with **OpenTofu**,
not Terraform. The version constraint already encoded in `versions.tf`
(`required_version = "~> 1.10"` per ADR-0010 §2) is honored by
OpenTofu identically and stays as written.

A `.opentofu-version` file at `terraform/k8s/.opentofu-version` pins a
specific 1.10.x release for reproducibility — same shape as a `.nvmrc`
or a `.tool-versions` entry. The pinned value is **`1.10.9`** (the
latest 1.10.x at the time this ADR was drafted; the implementation PR
re-checks for a newer 1.10.x patch and pins that instead if one has
shipped). The file lands with the implementation PR, not this one.

### 2. Scope: `terraform/k8s/` only

This decision applies to **`terraform/k8s/` and any future subtree
created under that path**. The root `terraform/` directory remains on
Terraform until ADR-0009 step 7 deletes it. No mid-flight switch on
files about to be removed.

When a future subtree is added (e.g. a sibling `terraform/<provider>/`
swap-target module per ADR-0009 §2), it inherits this ADR by default
unless its own ADR opts out.

### 3. CI

Any future workflow that runs Terraform/OpenTofu commands against
`terraform/k8s/**` uses the **`opentofu/setup-opentofu@v1`** action,
configured to read the version from `terraform/k8s/.opentofu-version`.
No `hashicorp/setup-terraform` against this subtree. Workflows that
touch the root `terraform/` continue to use whatever they use today
until step 7 retires them.

### 4. Documentation language

Every example in `docs/deploy.md`, `terraform/k8s/README.md`, and
`terraform/k8s/providers/<provider>/README.md` uses the **`tofu`**
command, not `terraform`. The migration ADR-0010 §Different mentions
("first land of the backend block requires `terraform init
-migrate-state`") is read as `tofu init -migrate-state` going forward;
the ADR-0010 prose itself is not amended — historical record stays as
written. The doc rewrite lands as a sibling implementation PR, not
here.

### 5. `required_version` stays as `~> 1.10`

The `required_version = "~> 1.10"` constraint in
`terraform/k8s/versions.tf` is already correct: OpenTofu honors the
`required_version` field identically, and `~> 1.10` is the same
constraint shape both tools accept. No change.

## Consequences

### Easier

- **License clarity.** MPL 2.0 end-to-end. No BSL text to re-read on
  every contributor onboarding, every CI image rebuild, every
  "can we mirror this provider" question.
- **Upstream tracking aligned with the tool we run.** The
  `skip_s3_checksum` workaround is tied to OpenTofu issue #2605 / PR
  #2606. Running `tofu` means the version that ships the fix is the
  version we deploy, with no translation step.
- **Community-governed roadmap.** The Linux Foundation governance
  model and the public OpenTofu roadmap are easier to track and
  contribute to than HashiCorp's commercial roadmap.

### Harder

- **One-time tooling switch for the maintainer.** `brew install
  opentofu` (or `asdf install opentofu 1.10.9`) instead of
  `terraform`. Both can coexist on `$PATH`; the switch is a
  five-minute step at the next `terraform/k8s/` workstream.
- **IDE plugin reconfiguration.** Editor plugins that target
  "Terraform" need to be pointed at OpenTofu. The major IDE plugins
  (JetBrains, the VS Code HCL/Terraform extensions, `tofu-ls`) all
  support OpenTofu today; the work is configuration, not waiting on
  upstream.
- **Provider registry default changes.** OpenTofu defaults to
  `registry.opentofu.org`, which mirrors the major Terraform Registry
  providers including `hetznercloud/hcloud` (the only provider this
  subtree uses at v1). No gap today; if a future obscure provider is
  unmirrored, the fallback is a `provider_installation` block
  pointing at the original Terraform Registry — documented when and
  if needed.

### Different

- **Documentation and CI reference `tofu`.** `docs/deploy.md` and
  the `terraform/k8s/**/README.md` files use `tofu init`, `tofu
  plan`, `tofu apply`. CI workflows install OpenTofu, not Terraform.
- **Root `terraform/` keeps the old commands.** Until ADR-0009 step 7
  deletes it, `terraform/main.tf` and friends are still operated with
  the `terraform` CLI. The split is explicit and time-bounded.

## Notes

Out of scope:

- Re-evaluating the root `terraform/` subtree. It dies at ADR-0009
  step 7; any switch there would be wasted churn.
- IDE plugin migration mechanics — contributor-local, not repo
  policy.
- Broadening OpenTofu beyond `terraform/k8s/` after step 7 deletes
  the root. If a new root-level Terraform tree is ever introduced,
  its own ADR picks tooling explicitly.

### Follow-up implementation

This ADR unblocks two discrete PRs:

1. **Doc rewrite + secret rename PR.** `docs/deploy.md`,
   `terraform/k8s/README.md`, and
   `terraform/k8s/providers/hetzner/README.md` flip every
   `terraform <subcmd>` example to `tofu <subcmd>`. The same PR
   renames the Hetzner Object Storage credential env vars
   `HCLOUD_OS_ACCESS_KEY` / `HCLOUD_OS_SECRET_KEY` to `S3_ACCESS_KEY`
   / `S3_SECRET_KEY` (the maintainer adopted the shorter names);
   coupling the rename with the tooling switch keeps both edits in
   the same files and avoids rebase friction.
2. **`.opentofu-version` file PR.** Adds
   `terraform/k8s/.opentofu-version` with the pinned 1.10.x release.
   May land with PR 1 or as a sibling — either is fine.

A future CI workflow that runs `tofu fmt -check` and `tofu validate`
on PRs touching `terraform/k8s/**` is a separate workstream; this ADR
makes no commitment on its timing.

ADR-0010 stays Proposed; this ADR is a sibling that resolves its
TF/OpenTofu hedge. No infrastructure or Terraform/OpenTofu config
ships in this PR.
