# ADR-0005: Brand Identity (WordSparrow)

## Status

Accepted

## Context

The project has shipped its hello-world deployment, the first OpenAPI
contract, and an interactive grid component. It now needs a real product
identity beyond the internal codename "Bliss":

- The maintainer has registered `wordsparrow.io` as the production domain.
- The `frontend/` static bundle currently renders `<h1>Bliss</h1>` and ships
  with PWA manifest fields, document title, and README copy still bound to
  the codename.
- The FSL-1.1-MIT license now has a real product name to protect.
- The audience is primarily French (mots fléchés is a French puzzle
  variant), so the brand must work in French even if the wordmark stays
  English.

This ADR records the brand identity so subsequent implementation work-
streams (theming, custom domain, marketing copy) have a single source of
truth. Per ADR-0001 §7, a project rename is non-trivial and requires an
ADR before the implementation PR.

The selection space considered:

- **Codename retirement vs. dual-name.**
- **Visual mood** across newspaper-classical, sharp-contemporary, and
  playful-mascot directions.
- **Logo timing** (DIY now vs. wait for a designer commission).
- **Typography** within free-licensed Google Fonts that support French
  diacritics.
- **Palette** committed to specific hex values rather than vague
  directions.

## Decision

### 1. Product name and codename retirement

- **Product name:** `WordSparrow` (one word, mixed case in prose, all
  lowercase in domain and identifier contexts: `wordsparrow.io`,
  `wordsparrow` package name).
- **Domain:** `wordsparrow.io` (registered).
- **Codename:** `Bliss` is retired. The repository, package identifiers,
  Cloudflare Pages project, Terraform variables, route copy, PWA manifest,
  README, and ADR cross-references all flip to WordSparrow in subsequent
  workstreams. Repo-level rename (`Ishou/bliss` → `Ishou/wordsparrow`) is
  its own workstream because it has GitHub-side coordination cost; this
  ADR commits to the intent so the rename is not relitigated each time
  the codename appears in a new file.
- **Pronunciation / spelling.** Always `WordSparrow` (no space, no
  hyphen). Lowercase forms (`wordsparrow`) only in machine identifiers.

### 2. Positioning and voice

- **Positioning:** modern *mots fléchés* and word puzzles, designed to
  feel as good on a phone as on paper, with a friendly tone that doesn't
  take itself too seriously.
- **Voice (English):** warm, direct, slightly cheeky. No corporate
  filler. Aligned with `CLAUDE.md`'s "no sycophancy" rule applied to
  user-facing copy.
- **Voice (French):** `tu` by default, casual app register (Duolingo-
  style), with optional `vous` if a later usability study shows it
  matters to the audience.
- **Inclusive language** per `MANIFESTO.md` Ethics: copy is gender-
  neutral by default, accessible language, no jargon-as-gatekeeping.

### 3. Visual mood: playful, Duolingo-direction

The visual identity leans **playful and warm** rather than newspaper-
classical or sharp-contemporary:

- Generous border-radii (rounded shapes everywhere, not sharp corners).
- Warm cream / white surfaces, not pure white.
- Vibrant primary that signals energy (coral, not corporate blue).
- Friendly rounded sans-serif typography, no serifs in v1.
- Celebratory micro-moments (color shifts on word completion) rather
  than minimalist neutrality.
- Subtle texture/depth via soft shadows; no skeuomorphism, no flat-only
  either.

Reference points: Duolingo, NYT Games (the warmer recent direction),
Wordscapes — adapted to a French *mots fléchés* sensibility (less
cartoon, no mascot animations in v1).

### 4. Color palette

Six tokens, committed to specific hex values. These are *named tokens*,
not raw hex; raw hex values appear in Panda config only. Implementation
workstream extends each into a 50–900 ramp.

| Token | Hex | Role |
|---|---|---|
| `sparrow` (primary) | `#FF6B5B` | Brand primary; CTAs, focused-cell ring, brand wordmark |
| `sun` (accent) | `#FFC857` | Win states, badges, highlights |
| `cream` (surface) | `#FFFAF3` | Page background; the paper-like canvas |
| `sand` (subtle) | `#E5DCC6` | Cell dividers, blocked-cell fill, secondary borders |
| `ink` (foreground) | `#1B2845` | Body text, grid letters, primary headings |
| `breath` (white) | `#FFFFFF` | Letter-cell input background, floating-card surfaces |

**Accessibility constraint (WCAG AA):** `sparrow` (#FF6B5B) does not meet
WCAG AA contrast against `breath` or `cream` (~2.6:1; minimums are 3:1
for UI components and 4.5:1 for text). Any element placing foreground
content on a `sparrow` background **must** use `ink` (#1B2845) as the
foreground color (~5.4:1, passes AA). White-text-on-coral is excluded from
this palette. The implementation workstream must verify contrast at each
`sparrow` usage site before shipping.

A small `signal` palette is reserved (added later, not in v1):
- `signal.error` — distinct from `sparrow` so error states don't conflict
  with the brand.
- `signal.success` — distinct from `sun` so success states pop separately
  from in-grid highlights.

Dark mode is **not** in scope for v1. Reasoning: the playful + warm
direction is harder to translate to dark surfaces without losing
character, and the manifesto's "Right-sized infra" / "no premature
features" applies to design too. Revisit when a real user requests it
or when night-time-puzzle telemetry justifies it.

### 5. Typography

Single typeface for v1:

- **Nunito Variable** (Google Fonts, OFL license).
  - Variable axis covers weights 200–1000.
  - Full Latin Extended-A coverage (handles French diacritics including
    `œ`, `ç`, `é`, `à`, `ê`, etc., plus Spanish/Portuguese/etc. for
    future markets).
  - Rounded-sans aesthetic matches the playful direction without being
    childish.
  - Self-hosted via the build pipeline (no Google Fonts CDN call —
    privacy + offline-PWA friendly).

Type scale (mobile-first; desktop scales bump 1.125×):

| Token | Size (rem / mobile) | Weight | Use |
|---|---|---|---|
| `display` | 2.5 | 800 | Hero / brand wordmark |
| `xl` | 1.875 | 700 | Page titles |
| `lg` | 1.5 | 700 | Section titles |
| `md` | 1.125 | 600 | Subsection / card titles |
| `body` | 1 | 400 | Default body |
| `sm` | 0.875 | 400 | Secondary / labels |
| `xs` | 0.75 | 500 | Captions / metadata |
| `cell` | 1.5 | 700 | Letter-cell content (with `font-variant-numeric: tabular-nums`) |

A second typeface is **not** introduced in v1. Reasoning: a single face
covers the playful brief, ships in one self-hosted file, and avoids the
"two-font contrast" trap that often makes brands feel busy.

### 6. Logo: type-only wordmark, designer commission deferred

- **v1 logo:** the word `WordSparrow` set in Nunito Variable at the
  `display` token (2.5 rem mobile / 2.8125 rem desktop), weight 800,
  letter-spacing slightly tightened, color `ink`.
- **Wordmark variants:** none in v1. No icon-only mark, no monochrome
  variant, no animated form.
- **Designer commission:** queued for after first 100 paying users (or
  six months, whichever first). The brief will hand the designer this
  ADR plus a usage audit. Until then, the type-only wordmark is the
  canonical mark.

### 7. Language defaults

- **UI primary language:** French (`fr-FR`). The audience is French and
  the puzzle form is French; an English-first UI would be a tone error.
- **UI fallback:** English (`en-US`). All copy is authored in both;
  English is the canonical authoring language with French translations
  reviewed by a native speaker before release.
- **Brand name:** stays English, no localization. `WordSparrow` is
  pronounced and written the same way in any locale.
- **Wordmark `lang` attribute:** the `<html>` root reflects the user's
  detected/selected language; the wordmark element carries
  `lang="en"` so screen readers pronounce it correctly even when the
  surrounding page is French.

### 8. What this ADR explicitly does not decide

- The actual logo design once a designer is engaged.
- Marketing-site copy beyond the in-app wordmark and one tagline (the
  tagline itself is deferred to the implementation workstream's PR
  body, not pinned here).
- Dark mode. Held until justified by user signal.
- Animation / motion language. Held until the first interactive
  feature beyond the static grid lands.
- Iconography system. Held until more than three icons exist on screen
  at once (currently zero).
- Email / transactional design. Held until there are accounts and
  transactional emails to send.
- Sound design / haptics. Held indefinitely; revisit if a competitive
  app makes it table stakes.
- Pricing-page visual design. Held until pricing exists.

## Consequences

### Easier

- A single source of truth for brand decisions: theming PRs, marketing
  copy, deploy ADRs all reference this file instead of relitigating the
  palette / typography / voice each time.
- Implementation workstream is mechanical: take each named token here,
  encode in `panda.config.ts`, propagate through components.
- Repo / Pages-project / Terraform rename is now a single workstream
  with a well-bounded scope ("everything that says Bliss flips to
  WordSparrow") instead of an open-ended audit.
- Future designer commission has a brief: this ADR plus a usage audit.
  No "what does the brand stand for?" round-tripping.

### Harder

- Committing to specific hex values means changing them later requires
  an ADR amendment, not a one-line config change. Acceptable: the
  values were chosen carefully; if we want to change them in three
  months, the amendment cost will tell us whether the change is worth
  it.
- Single-typeface decision means the visual identity has less contrast
  between display and body than a two-typeface system. Mitigation: the
  Nunito variable axis handles weight contrast (800 display vs. 400
  body), which carries enough visual hierarchy for the playful brief.
- French-first UI means English copy can't be the first thing authored;
  every string has to travel through translation. At v1 scale this is
  ~5 strings; manageable. At full-product scale this is a translation
  pipeline (deferred ADR).

### Different

- "Bliss" stops being the public name. Internal docs that reference
  "Bliss" are now historical artifacts and stay as-is for accuracy
  (this ADR doesn't backdate prior ADRs); new docs use "WordSparrow".
- The PWA install banner, browser tab title, mobile home-screen icon
  text all flip to "WordSparrow" once the implementation workstream
  lands. The Cloudflare Pages preview URLs (`bliss-cb4.pages.dev`)
  stay until the rename + custom-domain workstreams.
- License copyright (`Copyright 2026 ISHO IT EURL`) is unaffected.
  Trademark on "WordSparrow" is its own decision (deferred to a
  separate workstream alongside revenue planning).

## Notes

This ADR is revisited if any of the following occur:

- A user research session shows the playful direction misaligns with
  the actual French *mots fléchés* audience expectations.
- The designer commission produces a logo whose colors clash with the
  palette pinned here. The palette is the constraint; the logo
  conforms, or this ADR is amended.
- A performance budget pushes back on Nunito's variable-font size
  (~75 KB vs. a static-weight subset at ~12 KB). Subsetting is the
  first response, replacement only after.
- A material change to the audience (e.g., expansion to non-French
  markets as primary) breaks the French-first UI assumption.
- Three or more brand-related decisions accumulate as exceptions; per
  manifesto, that triggers a brand-revision review.

Implementation workstreams that follow this ADR (each is its own PR
under the 400-line cap):

1. **`feat/frontend-rebrand-wordsparrow`** — Panda token replacement,
   Nunito Variable self-hosted, `<h1>` and PWA manifest update,
   `index.html` lang/title/favicon, README intro update. Single
   bounded context (`frontend/`), <250 lines hand-written.
2. **`chore/repo-rename-bliss-to-wordsparrow`** — repo rename
   (`Ishou/bliss` → `Ishou/wordsparrow`), Cloudflare Pages project
   rename, Terraform `pages_project_name` default flip, internal
   identifier sweep. Coordinated with the maintainer because the repo
   rename is a GitHub-side click.
3. **`feat/infra-wordsparrow-custom-domain`** — add `wordsparrow.io`
   as a custom domain in `terraform/cloudflare-pages.tf`, document the
   DNS setup at the registrar, update `docs/deploy.md`. Has a manual
   step (DNS records at the registrar) the maintainer applies once.

The order matters: workstream 1 can land independently. Workstream 2
should land before workstream 3 so the custom domain attaches to a
project named `wordsparrow`, not `bliss`.
