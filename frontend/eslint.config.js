// Flat ESLint config for the frontend bounded context.
//
// `eslint-plugin-boundaries` is the TypeScript equivalent of ArchUnit
// (per ADR-0001 §5 and ADR-0002 §7). It is a non-negotiable architecture
// gate. The element-types declared below mirror the hexagonal layering
// on disk: domain ← application ← infrastructure / ui.
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactPlugin from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import importPlugin from 'eslint-plugin-import';
import boundaries from 'eslint-plugin-boundaries';

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'styled-system/**',
      'node_modules/**',
      'coverage/**',
      '*.config.js',
      '*.config.cjs',
      '*.config.ts',
      'vitest.setup.ts',
      'public/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: {
      react: reactPlugin,
      'react-hooks': reactHooks,
      import: importPlugin,
      boundaries,
    },
    languageOptions: {
      parserOptions: {
        ecmaVersion: 2022,
        sourceType: 'module',
        ecmaFeatures: { jsx: true },
      },
      globals: { ...globals.browser, ...globals.es2022 },
    },
    settings: {
      react: { version: 'detect' },
      'import/resolver': {
        typescript: { project: './tsconfig.app.json' },
      },
      'boundaries/elements': [
        { type: 'domain', pattern: 'src/domain/**' },
        { type: 'application', pattern: 'src/application/**' },
        { type: 'infrastructure', pattern: 'src/infrastructure/**' },
        { type: 'ui', pattern: 'src/ui/**' },
      ],
      // `src/main.tsx` is the composition root: it is allowed to wire ui
      // and infrastructure together. Every other `src/**/*` file must
      // belong to a declared element-type.
      'boundaries/include': ['src/**/*'],
      'boundaries/ignore': ['src/main.tsx'],
    },
    rules: {
      ...reactPlugin.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
      // Hexagonal layering: enforce dependency direction.
      // - domain depends on nothing inside the app;
      // - application may depend on domain only;
      // - infrastructure may depend on domain and application;
      // - ui may depend on application and domain (never infrastructure
      //   directly — adapters are wired via application hooks).
      'boundaries/element-types': [
        'error',
        {
          default: 'disallow',
          rules: [
            { from: 'domain', allow: [] },
            { from: 'application', allow: ['domain'] },
            {
              from: 'infrastructure',
              allow: ['domain', 'application'],
            },
            { from: 'ui', allow: ['domain', 'application'] },
          ],
        },
      ],
      'boundaries/no-unknown': 'error',
      'boundaries/no-unknown-files': 'off',
    },
  },
  {
    files: ['tests/**/*.{ts,tsx}', '**/*.test.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.es2022, ...globals.node },
    },
    rules: {
      'boundaries/element-types': 'off',
      'boundaries/no-unknown': 'off',
    },
  },
);
