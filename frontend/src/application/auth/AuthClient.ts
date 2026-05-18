// Application-layer port for the identity-api surface (ADR-0002 §7, Phase 5).
export interface WhoAmIResult {
  readonly userId: string;
  readonly displayName: string;
}

export interface LinkedProvider {
  readonly provider: 'google' | 'apple';
  readonly linkedAt: string;
  readonly emailOptIn: boolean;
}

export interface GetMeResult {
  readonly id: string;
  readonly displayName: string;
  readonly createdAt: string;
  readonly providers: ReadonlyArray<LinkedProvider>;
}

// Thrown by `updateMe` on HTTP 400; carries the server's RFC 7807 `detail`.
export class InvalidDisplayNameError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InvalidDisplayNameError';
  }
}

// Cookie-bearing calls require `__Host-ws_session`; the adapter sets
// `credentials: 'include'` per call.
export interface AuthClient {
  whoami(): Promise<WhoAmIResult | null>; // null on 401
  getMe(): Promise<GetMeResult>;          // throws on 401
  updateMe(displayName: string): Promise<void>; // throws InvalidDisplayNameError on 400
  deleteMe(): Promise<void>;
  logout(): Promise<void>;
  signInUrl(returnTo: string): string;
}
