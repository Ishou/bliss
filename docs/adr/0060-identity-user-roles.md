# ADR-0060: Identity user roles + `UserRoleChanged` event

## Status
Accepted

## Context
The survey context needs to give *maintainer-authored* correctifs gold training
weight (Specs B–D of the 2026-05-30 clue-gen gold-weighting rollout). That
requires distinguishing a maintainer from any other authenticated rater. Users
live in the identity bounded context; survey cannot import identity and learns
about users only through NATS events. We also anticipate other role-gated
features (admin/moderation/campaign control), so the primitive should be
reusable rather than single-use.

## Decision
Add a `role` column to `identity_users` (`player` default, `maintainer`) and a
`Role` domain type. Role changes publish a fire-and-forget `UserRoleChanged`
event on `wordsparrow.user.role-changed` (ADR-0049 posture), which survey (and
future consumers) cache.

Roles are assigned only by a configure-in-cluster Helm `post-install,post-upgrade`
bootstrap Job that runs the identity image with `--set-maintainer-roles` and a
configured `MAINTAINER_USER_IDS` list. There is deliberately **no HTTP
role-mutation endpoint** in this ADR.

### Threat model
- **Asset:** the `maintainer` role (confers gold training weight today; more
  later).
- **Mutation surface:** only the bootstrap Job. The id list is a chart value /
  k8s Secret, never code; a DB write needs cluster access. No runtime
  privilege-escalation path, no IDOR on a role route.
- **Event exposure:** internal NATS subject, NetworkPolicy-guarded; payload
  carries `userId`, `role`, `changedAt` — no secrets.
- **Event-loss failure mode:** tolerable. The Job re-runs on every
  `helm upgrade` (re-emitting only on actual change), and consumers must be
  idempotent with their own reconciliation. Delivery is not guaranteed.
- **Spoofing:** only identity publishes to the subject; consumers trust the
  in-cluster broker, as with `user.deleted`.

## Consequences
- Easier: a single reusable authz primitive for current and future role gates;
  survey can gate `training_weight` on a cached maintainer role.
- Harder: cross-context role propagation now has a contract to maintain
  (`UserRoleChanged`); consumers must handle best-effort delivery.
- Deferred: a runtime role-management API (YAGNI until a second assignment need).
