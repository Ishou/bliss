import { describe, expect, it } from 'vitest';
import { normalizeAnswerLetter } from '@/domain/puzzle/letterNormalize';

describe('normalizeAnswerLetter', () => {
  it.each([
    ['é', 'E', 'acute accent'],
    ['à', 'A', 'grave accent'],
    ['ê', 'E', 'circumflex'],
    ['ç', 'C', 'cedilla'],
    ['ï', 'I', 'diaeresis'],
    ['É', 'E', 'uppercase acute'],
    ['À', 'A', 'uppercase grave'],
    ['Ê', 'E', 'uppercase circumflex'],
    ['Ç', 'C', 'uppercase cedilla'],
    ['Ï', 'I', 'uppercase diaeresis'],
  ])('strips diacritic %s → %s (%s)', (input, expected) => {
    expect(normalizeAnswerLetter(input)).toBe(expected);
  });

  it('passes already-clean ASCII letter through unchanged', () => {
    expect(normalizeAnswerLetter('B')).toBe('B');
  });

  it('returns null for empty string', () => {
    expect(normalizeAnswerLetter('')).toBeNull();
  });

  it('returns null for multi-char input', () => {
    expect(normalizeAnswerLetter('AB')).toBeNull();
  });

  it('returns null for digit', () => {
    expect(normalizeAnswerLetter('3')).toBeNull();
  });

  it('returns null for whitespace-only', () => {
    expect(normalizeAnswerLetter(' ')).toBeNull();
  });
});
