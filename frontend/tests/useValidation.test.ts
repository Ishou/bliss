import { renderHook, act } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Cell, Puzzle } from '@/domain';
import { useValidation } from '@/ui/components/grid/useValidation';

// Build a minimal single-across-word puzzle whose answers carry French
// diacritics. The definition at (0,0) points right; letter cells follow
// at (0,1)..(0,N). `wordRange` walks consecutive letter cells, so this
// layout gives exactly one word of length N.
function makePuzzle(word: string): Puzzle {
  const chars = [...word]; // split by Unicode code point, not UTF-16 unit
  const cells: Cell[] = [
    {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [{ text: 'accent test', arrow: 'right' }],
    },
    ...chars.map((ch, i): Cell => ({
      kind: 'letter',
      position: { row: 0, col: i + 1 },
      answer: ch,
      entry: '',
    })),
  ];
  return {
    id: 'accent-test',
    title: 'accent test',
    language: 'fr',
    width: chars.length + 1,
    height: 1,
    cells,
  };
}

// `useValidation` reads cell values via `document.querySelector` on the
// global document. We mount bare <input> elements with the matching
// data-attributes so `verify()` finds the typed values without needing
// a full Grid render.
function mountInputs(
  positions: ReadonlyArray<{ row: number; col: number; value: string }>,
): HTMLInputElement[] {
  return positions.map(({ row, col, value }) => {
    const el = document.createElement('input');
    el.setAttribute('data-cell-kind', 'letter');
    el.setAttribute('data-row', String(row));
    el.setAttribute('data-col', String(col));
    el.value = value;
    document.body.appendChild(el);
    return el;
  });
}

describe('useValidation — accent-blind normalization', () => {
  let mounted: HTMLInputElement[] = [];

  afterEach(() => {
    for (const el of mounted) el.remove();
    mounted = [];
  });

  // Each entry: accented canonical answer + the un-accented input a
  // player would naturally type. All five French diacritic classes are
  // covered: acute (É), grave (À), circumflex (Ê), cedilla (Ç),
  // diaeresis (Ï).
  const cases: Array<{ label: string; answer: string; typed: string }> = [
    { label: 'acute É — ÉTÉ → ETE',      answer: 'ÉTÉ', typed: 'ETE' },
    { label: 'grave À — ÀGE → AGE',       answer: 'ÀGE', typed: 'AGE' },
    { label: 'circumflex Ê — ÊTR → ETR', answer: 'ÊTR', typed: 'ETR' },
    { label: 'cedilla Ç — ÇAS → CAS',    answer: 'ÇAS', typed: 'CAS' },
    { label: 'diaeresis Ï — ÏLE → ILE',  answer: 'ÏLE', typed: 'ILE' },
  ];

  for (const { label, answer, typed } of cases) {
    it(`validates unaccented input against accented answer: ${label}`, () => {
      const puzzle = makePuzzle(answer);
      const chars = [...typed];
      mounted = mountInputs(chars.map((ch, i) => ({ row: 0, col: i + 1, value: ch })));

      const { result } = renderHook(() => useValidation(puzzle));

      act(() => { result.current.verify(); });

      // Every letter cell must be in the validated set.
      const len = [...answer].length;
      for (let i = 0; i < len; i++) {
        expect(result.current.validated.has(`0,${i + 1}`)).toBe(true);
      }
      // No errors — the word is correctly typed.
      expect(result.current.errors.size).toBe(0);
    });
  }

  it('still rejects a genuinely wrong letter even when other cells carry diacritics', () => {
    // Answer: ÉTÉ  — player types EXE (wrong middle letter).
    const puzzle = makePuzzle('ÉTÉ');
    mounted = mountInputs([
      { row: 0, col: 1, value: 'E' },
      { row: 0, col: 2, value: 'X' }, // wrong
      { row: 0, col: 3, value: 'E' },
    ]);

    const { result } = renderHook(() => useValidation(puzzle));
    act(() => { result.current.verify(); });

    expect(result.current.validated.size).toBe(0);
    // All three cells shake (whole-word error rule).
    expect(result.current.errors.has('0,1')).toBe(true);
    expect(result.current.errors.has('0,2')).toBe(true);
    expect(result.current.errors.has('0,3')).toBe(true);
  });
});
