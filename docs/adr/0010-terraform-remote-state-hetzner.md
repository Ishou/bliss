# ADR-0010: Terraform remote state and CNPG backups on Hetzner Object Storage

## Status

Proposed. Partially supersedes ADR-0009 §3 (the CNPG backup destination
flips from the "Backblaze B2 / Scaleway interim" interim to Hetzner
Object Storage as the v1 destination). The §3 text edit lands in a
sibling PR; this ADR records the decision.

## Context

ADR-0009 committed the project to a self-managed k3s cluster on Hetzner
Cloud, with Terraform as the only path that creates infrastructure. The
Terraform skeleton from PR #42 left state on the contributor's local
disk: there is no remote backend, no locking, no audit trail, no
recovery if the laptop dies. The next workstream — the Hetzner
cloud-resources module from ADR-0009 §2 — is the first PR that creates
real cloud resources, and it must not land before state has somewhere
durable to live.

The manifesto rules at stake are not subtle. "All infrastructure
defined in code, in the repo. No manual infra changes" presumes a
shared, recoverable state file: a state file on one laptop is one
laptop-loss away from a fork in reality between Terraform's view of
the world and the cloud's. "GitOps: repo is the source of truth" makes
the same demand from the other direction — the source of truth needs a
home that the repo can describe and CI can reach. "Rollback is always
one click" assumes the prior state exists; losing state makes
`terraform apply` go from idempotent to destructive. Local state does
not survive contact with v1.

The decision space has two real axes: **where state lives** (which
S3-compatible bucket on which provider) and **how many providers we
take on**. Cross-provider posture (state on a different cloud than
compute) gives a stronger blast-radius story; single-provider posture
gives a smaller credential surface, one billing relationship, one
vendor to audit, and one set of trust boundaries to reason about.

The maintainer's stated jurisdictional preference is France first,
EU otherwise. State backends in France today means Scaleway or
OVHcloud Object Storage. Both are S3-compatible enough to host
Terraform state — and neither supports the conditional-write
(`If-None-Match`) header that Terraform's native S3 locking
(`use_lockfile = true`) requires. Scaleway has had a public feature
request open since July 2025; OVH lists it on their public roadmap.
Choosing a French bucket today means accepting "no native locking" or
running an external locking service (DynamoDB-equivalent) on a third
provider — strictly more vendor surface to win one jurisdiction
preference. The maintainer accepted EU pref-2 (Germany) to keep the
project on a single cloud rather than split state across a second
vendor for jurisdiction reasons alone.

The ADR-0009 §3 line that reads "until Hetzner Object Storage GAs" is
stale: **Hetzner Object Storage went GA in October 2024**. It supports
S3-compatible API, `If-None-Match` conditional writes (so
`use_lockfile = true` works), bucket versioning, and Object Lock
(retention + legal hold). FSN1 — the same region as the cluster default
in ADR-0009 §2 — is one of the GA regions. The CNPG backup destination
question that ADR-0009 §3 deferred ("Backblaze B2 interim, Scaleway if
EU becomes hard") is also answered by the same bucket-set: Hetzner OS
is now the v1 destination for backups, not the eventual one.

There is one known wrinkle. OpenTofu issue #2605 reports that with
`use_lockfile = true` against Hetzner OS, the SDK currently sends an
`x-amz-content-sha256` header that Hetzner rejects with
`XAmzContentSHA256Mismatch`. The fix is in OpenTofu PR #2606 and not
yet released. The documented workaround is `skip_s3_checksum = true`
in the backend block. This is an OpenTofu bug, not a Hetzner gap; the
flag is time-limited and removed when the OpenTofu fix ships.

## Decision

### 1. Backend: Hetzner Object Storage, FSN1, single project, two buckets

Terraform remote state and CNPG backups both live in a single Hetzner
Cloud project, in the **FSN1 (Falkenstein) region** — co-located with
the cluster from ADR-0009 §2 default. Two buckets, by purpose:

- `<project>-tf-state` — Terraform remote state for every Terraform
  root in the repo (currently `terraform/k8s/`; future roots key into
  the same bucket under distinct prefixes).
- `<project>-cnpg-backups` — CNPG `Backup` and WAL archive
  destination.

The `<project>` prefix is a placeholder that the bucket-bootstrap
follow-up PR pins to a concrete value. The choice is single-project
(one Hetzner Cloud project, one set of API credentials, one billing
boundary) over split-project: split-project would add a credential
boundary the v1 footprint does not need.

### 2. Locking: native S3 locking via `use_lockfile`

Terraform's native S3 backend locking — added in Terraform 1.10 / the
equivalent OpenTofu release — is enabled. No DynamoDB, no external
locking service. The full backend block, with all flags non-AWS S3
backends require:

```hcl
terraform {
  backend "s3" {
    bucket = "<project>-tf-state"
    key    = "k8s/terraform.tfstate"
    region = "fsn1"

    endpoints = {
      s3 = "https://fsn1.your-objectstorage.com"
    }

    use_path_style              = true
    use_lockfile                = true

    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_requesting_account_id  = true

    # Time-limited workaround for OpenTofu issue #2605 / PR #2606:
    # remove once an OpenTofu release containing the fix lands.
    skip_s3_checksum            = true
  }
}
```

The `skip_*` flags are the standard non-AWS-S3 set — without them the
backend tries to talk to AWS-specific endpoints (STS, IMDS) that do
not exist on Hetzner. `use_path_style` matches Hetzner's URL shape.
`skip_s3_checksum = true` is the OpenTofu-2605 workaround; the
follow-up to remove it is tracked in the implementation workstream.

### 3. Versioning and Object Lock

Both buckets enable **bucket versioning**. State recovery from an
accidental overwrite or a bad `terraform apply` is then a versioned
object restore, not a tape-from-backups exercise. The state bucket
relies on versioning alone; Object Lock on the state bucket would
fight legitimate state churn.

The backup bucket additionally enables **Object Lock in compliance
mode** with a Retention period set by the operator-config workstream.
Retention plus versioning is the ransomware / accidental-delete
defence for CNPG backups: a compromised cluster credential cannot
delete or rewrite backups within the retention window, even with the
bucket's own access keys.

### 4. Bucket bootstrap: documented, time-bounded, one-time

Bucket creation is a one-time human step at project bring-up. It is
documented in `docs/deploy.md` — the same file ADR-0009 §10 nominated
for the secrets-bootstrap shape — alongside the rest of the cluster
bring-up. The documentation explains *what* gets created and *why*;
values (access keys, secret keys) are never committed.

The mechanism — Hetzner Console UI vs. a sibling Terraform root using
`aminueza/minio` with local state — is **deliberately not decided
here**. The follow-up implementation PR picks one. Either is
manifesto-compatible: the Console path is a documented one-time human
step (same shape as ADR-0009 §10's `kubectl create secret` interim);
the sibling-root path is IaC at the cost of a chicken-and-egg local
state file for exactly two buckets. Punting the decision to the
implementation PR keeps this ADR about *where state lives*, not *how
the bucket gets created*.

### 5. CNPG backup destination

CNPG `Cluster` resources reference `<project>-cnpg-backups` via the
S3-endpoint configuration on the `Backup` and WAL-archive specs, using
the same FSN1 endpoint shape as §2. Object Lock retention from §3
applies. **This partially supersedes ADR-0009 §3**: the "Backblaze B2
interim, Scaleway swap if EU is hard" plan retires; Hetzner Object
Storage is the v1 destination, not the eventual one. The §3 prose
edit lands as a sibling PR (see Follow-up implementation below).

### 6. Credentials

Two credential pairs, one provider:

- One **Hetzner Cloud API token** (already required by ADR-0009 §2
  for the `hetznercloud/hcloud` provider).
- One **Hetzner Object Storage access-key / secret-key pair** scoped
  to the two buckets in §1.

Both are stored as GitHub Actions secrets, injected at runtime, never
committed. The bootstrap shape mirrors ADR-0009 §10's secrets-bootstrap
interim: documented in `docs/deploy.md`, explicit values held by the
maintainer. Rotation cadence and an automated rotation flow are owned
by the operator-config workstream.

## Consequences

### Easier

- **One trust boundary, one credential set to rotate.** Hetzner Cloud
  API + Hetzner OS keys, both in the same project, both governed by
  one provider's auth model. No second-vendor account to audit, no
  third-vendor locking service to operate.
- **One billing relationship.** Same Hetzner project covers compute,
  block storage, object storage, and bandwidth.
- **Co-location with the workload.** State and backups sit in FSN1
  alongside the cluster. Backup ingest and `terraform apply` see
  same-region latency; egress between cluster and backup bucket stays
  inside Hetzner.
- **State recovery via versioning.** Object versioning on the state
  bucket turns "we lost state" from a project-ending event into a
  versioned-restore.
- **Ransomware / accidental-delete defence on backups.** Object Lock
  retention on `<project>-cnpg-backups` means a compromised cluster
  credential cannot rewrite backup history within the retention
  window. ADR-0009 §3's interim posture (B2, no Object Lock guarantee
  documented) did not have this property.
- **Locking without an extra service.** `use_lockfile = true` removes
  the DynamoDB-equivalent dependency that traditional S3 backends
  needed.

### Harder

- **Jurisdiction is German-EU, not French.** Accepted explicitly. The
  alternative (state on Scaleway / OVH) costs native locking today.
  When Scaleway or OVH ship `If-None-Match` support, this ADR is
  revisited.
- **Time-limited backend workaround.** `skip_s3_checksum = true` is in
  the backend block until OpenTofu ships the fix from issue #2605 /
  PR #2606. A follow-up PR removes the flag and the comment when the
  fix is released; failing to do that leaves an unnecessary skip
  flag in production config.
- **CNPG backups now share blast-radius with the cluster.** A
  region-wide Hetzner outage takes down both the workload *and* its
  backup destination simultaneously; a Hetzner-account compromise
  reaches both. This is a real downgrade compared with cross-cloud
  backup posture (B2 / Scaleway in a separate trust boundary). The
  trade was made deliberately for single-vendor simplicity at v1
  scale; a future ADR can reintroduce a cross-cloud backup mirror if
  uptime / DR posture demands it.
- **Bucket bootstrap is a documented human step.** Same shape as
  ADR-0009 §10's secrets-bootstrap interim — small, explicit, one-time,
  and governed by `docs/deploy.md` so it does not become tribal
  knowledge.

### Different

- **The §3 backup destination flips.** "Backblaze B2 interim,
  Scaleway swap if needed, Hetzner OS once GA" collapses to "Hetzner
  OS now". B2, Scaleway, and OVH leave the trust boundary entirely.
- **Terraform `init` flow changes.** First land of the backend block
  requires `terraform init -migrate-state` to move the local state
  file into the bucket. Documented in `docs/deploy.md` alongside the
  bootstrap step.
- **One new credential type to bootstrap at project bring-up.** The
  HC OS access-key / secret pair joins the HC API token in the
  `docs/deploy.md` checklist.

## Notes

Out of scope, owned by the operator-config workstream:

- Concrete Object Lock retention period for `<project>-cnpg-backups`.
- Cost monitoring / budget alerts on the OS buckets.
- Credential rotation cadence and the automated rotation flow.
- Cross-cloud backup mirror posture (revisit if a real outage or DR
  exercise shows single-provider blast-radius is insufficient).
- Removal of the `skip_s3_checksum` workaround once OpenTofu issue
  #2605 / PR #2606 ships in a release.

### Follow-up implementation

This ADR unblocks three discrete PRs, each independently small enough
to fit the manifesto's 400-line cap:

1. **ADR-0009 §3 amendment PR.** Drops the "Backblaze B2 interim,
   Scaleway swap" language; replaces it with a one-line reference to
   this ADR. Documentation-only.
2. **`terraform/k8s/` backend swap PR.** Adds the `backend "s3" {}`
   block from §2 to the existing skeleton. Documents the
   `terraform init -migrate-state` step in `docs/deploy.md`. Picks
   the bucket-bootstrap mechanism (Console UI vs. `aminueza/minio`
   sibling root) and lands the bootstrap procedure alongside.
3. **CNPG backup wiring PR.** Lands with the cluster bring-up
   workstream from ADR-0009 step 3. Adds the S3-endpoint config to
   the CNPG `Cluster` / `Backup` CRDs and the Object Lock retention
   value to the bucket configuration.

ADR-0009 stays Proposed; this ADR is a sibling, not a successor. No
infrastructure or Terraform code ships in this PR.
