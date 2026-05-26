import { describe, expect, it } from 'vitest';
import { messageForApiError } from './messageForApiError';

describe('messageForApiError', () => {
  it('maps fetch TypeError to a French network message', () => {
    // Browser produces these literal strings on CORS rejection / offline.
    expect(messageForApiError(new TypeError('Failed to fetch'))).toBe(
      'Connexion impossible. Vérifiez votre réseau et réessayez.',
    );
    expect(
      messageForApiError(
        new TypeError('NetworkError when attempting to fetch resource.'),
      ),
    ).toBe('Connexion impossible. Vérifiez votre réseau et réessayez.');
  });

  it('maps any other Error to the generic French fallback', () => {
    expect(messageForApiError(new Error('whatever'))).toBe(
      'Une erreur est survenue. Réessayez.',
    );
  });

  it('maps non-Error throwables to the generic French fallback', () => {
    expect(messageForApiError('a string')).toBe('Une erreur est survenue. Réessayez.');
    expect(messageForApiError(undefined)).toBe('Une erreur est survenue. Réessayez.');
    expect(messageForApiError(null)).toBe('Une erreur est survenue. Réessayez.');
    expect(messageForApiError({ random: 'object' })).toBe(
      'Une erreur est survenue. Réessayez.',
    );
  });

  it('never returns an English string', () => {
    const inputs: unknown[] = [
      new TypeError('Failed to fetch'),
      new Error('Bad Request'),
      new Error('Internal Server Error'),
      'NetworkError',
      undefined,
    ];
    for (const input of inputs) {
      const out = messageForApiError(input);
      expect(out).toMatch(/[éèàù]/i); // any French diacritic ⇒ definitely not English
    }
  });
});
