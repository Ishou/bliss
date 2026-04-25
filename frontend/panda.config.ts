import { defineConfig } from '@pandacss/dev';

// Panda CSS configuration for Bliss. Per ADR-0002 §3, design tokens are
// typed and the runtime is zero. Tokens here are intentionally minimal —
// just enough to render the v1 landing route with brand-aware typography
// and spacing. Expand as the design language matures.
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
        sans: {
          value:
            'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
        },
      },
      fontSizes: {
        sm: { value: '0.875rem' },
        md: { value: '1rem' },
        lg: { value: '1.5rem' },
        xl: { value: '2.5rem' },
        '2xl': { value: '4rem' },
      },
      fontWeights: {
        regular: { value: '400' },
        bold: { value: '700' },
      },
      radii: {
        sm: { value: '4px' },
        md: { value: '8px' },
      },
    },
    semanticTokens: {
      colors: {
        bg: { value: '{colors.bliss.background}' },
        fg: { value: '{colors.bliss.text}' },
        accent: { value: '{colors.bliss.accent}' },
      },
    },
  },
});
