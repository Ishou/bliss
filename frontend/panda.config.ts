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
        // Primary ramp — brand sage (validation, CTA, accent). The
        // "charbon + sage" palette uses sage as both the brand colour
        // AND the success/validation signal — they share the same
        // visual language ("you completed something", "this is the
        // primary action"). Anchors:
        //   .500 = #A0B394 (sage main — solid CTA, timer, accent text)
        //   .800 = #1F2820 (dark sage tint — validated cell bg)
        //   .900 = #1A2218 (very dark sage — text on solid sage)
        primary: {
          50:  { value: '#ECF1E8' },
          100: { value: '#DBE3D2' },
          200: { value: '#C5D2B7' },
          300: { value: '#B3C2A4' },
          400: { value: '#ABBC9D' },
          500: { value: '#A0B394' },
          600: { value: '#88A07A' },
          700: { value: '#5A6B52' },
          800: { value: '#1F2820' },
          900: { value: '#1A2218' },
        },
        // Secondary ramp — clue pink (def-cell surface + focus ring).
        // Anchors:
        //   .400 = #E8A3B3 (clue cell surface — light dusty pink)
        //   .900 = #3A141E (text on clue cells — dark plum)
        secondary: {
          50:  { value: '#FBF1F2' },
          100: { value: '#F8E4E7' },
          200: { value: '#F2C6CC' },
          300: { value: '#ECB5BE' },
          400: { value: '#E8A3B3' },
          500: { value: '#DC88A1' },
          600: { value: '#C46A87' },
          700: { value: '#985166' },
          800: { value: '#5C2B3A' },
          900: { value: '#3A141E' },
        },
        // Neutral ramp — charbon (cool charcoal grays). Anchors:
        //   .50  = #E8E8EB (text)
        //   .300 = #80818B (muted text)
        //   .500 = #30323D (line / border)
        //   .600 = #292B34 (surface-2, elevated panels, progress bg)
        //   .700 = #21222A (letter-cell surface)
        //   .800 = #17181D (page bg)
        //   .900 = #0E0F12 (block / void)
        // Slight cool-blue lean (vs warm-pink in prior twilight ramp)
        // is intentional — keeps the cool-and-pink contrast crisp.
        neutral: {
          50:  { value: '#E8E8EB' },
          100: { value: '#C0C0C5' },
          200: { value: '#9A9BA3' },
          300: { value: '#80818B' },
          400: { value: '#5E5F69' },
          500: { value: '#30323D' },
          600: { value: '#292B34' },
          700: { value: '#21222A' },
          800: { value: '#17181D' },
          900: { value: '#0E0F12' },
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
        // a token — it's a shadow tint, not a theme-swap dimension;
        // shadows on dark surfaces always want this near-black-with-
        // a-touch-of-warmth, regardless of brand hue.
        floating: { value: '0 2px 4px rgba(10, 10, 12, 0.6)' },
      },
    },
    semanticTokens: {
      colors: {
        // ── Surfaces ────────────────────────────────────────────────
        bg:             { value: '{colors.neutral.800}' },  // page background (charbon)
        surface:        { value: '{colors.neutral.700}' },  // letter cell ("slot") — neutral charcoal
        // `surfaceVariant` is the def-cell ("clue") surface. The charbon
        // palette pairs a DARK plum surface with a LIGHT dusty-pink
        // text — the inverse of the spec's "rose bg / dark-rose text"
        // initial cut, picked after a side-by-side review preferred the
        // dark plum reading on the charbon page. Both halves stay in
        // the secondary ramp so the clue surface keeps its rose family.
        surfaceVariant: { value: '{colors.secondary.900}' }, // def cell — dark plum
        surfaceMuted:   { value: '{colors.neutral.900}' },   // block / inert-cell void
        // Elevated charcoal surface (e.g. progress-bar track behind a
        // sage fill). Useful when a secondary surface is needed without
        // taking on the rose clue colour.
        surfaceElevated:{ value: '{colors.neutral.600}' },
        // Progress-bar pending-segment fill (the gray "unfilled but
        // entered" region that sits on top of `surfaceElevated`). Was
        // `border` (`neutral.500` #30323D), which only contrasted 1.24:1
        // against the surrounding card surface (`neutral.700` #21222A) —
        // failing WCAG 2.1 SC 1.4.11 "Non-text Contrast" (3:1). Bumped
        // to `neutral.300` (#80818B): 4.09:1 vs card surface and 4.58:1
        // vs page bg, comfortably AA. Component-specific token rather
        // than reusing `border` so future track tweaks don't drag every
        // UI border with them.
        progressTrackPending: { value: '{colors.neutral.300}' },

        // ── Foreground ──────────────────────────────────────────────
        fg:                 { value: '{colors.neutral.50}' },   // primary text on charcoal surfaces
        // Was `neutral.300` (#80818B); bumped to `neutral.200`
        // (#9A9BA3) so de-emphasized body text (`fontSize: sm`,
        // `fontWeight: 400`) clears WCAG AA's 4.5:1 threshold on every
        // surface in the palette: 6.44:1 on `bg`, 5.76:1 on `surface`,
        // 5.03:1 on `surfaceElevated`. The previous value rendered the
        // accueil progress widget at 4.09:1 — visibly low and a
        // serious axe violation. See ADR-0034.
        fgMuted:            { value: '{colors.neutral.200}' },  // de-emphasized text (timer label, "Grille n°")
        // Text colour on the dark-plum clue surface. `secondary.400`
        // (the light dusty pink that USED to be the surface) gives a
        // ~7:1 contrast on `surfaceVariant`'s plum — well clear of AA.
        onSurfaceVariant:   { value: '{colors.secondary.400}' },

        // ── Lines ───────────────────────────────────────────────────
        border:         { value: '{colors.neutral.500}' },  // UI borders (lobby, primitives)
        gridLine:       { value: '{colors.neutral.500}' },  // grid cell perimeter + stack divider
        muted:          { value: '{colors.neutral.500}' },  // legacy alias of border (used by some lobby code)

        // ── Brand · primary (sage — also the success colour) ────────
        // `accent` / `accentText` are aliases — same value, different
        // semantic intent at the call site (one reads as "the brand
        // colour", the other as "the colour for branded text").
        accent:         { value: '{colors.primary.500}' },  // sage — wordmark, current-clue, timer
        accentText:     { value: '{colors.primary.500}' },  // alias for clarity
        accentBg:       { value: '{colors.primary.800}' },  // dark-sage tint (letter-in-word bg, validated cell bg)
        accentHover:    { value: '{colors.primary.700}' },  // hover state of solid primary CTAs

        // ── Brand · secondary (pink — clue surface + focus ring) ────
        secondaryAccent:{ value: '{colors.secondary.500}' },
        secondaryText:  { value: '{colors.secondary.300}' },
        secondaryBg:    { value: '{colors.secondary.800}' },

        // ── Status ─────────────────────────────────────────────────
        // `success` aliased onto sage primary (validation cells,
        // progress, timer). `error` keeps secondary (pink) for now;
        // could move to a dedicated signal ramp later.
        success:        { value: '{colors.primary.500}' },
        successBg:      { value: '{colors.primary.800}' },
        successText:    { value: '{colors.primary.500}' },
        error:          { value: '{colors.secondary.500}' },
        errorBg:        { value: '{colors.secondary.800}' },
        errorText:      { value: '{colors.secondary.300}' },

        // ── On-bg foregrounds ───────────────────────────────────────
        // Text colors paired with specific solid backgrounds.
        onAccent:       { value: '{colors.primary.900}' },  // text on solid sage CTA / "Vérifier" button
        onSecondary:    { value: '{colors.neutral.50}' },   // text on solid secondary bg

        // ── Focus ───────────────────────────────────────────────────
        // The focused letter cell uses `focusBg` for its background
        // and an inset 1.5 px `focusRing` (pink) for the visual
        // signal — see Cell.tsx letterInput `_focus`. `focusBg` is a
        // literal hex (the warm charcoal-with-pink-hint that escapes
        // the ramps); a future theme can replace the value here
        // without touching components.
        focusBg:        { value: '#2A1C22' },
        focusRing:      { value: '{colors.secondary.400}' },
      },
    },
  },
});
