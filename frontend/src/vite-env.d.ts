/// <reference types="vite/client" />

// Build-time env injected by Vite. The composition root resolves
// `VITE_GRID_API_URL` once and threads it through router context, so
// `ui/` and `application/` never read `import.meta.env`. ADR-0007 §5
// adds `VITE_USE_MOCK_API` so preview deploys swap the real API for
// spec-driven Mock Service Worker handlers.
interface ImportMetaEnv {
  /** Absolute base URL of the Grid API (production target). */
  readonly VITE_GRID_API_URL: string;
  /**
   * `'true'` for preview builds: register Mock Service Worker so the
   * SPA never reaches the real API. `'false'` (default) for prod.
   * String, not boolean — Vite injects env vars verbatim.
   */
  readonly VITE_USE_MOCK_API: 'true' | 'false';
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// Virtual ESM modules served by the `gridApiExamplesAsVirtualModule`
// plugin in `vite.config.ts`. Each id maps to
// `grid/api/examples/<name>.json`; the plugin reads the file at
// resolve time so the spec stays the single source of truth.
declare module 'virtual:grid-api-examples/*' {
  const content: unknown;
  export default content;
}
