import { defineConfig } from '@pandacss/dev';

// Panda CSS — ADR-0002 §3 + ADR-0005 (WordSparrow brand).
//
// Three-tier color system:
//
//   1. **Ramps** (`tokens.colors`): three 50–900 tonal scales.
//      - `primary`   — brand green (lime-leaf family).
//      - `secondary` — brand pink (dusty rose / sakura family).
//      - `neutral`   — surface tonal ramp; powers all dark surfaces and
//                      text. Slight pink tint at .500–.700 transitions
//                      to neutral gray at .800–.900 (intentional;
//                      "page background is hue-less" is a brand brief).
//
//   2. **Semantic role tokens** (`semanticTokens.colors`): every UI role
//      maps to a ramp shade here. Components reference these names, not
//      ramp shades directly — that's the whole indirection point.
//      Adding a new role is one line below; theme-swapping is changing
//      this file ONLY.
//
//   3. **Components**: reference role tokens (`bg: 'surface'`, `color:
//      'accent'`) or, when a state derivation needs a specific shade
//      (`_hover: { bg: 'primary.800' }`), the renamed ramp.
//
// Theme-swap workflow:
//   1. Re-tune ramps for the new palette (primary / secondary / neutral).
//   2. If the new theme inverts dark↔light, re-map semantic roles to
//      different ramp shades (e.g. `accentText: primary.400` on dark
//      becomes `primary.700` on light; `bg: neutral.800` becomes
//      `neutral.50`). No component code changes required.
//
// Accessibility: WCAG AA contrast is verified at every brand-color
// usage site in the components that consume the role tokens. The
// `accent` / `accentText` / `accentBg` family is calibrated for
// readable text on dark surfaces; if the theme inverts, the calibration
// must be re-run (see ADR-0005 §4).
export default defineConfig({
  preflight: true,
  include: ['./src/**/*.{ts,tsx}'],
  exclude: [],
  jsxFramework: 'react',
  outdir: 'styled-system',
  theme: {
    tokens: {
      colors: {
        // Primary ramp — brand green (lime-leaf family). Anchors:
        //   .400 = #A2D481 (vivid soft leaf — was leaf.400)
        //   .600 = #70985E (deeper sage — was leaf.600)
        // Was named `leaf`. Renamed to `primary` so theme-swap can
        // change the brand hue without touching component code.
        primary: {
          50: { value: '#ECFDF5' },
          100: { value: '#DBF3D5' },
          200: { value: '#C4E8B7' },
          300: { value: '#B3DD9A' },
          400: { value: '#A2D481' },
          500: { value: '#89B670' },
          600: { value: '#70985E' },
          700: { value: '#587C4B' },
          800: { value: '#406038' },
          900: { value: '#2A4226' },
        },
        // Secondary ramp — brand pink (sakura / dusty rose family).
        // Anchors:
        //   .300 = #F2C2C4 (light dusty pink — was blossom.300)
        //   .500 = #DC88B1 (saturated rose-pink — was blossom.500)
        // Was named `blossom`. Renamed to `secondary` for the same
        // reason as primary.
        secondary: {
          50: { value: '#FBF1F2' },
          100: { value: '#F8E4E7' },
          200: { value: '#F4D5D8' },
          300: { value: '#F2C2C4' },
          400: { value: '#E8A5B0' },
          500: { value: '#DC88B1' },
          600: { value: '#C46894' },
          700: { value: '#A04D78' },
          800: { value: '#783A5C' },
          900: { value: '#4F2440' },
        },
        // Neutral ramp — surface tonal scale. Replaces the prior named
        // primitives (aubergine / plum / mauve / bramble / pitch /
        // petal) with a single 50–900 scale. Anchors at .500–.700 are
        // the existing sakura-twilight surface colours; .800 (page bg)
        // intentionally drops the pink tint to neutral gray per the
        // brand brief ("page background recedes, no hue"); .900 is the
        // near-black void / block fill.
        //
        // .100–.400 are interpolated padding stops — currently unused
        // by any component; available for future hover / disabled /
        // muted states without needing new primitives.
        neutral: {
          50:  { value: '#F5EAEC' }, // was `petal`     — fg text
          100: { value: '#D8C0CB' }, // interpolated
          200: { value: '#B894A4' }, // interpolated
          300: { value: '#90697E' }, // interpolated
          400: { value: '#7A5266' }, // interpolated
          500: { value: '#6E3D55' }, // was `bramble`   — borders, grid lines
          600: { value: '#5E3450' }, // was `plum`      — letter cell ("slot")
          700: { value: '#4A2A40' }, // was `mauve`     — def cell ("clue")
          800: { value: '#1B1B1F' }, // was `aubergine` — page bg (hue-shift to neutral)
          900: { value: '#0A0A0C' }, // was `pitch`     — block / inert-cell void
        },
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
      shadows: {
        // Subtle near-black glow under floating surfaces (toggle,
        // dialog, dropdown). The rgba is intentionally not bound to
        // a token — it's a shadow tint, not a theme-swap dimension;
        // shadows on dark surfaces always want this near-black-with-
        // a-touch-of-warmth, regardless of brand hue.
        floating: { value: '0 2px 4px rgba(10, 10, 12, 0.6)' },
      },
    },
    semanticTokens: {
      colors: {
        // ── Surfaces ────────────────────────────────────────────────
        bg:             { value: '{colors.neutral.800}' },  // page background
        surface:        { value: '{colors.neutral.600}' },  // letter cell ("slot")
        surfaceVariant: { value: '{colors.neutral.700}' },  // def cell ("clue") + elevated panels
        surfaceMuted:   { value: '{colors.neutral.900}' },  // block / inert-cell void

        // ── Foreground ──────────────────────────────────────────────
        fg:             { value: '{colors.neutral.50}' },   // primary text
        fgMuted:        { value: '{colors.neutral.300}' },  // de-emphasized text (currently unused; available)

        // ── Lines ───────────────────────────────────────────────────
        border:         { value: '{colors.neutral.500}' },  // UI borders (lobby, primitives)
        gridLine:       { value: '{colors.neutral.500}' },  // grid cell perimeter + stack divider
        muted:          { value: '{colors.neutral.500}' },  // legacy alias of border (used by some lobby code)

        // ── Brand · primary ─────────────────────────────────────────
        // `accent` / `accentText` are aliases — same value, different
        // semantic intent at the call site (one reads as "the brand
        // colour", the other as "the colour for branded text").
        accent:         { value: '{colors.primary.400}' },  // current-clue marker, branded text
        accentText:     { value: '{colors.primary.400}' },  // alias for clarity
        accentBg:       { value: '{colors.primary.700}' },  // soft brand-tint bg (letter-in-word, hover bg)
        accentHover:    { value: '{colors.primary.800}' },  // hover state of solid primary CTAs

        // ── Brand · secondary ───────────────────────────────────────
        secondaryAccent:{ value: '{colors.secondary.500}' },
        secondaryText:  { value: '{colors.secondary.300}' },
        secondaryBg:    { value: '{colors.secondary.800}' },

        // ── Status ─────────────────────────────────────────────────
        // Currently aliased onto the brand ramps (success ≈ primary,
        // error ≈ secondary). A future palette swap can re-map these
        // to a dedicated `signal` ramp without touching components —
        // ADR-0005 §4 reserves space for that.
        success:        { value: '{colors.primary.400}' },
        successBg:      { value: '{colors.primary.800}' },
        successText:    { value: '{colors.primary.300}' },
        error:          { value: '{colors.secondary.500}' },
        errorBg:        { value: '{colors.secondary.800}' },
        errorText:      { value: '{colors.secondary.300}' },

        // ── On-bg foregrounds ───────────────────────────────────────
        // Text colors paired with specific solid backgrounds.
        onAccent:       { value: '{colors.neutral.900}' },  // text on bright primary bg (focused-cell letter)
        onSecondary:    { value: '{colors.neutral.50}' },   // text on solid secondary bg

        // ── Focus ───────────────────────────────────────────────────
        focusRing:      { value: '{colors.primary.500}' },  // focus-visible outline
      },
    },
  },
});
