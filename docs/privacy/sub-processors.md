# Sub-processors Register

> Authoritative list of third parties that process personal data on
> behalf of WordSparrow. Every PR that introduces a new vendor in the data
> path must update this file.

## Current sub-processors

### Hetzner Online GmbH

- **Role.** Infrastructure provider. Hosts the k3s cluster (game-api,
  grid-api, Postgres CNPG, MariaDB for Matomo, Matomo itself), the
  floating IP (ADR-0012), and the Storage Box used for backups
  (ADR-0010).
- **Location.** Germany (Falkenstein, Nuremberg). Data does not leave
  the EU.
- **Categories of data processed.** Visitor IP addresses (load-balancer
  level, not persisted by application code), all data stored in the
  application databases (anonymous `sessionId`, pseudonyms, hint
  usage, Matomo visit data).
- **Lawful basis for transfer.** None required: Hetzner is established
  in the EU and processing is intra-EU.
- **DPA.** Hetzner's standard Auftragsverarbeitungsvertrag (AVV / DPA)
  applies. Reference: <https://www.hetzner.com/AV/AV_en.pdf>.

### Cloudflare, Inc.

- **Role.** DNS and CDN for the static frontend hosted at Cloudflare
  Pages (ADR-0002 / ADR-0009 deployment context). Sees visitor IPs
  because it terminates TLS for the frontend hostname; does not see
  application API traffic when the API hostname is delegated to
  Hetzner directly.
- **Location.** United States (corporate seat). Operates a global Anycast
  network with European edge nodes; EU traffic is generally served from
  EU PoPs.
- **Categories of data processed.** Visitor IP addresses, request
  metadata (URL, user agent), TLS termination.
- **Lawful basis for transfer.** Standard Contractual Clauses (SCC)
  under the EU Commission's 2021/914 implementing decision, plus the
  EU-US Data Privacy Framework where applicable. Cloudflare is
  certified.
- **DPA.** Cloudflare's standard DPA applies. Reference:
  <https://www.cloudflare.com/cloudflare-customer-dpa/>.

## Vendors **not** in scope

The following are explicitly **not** sub-processors at this time. If
they ever are, this file changes:

- **Matomo Cloud** — we self-host Matomo on Hetzner; no data leaves
  for Matomo's infrastructure.
- **Google Analytics, Mixpanel, Amplitude, Segment, PostHog Cloud** —
  rejected during ADR-0025 alternatives review.
- **Sentry, Datadog, New Relic, any APM** — observability rollout
  (ADR-0007) is a separate concern; if a vendor is selected there,
  this file is updated as part of that PR.
- **Email / SMS providers** — WordSparrow has no notification system.
- **LLM providers (OpenAI, Anthropic, Mistral, Cohere)** — clue
  generation is an offline batch pipeline (ADR-0013). LLM calls happen
  on the operator's machine over individual API keys, with input limited
  to public lexical data; **no user-generated content** is sent to any
  LLM provider at runtime. If runtime LLM usage is ever introduced,
  this file is updated.

## Adding a sub-processor

A PR introducing a new sub-processor must:

1. Add a section here with role, location, categories of data, lawful
   basis for transfer, and DPA reference.
2. Update [`privacy-notice.md`](./privacy-notice.md) so users learn of
   the change.
3. Update the retention schedule if the new vendor's retention differs.
4. Be reviewed against the manifesto's `MUST NOT` clauses on data
   sharing.
