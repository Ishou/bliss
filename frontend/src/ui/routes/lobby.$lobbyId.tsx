import { createRoute, useNavigate } from '@tanstack/react-router';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { css } from 'styled-system/css';
import {
  LobbyClientError,
  type ConnectionState,
  type GameEvent,
} from '@/application/game';
import type {
  ArrowDirection,
  Cell,
  DefinitionClue,
  Puzzle,
} from '@/domain';
import type {
  GameArrowDirection,
  GameCell,
  GameDefinitionCell,
  GamePuzzle,
  Letter,
  Lobby,
  LobbyId,
  Pseudonym,
} from '@/domain/game';
import { Grid } from '@/ui/components/grid';
import { ConnectionBanner } from '@/ui/components/lobby/ConnectionBanner';
import { EndGameModal } from '@/ui/components/lobby/EndGameModal';
import { PlayerList } from '@/ui/components/lobby/PlayerList';
import { Timer } from '@/ui/components/lobby/Timer';
import { WaitingRoom } from '@/ui/components/lobby/WaitingRoom';
import { Route as RootRoute } from './__root';

// `/lobby/:lobbyId` route. Loader bootstraps lobby state via REST; the
// component opens the WebSocket on mount, tears it down on unmount, and
// folds inbound `GameEvent` frames into local state so child components
// read a single source of truth. WaitingRoom / Grid+Timer / EndGameModal
// are mounted exclusively per the lifecycle phase. Registered only when
// the multiplayer flag is on (ADR-0018 §10), so the context fields it
// relies on are guaranteed present at the call site.

const pageStyles = css({
  minHeight: '100dvh', display: 'flex', flexDirection: 'column',
  alignItems: 'center', gap: 'sm',
  padding: 'lg', bg: 'bg', color: 'fg', fontFamily: 'body', textAlign: 'center',
});

const headingStyles = css({
  fontSize: { base: 'xl', md: 'display' }, fontWeight: 'bold',
  letterSpacing: '-0.02em', margin: 0,
});

const detailStyles = css({ fontSize: 'body', margin: 0, color: 'accent' });

const inGameLayoutStyles = css({
  display: 'flex', flexDirection: 'column',
  alignItems: 'center', gap: 'md',
  width: '100%',
});

const LobbyShell = ({ children }: { children: React.ReactNode }) => (
  <main className={pageStyles}>{children}</main>
);

const LobbyStatus = ({ role, text }: { role: 'status' | 'alert'; text: string }) => (
  <LobbyShell>
    <p className={detailStyles} role={role}>{text}</p>
  </LobbyShell>
);

// Internal lobby state — the route-local snapshot the reducer folds
// events into. Wraps the domain `Lobby` and adds two integration-only
// fields (`durationMs`, `modalDismissed`) that no other layer needs:
// `durationMs` arrives in `gameSolved` and is consumed by `Timer` /
// `EndGameModal` only; `modalDismissed` is local UI state for the
// close-without-leaving-the-page flow. Keeping them off the domain
// `Lobby` keeps `domain/game/types.ts` aligned with the wire schema.
interface LobbyView {
  readonly lobby: Lobby;
  readonly durationMs: number | null;
  readonly modalDismissed: boolean;
}

function LobbyPage() {
  const initialLobby = Route.useLoaderData() as Lobby;
  const { lobbyId } = Route.useParams();
  const ctx = Route.useRouteContext();
  // Multiplayer adapters are guaranteed present: the lobby route is
  // only registered when the multiplayer flag is on (see `router.ts`).
  const gameClient = ctx.gameClient!;
  const getSession = ctx.getSession!;
  const setPersistedPseudonym = ctx.setPseudonym;
  const navigate = useNavigate();

  const [view, setView] = useState<LobbyView>(() => ({
    lobby: initialLobby,
    durationMs: null,
    modalDismissed: false,
  }));
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting');

  // Single side effect: connect on mount, disconnect on unmount.
  // `joinLobby` is auto-sent by the adapter inside `connect` (PR #138's
  // WebSocketGameClient sends the handshake on `onopen`), so the route
  // does not call `joinLobby()` again. Connect failures are non-fatal
  // because the `ConnectionBanner` surfaces transport health.
  useEffect(() => {
    const { sessionId, pseudonym } = getSession();
    const unsubscribeEvents = gameClient.subscribe((event) => {
      setView((current) => applyEvent(current, event));
    });
    const unsubscribeConnection = gameClient.subscribeConnectionState((state) => {
      setConnectionState(state);
    });
    void gameClient.connect({ lobbyId: lobbyId as LobbyId, sessionId, pseudonym }).catch(() => {});
    return () => {
      unsubscribeEvents();
      unsubscribeConnection();
      gameClient.disconnect();
    };
  }, [gameClient, lobbyId, getSession]);

  const { sessionId } = getSession();
  const lobby = view.lobby;

  const handleRename = useCallback((newPseudonym: Pseudonym) => {
    gameClient.renameSelf(newPseudonym);
    // Persist via the context-supplied writer — never touches
    // `infrastructure/session/` from this layer (boundary rule).
    setPersistedPseudonym?.(newPseudonym);
  }, [gameClient, setPersistedPseudonym]);

  const handleSetGridConfig = useCallback((width: number, height: number) => {
    gameClient.setGridConfig({ width, height });
  }, [gameClient]);

  const handleStart = useCallback(() => {
    gameClient.startGame();
  }, [gameClient]);

  const handleCopyShareUrl = useCallback(() => {
    void navigator.clipboard?.writeText(window.location.href);
  }, []);

  const handleCellChange = useCallback((row: number, col: number, letter: string | null) => {
    // The Grid hook reports `string | null`; `cellUpdate` expects
    // `Letter | null`. The hook normalizes to a single uppercase letter
    // before firing `onCellChange` (see `useGridNavigation.handleInput`),
    // so the cast is sound — branded types only narrow at compile time.
    gameClient.cellUpdate(row, col, letter as Letter | null);
  }, [gameClient]);

  // Stable subscribe registrar for `Grid`'s `subscribeToRemoteCellUpdates`
  // prop. Filtering to `cellUpdated` lives inside Grid (it ignores
  // every other event type), so we hand it the raw subscribe and let it
  // discriminate.
  const subscribeToRemoteCellUpdates = useCallback(
    (handler: (event: GameEvent) => void) => gameClient.subscribe(handler),
    [gameClient],
  );

  const handlePlayAgain = useCallback(() => {
    void navigate({ to: '/' });
  }, [navigate]);

  const handleCloseModal = useCallback(() => {
    setView((current) => ({ ...current, modalDismissed: true }));
  }, []);

  // `GamePuzzle` (game/) ↔ `Puzzle` (puzzle/) shapes diverge on three
  // axes: `Position.column` vs `.col`, the wire's `LetterCell.letter`
  // (server blank/pre-fill slot — distinct from the player's `entry`,
  // which always starts empty), and `GameDefinitionCell.clues[1|2]` vs
  // the UI's `clues: [DefinitionClue]|[DefinitionClue, DefinitionClue]`
  // tuple. Mapping at the route boundary keeps `domain/` faithful to
  // each context's wire shape while letting the existing `Grid` consume
  // the multiplayer puzzle. Memoized on the puzzle ref so a steady-state
  // render does not rebuild the cell array.
  const gamePuzzle = lobby.game?.puzzle ?? null;
  const gridPuzzle = useMemo<Puzzle | null>(
    () => (gamePuzzle ? gamePuzzleToPuzzle(gamePuzzle) : null),
    [gamePuzzle],
  );

  return (
    <>
      <ConnectionBanner state={connectionState} />
      <LobbyShell>
        <h1 className={headingStyles}>Salon · {lobbyId}</h1>
        <p className={detailStyles}>
          {lobby.players.length} {lobby.players.length === 1 ? 'joueur' : 'joueurs'}
        </p>

        {lobby.state === 'WAITING' ? (
          <WaitingRoom
            lobby={lobby}
            currentSessionId={sessionId}
            onRename={handleRename}
            onSetGridConfig={handleSetGridConfig}
            onStart={handleStart}
            onCopyShareUrl={handleCopyShareUrl}
          />
        ) : null}

        {lobby.state === 'IN_PROGRESS' && lobby.game && gridPuzzle ? (
          <div className={inGameLayoutStyles}>
            <PlayerList
              players={lobby.players}
              ownerSessionId={lobby.ownerSessionId}
              currentSessionId={sessionId}
              variant="inline"
            />
            <Timer startedAt={lobby.game.startedAt} />
            <Grid
              puzzle={gridPuzzle}
              onCellChange={handleCellChange}
              subscribeToRemoteCellUpdates={subscribeToRemoteCellUpdates}
            />
          </div>
        ) : null}

        {lobby.state === 'COMPLETED' && lobby.game ? (
          <div className={inGameLayoutStyles}>
            <PlayerList
              players={lobby.players}
              ownerSessionId={lobby.ownerSessionId}
              currentSessionId={sessionId}
              variant="inline"
            />
            <Timer startedAt={lobby.game.startedAt} frozenAtMs={view.durationMs ?? 0} />
            {gridPuzzle ? (
              <Grid
                puzzle={gridPuzzle}
                subscribeToRemoteCellUpdates={subscribeToRemoteCellUpdates}
              />
            ) : null}
          </div>
        ) : null}
      </LobbyShell>

      {lobby.state === 'COMPLETED' && view.durationMs !== null && !view.modalDismissed ? (
        <EndGameModal
          durationMs={view.durationMs}
          onPlayAgain={handlePlayAgain}
          onClose={handleCloseModal}
        />
      ) : null}
    </>
  );
}

// Folds a server→client `GameEvent` into the locally-cached `LobbyView`.
// Membership events update `lobby.players`; `gameStarted` flips the
// state to `IN_PROGRESS` and embeds the `GameSession`; `gameSolved`
// flips to `COMPLETED` and stashes the authoritative server-emitted
// `durationMs` so `Timer` can freeze and `EndGameModal` can display it.
// `cellUpdated` is consumed directly by `Grid`'s
// `subscribeToRemoteCellUpdates` — the reducer leaves it untouched so
// no React render is triggered per keystroke (ADR-0002 §4).
function applyEvent(current: LobbyView, event: GameEvent): LobbyView {
  switch (event.type) {
    case 'lobbyState':
      return {
        ...current,
        lobby: {
          players: event.players, ownerSessionId: event.ownerSessionId,
          state: event.state, gridConfig: event.gridConfig, game: event.game,
        },
      };
    case 'playerJoined':
      if (current.lobby.players.some((p) => p.sessionId === event.sessionId)) return current;
      return {
        ...current,
        lobby: {
          ...current.lobby,
          players: [...current.lobby.players, {
            sessionId: event.sessionId, pseudonym: event.pseudonym, joinedAt: event.joinedAt,
          }],
        },
      };
    case 'playerLeft':
      return {
        ...current,
        lobby: {
          ...current.lobby,
          players: current.lobby.players.filter((p) => p.sessionId !== event.sessionId),
        },
      };
    case 'playerRenamed':
      return {
        ...current,
        lobby: {
          ...current.lobby,
          players: current.lobby.players.map((p) =>
            p.sessionId === event.sessionId ? { ...p, pseudonym: event.newPseudonym } : p,
          ),
        },
      };
    case 'gameStarted':
      return {
        ...current,
        lobby: {
          ...current.lobby,
          state: 'IN_PROGRESS',
          game: {
            puzzle: event.puzzle,
            startedAt: event.startedAt,
            completedAt: null,
          },
        },
      };
    case 'gameSolved':
      return {
        ...current,
        lobby: {
          ...current.lobby,
          state: 'COMPLETED',
        },
        durationMs: event.durationMs,
        modalDismissed: false,
      };
    default:
      // `cellUpdated` and `error` frames do not change route-level state.
      return current;
  }
}

// Adapter from the wire-shape `GamePuzzle` (mirrors AsyncAPI) to the
// UI-shape `Puzzle` consumed by `Grid`. The two shapes belong to
// distinct bounded contexts (game/ vs puzzle/) and do not share a
// physical type by design — the wire format is dictated by
// `game/api/asyncapi.yaml` and renaming it would break the contract.
// This mapping is route-local because no other consumer needs it; if
// a second consumer appears, lift it to `application/game/`.
function gamePuzzleToPuzzle(gamePuzzle: GamePuzzle): Puzzle {
  // Definition cells in `GamePuzzle` carry exactly one clue (one
  // `text` + one `arrow`). The UI `DefinitionCell` shape allows one or
  // two; we always emit a single-element tuple, which matches the
  // existing v1 grid renderer for non-stacked clues.
  const cells: Cell[] = gamePuzzle.cells.map((cell) => gameCellToCell(cell));
  return {
    id: gamePuzzle.id,
    title: gamePuzzle.title,
    language: gamePuzzle.language,
    width: gamePuzzle.width,
    height: gamePuzzle.height,
    cells,
  };
}

function gameCellToCell(cell: GameCell): Cell {
  const position = { row: cell.position.row, col: cell.position.column };
  switch (cell.kind) {
    case 'letter':
      // `entry` is the player's local input — ALWAYS empty on first paint
      // of a freshly-started game. The wire's `letter` field is the
      // server's blank/pre-fill slot, NOT the canonical solution: per
      // game/api/asyncapi.yaml `GameLetterCell`, the server sends `null`
      // here in v1 precisely because the answer is domain-private until
      // `gameSolved` (otherwise the grid would render pre-solved on every
      // client). Routing `letter` into `entry` here was the original bug
      // — keep this defensive even if a future server starts sending a
      // pre-filled hint, because that hint is still NOT the player's own
      // input.
      return { kind: 'letter', position, entry: '' };
    case 'definition':
      return definitionCellToCell(cell, position);
    case 'block':
      return { kind: 'block', position };
  }
}

// `GameDefinitionCell` carries 1 or 2 clues per game/api/asyncapi.yaml's
// `clues` array (mots-fléchés corner-cell idiom: an across clue and a down
// clue stacked at the same position). The UI `DefinitionCell` accepts either
// shape via its `[clue]` / `[clue, clue]` tuple, so we pass the wire order
// straight through; the renderer in `Cell.tsx` re-orders for visual layout
// (mixed-origin pairs put the right-origin clue on top) without touching
// domain order — see ADR-0005 §3a.
function definitionCellToCell(
  cell: GameDefinitionCell,
  position: { row: number; col: number },
): Cell {
  if (cell.clues.length === 0) {
    // Spec violation (asyncapi minItems: 1) — guard against malformed wire
    // data so we degrade to an inert block instead of crashing the grid.
    return { kind: 'block', position };
  }
  const first: DefinitionClue = {
    text: cell.clues[0].text,
    arrow: gameArrowToArrow(cell.clues[0].arrow),
  };
  if (cell.clues.length === 1) {
    return { kind: 'definition', position, clues: [first] };
  }
  const second: DefinitionClue = {
    text: cell.clues[1].text,
    arrow: gameArrowToArrow(cell.clues[1].arrow),
  };
  return { kind: 'definition', position, clues: [first, second] };
}

// `GameArrowDirection` and `ArrowDirection` happen to enumerate the
// exact same four labels (per asyncapi.yaml & ADR-0005 §3a). The cast
// is a no-op at runtime; the explicit map keeps the boundary obvious
// and surfaces a TS error if either union ever drifts.
function gameArrowToArrow(arrow: GameArrowDirection): ArrowDirection {
  switch (arrow) {
    case 'right': return 'right';
    case 'down': return 'down';
    case 'down-right': return 'down-right';
    case 'right-down': return 'right-down';
  }
}

function LobbyErrorComponent({ error }: { error: Error }) {
  if (error instanceof LobbyClientError) {
    switch (error.kind) {
      case 'not-found':
        return <LobbyStatus role="alert" text="Salon introuvable." />;
      case 'upstream-unavailable':
        return <LobbyStatus role="alert" text="Serveur indisponible. Réessayez dans un instant." />;
      case 'validation':
      case 'transient':
        return <LobbyStatus role="alert" text="Une erreur est survenue. Réessayez." />;
    }
  }
  return <LobbyStatus role="alert" text="Une erreur est survenue. Réessayez." />;
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/lobby/$lobbyId',
  loader: ({ context, params }): Promise<Lobby> =>
    // Asserted non-null: this route is only registered when the
    // multiplayer flag is on, in which case the composition root
    // guarantees `lobbyClient` is present in context.
    context.lobbyClient!.getLobby(params.lobbyId as LobbyId),
  component: LobbyPage,
  pendingComponent: () => <LobbyStatus role="status" text="Chargement du salon…" />,
  errorComponent: LobbyErrorComponent,
  head: () => ({ meta: [{ title: 'Salon · WordSparrow' }] }),
});
