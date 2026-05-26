// Single chokepoint for converting any thrown value from an API call into
// French user-facing copy. UI components MUST NOT render `Error.message`
// directly — see the 2026-05-26 5th-CORS regression: `cause.message` was
// the browser's untranslated "Failed to fetch", which rendered raw to a
// French-speaking audience because both `/sondage` and `/compte` fell
// back to `cause.message` in their catch blocks.
//
// The ESLint guard in `frontend/eslint.config.js` (no-restricted-syntax on
// MemberExpression[property.name='message'] in src/ui/**) blocks any new
// leak at PR time. This helper is the supported escape.
//
// Routes still map their own typed errors (LobbyClientError,
// SignInRequiredError, …) to specific French copy where the message
// matters. This function is the FALLBACK — call it for the unknown-
// shape catch path that previously stringified `cause.message`.

export function messageForApiError(cause: unknown): string {
  // `fetch()` rejects with TypeError for every transport-layer failure:
  // CORS preflight rejection, DNS failure, connection refused, offline.
  // The browser populates `.message` with strings like "Failed to fetch"
  // or "NetworkError when attempting to fetch resource." — never
  // user-facing copy.
  if (cause instanceof TypeError) {
    return 'Connexion impossible. Vérifiez votre réseau et réessayez.';
  }
  return 'Une erreur est survenue. Réessayez.';
}
