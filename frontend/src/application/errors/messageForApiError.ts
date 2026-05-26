// TypeError (fetch transport failure) → French network copy; anything else → generic French fallback.

export function messageForApiError(cause: unknown): string {
  if (cause instanceof TypeError) {
    return 'Connexion impossible. Vérifiez votre réseau et réessayez.';
  }
  return 'Une erreur est survenue. Réessayez.';
}
