import { act, fireEvent, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/grille';

// Integration regression for the hint feature. Renders the real Index
// route (Grid + HintControl wired through the route's `getFocusedCell`
// seam) and exercises the realistic blur→click sequence a real browser
// produces when the user has a letter cell focused and clicks the hint
// button.
//
// Why this test exists: the unit tests (`hint-control.test.tsx`,
// `use-hint-request.test.ts`) stub `getFocusedCell` directly, so they
// never exercise the route's `activeFocusRef` plumbing. In production
// the cell `<input onBlur>` sets `focused = null`, the focus-change
// `useEffect` flushes between the blur and the click (React 18's
// between-discrete-event passive-effect flush), and `getFocusedCell`
// would return `null` — the hint silently no-ops. This test fails on
// the pre-fix wiring and passes once `handleLocalFocusChange` keeps
// the last non-null focus.

// 1×3 row: definition at (0,0) + letter cells at (0,1) and (0,2). The
// across word starts at (0,1) so clicking (0,1) sets direction=across
// without us having to disambiguate stacked clues.
const puzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 'hint-test',
  language: 'fr',
  width: 3,
  height: 1,
  hintsAllowed: 3,
  hintsRemaining: 3,
  cells: [
    {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [{ text: 'a', arrow: 'right' }],
    },
    { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
  ],
};

const inputAt = (row: number, col: number) =>
  document.querySelector<HTMLInputElement>(
    `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
  );

// In-memory soloEntriesStore so reset-path tests can observe the
// effect of `clearForPuzzle` propagating into the React state.
const makeInMemoryStore = (
  seed: {
    entries?: Array<{ row: number; column: number; letter: string }>;
    locks?: Array<{ row: number; column: number }>;
    hintsUsed?: number;
  } = {},
) => {
  const entries = new Map<string, string>(
    (seed.entries ?? []).map((e) => [`${e.row},${e.column}`, e.letter]),
  );
  const locks = new Set<string>(
    (seed.locks ?? []).map((c) => `${c.row},${c.column}`),
  );
  let hintsUsed = seed.hintsUsed ?? 0;
  return {
    load: () =>
      [...entries.entries()].map(([k, letter]) => {
        const [row, column] = k.split(',').map(Number);
        return { row, column, letter };
      }),
    save: (_id: string, row: number, column: number, letter: string | null) => {
      const key = `${row},${column}`;
      if (letter == null || letter === '') entries.delete(key);
      else entries.set(key, letter);
    },
    loadLockedCells: () =>
      [...locks].map((k) => {
        const [row, column] = k.split(',').map(Number);
        return { row, column };
      }),
    lockCell: (_id: string, row: number, column: number) => {
      locks.add(`${row},${column}`);
    },
    loadHintsUsed: () => hintsUsed,
    recordHintUsed: () => {
      hintsUsed += 1;
    },
    clearForPuzzle: () => {
      entries.clear();
      locks.clear();
      hintsUsed = 0;
    },
  };
};

const renderHomeRoute = (
  solver: PuzzleSolver,
  storeOverride?: ReturnType<typeof makeInMemoryStore>,
) => {
  const repository: PuzzleRepository = {
    fetchById: vi.fn().mockResolvedValue(puzzle),
    fetchDaily: vi.fn().mockResolvedValue(puzzle),
    listDailySummaries: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
  };
  const routeTree = RootRoute.addChildren([IndexRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/grille'] }),
    context: {
      puzzleRepository: repository,
      puzzleSolver: solver,
      sessionClient: {
        eraseSession: () => Promise.resolve({ deleted: 0 }),
        getSessionId: () => 'test-session-id',
        clearLocalSession: () => {},
      },
      soloEntriesStore: storeOverride ?? makeInMemoryStore(),
      tourSeenStore: {
        get: () => true,
        set: () => {},
        clear: () => {},
      },
    },
  });
  return render(<RouterProvider router={router} />);
};

const click = (el: HTMLElement) => {
  el.focus();
  fireEvent.click(el);
};
const typeChar = (el: HTMLInputElement, ch: string) =>
  fireEvent.keyDown(el, { key: ch });

// Bug 2 — the hint button used to consult only `lockedHintCells`, so
// a cell already auto-validated by typing the correct word was still
// hint-eligible (wasted hint). The fix has `getFocusedCell` read the
// full `validatedPositions` (auto-validated ∪ hint-revealed).
describe('Index route — hint refused on validated cells', () => {
  it('does not call the solver when the focused cell is already auto-validated', async () => {
    const validate = vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] });
    const requestHint = vi.fn().mockResolvedValue({
      row: 0,
      column: 1,
      letter: 'A',
      hintsRemaining: 2,
    });
    const solver: PuzzleSolver = { validate, requestHint };
    renderHomeRoute(solver);
    await screen.findByRole('grid');

    // Type the full 2-letter word at (0,1)..(0,2). After the second
    // letter the auto-validate flow POSTs once and locks both cells.
    const first = inputAt(0, 1)!;
    click(first);
    typeChar(first, 'a');
    const second = inputAt(0, 2)!;
    typeChar(second, 'i');
    await vi.waitFor(() => expect(validate).toHaveBeenCalled());
    // ProgressBar reflects the lock — surface signal that
    // `validatedPositions` includes (0,1)..(0,2).
    await screen.findByText('2 / 2 cases');

    // Focus the validated cell and click the hint button.
    click(first);
    act(() => { first.blur(); });
    const hintButton = screen.getByRole('button', { name: 'Indice (3 / 3)' });
    await act(async () => { fireEvent.click(hintButton); });

    expect(requestHint).not.toHaveBeenCalled();
    // Counter unchanged.
    expect(screen.getByRole('button', { name: 'Indice (3 / 3)' })).toBeInTheDocument();
  });
});

// Bug 3 — `clearForPuzzle` deletes locks from storage, but the React
// `lockedHintCells` state was bound to a `[puzzle.id]`-only effect, so
// the in-memory set survived the storage clear. Adding `refreshCount`
// to the effect deps re-reads the now-empty store on the next render.
describe('Index route — refresh clears the locked-cell state', () => {
  it('un-locks cells in the DOM after clicking Actualiser', async () => {
    const validate = vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] });
    const requestHint = vi.fn();
    const solver: PuzzleSolver = { validate, requestHint };
    const store = makeInMemoryStore({
      entries: [
        { row: 0, column: 1, letter: 'A' },
        { row: 0, column: 2, letter: 'I' },
      ],
      locks: [
        { row: 0, column: 1 },
        { row: 0, column: 2 },
      ],
    });
    renderHomeRoute(solver, store);
    await screen.findByRole('grid');
    // Cells start sage / read-only because storage seeded the locks.
    await vi.waitFor(() => expect(inputAt(0, 1)!.readOnly).toBe(true));
    expect(inputAt(0, 2)!.readOnly).toBe(true);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Actualiser la grille' }));
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('refresh-confirm-accept'));
    });

    await vi.waitFor(() => expect(inputAt(0, 1)!.readOnly).toBe(false));
    expect(inputAt(0, 2)!.readOnly).toBe(false);
    // ProgressBar reflects the clean slate.
    expect(screen.getByText('0 / 2 cases')).toBeInTheDocument();
  });
});

describe('Index route — hint count sourced from the server', () => {
  it('seeds hintsRemaining from puzzle.hintsRemaining on mount', async () => {
    const solver: PuzzleSolver = {
      validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
      requestHint: vi.fn(),
    };
    const repository: PuzzleRepository = {
      fetchById: vi.fn().mockResolvedValue({ ...puzzle, hintsRemaining: 1 }),
      fetchDaily: vi.fn().mockResolvedValue({ ...puzzle, hintsRemaining: 1 }),
      listDailySummaries: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
    };
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/grille'] }),
      context: {
        puzzleRepository: repository,
        puzzleSolver: solver,
        sessionClient: {
          eraseSession: () => Promise.resolve({ deleted: 0 }),
          getSessionId: () => 'test-session-id',
          clearLocalSession: () => {},
        },
        soloEntriesStore: makeInMemoryStore(),
        tourSeenStore: { get: () => true, set: () => {}, clear: () => {} },
      },
    });
    render(<RouterProvider router={router} />);
    await screen.findByRole('grid');
    expect(
      await screen.findByRole('button', { name: 'Indice (1 / 3)' }),
    ).toBeInTheDocument();
  });

  it('persists each consumed hint via recordHintUsed', async () => {
    const solver: PuzzleSolver = {
      validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
      requestHint: vi.fn().mockResolvedValue({ row: 0, column: 2, letter: 'B', hintsRemaining: 2 }),
    };
    const store = makeInMemoryStore();
    const recordSpy = vi.spyOn(store, 'recordHintUsed');
    renderHomeRoute(solver, store);
    await screen.findByRole('grid');
    const first = inputAt(0, 1)!;
    click(first);
    typeChar(first, 'a');
    act(() => { inputAt(0, 2)!.blur(); });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Indice (3 / 3)' }));
    });
    await screen.findByRole('button', { name: 'Indice (2 / 3)' });
    expect(recordSpy).toHaveBeenCalledTimes(1);
  });
});

describe('Index route — hint button integration', () => {
  it('sends the focused cell coordinates to the solver after a real blur→click sequence', async () => {
    const requestHint = vi.fn().mockResolvedValue({
      row: 0,
      column: 2,
      letter: 'B',
      hintsRemaining: 2,
    });
    const solver: PuzzleSolver = {
      validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
      requestHint,
    };

    renderHomeRoute(solver);
    await screen.findByRole('grid');

    // Focus the first letter cell (= start of the across word) and type
    // a letter; the cell handler advances focus to (0, 2). That cell is
    // the one the hint should target after the blur→click race below.
    const first = inputAt(0, 1)!;
    click(first);
    typeChar(first, 'a');

    // Real browsers fire `blur` on the focused cell when the user
    // mousedowns the toolbar button, and React 18 flushes pending
    // passive effects between the blur and the click — so the
    // focus-change `useEffect` would write `null` into `activeFocusRef`
    // before `onClick` reads it. jsdom doesn't move focus on
    // `fireEvent.click`, so we simulate the sequence explicitly: blur
    // the cell inside `act()` (forcing the same passive-effect flush
    // the real browser does between events), then click the button.
    const second = inputAt(0, 2)!;
    act(() => {
      second.blur();
    });

    const hintButton = screen.getByRole('button', { name: 'Indice (3 / 3)' });
    await act(async () => {
      fireEvent.click(hintButton);
    });

    expect(requestHint).toHaveBeenCalledTimes(1);
    expect(requestHint).toHaveBeenCalledWith(puzzle.id, 0, 2);
    // The success path writes the new hintsRemaining into the badge
    // and writes the revealed letter into the matching <input>.
    await screen.findByRole('button', { name: 'Indice (2 / 3)' });
    expect(inputAt(0, 2)!.value).toBe('B');
  });
});
