# ADR-0043: Nature/forest visual direction

## Status

Accepted, 2026-05-16. Supersedes ADR-0005 §4 (palette) and §5 (typography); supersedes ADR-0008 (Nunito FOUT strategy). Cites ADR-0034 (a11y baseline) for the contrast verification matrix below.

## Context

The first-cut WordSparrow look — charbon background, sage/dusty-pink accents, Nunito Variable everywhere — reads as "developer-themed" to the maintainer. Two design mockups (handed in via `~/Downloads/wordsparrow-light-direction.html` and `wordsparrow-textures.html`, transcribed inline below for permanence) pivot the brand toward a paper-and-forest aesthetic: cream paper, forest-deep ink, moss-green brand, honey cursor, terracotta error, Fraunces+Outfit+Lekton typography, a redrawn sparrow, and a signature texture combining a silent grain layer with sparse botanical line drawings.

This change is non-trivial — it supersedes the palette and typography decisions in ADR-0005 — and warrants an ADR per ADR-0001 §7 and CLAUDE.md.

## Decision

### 1. Light-only theme

Drop the dark default. The new palette is light-only; dark mode is removed from the roadmap rather than reimagined. Justification: the visual brief is paper-and-ink, not "two themes that happen to share components"; carrying a dual-mode infrastructure forward would dilute the editorial intent of every color choice. If demand for dark mode resurfaces post-launch, it gets its own ADR and its own palette.

### 2. Palette

All values mirror `wordsparrow-light-direction.html`. The semantic-token layer in `frontend/panda.config.ts` (introduced by the 2026-05-08 amendment to ADR-0005) absorbs the change without component-level edits.

| Semantic token | Old hex (charbon) | New hex (paper) | Source name |
|---|---|---|---|
| `bg` | `#17181d` | `#faf6eb` | papier crème |
| `surface` | `#21222a` | `#ffffff` | cellule |
| `surfaceElevated` | (neutral.600) | `#f5efe0` | papier chaud |
| `border` | `#30323d` | `#e0d8c4` | bordure sable |
| `gridLine` | `#30323d` | `#d4ccb8` | trait de grille |
| `fg` | `#e8e8eb` | `#1f2e25` | forêt profonde |
| `fgMuted` | `#cccfd4` (neutral.200) | `#6a7565` | encre sourde |
| `accent` / `accentText` | `#a0b394` | `#5a8a4a` | mousse |
| `accentBg` | (primary.800) | `#dfeacb` | mousse pâle |
| `accentHover` | (primary.700) | `#3a5a2a` | mousse profonde |
| `onAccent` | (primary.900) | `#ffffff` | sur mousse |
| `secondaryAccent` / `focusRing` | `#e8a3b3` | `#c89456` | miel |
| `secondaryBg` / `focusBg` | (secondary.800) | `#fbedd0` | miel pâle |
| `secondaryText` / `onSurfaceVariant` | (secondary.300) | `#7a4e1a` | miel profond |
| `error` / `errorText` | (secondary.500) | `#b85540` | terracotta |
| `errorBg` | (secondary.900) | `#f5dccc` | terracotta pâle |
| `success` family | aliases of primary | aliases of moss — unchanged role |

Semantic intent (verbatim from the mockup's *Notes* panel):
- **Mousse vert** carries the brand and the validated state — calm success.
- **Miel ambré** carries the cursor and in-progress state — warm action.
- **Terracotta** carries error — distinct, organic, never alarming.

### 3. Typography

Replace Nunito Variable with a three-family stack:

- **Display / headings:** Fraunces (variable, opsz 9–144, italic + roman). Used for `h1`–`h3`, the wordmark, section eyebrows. Italic for the "Sparrow" half of the wordmark and for `lede`/`tagline` copy.
- **Body / UI:** Outfit (variable, 300–600). Replaces Nunito everywhere as the default sans.
- **Mono:** Lekton (unchanged from ADR-0008). Still used only for definition-cell clue text + occasional code spans.

All three families self-hosted under `frontend/public/`. `fontaine` continues to generate fallback metrics. `font-display: swap`. `unicode-range` partitioning matches the existing Nunito setup.

### 4. Sparrow + wordmark

The sparrow keeps its 5-shape, 36×24 viewBox construction but adopts the mockup's exact path data (subtly different proportions; eye radius `0.95` not `0.85`):

```
<path d="M 1 11 L 9 12 L 7 17 L 4 15 Z"/>   <!-- wing -->
<ellipse cx="15" cy="13" rx="9" ry="6"/>    <!-- body -->
<circle cx="23" cy="9" r="5"/>               <!-- head -->
<path d="M 27 8 L 33 9 L 27 10 Z"/>          <!-- beak -->
<circle cx="24" cy="8" r="0.95" fill="paper"/> <!-- eye -->
```

Body parts: `fill="currentColor"` → resolves to `accent` (moss). Eye fill: `bg` (papier crème) so it reads as cut paper.

Wordmark stays bicolor: "Word" in `fg` (forêt profonde), roman. "Sparrow" in `accent` (mousse), italic Fraunces with `font-variation-settings: 'opsz' 144`.

### 5. Texture: grain + herbier

Two motifs combined per the mockup's *Combiner sans s'épuiser* note:

- **Grain de papier** — SVG `feTurbulence` noise filter applied to `body` background. Baked-in opacity (~0.12) via `feColorMatrix` so it sits silently under everything without competing for attention.
- **Herbier** — sparse botanical line drawings (lance leaves, ovals, twigs, single leaves) placed in margins/corners of `ContentPage` routes. Strict rule: **never on the grid itself, never on `/grille`**. The grid playing surface stays clean.

The "branche habitée" hero illustration (option 3 from the textures mockup) is deferred. Grain + herbier already deliver the editorial nature feel without bespoke per-page art.

## Consequences

**Easier:**
- The semantic-token layer pays its dividend — palette swap is one config file edit, components inherit automatically.
- The new ramp is grounded in real natural materials, so future product copy ("le moineau", "la mousse", "le miel") has lexical grounding.
- A single light palette is half the surface area of a dual-mode design system.

**Harder:**
- OG images (`frontend/public/og-*.png`) ship with the old dark palette until regenerated. Tracked as a follow-up; not a launch blocker.
- Three new font families increase the initial-load font budget. `fontaine` mitigates layout shift; `font-display: swap` mitigates FOUT. Lighthouse should remain green per ADR-0008.
- The grain background reduces pixel-perfect predictability of screenshot tests. None exist today; if they're added later, they need to anchor on tokens, not raw rendered images.
- Dropping dark mode closes a door for accessibility preferences. Decision accepted; revisit if user feedback demands it.

**Different:**
- Every component that used `bg` / `fg` / `accent` now renders against cream, not charbon. Visual regressions to verify: cell-state contrasts on `/grille`, ProgressBar locked/pending segments, focus rings, button hover states, error banners.

## Verification matrix (AA contrast)

Approximate WCAG 2.1 ratios for the key pairs the new palette introduces. These are the targets PR 1 (palette swap) must clear via `pnpm a11y`. Borderline pairs are flagged for empirical verification — if axe-core fails them on cream, the implementation PR adjusts the affected token (typically by darkening the lighter side) and revises this matrix.

| Pair | Hex pair | Ratio (approx.) | AA pass? | Notes |
|---|---|---|---|---|
| `fg` on `bg` | `#1f2e25` on `#faf6eb` | ~13.5:1 | ✓ | Strong; body text everywhere. |
| `fgMuted` on `bg` | `#6a7565` on `#faf6eb` | ~4.6:1 | ✓ borderline | Just above the 4.5:1 small-text floor. Verify. |
| `accent` on `bg` | `#5a8a4a` on `#faf6eb` | ~4.7:1 | ✓ borderline | Holds for AA large; verify for inline links/CTAs. |
| `accentHover` on `bg` | `#3a5a2a` on `#faf6eb` | ~7.8:1 | ✓ | Used for hover states; comfortably above floor. |
| `onAccent` on `accent` | `#ffffff` on `#5a8a4a` | ~4.7:1 | ✓ borderline | Button label on solid moss; AA large yes, AA small borderline. |
| `secondaryText` on `secondaryBg` | `#7a4e1a` on `#fbedd0` | ~7.5:1 | ✓ | Honey-deep on honey-pale; comfortable. |
| `secondaryText` on `bg` | `#7a4e1a` on `#faf6eb` | ~7.3:1 | ✓ | Cursor-cell text on cream gutters. |
| `errorText` on `errorBg` | `#b85540` on `#f5dccc` | ~3.9:1 | ✗ small / ✓ large | **Adjustment needed if used for body-size text.** Implementation PR commits to one of: (a) darken `errorText` to `#9b3f2a` (~5.3:1), (b) restrict the pair to AA-large surfaces (toast headlines, button labels ≥18.66px), (c) move body error copy to `errorText` on `bg`. Default: option (a). |
| `errorText` on `bg` | `#b85540` on `#faf6eb` | ~4.6:1 | ✓ borderline | Inline error text on the page. |
| `accentBg` text contrast | text on `#dfeacb` | varies | n/a | `accentBg` is a *fill*, not a text background. If used behind text, pair with `fg` (~13:1) or `accentHover` (~6:1). |

All values are SI estimates; the load-bearing gate is `pnpm a11y` against the assembled UI on PR 1.

## Rollout

PR 0 (this ADR) lands first per ADR-0001 §7. Implementation follows in four PRs as scoped in `docs/superpowers/plans/eager-cuddling-gem.md` (note: that plan is brainstorm-private; the implementation PR descriptions will summarise the relevant slice). Order:

1. PR 1 — palette swap in `panda.config.ts` + `index.css` + `manifest.webmanifest` + `index.html` theme-color.
2. PR 2 — fonts (Fraunces + Outfit) self-hosted; Nunito files removed; token rename.
3. PR 3 — sparrow path data + wordmark italic + favicon + manifest icons.
4. PR 4 — texture: grain `background-image` + `HerbierCorner` decoration component.

PR 5 (OG image regeneration) is optional follow-up.

## Supersedes / amendments

- Supersedes ADR-0005 §4 (six-color palette) and §5 (single-typeface Nunito justification).
- Supersedes ADR-0008 in part: Nunito-specific FOUT mitigation is retired; Fraunces + Outfit inherit the same self-hosted + `fontaine` + `font-display: swap` strategy. The shape of ADR-0008 (self-host + fallback metrics + `swap`) stands; only the family roster changes.
- Cites ADR-0034 (a11y baseline) for the AA contrast targets the verification matrix must satisfy.
- Does not affect ADR-0002 (frontend stack — Panda CSS stays), ADR-0001 §3 (schema-first — irrelevant to a frontend visual change), ADR-0025 (RGPD — irrelevant).
