import { describe, expect, it } from 'vitest';
import { normalizeForMatch } from '@/application/survey';

describe('normalizeForMatch', () => {
  it('returns empty string for empty input', () => {
    expect(normalizeForMatch('')).toBe('');
  });

  it('lowercases ASCII', () => {
    expect(normalizeForMatch('ABC')).toBe('abc');
  });

  it('strips diacritics via NFD fold', () => {
    expect(normalizeForMatch('félin')).toBe('felin');
    expect(normalizeForMatch('crème brûlée')).toBe('creme brulee');
  });

  it('strips a leading determiner (le / la / les / l’)', () => {
    expect(normalizeForMatch('Le chat')).toBe('chat');
    expect(normalizeForMatch('la maison')).toBe('maison');
    expect(normalizeForMatch('Les chats')).toBe('chats');
    expect(normalizeForMatch("L'animal")).toBe('animal');
    expect(normalizeForMatch('L’animal')).toBe('animal');
  });

  it('matches the ADR-0061 canonical example: L’animal félin ≡ animal felin', () => {
    expect(normalizeForMatch('L’animal félin')).toBe('animal felin');
    expect(normalizeForMatch('animal felin')).toBe('animal felin');
  });

  it('collapses whitespace and trims', () => {
    expect(normalizeForMatch('  animal   félin  ')).toBe('animal felin');
    expect(normalizeForMatch('a\tb')).toBe('a b');
  });

  it('is idempotent', () => {
    const sample = "L'animal félin domestique";
    const once = normalizeForMatch(sample);
    expect(normalizeForMatch(once)).toBe(once);
  });

  it('does not strip a determiner that appears mid-string', () => {
    expect(normalizeForMatch('chez le chat')).toBe('chez le chat');
  });
});
