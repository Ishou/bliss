import { describe, it, expect } from 'vitest';

import type { SessionId } from '@/domain/game';
import {
  playerColorVars,
  playerInitial,
  sessionIdToHue,
} from '@/ui/lib/playerColor';

const sid = (raw: string): SessionId => raw as unknown as SessionId;

describe('sessionIdToHue', () => {
  it('returns a hue in [0, 360)', () => {
    for (const raw of ['a', 'abcdefg', 'a-b-c-d-e', '0', '🟦']) {
      const hue = sessionIdToHue(sid(raw));
      expect(hue).toBeGreaterThanOrEqual(0);
      expect(hue).toBeLessThan(360);
    }
  });

  it('is deterministic for a given sessionId', () => {
    expect(sessionIdToHue(sid('alice'))).toBe(sessionIdToHue(sid('alice')));
  });

  it('returns 0 for empty input', () => {
    expect(sessionIdToHue(sid(''))).toBe(0);
  });
});

describe('playerColorVars', () => {
  it('returns the four palette-recipe vars', () => {
    const vars = playerColorVars(sid('any'));
    expect(Object.keys(vars).sort()).toEqual([
      '--player-active-bg',
      '--player-color',
      '--player-on',
      '--player-word-bg',
    ]);
  });

  it('encodes the documented saturation/lightness recipe', () => {
    const vars = playerColorVars(sid('xyz'));
    const hue = sessionIdToHue(sid('xyz'));
    expect(vars['--player-color']).toBe(`hsl(${hue} 50% 72%)`);
    expect(vars['--player-active-bg']).toBe(`hsl(${hue} 22% 14%)`);
    expect(vars['--player-word-bg']).toBe(`hsl(${hue} 15% 14%)`);
    expect(vars['--player-on']).toBe(`hsl(${hue} 48% 16%)`);
  });

  it('drops the legacy --player-color-soft / --player-text-color vars', () => {
    const vars = playerColorVars(sid('legacy'));
    expect(vars['--player-color-soft']).toBeUndefined();
    expect(vars['--player-text-color']).toBeUndefined();
    expect(vars['--player-hue']).toBeUndefined();
  });
});

describe('playerInitial', () => {
  it('returns the uppercase first character', () => {
    expect(playerInitial('alice')).toBe('A');
    expect(playerInitial('Marc')).toBe('M');
  });

  it('strips combining diacritics', () => {
    expect(playerInitial('Élodie')).toBe('E');
    expect(playerInitial('Ñoño')).toBe('N');
    expect(playerInitial('Ärger')).toBe('A');
  });

  it('falls back to ? on empty input', () => {
    expect(playerInitial('')).toBe('?');
    expect(playerInitial('   ')).toBe('?');
  });

  it('uppercases idempotently', () => {
    expect(playerInitial('SOPHIE')).toBe('S');
  });
});
