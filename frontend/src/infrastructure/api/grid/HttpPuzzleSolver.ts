import {
  HintRequestError,
  type FilledCellInput,
  type HintResult,
  type PuzzleSolver,
  type ValidationResult,
} from '@/application';
import { createGridApiClient, type GridApiClient } from './client';

// HTTP adapter for the application-layer `PuzzleSolver` port. Wraps
// `createGridApiClient` and the two server-authoritative endpoints
// added in PR #218 — `POST /v1/puzzles/{puzzleId}/validate` and
// `POST /v1/puzzles/{puzzleId}/hints`. RFC 7807 problem bodies are
// flattened into a typed `HintRequestError` for the hint flow (so the
// UI can branch on `kind`) and into a flat `Error.message` for the
// validate flow (route loaders render it verbatim). Per ADR-0002 §7
// only this layer may import the generated client; the composition
// root threads an instance through the router context.

export interface HttpPuzzleSolverOptions {
  readonly baseUrl: string;
  readonly fetch?: typeof globalThis.fetch;
  /**
   * Legacy parameter — retained for callers that still pass it; ignored on
   * the wire. The hints endpoint now authenticates via the
   * `__Secure-ws_session` cookie set by identity-api.
   */
  readonly sessionId?: string;
}

export function createHttpPuzzleSolver(
  options:
    | HttpPuzzleSolverOptions
    | { readonly client: GridApiClient; readonly sessionId?: string },
): PuzzleSolver {
  const client =
    'client' in options
      ? options.client
      : createGridApiClient({ baseUrl: options.baseUrl, fetch: options.fetch });

  return {
    async validate(
      puzzleId: string,
      filledCells: ReadonlyArray<FilledCellInput>,
    ): Promise<ValidationResult> {
      const { data, error, response } = await client.POST(
        '/v1/puzzles/{puzzleId}/validate',
        {
          params: { path: { puzzleId } },
          // Spread to a plain array so a `ReadonlyArray<…>` caller still
          // satisfies the openapi-fetch mutable-array body type.
          body: { filledCells: [...filledCells] },
        },
      );
      if (error || !data) {
        const detail =
          error?.detail ?? error?.title ?? `HTTP ${response.status}`;
        throw new Error(`puzzle validate failed: ${detail}`);
      }
      return {
        solved: data.solved,
        incorrectCells: data.incorrectCells.map((p) => ({
          row: p.row,
          column: p.column,
        })),
      };
    },

    async requestHint(
      puzzleId: string,
      row: number,
      column: number,
    ): Promise<HintResult> {
      const { data, error, response } = await client.POST(
        '/v1/puzzles/{puzzleId}/hints',
        {
          params: { path: { puzzleId } },
          body: { row, column },
        },
      );
      if (error || !data) {
        const detail =
          error?.detail ?? error?.title ?? `HTTP ${response.status}`;
        // Status-code-driven routing is the contract per ADR-0003 §6;
        // we don't parse `error.type` URIs here.
        if (response.status === 429) {
          throw new HintRequestError('budget-exhausted', 0, detail);
        }
        if (response.status === 400) {
          throw new HintRequestError('invalid-coord', null, detail);
        }
        throw new HintRequestError('transient', null, detail);
      }
      return {
        row: data.row,
        column: data.column,
        letter: data.letter,
        hintsRemaining: data.hintsRemaining,
      };
    },
  };
}
