/// <reference types="vite/client" />

// Build-time env injected by Vite. The composition root resolves
// `VITE_GRID_API_URL` once and threads it through router context, so
// `ui/` and `application/` never read `import.meta.env`.
interface ImportMetaEnv {
  readonly VITE_GRID_API_URL: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
