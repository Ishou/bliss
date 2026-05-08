/// <reference types="vite/client" />

// Build-time env injected by Vite. The composition root resolves
// `VITE_GRID_API_URL` once and threads it through router context, so
// `ui/` and `application/` never read `import.meta.env`. ADR-0007 §5
// adds the per-surface `VITE_MOCK_GRID_API` / `VITE_MOCK_GAME_API`
// flags so preview deploys swap the real APIs for spec-driven Mock
// Service Worker handlers and `pnpm dev` can mock just the game-api
// while still hitting a real grid backend on localhost.
interface ImportMetaEnv {
  /** Absolute base URL of the Grid API (production target). */
  readonly VITE_GRID_API_URL: string;
  /**
   * Absolute base URL of the Game API (production target). Used by the
   * lobby route's `HttpLobbyClient` and `WebSocketGameClient` adapters.
   * The composition root resolves it once and threads the adapters
   * through router context per ADR-0002 §7.
   */
  readonly VITE_GAME_API_BASE_URL: string;
  /**
   * `'true'` to mount MSW handlers for the Grid REST surface
   * (`/v1/puzzles/...`). `.env.preview` enables this for self-
   * contained previews; `.env.development` keeps it off so a real
   * grid backend on localhost serves real puzzles + answer-validation.
   * String, not boolean — Vite injects env vars verbatim.
   */
  readonly VITE_MOCK_GRID_API: 'true' | 'false';
  /**
   * `'true'` to mount MSW handlers for the Game REST + WebSocket
   * surfaces (`/v1/lobbies/...`). Enabled in both `.env.preview` and
   * `.env.development` so multiplayer works without the game-api
   * Helm chart running locally.
   */
  readonly VITE_MOCK_GAME_API: 'true' | 'false';
  /**
   * Multiplayer feature flag (ADR-0018 §10). When `'true'`, the
   * `/lobby/:lobbyId` route is registered and the lobby/game adapters
   * are instantiated. Defaults to `'false'` in every environment;
   * production flips to `'true'` only after the `game-api` Helm chart
   * is live. Expires no later than 90 days after first enablement
   * (MANIFESTO: expired flags fail CI).
   */
  readonly VITE_FEATURE_MULTIPLAYER: 'true' | 'false';
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
