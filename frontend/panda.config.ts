import { defineConfig } from '@pandacss/dev';

// Panda CSS — ADR-0002 §3 + ADR-0005 (WordSparrow brand).
//
// Six brand primitives from ADR-0005 §4 (`leaf`, `blossom`, `cream`,
// `sand`, `ink`, `breath`). The `leaf`/`blossom` pair gets a 50–900 ramp
// so the WCAG escape hatches in ADR-0005 §4 are usable: `.700` shades
// for brand-colored text on light surfaces, `.50` shades for tinted
// backgrounds. Other primitives ship as single tokens because they are
// surface/foreground anchors, not gradient sources.
//
// Accessibility: per ADR-0005 §4, foreground text on a `leaf` or
// `blossom` background must be `ink`, never `breath`/white. Brand-
// colored text on a light surface uses the `.700` ramp shade.
export default defineConfig({
  preflight: true,
  include: ['./src/**/*.{ts,tsx}'],
  exclude: [],
  jsxFramework: 'react',
  outdir: 'styled-system',
  theme: {
    tokens: {
      colors: {
        // Leaf ramp anchored on the brand-supplied lime-leaf greens:
        //   .400 = #A2D481 (vivid soft leaf — primary accent)
        //   .600 = #70985E (deeper sage — secondary accent / hover)
        // Was emerald (#10B981 family). Neighbors interpolate for a
        // smooth 50 → 900 ramp.
        leaf: {
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
        // Blossom ramp anchored on the brand-supplied pinks:
        //   .300 = #F2C2C4 (light dusty pink)
        //   .500 = #DC88B1 (saturated rose-pink, slight purple lean)
        // .400 keeps the prior #E8A5B0 (sits naturally between the
        // two anchors).
        blossom: {
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
        // Twilight palette (ADR-0005 §4 amendment, 2026-05-07): dark-
        // theme pivot. Replaces the prior warm cream/sand/ink/breath
        // set. Single theme — no light-mode toggle.
        //
        // Layout: the **page** is a neutral dark gray (no hue) that
        // recedes; the **grid surfaces** carry all the brand pink, in
        // a sakura-rose family (hsl ≈ 325°, slightly cooler than the
        // warmer dusky-rose `blossom` ramp at 345°). Letter cells
        // (`plum`) sit one notch lighter than definition cells
        // (`mauve`) so the slot a player types into pops above the
        // clue surrounding it. Block cells (`pitch`) are the darkest
        // fill, near-black neutral — the inert "void" square. Text
        // (`petal`) is a warm pink-white that picks up the surface
        // hue at AA contrast on every surface.
        //
        // The cooler hue (vs the prior 345° rose) gives surfaces a
        // sakura-twilight feel — petal-pink with a hint of lavender
        // — instead of the wine/burgundy reading of warmer rose.
        //
        // (Primitive names predate the dark-theme pivot and are kept
        // as abstract handles; their values are what the palette
        // delivers.)
        aubergine: { value: '#1B1B1F' }, // page background — neutral dark gray
        plum: { value: '#5E3450' },      // letter cells — lifted sakura twilight
        mauve: { value: '#4A2A40' },     // definition cells — medium sakura twilight
        bramble: { value: '#6E3D55' },   // borders + grid lines — sakura rule
        pitch: { value: '#0A0A0C' },     // inert-cell fill — near-black neutral
        petal: { value: '#F5EAEC' },     // foreground text — warm pink-white
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
        // Pitch-based shadow for the dark palette — subtle near-black
        // glow under floating surfaces (toggle, dialog, dropdown).
        // Was ink-tinted for the prior light palette.
        floating: { value: '0 2px 4px rgba(10, 10, 12, 0.6)' },
      },
    },
    semanticTokens: {
      colors: {
        // Surfaces and foregrounds — twilight palette (dark-only).
        bg: { value: '{colors.aubergine}' },
        fg: { value: '{colors.petal}' },
        // Brand-coloured text and CTAs lift to leaf.400 from the prior
        // .700 — on the dark page .700 (#0B815A) sits below the WCAG
        // AA threshold, while .400 (#34D399) renders ~7:1 contrast and
        // matches the "vibrant green pop" the brand wants.
        accent: { value: '{colors.leaf.400}' },
        // Grid surfaces. `surface` is the letter-cell input bg (lifted
        // sakura, so typed letters pop above the clue surface);
        // `definition` is the clue-cell bg (medium sakura, one notch
        // darker); `block` is the inert-square fill (deepest pitch —
        // the "void" square in mots-fléchés); `border` separates
        // lobby/primitive UI.
        surface: { value: '{colors.plum}' },
        definition: { value: '{colors.mauve}' },
        block: { value: '{colors.pitch}' },
        border: { value: '{colors.bramble}' },
        muted: { value: '{colors.bramble}' },
        // Grid line — used for both the cell perimeter and the dual-clue
        // half-cell divider. Solid bramble (sakura rule lifted from the
        // surface family). Single source of truth so cell outlines and
        // stack dividers always match exactly. The grid container's
        // `gap: 1px` + `bg: gridLine` paints internal lines; the same
        // colour edges the perimeter.
        gridLine: { value: '{colors.bramble}' },
      },
    },
  },
});
