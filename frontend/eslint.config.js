// Flat ESLint config. eslint-plugin-boundaries is the ArchUnit equivalent
// for TS (ADR-0001 §5, ADR-0002 §7) and enforces hexagonal layering on disk.
//
// React linting goes through `@eslint-react/eslint-plugin` (the active
// rewrite under the @eslint-react umbrella) rather than the legacy
// `eslint-plugin-react`, which still pins `eslint: ^…|^9.7` and breaks
// on ESLint 10's `contextOrFilename.getFilename` removal.
// `eslint-plugin-react-hooks` 7.x covers ESLint 10 directly so the hooks
// rules-of-hooks / exhaustive-deps coverage is unchanged.
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import eslintReact from '@eslint-react/eslint-plugin';
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
      '*.config.{js,cjs,ts}',
      'vitest.setup.ts',
      'public/**',
      // Playwright e2e tests use a separate tsconfig with Playwright
      // globals (not jest-dom / vitest); skip the source-tree lint
      // rules to avoid false positives.
      'e2e/**',
      'playwright.config.ts',
      'playwright-report/**',
      'test-results/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  eslintReact.configs['recommended-typescript'],
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks, import: importPlugin, boundaries },
    languageOptions: {
      parserOptions: { ecmaVersion: 2022, sourceType: 'module', ecmaFeatures: { jsx: true } },
      globals: { ...globals.browser, ...globals.es2022 },
    },
    settings: {
      'import/resolver': { typescript: { project: './tsconfig.app.json' } },
      'boundaries/elements': [
        { type: 'domain', pattern: 'src/domain/**' },
        { type: 'application', pattern: 'src/application/**' },
        { type: 'infrastructure', pattern: 'src/infrastructure/**' },
        { type: 'ui', pattern: 'src/ui/**' },
      ],
      // src/main.tsx is the composition root and may wire ui + infrastructure.
      'boundaries/include': ['src/**/*'],
      'boundaries/ignore': ['src/main.tsx'],
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // react-hooks 7.x ships several new behavioural rules that fire
      // on existing code (refs read during render, setState inside
      // useEffect bodies, mutation of values borrowed across renders).
      // Each is legitimate guidance — we will work through the
      // findings in dedicated PRs. Disabling here keeps the ESLint 10
      // / @eslint-react migration to a single workstream.
      'react-hooks/refs': 'off',
      'react-hooks/set-state-in-effect': 'off',
      'react-hooks/immutability': 'off',
      // @eslint-react ships several real-bug detectors that fire on
      // existing code (cascading setState in effects, flushSync usage,
      // ref naming, etc). Each is legitimate guidance — we will work
      // through them in dedicated PRs. Disabling here keeps the
      // ESLint 10 / @eslint-react migration to a single workstream.
      '@eslint-react/set-state-in-effect': 'off',
      '@eslint-react/dom-no-flush-sync': 'off',
      '@eslint-react/purity': 'off',
      '@eslint-react/no-forward-ref': 'off',
      '@eslint-react/no-array-index-key': 'off',
      '@eslint-react/no-context-provider': 'off',
      '@eslint-react/no-use-context': 'off',
      '@eslint-react/exhaustive-deps': 'off',
      '@eslint-react/naming-convention/use-state': 'off',
      '@eslint-react/naming-convention/ref-name': 'off',
      '@eslint-react/naming-convention-ref-name': 'off',
      '@eslint-react/web-api/no-leaked-event-listener': 'off',
      // eslint-plugin-boundaries v6 renamed `element-types` → `dependencies`
      // and switched to object-based selectors. Same wall, new syntax.
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          rules: [
            { from: { type: 'domain' }, allow: [] },
            { from: { type: 'application' }, allow: [{ to: { type: 'domain' } }] },
            {
              from: { type: 'infrastructure' },
              allow: [{ to: { type: 'domain' } }, { to: { type: 'application' } }],
            },
            {
              from: { type: 'ui' },
              allow: [{ to: { type: 'domain' } }, { to: { type: 'application' } }],
            },
          ],
        },
      ],
      // boundaries v6 enforces `no-unknown` more strictly than v5: any
      // import to a path outside the declared element patterns now
      // errors. That includes Panda's `styled-system/*` codegen, which
      // is consumed all over `src/ui/`. The actual cross-context wall
      // we care about is `boundaries/element-types` above; keep that
      // strict and demote `no-unknown` to off rather than trying to
      // teach boundaries about every external module.
      'boundaries/no-unknown': 'off',
      'boundaries/no-unknown-files': 'off',
      // warn/error allowed; new error reporting goes through reportCaughtError in otelTracer.
      'no-console': ['error', { allow: ['warn', 'error'] }],
    },
  },
  {
    files: ['tests/**/*.{ts,tsx}', '**/*.test.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser, ...globals.es2022, ...globals.node } },
    rules: { 'boundaries/dependencies': 'off', 'boundaries/no-unknown': 'off' },
  },
  // UI layer must not render raw Error.message — use messageForApiError() or route-level typed-error mapping.
  {
    files: ['src/ui/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: "MemberExpression[property.name='message']",
          message:
            "Don't render `Error.message` in the UI — it leaks browser/English strings. Use `messageForApiError(cause)` from `@/application/errors`, or map typed errors to local French copy at the route. Justified exceptions need an eslint-disable-next-line with a one-line rationale.",
        },
      ],
    },
  },
);
