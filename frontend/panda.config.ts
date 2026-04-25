import { defineConfig } from '@pandacss/dev';

// Panda CSS — ADR-0002 §3. Tokens minimal by design; expand as the
// design language matures.
export default defineConfig({
  preflight: true,
  include: ['./src/**/*.{ts,tsx}'],
  exclude: [],
  jsxFramework: 'react',
  outdir: 'styled-system',
  theme: {
    tokens: {
      colors: {
        bliss: {
          background: { value: '#0b0d12' },
          surface: { value: '#13161d' },
          text: { value: '#f5f7fa' },
          muted: { value: '#9aa3b2' },
          accent: { value: '#7c5cff' },
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
        sans: { value: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' },
      },
      fontSizes: {
        md: { value: '1rem' },
        lg: { value: '1.5rem' },
        xl: { value: '2.5rem' },
        '2xl': { value: '4rem' },
      },
      fontWeights: { regular: { value: '400' }, bold: { value: '700' } },
      radii: { sm: { value: '4px' }, md: { value: '8px' } },
    },
    semanticTokens: {
      colors: {
        bg: { value: '{colors.bliss.background}' },
        fg: { value: '{colors.bliss.text}' },
        accent: { value: '{colors.bliss.accent}' },
        // Grid surfaces. `surface` is the letter cell background;
        // `definition` is the clue-cell background; `block` is the
        // inert-square background; `border` separates cells.
        surface: { value: '{colors.bliss.surface}' },
        definition: { value: '{colors.bliss.surface}' },
        block: { value: '{colors.bliss.background}' },
        border: { value: '{colors.bliss.muted}' },
        muted: { value: '{colors.bliss.muted}' },
      },
    },
  },
});
