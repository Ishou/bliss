// Panda CSS is integrated through PostCSS. The Vite/PostCSS pipeline
// transforms `@layer` directives in `src/styles/index.css` into the final
// stylesheet, with all token + recipe + utility CSS extracted from
// component source. This is the zero-runtime path described in ADR-0002 §3.
module.exports = {
  plugins: {
    '@pandacss/dev/postcss': {},
  },
};
