import { createRoute, useNavigate } from '@tanstack/react-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  Position,
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
  Player,
  PresenceEntry,
  Pseudonym,
  SessionId,
} from '@/domain/game';
import { Grid } from '@/ui/components/grid';
import { ConnectionBanner } from '@/ui/components/lobby/ConnectionBanner';
import { EndGameModal } from '@/ui/components/lobby/EndGameModal';
import { PlayerList } from '@/ui/components/lobby/PlayerList';
import { Timer } from '@/ui/components/lobby/Timer';
import { WaitingRoom } from '@/ui/components/lobby/WaitingRoom';
import { Button } from '@/ui/components/primitives';
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

// Wordmark — mirrors `routes/index.tsx` so `/` and `/lobby/:lobbyId`
// render the same brand title (ADR-0005 §6 amended). The lobby id stays
// in the URL bar and surfaces via the WaitingRoom share-URL button, so
// dropping it from the heading does not hide it from the player.
const wordmarkStyles = css({
  fontFamily: 'heading',
  fontSize: { base: 'display', md: '2.8125rem' },
  fontWeight: 'black',
  letterSpacing: '-0.02em',
  color: 'leaf.700',
  margin: 0,
});

// "DÉMO" pill — same rendering as the home route (ADR-0005 §4).
const demoBadgeStyles = css({
  fontSize: 'xs',
  fontWeight: 'bold',
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'blossom.700',
  bg: 'blossom.50',
  paddingInline: 'sm',
  paddingBlock: 'xs',
  borderRadius: '9999px',
  margin: 0,
});

const detailStyles = css({ fontSize: 'body', margin: 0, color: 'accent' });

// Stack the alert copy on top of the back-home CTA so the user always
// has a one-click exit when the lobby fails to load. Centered to match
// the surrounding `LobbyShell` layout.
const errorActionsStyles = css({
  display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 'md',
});

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
  // Inline pseudonym-rename error surfaced by the WaitingRoom editor.
  // The server emits an `invalid-pseudonym` error frame when the rename
  // payload fails the `Pseudonym` invariants (over MAX_LENGTH, empty,
  // leading/trailing whitespace). Stored at the route level because
  // (a) WaitingRoom is intentionally pure and (b) a successful rename
  // arriving as `playerRenamed` clears the slate.
  const [pseudonymError, setPseudonymError] = useState<string | null>(null);
  const handleClearPseudonymError = useCallback(() => {
    setPseudonymError(null);
  }, []);
  // True between "Démarrer la partie" click and the server-side
  // confirmation. WaitingRoom uses the flag to disable the button and
  // flip the label to "Démarrage…" so the WS round-trip (frame →
  // server → broadcast) is not perceived as a dead click.
  const [isStarting, setIsStarting] = useState(false);

  // Single side effect: connect on mount, disconnect on unmount.
  // `joinLobby` is auto-sent by the adapter inside `connect` (PR #138's
  // WebSocketGameClient sends the handshake on `onopen`), so the route
  // does not call `joinLobby()` again. Connect failures are non-fatal
  // because the `ConnectionBanner` surfaces transport health.
  useEffect(() => {
    const { sessionId, pseudonym } = getSession();
    const unsubscribeEvents = gameClient.subscribe((event) => {
      setView((current) => applyEvent(current, event));
      // Surface `invalid-pseudonym` errors inline next to the editor;
      // clear the inline error once the server confirms the rename via
      // `playerRenamed` for the local session.
      if (event.type === 'error' &&
        event.errorType === 'https://bliss.example/errors/invalid-pseudonym') {
        setPseudonymError(event.detail ?? event.title);
      } else if (event.type === 'playerRenamed' && event.sessionId === sessionId) {
        setPseudonymError(null);
      }
      // Clear the in-flight Start spinner once the server either
      // confirms the new game or rejects the request. `gameStarted`
      // also unmounts WaitingRoom, but resetting the flag is good
      // hygiene for any future code path that reuses the component
      // (e.g. a play-again flow that re-enters WAITING).
      if (event.type === 'gameStarted' || event.type === 'error') {
        setIsStarting(false);
      }
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
    // Optimistic flip to the loading state — cleared either when the
    // server's `gameStarted` event arrives (via the subscribe handler)
    // or when an `error` frame surfaces a server-side rejection.
    setIsStarting(true);
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

  // Local-user focus → outbound `cellFocus` frame. The hook fires this
  // synchronously on every focused-cell / direction transition; the
  // adapter's 200 ms debounce collapses bursts. `null` position means
  // the player has no cell focused (and we send `null` row/column to
  // peers so they can drop the cursor). Stable reference so the hook's
  // option-stash ref doesn't churn.
  const handleLocalFocusChange = useCallback(
    (position: Position | null, direction: 'across' | 'down' | null) => {
      gameClient.cellFocus(position?.row ?? null, position?.col ?? null, direction);
    },
    [gameClient],
  );

  // Snapshot presence ref. The reducer overwrites this on every
  // `lobbyState` event; the registrar below reads the latest value at
  // subscription time so the overlay receives a one-shot replay of
  // current cursors. Avoids re-subscribing when the snapshot changes
  // (which would tear down + re-mount the overlay's listener every time
  // a peer joined/left).
  const snapshotPresenceRef = useRef<readonly PresenceEntry[]>(
    initialLobby.game?.presence ?? [],
  );
  useEffect(() => {
    snapshotPresenceRef.current = view.lobby.game?.presence ?? [];
  }, [view.lobby.game?.presence]);

  // Stable subscribe registrar for `Grid`'s `subscribeToRemotePresence`
  // prop. On every fresh subscription:
  //   1. Replay the current `snapshotPresenceRef` as synthetic
  //      `presenceUpdated` events so a freshly-mounted overlay paints
  //      immediately, before the next live frame arrives.
  //   2. Forward the raw `gameClient.subscribe` stream — the overlay
  //      filters to `presenceUpdated` internally.
  // The replay fires synchronously inside the registrar (same shape as
  // `subscribeConnectionState`'s synchronous priming call) so the
  // overlay's reducer sees the snapshot before any other render.
  const subscribeToRemotePresence = useCallback(
    (handler: (event: GameEvent) => void) => {
      for (const entry of snapshotPresenceRef.current) {
        handler({
          type: 'presenceUpdated',
          sessionId: entry.sessionId,
          row: entry.row,
          column: entry.column,
          direction: entry.direction,
        });
      }
      return gameClient.subscribe(handler);
    },
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
  // Stable reference for `Grid.initialEntries`: only re-computed when the
  // domain `entries` array reference changes (i.e. on `lobbyState` after
  // reconnect or on the loader's REST snapshot). The reducer already
  // leaves `gameSession` untouched on `cellUpdated` (those go straight to
  // the DOM through `subscribeToRemoteCellUpdates`), so the array stays
  // stable across keystrokes and a player's local typing is not wiped on
  // every re-render.
  const initialEntries = useMemo(
    () =>
      lobby.game?.entries.map((e) => ({
        row: e.row,
        column: e.column,
        letter: e.letter,
      })) ?? [],
    [lobby.game?.entries],
  );

  // Lookup table for the `<PresenceOverlay>` chip text. Re-derived only
  // when the players list reference changes (i.e. on join / leave /
  // rename), so steady-state in-game renders share the same Map across
  // re-renders. Keyed by sessionId because the overlay sees presences
  // identified by sessionId, not by index.
  const playersBySessionId = useMemo<ReadonlyMap<SessionId, Player>>(
    () => new Map(lobby.players.map((p) => [p.sessionId, p])),
    [lobby.players],
  );

  return (
    <>
      <ConnectionBanner state={connectionState} />
      <LobbyShell>
        <h1 lang="en" className={wordmarkStyles}>WordSparrow</h1>
        <span className={demoBadgeStyles} aria-label="version démo">Démo</span>
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
            pseudonymError={pseudonymError}
            onClearPseudonymError={handleClearPseudonymError}
            isStarting={isStarting}
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
              initialEntries={initialEntries}
              onLocalFocusChange={handleLocalFocusChange}
              subscribeToRemotePresence={subscribeToRemotePresence}
              playersBySessionId={playersBySessionId}
              currentSessionId={sessionId}
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
                initialEntries={initialEntries}
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
          // Fresh game = no entries yet. The list grows as `cellUpdated`
          // frames arrive (and any reconnect-time `lobbyState` snapshot
          // carries the authoritative server-side set).
          game: {
            puzzle: event.puzzle,
            entries: [],
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
      // `cellUpdated`, `presenceUpdated`, and `error` frames do not
      // change route-level state. Presence is overlay-only — the
      // overlay manages its own per-session map directly off the
      // event stream (see `subscribeToRemotePresence` above).
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
  const [first, second] = cell.clues;
  const clue0: DefinitionClue = { text: first.text, arrow: gameArrowToArrow(first.arrow) };
  const clues: readonly [DefinitionClue] | readonly [DefinitionClue, DefinitionClue] = second
    ? [clue0, { text: second.text, arrow: gameArrowToArrow(second.arrow) }]
    : [clue0];
  return { kind: 'definition', position, clues };
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

// Both "Salon introuvable" and "Serveur indisponible" leave the user
// stranded on a page that cannot recover on its own — the lobby id is
// either gone for good or the upstream is down. Surface a primary CTA
// that drops them back on `/` so they can spin up a new lobby (or join
// another one). Uses `useNavigate` + a `Button` rather than a TanStack
// `<Link>` because the visual treatment matches the rest of the app's
// CTAs (solid `leaf.700`); a text link would read as secondary.
function BackHomeButton() {
  const navigate = useNavigate();
  return (
    <Button
      variant="primary"
      onClick={() => { void navigate({ to: '/' }); }}
    >
      Retour à l&apos;accueil
    </Button>
  );
}

function LobbyErrorWithBackHome({ text }: { text: string }) {
  return (
    <LobbyShell>
      <div className={errorActionsStyles}>
        <p className={detailStyles} role="alert">{text}</p>
        <BackHomeButton />
      </div>
    </LobbyShell>
  );
}

function LobbyErrorComponent({ error }: { error: Error }) {
  if (error instanceof LobbyClientError) {
    switch (error.kind) {
      case 'not-found':
        return <LobbyErrorWithBackHome text="Salon introuvable." />;
      case 'upstream-unavailable':
        return <LobbyErrorWithBackHome text="Serveur indisponible. Réessayez dans un instant." />;
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
