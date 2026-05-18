import type { Lobby, LobbyId, LobbyLifecycleState, Pseudonym, SessionId } from '@/domain/game';

// Application-layer port for the lobby REST surface. The concrete adapter
// (`HttpLobbyClient` in `infrastructure/`) speaks the OpenAPI contract
// declared in `game/api/openapi.yaml`. Routes receive an instance through
// the TanStack Router context so `ui/` keeps zero `infrastructure/` imports
// per ADR-0002 §7. One method per `operationId`.

export interface LobbyClient {
  /**
   * `POST /v1/lobbies`. Mints a new lobby in WAITING with the calling
   * player as the sole member and owner. Throws {@link LobbyClientError}
   * on a non-2xx response — callers `switch` on `error.kind` to render
   * the matching UI banner.
   *
   * Resolves with the freshly-minted lobby intersected with its
   * server-issued `LobbyId`. Unlike {@link getLobby}, the `id` is not
   * known from the URL on this seam — the home-route "Créer une partie
   * multijoueur" button reads it to navigate to `/lobby/:lobbyId`.
   */
  createLobby(args: {
    ownerSessionId: SessionId;
    ownerPseudonym: Pseudonym;
  }): Promise<Lobby & { readonly id: LobbyId }>;

  /**
   * `GET /v1/lobbies/{lobbyId}`. Bootstraps the lobby route loader before
   * the WebSocket opens. Throws {@link LobbyClientError} with
   * `kind: 'not-found'` when the lobby has never existed or has been GC'd
   * (in-memory v1 — ADR-0018 §3).
   */
  getLobby(lobbyId: LobbyId): Promise<Lobby>;

  /**
   * `GET /v1/lobbies/by-code/{code}`. Resolves a human-friendly join
   * code to the canonical lobby resource. Powers the Accueil "Rejoindre
   * avec un code" flow — the resolved `id` is the same `LobbyId` the
   * URL-share path uses, so the caller can navigate to `/lobby/:lobbyId`
   * and let the existing route loader open the WebSocket.
   *
   * Throws {@link LobbyClientError} with:
   *   - `kind: 'validation'` for a malformed code (400 — pattern reject),
   *   - `kind: 'not-found'` when no lobby carries the supplied code (404).
   */
  findByCode(code: string): Promise<Lobby & { readonly id: LobbyId }>;

  /**
   * `GET /v1/sessions/{sessionId}/lobbies`. Lists the lobbies the
   * calling session is a member of, in every lifecycle state, ordered
   * by `lastActivityAt` descending. Powers the Accueil "Mes parties"
   * surface (ADR-0039). An empty array is the "no lobbies" answer —
   * there is no 404 here, which would leak whether the session has ever
   * played. Not paginated.
   */
  listMyLobbies(sessionId: SessionId): Promise<readonly LobbySummary[]>;
}

// Lightweight projection of a lobby for the "Mes parties" list. The
// server-side adapter computes `playerCount` so the summary endpoint
// avoids loading the full player list (and to keep this seam thin).
// `connectedCount` is the subset of `playerCount` whose WebSocket
// session is currently live — drives the `X / Y` display on the surface
// where X = connected, Y = playerCount.
// `title` is absent when the owner did not set one at creation; per
// ADR-0003 §6 optional means absent on the wire, never `null`. `progress`
// drives the per-row progress bar.
export interface LobbySummary {
  readonly id: LobbyId;
  readonly code: string;
  readonly state: LobbyLifecycleState;
  readonly gridConfig: { readonly width: number; readonly height: number };
  readonly playerCount: number;
  readonly connectedCount: number;
  readonly lastActivityAt: string;
  readonly progress: LobbyProgress;
  readonly title?: string;
}

export interface LobbyProgress {
  readonly solvedCells: number;
  readonly totalCells: number;
}

// Typed error envelope. One concrete `Error` subclass with a `kind`
// discriminator so callers route on `kind` instead of HTTP status codes.
// The wire-level problem-details body is preserved on `problem` for
// diagnostic UIs.

export type LobbyClientErrorKind =
  | 'validation' // 400 — request body malformed.
  | 'not-found' // 404 — lobby id unknown / GC'd.
  | 'transient' // 5xx — server-side, safe to retry.
  | 'upstream-unavailable'; // network error — DNS / connection refused / abort.

export interface ProblemDetails {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  readonly instance?: string;
}

export class LobbyClientError extends Error {
  readonly kind: LobbyClientErrorKind;
  readonly status: number | null;
  readonly problem: ProblemDetails | null;

  constructor(args: {
    kind: LobbyClientErrorKind;
    status: number | null;
    problem: ProblemDetails | null;
    message: string;
  }) {
    super(args.message);
    this.name = 'LobbyClientError';
    this.kind = args.kind;
    this.status = args.status;
    this.problem = args.problem;
  }
}
