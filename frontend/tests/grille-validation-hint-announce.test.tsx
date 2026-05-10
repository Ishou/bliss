import { describe, expect, it, vi } from 'vitest';
import {
  makeWordValidatedHandler,
  makeHintRevealHandler,
} from '@/ui/routes/grille';

// ---------------------------------------------------------------------------
// Minimal stubs
// ---------------------------------------------------------------------------

// Vitest 4 narrowed `vi.fn()`'s default return type from a typed Mock to
// `Mock<Procedure | Constructable>`, which is no longer assignable to a
// concrete callable signature. Pass the real signature to `vi.fn<…>()`
// so the return type is the function shape the SUT expects.
function makeStore(): {
  lockCell: (puzzleId: string, row: number, column: number) => void;
  save: (puzzleId: string, row: number, column: number, letter: string | null) => void;
} {
  return {
    lockCell: vi.fn<(puzzleId: string, row: number, column: number) => void>(),
    save: vi.fn<(puzzleId: string, row: number, column: number, letter: string | null) => void>(),
  };
}

function makeAnnouncer(): { say: (text: string) => void } {
  return { say: vi.fn<(text: string) => void>() };
}

// ---------------------------------------------------------------------------
// makeWordValidatedHandler
// ---------------------------------------------------------------------------

describe('makeWordValidatedHandler', () => {
  it('calls announcer.say with the validated word', () => {
    const announcer = makeAnnouncer();
    const store = makeStore();

    // Provide a readLetterAt stub so we avoid querying the real DOM
    const letters: Record<string, string> = {
      '0,1': 'C',
      '0,2': 'H',
      '0,3': 'A',
      '0,4': 'T',
    };
    const readLetterAt = (row: number, col: number) =>
      letters[`${row},${col}`] ?? '';

    const handler = makeWordValidatedHandler({
      puzzleId: 'puzzle-1',
      soloEntriesStore: store,
      announcer,
      readLetterAt,
    });

    handler([
      { row: 0, col: 1 },
      { row: 0, col: 2 },
      { row: 0, col: 3 },
      { row: 0, col: 4 },
    ]);

    expect(announcer.say).toHaveBeenCalledWith('mot validé : CHAT');
  });

  it('locks every cell in the store', () => {
    const announcer = makeAnnouncer();
    const store = makeStore();
    const readLetterAt = () => 'X';

    const handler = makeWordValidatedHandler({
      puzzleId: 'puzzle-1',
      soloEntriesStore: store,
      announcer,
      readLetterAt,
    });

    handler([
      { row: 0, col: 1 },
      { row: 0, col: 2 },
    ]);

    expect(store.lockCell).toHaveBeenCalledTimes(2);
    expect(store.lockCell).toHaveBeenCalledWith('puzzle-1', 0, 1);
    expect(store.lockCell).toHaveBeenCalledWith('puzzle-1', 0, 2);
  });

  it('does not call say when all letters are empty', () => {
    const announcer = makeAnnouncer();
    const store = makeStore();

    const handler = makeWordValidatedHandler({
      puzzleId: 'puzzle-1',
      soloEntriesStore: store,
      announcer,
      readLetterAt: () => '',
    });

    handler([{ row: 0, col: 1 }]);

    expect(announcer.say).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// makeHintRevealHandler
// ---------------------------------------------------------------------------

describe('makeHintRevealHandler', () => {
  it('calls announcer.say with the revealed letter, row+1, col+1', () => {
    const announcer = makeAnnouncer();
    const store = makeStore();
    const setLockedHintCells = vi.fn();

    const handler = makeHintRevealHandler({
      puzzleId: 'puzzle-1',
      soloEntriesStore: store,
      announcer,
      setLockedHintCells,
    });

    handler(1, 4, 'R');

    expect(announcer.say).toHaveBeenCalledWith(
      'lettre R révélée à la ligne 2, colonne 5',
    );
  });

  it('saves and locks the cell in the store', () => {
    const announcer = makeAnnouncer();
    const store = makeStore();
    const setLockedHintCells = vi.fn();

    const handler = makeHintRevealHandler({
      puzzleId: 'puzzle-1',
      soloEntriesStore: store,
      announcer,
      setLockedHintCells,
    });

    handler(1, 4, 'R');

    expect(store.save).toHaveBeenCalledWith('puzzle-1', 1, 4, 'R');
    expect(store.lockCell).toHaveBeenCalledWith('puzzle-1', 1, 4);
  });

  it('updates locked hint cells set', () => {
    const announcer = makeAnnouncer();
    const store = makeStore();
    const setLockedHintCells = vi.fn();

    const handler = makeHintRevealHandler({
      puzzleId: 'puzzle-1',
      soloEntriesStore: store,
      announcer,
      setLockedHintCells,
    });

    handler(1, 4, 'R');

    expect(setLockedHintCells).toHaveBeenCalledTimes(1);
    // The updater fn should add '1,4' to the set
    const updaterFn = setLockedHintCells.mock.calls[0][0];
    const result = updaterFn(new Set<string>());
    expect(result.has('1,4')).toBe(true);
  });
});
