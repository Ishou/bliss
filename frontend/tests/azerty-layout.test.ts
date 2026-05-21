import { describe, it, expect } from 'vitest';
import { AZERTY_ROWS } from '@/ui/components/keyboard/azertyLayout';

describe('azertyLayout', () => {
  it('has three rows of 10, 10, 6 keys', () => {
    expect(AZERTY_ROWS.map((r) => r.length)).toEqual([10, 10, 6]);
  });

  it('contains every letter from A to Z exactly once', () => {
    const all = AZERTY_ROWS.flat();
    expect(new Set(all).size).toBe(26);
    expect([...all].sort().join('')).toBe('ABCDEFGHIJKLMNOPQRSTUVWXYZ');
  });

  it('rows reflect French AZERTY ordering', () => {
    expect(AZERTY_ROWS[0]).toEqual(['A', 'Z', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P']);
    expect(AZERTY_ROWS[1]).toEqual(['Q', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L', 'M']);
    expect(AZERTY_ROWS[2]).toEqual(['W', 'X', 'C', 'V', 'B', 'N']);
  });
});
