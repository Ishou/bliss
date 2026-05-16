import { defineConfig } from '@pandacss/dev';

// Panda CSS — ADR-0002 §3 + ADR-0043 (WordSparrow nature/forest visual
// direction; supersedes ADR-0005 §4 palette).
//
// Three-tier color system:
//
//   1. **Ramps** (`tokens.colors`): four tonal scales (primary / secondary /
//      neutral at 50–900; terra sparse at 100–900 — error has narrower usage
//      than brand ramps).
//      - `primary`   — mousse (moss-green brand + success).
//      - `secondary` — miel (honey amber — cursor, in-progress, focus).
//      - `neutral`   — papier + encre (cream paper bg, forest-deep ink
//                      text, sand bordure mid-tones). Warm, paper-toned.
//      - `terra`     — terracotta (error). Distinct hue from secondary,
//                      so error stops aliasing onto the honey ramp.
//
//   2. **Semantic role tokens** (`semanticTokens.colors`): every UI role
//      maps to a ramp shade here. Components reference these names, not
//      ramp shades directly — that's the whole indirection point.
//      Adding a new role is one line below; palette-swapping is changing
//      this file ONLY.
//
//   3. **Components**: reference role tokens (`bg: 'surface'`, `color:
//      'accent'`) or, when a state derivation needs a specific shade
//      (`_hover: { bg: 'primary.700' }`), the renamed ramp.
//
// Palette-swap workflow (this file is the only edit point for visual
// re-themes):
//   1. Re-tune ramps for the new palette (primary / secondary / neutral
//      / terra). Anchors are the load-bearing stops cited in the ADR;
//      interpolated stops are best-effort perceptual ramps.
//   2. Re-map semantic roles to whichever ramp stop carries the right
//      shade for that role. No component code changes required.
//
// Accessibility: WCAG AA contrast is verified at every brand-color
// usage site via `pnpm a11y` (axe-core through Playwright). ADR-0043's
// verification matrix calls out the borderline pairs; if axe-core
// fails one, tune the affected stop in the interpolated ramp range
// rather than touching anchor hexes. See ADR-0034 for the a11y
// baseline policy.
export default defineConfig({
  preflight: true,
  include: ['./src/**/*.{ts,tsx}'],
  exclude: [],
  jsxFramework: 'react',
  outdir: 'styled-system',
  theme: {
    tokens: {
      colors: {
        // Primary ramp — mousse (moss-green brand + success/validation).
        // ADR-0043 anchors:
        //   .100 = #dfeacb (mousse pâle — accentBg, validated cell bg)
        //   .500 = #3f6431 (mousse main — wordmark, CTA, accent text)
        //                   AA tune: ADR anchor #5a8a4a (~4.5:1 on bg)
        //                   below AA threshold; darkened to ~6.3:1.
        //                   Hue unchanged; luminance shift only.
        //   .700 = #2d4920 (mousse profonde — hover, success text)
        //                   Proportionally darkened to preserve the
        //                   .500 → .700 visual separation.
        primary: {
          50:  { value: '#f0f5e8' },
          100: { value: '#dfeacb' },
          200: { value: '#c5d7a5' },
          300: { value: '#a8c180' },
          400: { value: '#6a9358' },
          500: { value: '#3f6431' },
          600: { value: '#365528' },
          700: { value: '#2d4920' },
          800: { value: '#1f3517' },
          900: { value: '#13230f' },
        },
        // Secondary ramp — miel (honey amber — cursor, in-progress,
        // focus, clue-cell surface). ADR-0043 anchors:
        //   .100 = #fbedd0 (miel pâle — secondaryBg, focusBg, clue bg)
        //   .500 = #c89456 (miel main — secondaryAccent, focusRing)
        //   .700 = #7a4e1a (miel profond — secondaryText, clue text)
        secondary: {
          50:  { value: '#fef7e6' },
          100: { value: '#fbedd0' },
          200: { value: '#f5dca8' },
          300: { value: '#eac480' },
          400: { value: '#dba968' },
          500: { value: '#c89456' },
          600: { value: '#a87538' },
          700: { value: '#7a4e1a' },
          800: { value: '#5a3a14' },
          900: { value: '#3d270c' },
        },
        // Neutral ramp — papier + encre (warm cream paper through
        // sand bordure to forest-deep ink). The page background is
        // paper, not hue-less; the warm cream is intentional ("le
        // papier" anchors the brand). ADR-0043 anchors:
        //   .50  = #faf6eb (papier crème — bg)
        //   .100 = #f5efe0 (papier chaud — surfaceElevated)
        //   .200 = #e0d8c4 (bordure sable — border)
        //   .300 = #d4ccb8 (trait de grille — gridLine)
        //   .500 = #5a655a (encre sourde — fgMuted)
        //                   AA tune: ADR anchor #6a7565 (~4.6:1) was
        //                   borderline; darkened to ~5.6:1. Hue unchanged.
        //   .900 = #1f2e25 (forêt profonde — fg, primary text)
        neutral: {
          50:  { value: '#faf6eb' },
          100: { value: '#f5efe0' },
          200: { value: '#e0d8c4' },
          300: { value: '#d4ccb8' },
          400: { value: '#a8a89a' },
          500: { value: '#5a655a' },
          600: { value: '#4a5450' },
          700: { value: '#2f3a35' },
          800: { value: '#262e2a' },
          900: { value: '#1f2e25' },
        },
        // Terra ramp — terracotta (error). Kept separate from secondary (honey)
        // — error must not share a hue with cursor/focus signals (ADR-0043).
        // Sparse stops only — error has narrower usage than brand ramps.
        // Anchors:
        //   .100 = #f5dccc (terracotta pâle — errorBg)
        //   .500 = #b85540 (terracotta main — error icon, accent)
        //   .700 = #9b3f2a (terracotta foncée — errorText; darkened
        //                   from #b85540 per ADR-0043 Option (a) so
        //                   errorText-on-errorBg clears AA small text)
        terra: {
          100: { value: '#f5dccc' },
          300: { value: '#e2967c' },
          500: { value: '#b85540' },
          700: { value: '#9b3f2a' },
          900: { value: '#5a2417' },
        },
        // (Note: `focusBg` is defined as a *semantic* token only —
        // see `semanticTokens.colors` below. We don't ship a
        // matching primitive because Panda's variable graph emits
        // duplicate atomic classes when a semantic-token name
        // collides with a primitive name, which silently broke the
        // focused-cell bg when both were named `focusBg`. If the
        // theme grows enough to want `focusBg.50`/.900 ramp stops,
        // pick a different family name like `interaction` first.)
      },
      spacing: {
        xs: { value: '0.25rem' },
        sm: { value: '0.5rem' },
        md: { value: '1rem' },
        lg: { value: '2rem' },
        xl: { value: '4rem' },
      },
      fonts: {
        // Self-hosted Nunito Variable (ADR-0005 §5). The variable axis
        // covers 200–1000. `"Nunito Variable fallback"` is a metrics-
        // matched face emitted at build time by `FontaineTransform`
        // (see `vite.config.ts`); it remaps system-ui to Nunito's
        // ascent / descent / line-gap / size-adjust, so the fallback
        // text occupies the same pixels as Nunito and there is no
        // reflow when the woff2 swaps in. The remaining system-ui
        // chain stays as a hard fallback if the build-time face is
        // ever absent (e.g., during dev or in a stale-cache PWA).
        // (Typography pivot to Fraunces + Outfit per ADR-0043 §3 — not yet implemented.)
        body: { value: '"Nunito Variable", "Nunito Variable fallback", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' },
        heading: { value: '"Nunito Variable", "Nunito Variable fallback", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' },
        // Monospace — used ONLY for def-cell clue text (Cell.tsx
        // defText/defStackText). Lekton's constant glyph advance is what
        // lets the offline `scripts/eval/clue_metrics.py` gate be a pure
        // char-count predicate (no PIL/font-metric coupling). ADR-0005
        // §5 amendment documents the exception to the single-typeface
        // rule. Letter-input cells, headings, lobby surfaces, and the
        // CurrentCluePanel stay on the Nunito `body` token.
        mono: { value: '"Lekton", ui-monospace, "SFMono-Regular", Menlo, "Cascadia Code", monospace' },
      },
      // Type scale — ADR-0005 §5. Mobile-first sizes; the `md` breakpoint
      // bumps each by 1.125× via the `md` conditional in route styles.
      fontSizes: {
        display: { value: '2.5rem' },
        xl: { value: '1.875rem' },
        lg: { value: '1.5rem' },
        md: { value: '1.125rem' },
        body: { value: '1rem' },
        sm: { value: '0.875rem' },
        xs: { value: '0.75rem' },
        xxs: { value: '0.625rem' },
        cell: { value: '1.5rem' },
      },
      fontWeights: {
        regular: { value: '400' },
        medium: { value: '500' },
        semibold: { value: '600' },
        bold: { value: '700' },
        black: { value: '800' },
      },
      radii: { sm: { value: '4px' }, md: { value: '8px' } },
      // Panda can't resolve cross-file import constants; token required (ADR-0036).
      sizes: {
        pageMaxWidth: { value: '720px' },
      },
      shadows: {
        // Subtle near-black glow under floating surfaces (toggle,
        // dialog, dropdown). The rgba is intentionally not bound to
        // a token — it's a shadow tint, not a palette-swap dimension;
        // a light cream surface still wants a near-black-with-warmth
        // shadow rather than a tinted one.
        floating: { value: '0 2px 4px rgba(10, 10, 12, 0.6)' },
      },
    },
    semanticTokens: {
      colors: {
        // ── Surfaces ────────────────────────────────────────────────
        bg:             { value: '{colors.neutral.50}' },     // page background (papier crème)
        surface:        { value: '#ffffff' },                 // letter cell ("cellule") — pure white paper
        // `surfaceVariant` is the def-cell ("clue") surface. The nature/
        // forest palette pairs a honey-pale fill (`secondary.100`) with
        // a honey-deep text (`secondary.700`) — the "indice fond / indice
        // texte" pair from the ADR-0043 mockup. Both halves stay in the
        // secondary ramp so the clue surface keeps its honey family.
        surfaceVariant: { value: '{colors.secondary.100}' },  // def cell — miel pâle
        surfaceMuted:   { value: '{colors.neutral.200}' },    // block / inert-cell — bordure sable
        // Elevated cream surface (e.g. progress-bar track behind a moss
        // fill). Slightly warmer than the page bg so layered panels read.
        surfaceElevated:{ value: '{colors.neutral.100}' },
        // Component-specific token; keeps track tweaks isolated from `border` uses
        progressTrackPending: { value: '{colors.neutral.300}' },

        // ── Foreground ──────────────────────────────────────────────
        fg:                 { value: '{colors.neutral.900}' },  // primary text — forêt profonde on papier
        fgMuted:            { value: '{colors.neutral.500}' },  // encre sourde — borderline AA (~4.6:1 on bg), intentional
        // Text colour on the honey-pale clue surface — honey-deep at
        // ~7.5:1 contrast on `surfaceVariant`'s miel pâle. Comfortably AA.
        onSurfaceVariant:   { value: '{colors.secondary.700}' },

        // ── Lines ───────────────────────────────────────────────────
        border:         { value: '{colors.neutral.200}' },  // UI borders — bordure sable
        gridLine:       { value: '{colors.neutral.300}' },  // grid cell perimeter + stack divider — trait de grille
        muted:          { value: '{colors.neutral.200}' },  // legacy alias of border (used by some lobby code)

        // ── Brand · primary (mousse — moss-green, also the success colour) ──
        // `accent` / `accentText` are aliases — same value, different
        // semantic intent at the call site (one reads as "the brand
        // colour", the other as "the colour for branded text").
        accent:         { value: '{colors.primary.500}' },  // mousse — wordmark, current-clue, timer
        accentText:     { value: '{colors.primary.500}' },  // alias for clarity
        accentBg:       { value: '{colors.primary.100}' },  // mousse pâle (letter-in-word bg, validated cell bg)
        accentHover:    { value: '{colors.primary.700}' },  // mousse profonde — hover state of solid primary CTAs

        // ── Brand · secondary (miel — honey amber, cursor + focus) ──
        secondaryAccent:{ value: '{colors.secondary.500}' },
        secondaryText:  { value: '{colors.secondary.700}' },
        secondaryBg:    { value: '{colors.secondary.100}' },

        // ── Status ─────────────────────────────────────────────────
        // `success` aliased onto mousse primary (validation cells,
        // progress, timer). `error` uses the dedicated `terra` ramp — kept
        // separate from secondary (honey) so error and focus carry distinct hues.
        success:        { value: '{colors.primary.500}' },
        successBg:      { value: '{colors.primary.100}' },
        successText:    { value: '{colors.primary.700}' },
        error:          { value: '{colors.terra.500}' },
        errorBg:        { value: '{colors.terra.100}' },
        errorText:      { value: '{colors.terra.700}' },     // #9b3f2a per ADR-0043 Option (a)

        // ── On-bg foregrounds ───────────────────────────────────────
        // Text colors paired with specific solid backgrounds.
        onAccent:       { value: '#ffffff' },                  // text on solid mousse CTA / "Vérifier" button — pure white "sur mousse"
        onSecondary:    { value: '{colors.secondary.700}' },   // text on solid honey bg — miel profond

        // ── Focus ───────────────────────────────────────────────────
        // The focused letter cell uses `focusBg` (honey-pale wash) for
        // its background and an inset 1.5 px `focusRing` (honey main) for
        // the visual signal — see Cell.tsx letterInput `_focus`. Both
        // alias the secondary ramp; honey IS the cursor colour per
        // ADR-0043's semantic intent ("miel — calme action, en cours").
        focusBg:        { value: '{colors.secondary.100}' },
        focusRing:      { value: '{colors.secondary.500}' },
      },
    },
  },
});
