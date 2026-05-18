// HTTP adapter for the `AuthClient` port (ADR-0002 §7, Phase 5).
// Cookie-bearing calls pass `credentials: 'include'` so `__Host-ws_session`
// is sent cross-origin; identity-api CORS permits the frontend origins.
import type { AuthClient, GetMeResult } from '@/application/auth';
import { InvalidDisplayNameError } from '@/application/auth';
import { createIdentityApiClient, type IdentityApiClient } from '@/infrastructure/api/identity/client';

export interface HttpAuthClientOptions {
  /** Absolute base URL of the identity API, e.g. `https://auth.wordsparrow.io`. */
  readonly baseUrl: string;
  readonly fetch?: typeof globalThis.fetch;
}

export function createHttpAuthClient(
  options: HttpAuthClientOptions | { readonly baseUrl: string; readonly client: IdentityApiClient },
): AuthClient {
  const baseUrl = options.baseUrl;
  const client =
    'client' in options
      ? options.client
      : createIdentityApiClient({ baseUrl, fetch: options.fetch });

  return {
    async whoami() {
      const { data, error, response } = await client.GET('/v1/auth/whoami', {
        credentials: 'include',
      });
      if (response.status === 401) return null;
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`whoami failed: ${detail}`);
      }
      return { userId: data.userId, displayName: data.displayName };
    },

    async getMe(): Promise<GetMeResult> {
      const { data, error, response } = await client.GET('/v1/users/me', {
        credentials: 'include',
      });
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`getMe failed: ${detail}`);
      }
      return {
        id: data.id,
        displayName: data.displayName,
        createdAt: data.createdAt,
        providers: data.providers.map((p) => ({
          provider: p.provider,
          linkedAt: p.linkedAt,
          emailOptIn: p.emailOptIn,
        })),
      };
    },

    async updateMe(displayName: string) {
      const { error, response } = await client.PATCH('/v1/users/me', {
        credentials: 'include',
        body: { displayName },
      });
      if (response.status === 400) {
        const detail = error?.detail ?? error?.title ?? 'invalid display name';
        throw new InvalidDisplayNameError(detail);
      }
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`updateMe failed: ${detail}`);
      }
    },

    async deleteMe() {
      const { error, response } = await client.DELETE('/v1/users/me', {
        credentials: 'include',
      });
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`deleteMe failed: ${detail}`);
      }
    },

    async logout() {
      const { error, response } = await client.POST('/v1/auth/logout', {
        credentials: 'include',
      });
      if (error) {
        const detail = error.detail ?? error.title ?? `HTTP ${response.status}`;
        throw new Error(`logout failed: ${detail}`);
      }
    },

    signInUrl(returnTo: string): string {
      const url = new URL('/v1/auth/google/login', baseUrl);
      url.searchParams.set('return_to', returnTo);
      return url.toString();
    },
  };
}
