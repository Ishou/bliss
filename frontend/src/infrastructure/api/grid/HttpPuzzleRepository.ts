import type {
  DailySummariesPage,
  ListDailySummariesOptions,
  PuzzleRepository,
} from '@/application';
import type { Puzzle } from '@/domain';
import { createGridApiClient, type GridApiClient } from './client';
import { apiPuzzleSummaryToDomain, apiPuzzleToDomain } from './mapper';

// HTTP adapter for the application-layer `PuzzleRepository` port. Wraps
// `createGridApiClient` and the wire→domain mapper, lifting RFC 7807
// problem bodies into a flat `Error.message` the route loader's
// `errorComponent` can render. Per ADR-0002 §7 only this layer may
// import the generated client; the composition root (`main.tsx`)
// constructs an instance and threads it through the router context.
export interface HttpPuzzleRepositoryOptions {
  readonly baseUrl: string;
  readonly fetch?: typeof globalThis.fetch;
}

export function createHttpPuzzleRepository(
  options: HttpPuzzleRepositoryOptions | { readonly client: GridApiClient },
): PuzzleRepository {
  const client =
    'client' in options
      ? options.client
      : createGridApiClient({ baseUrl: options.baseUrl, fetch: options.fetch });

  return {
    async fetchById(puzzleId: string): Promise<Puzzle> {
      const { data, error, response } = await client.GET('/v1/puzzles/{puzzleId}', {
        params: { path: { puzzleId } },
      });
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`puzzle fetch failed: ${detail}`);
      }
      return apiPuzzleToDomain(data);
    },
    async fetchDaily(date?: string): Promise<Puzzle | null> {
      const { data, error, response } = await client.GET('/v1/puzzles/daily', {
        params: { query: date != null ? { date } : {} },
      });
      // 404 → null: worker-not-ready sentinel (ADR-0042); all other failures throw.
      if (response.status === 404) return null;
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`daily puzzle fetch failed: ${detail}`);
      }
      return apiPuzzleToDomain(data);
    },
    async listDailySummaries(
      opts: ListDailySummariesOptions = {},
    ): Promise<DailySummariesPage> {
      const query: { from?: string; to?: string } = {};
      if (opts.from != null) query.from = opts.from;
      if (opts.to != null) query.to = opts.to;
      const { data, error, response } = await client.GET('/v1/puzzles/daily/list', {
        params: { query },
      });
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`daily puzzle list failed: ${detail}`);
      }
      return {
        items: data.items.map(apiPuzzleSummaryToDomain),
        hasMore: data.hasMore,
      };
    },
  };
}
