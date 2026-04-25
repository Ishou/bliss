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
        leaf: {
          50: { value: '#ECFDF5' },
          100: { value: '#D1FAE5' },
          200: { value: '#A7F3D0' },
          300: { value: '#6EE7B7' },
          400: { value: '#34D399' },
          500: { value: '#10B981' },
          600: { value: '#0E9F6E' },
          700: { value: '#0B815A' },
          800: { value: '#076244' },
          900: { value: '#044A33' },
        },
        blossom: {
          50: { value: '#FBF1F2' },
          100: { value: '#F6DCE0' },
          200: { value: '#F0C5CB' },
          300: { value: '#ECB5BD' },
          400: { value: '#E8A5B0' },
          500: { value: '#D88A96' },
          600: { value: '#B86E7B' },
          700: { value: '#8E5460' },
          800: { value: '#683E48' },
          900: { value: '#42272D' },
        },
        cream: { value: '#FFFAF3' },
        sand: { value: '#E5DCC6' },
        ink: { value: '#1B2845' },
        breath: { value: '#FFFFFF' },
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
    },
    semanticTokens: {
      colors: {
        // Surfaces and foregrounds — paper-like cream canvas, deep-navy ink.
        bg: { value: '{colors.cream}' },
        fg: { value: '{colors.ink}' },
        // Brand-colored text on light surfaces uses the .700 ramp shade
        // (ADR-0005 §4) so contrast meets WCAG AA at body sizes.
        accent: { value: '{colors.leaf.700}' },
        // Grid surfaces. `surface` is the letter-cell input background
        // (the paper-like white where the player types); `definition` is
        // the clue-cell background (warm sand to recede); `block` is the
        // inert-square fill (deeper sand for separation); `border`
        // separates cells.
        surface: { value: '{colors.breath}' },
        definition: { value: '{colors.sand}' },
        block: { value: '{colors.ink}' },
        border: { value: '{colors.sand}' },
        muted: { value: '{colors.ink}' },
      },
    },
  },
});
