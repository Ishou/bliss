// Lazy half of `/lobby/$lobbyId`. The eager half (`./lobby.$lobbyId.tsx`)
// keeps the route definition, `head()`, and the loader (which runs
// without the lazy chunk loaded). Everything below — the WaitingRoom /
// Grid / EndGameModal state machine, the `GamePuzzle → Puzzle` adapter,
// the connection banner — moves into this chunk and only loads when a
// user actually navigates to the lobby.
//
// `Route.useLoaderData()`, `Route.useParams()`, and `Route.useRouteContext()`
// resolve correctly under the lazy split because `createLazyRoute('/lobby/$lobbyId')`
// matches the eager route's id 1:1 — the hooks the LazyRoute exposes are typed
// against that id.

import { createLazyRoute, useNavigate } from '@tanstack/react-router';
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
  Position as GamePosition,
  PresenceEntry,
  Pseudonym,
  SessionId,
} from '@/domain/game';
import { Grid } from '@/ui/components/grid';
import { usePresenceState } from '@/ui/components/grid/usePresenceState';
import {
  ContentPage,
  ProgressBar,
  PuzzleToolbar,
  ViewportPage,
} from '@/ui/components/layout';
import { ConnectionBanner } from '@/ui/components/lobby/ConnectionBanner';
import { EndGameModal } from '@/ui/components/lobby/EndGameModal';
import { PlayerList } from '@/ui/components/lobby/PlayerList';
import { WaitingRoom } from '@/ui/components/lobby/WaitingRoom';
import { Button, useToast } from '@/ui/components/primitives';
import { useAnnouncer } from '@/ui/components/a11y/Announcer';

// Lighter charcoal panel behind the grid — same role-token + radius
// + padding as the solo route's panel.
const gridPanelStyles = css({
  width: '100%',
  flex: '1 1 0',
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  bg: 'surfaceElevated',
  borderRadius: '12px',
  padding: { base: '4px', md: '12px' },
});

// Visually-hidden h1 for the heading-hierarchy contract — matches
// the solo route's pattern. The visible brand mark is the styled
// Lockup inside `AppHeader`.
const srOnly = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
});

const detailStyles = css({
  fontSize: 'body',
  margin: 0,
  color: 'accent',
  textAlign: 'center',
});

// Stack the alert copy on top of the back-home CTA so the user
// always has a one-click exit when the lobby fails to load.
const errorActionsStyles = css({
  display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 'md',
});

// Per-phase shell choice (ADR-0036 §5):
//   - WAITING (and loading / error states) → ContentPage. WaitingRoom
//     is normal flow content with no `flex: 1` inner absorber, and the
//     status / error views are equally small. ContentPage's `flex: 1 0
//     auto` <main> grows to push the footer to the viewport bottom on
//     short content while never compressing tall content — fixes the
//     mobile footer-overlap bug where the WaitingRoom intrudes into
//     the footer's stacking position.
//   - IN_PROGRESS / COMPLETED → ViewportPage. The grid panel is the
//     `flex: 1` child that absorbs leftover viewport height; this is
//     the same chrome /grille uses.
const LobbyShell = ({
  variant,
  children,
}: {
  readonly variant: 'content' | 'viewport';
  readonly children: React.ReactNode;
}) => {
  const Page = variant === 'viewport' ? ViewportPage : ContentPage;
  return (
    <Page headerActiveNavId="grilles">
      <h1 lang="en" className={srOnly}>
        WordSparrow
      </h1>
      {children}
    </Page>
  );
};

const LobbyStatus = ({ role, text }: { role: 'status' | 'alert'; text: string }) => (
  <LobbyShell variant="content">
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
  const lobbyJoinCodeStash = ctx.lobbyJoinCodeStash!;
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
  // ADR-0027: when the server rejects the WS join with `wrong-code`
  // (no code or mismatched code on a new join), the route does NOT
  // mount the WaitingRoom — the un-authorised user lands on a
  // read-only-snapshot view with this banner asking the organiser
  // for the code. Already-joined sessions never raise this.
  const [joinDenied, setJoinDenied] = useState<string | null>(null);
  // ADR-0027: gate the WaitingRoom render on a confirmed join so a new
  // joiner whose code the server is about to reject doesn't see the
  // lobby contents (player list with the owner) flash before the
  // wrong-code banner takes over. Already-joined sessions (reconnect
  // path: sessionId is already in the snapshot's player list) start
  // confirmed because the server's reconnect branch never fails.
  const initialSessionId = getSession().sessionId;
  const [joinConfirmed, setJoinConfirmed] = useState<boolean>(() =>
    initialLobby.players.some((p) => p.sessionId === initialSessionId),
  );
  // True between "Démarrer la partie" click and the server-side
  // confirmation. WaitingRoom uses the flag to disable the button and
  // flip the label to "Démarrage…" so the WS round-trip (frame →
  // server → broadcast) is not perceived as a dead click.
  const [isStarting, setIsStarting] = useState(false);
  // Mirrored as a ref so the long-lived `subscribe` callback (set up
  // once per `useEffect` run) can read the latest value without being
  // re-attached on every state flip. Used to disambiguate "the error
  // we just received likely killed the start-game flow" from "a stray
  // server error unrelated to the in-flight Start click" so the toast
  // copy stays specific instead of falling through to the generic
  // catch-all.
  const isStartingRef = useRef(isStarting);
  isStartingRef.current = isStarting;
  // ADR-0029: rotation spinner; cleared in the subscribe handler below.
  const [isRotating, setIsRotating] = useState(false);
  const preRotationCodeRef = useRef<string | null>(null);
  // Destructure show/dismiss (not the wrapper object) — the object is recreated each render and would re-trigger the connection useEffect.
  const { show: showToast, dismiss: dismissToast } = useToast();

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
      if (event.type === 'error' &&
        event.errorType === 'https://bliss.example/errors/wrong-code') {
        // Server rejected the join — the WaitingRoom does not mount.
        // Drop the stash so a later reload doesn't replay the bad code.
        setJoinDenied(event.detail ?? 'Code invalide ou partie privée. Demandez le code à l’organisateur.');
        lobbyJoinCodeStash.clear(lobbyId as LobbyId);
      }
      // First `playerJoined` for our own sessionId confirms the WS join
      // — flip on the WaitingRoom render. Clear the stash now that the
      // server has accepted us; future reconnects bypass the code.
      // (Reconnect path: sessionId already in the snapshot's player
      // list, handled at mount time via the initial-state computation
      // above.)
      if (event.type === 'playerJoined' && event.sessionId === sessionId) {
        setJoinConfirmed(true);
        lobbyJoinCodeStash.clear(lobbyId as LobbyId);
      }
      // Clear the in-flight Start spinner once the server either
      // confirms the new game or rejects the request. `gameStarted`
      // also unmounts WaitingRoom, but resetting the flag is good
      // hygiene for any future code path that reuses the component
      // (e.g. a play-again flow that re-enters WAITING).
      if (event.type === 'gameStarted' || event.type === 'error') {
        setIsStarting(false);
      }
      // Surface server `error` frames not handled inline (i.e. neither
      // `invalid-pseudonym` next-to-the-editor nor `wrong-code`
      // join-denied banner) via a toast. Before this, a failed
      // `startGame` (e.g. grid generation failed server-side) left
      // *no* visible chrome and was easy to mistake for the misleading
      // "Connexion perdue" banner that pops on a real transport drop.
      // The toast is intentionally less invasive than a banner: it
      // sits bottom-right, auto-dismisses, and does not push the lobby
      // content around.
      if (event.type === 'error') {
        const inlineHandled =
          event.errorType === 'https://bliss.example/errors/invalid-pseudonym' ||
          event.errorType === 'https://bliss.example/errors/wrong-code';
        if (!inlineHandled) {
          showToast({
            text: messageForGameErrorEvent(event, { wasStarting: isStartingRef.current }),
            tone: 'error',
          });
        }
      }
      // ADR-0029: clear the rotation spinner on the refreshed `lobbyState`
      // (new `code`) or on any server `error` (defensive, e.g. not-owner).
      if ((event.type === 'lobbyState'
          && preRotationCodeRef.current != null
          && event.code !== preRotationCodeRef.current)
        || event.type === 'error') {
        setIsRotating(false);
        preRotationCodeRef.current = null;
      }
    });
    const unsubscribeConnection = gameClient.subscribeConnectionState((state) => {
      setConnectionState(state);
    });
    // ADR-0027: read the code stash the navigation populated. Read is
    // non-destructive so React StrictMode's mount-unmount-remount
    // cycle doesn't drain the stash on the first mount and starve the
    // second of its code. The stash is cleared on either confirmed
    // join (`playerJoined` for our sessionId) or wrong-code rejection
    // — see the subscribe handler above.
    const code = lobbyJoinCodeStash.read(lobbyId as LobbyId) ?? undefined;
    void gameClient.connect({ lobbyId: lobbyId as LobbyId, sessionId, pseudonym, code }).catch(() => {});
    return () => {
      unsubscribeEvents();
      unsubscribeConnection();
      gameClient.disconnect();
    };
  }, [gameClient, lobbyId, getSession, lobbyJoinCodeStash, showToast]);

  // Skip initial `connecting` — first `connected` arms the ref; only then do transient drops earn toast chrome.
  const hasConnectedRef = useRef(false);
  useEffect(() => {
    if (connectionState === 'connected') {
      if (hasConnectedRef.current) dismissToast();
      hasConnectedRef.current = true;
      return;
    }
    if (!hasConnectedRef.current) return;
    if (connectionState === 'reconnecting' || connectionState === 'connecting') {
      showToast({ text: 'Reconnexion…', tone: 'info', duration: null });
    } else if (connectionState === 'disconnected') {
      dismissToast();
    }
  }, [connectionState, showToast, dismissToast]);

  const { sessionId } = getSession();
  const lobby = view.lobby;

  // Announce multiplayer events to screen readers. Lives parallel to
  // the reducer subscription above so the announce path is independent
  // of the state-folding path. Local user's own join/leave events are
  // filtered by `multiAnnouncementFor` (returning null = no announce).
  const announcer = useAnnouncer();
  useEffect(() => {
    const pseudonymBySessionId = new Map(
      lobby.players.map((p) => [p.sessionId, p.pseudonym] as const),
    );
    const ctx: MultiAnnounceContext = { localSessionId: sessionId, pseudonymBySessionId };
    const unsubscribe = gameClient.subscribe((event) => {
      const text = multiAnnouncementFor(event, ctx);
      if (text != null) announcer.say(text);
    });
    return unsubscribe;
  }, [gameClient, sessionId, lobby.players, announcer]);

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

  // ADR-0029: dispatch rotation; stash the pre-click code so the
  // subscribe handler can detect the refreshed snapshot.
  const handleRotateCode = useCallback(() => {
    preRotationCodeRef.current = view.lobby.code ?? null;
    setIsRotating(true);
    gameClient.rotateCode();
  }, [gameClient, view.lobby.code]);

  // ADR-0027: the address-bar URL is `/lobby/$lobbyId` and is NEVER a
  // share token (a viewer copying it cannot join — the WS rejects on
  // missing code). The share button copies a `/join/$code` link
  // instead; recipients clicking it land on the lobby with the code
  // already stashed, and the URL replaces back to `/lobby/$lobbyId`.
  const lobbyCode = lobby.code;
  const handleCopyShareUrl = useCallback(() => {
    if (lobbyCode == null) return;
    const shareUrl = `${window.location.origin}/join/${lobbyCode}`;
    void navigator.clipboard?.writeText(shareUrl);
  }, [lobbyCode]);

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

  // ADR-0036 §5: pick ViewportPage only once a `flex: 1` inner child
  // (the grid panel) is present to absorb leftover viewport height.
  // `lobby.game && gridPuzzle` gates that — until the puzzle is mapped
  // we stay in ContentPage even if the state has flipped, otherwise
  // there's a frame where <main> collapses to 0 with no absorber.
  const shellVariant: 'content' | 'viewport' =
    (lobby.state === 'IN_PROGRESS' || lobby.state === 'COMPLETED')
      && lobby.game != null
      && gridPuzzle != null
      ? 'viewport'
      : 'content';

  return (
    <>
      {/* Only terminal disconnect reaches here; transient states use the toast above. */}
      {connectionState === 'disconnected' ? (
        <ConnectionBanner state="disconnected" />
      ) : null}
      <LobbyShell variant={shellVariant}>
        {lobby.state === 'WAITING' && joinDenied != null ? (
          <p
            role="alert"
            className={css({ fontSize: 'body', color: 'errorText', textAlign: 'center', margin: 0, paddingBlock: 'md' })}
          >
            {joinDenied}
          </p>
        ) : null}
        {lobby.state === 'WAITING' && joinDenied == null && !joinConfirmed ? (
          // Brief "connecting" status while the WS join is in flight.
          // Prevents the "see the owner for a fraction of a second" flash
          // when a wrong-code response is on its way (ADR-0027).
          <p
            role="status"
            className={css({ fontSize: 'body', color: 'fgMuted', textAlign: 'center', margin: 0, paddingBlock: 'md' })}
          >
            Connexion à la partie…
          </p>
        ) : null}
        {lobby.state === 'WAITING' && joinDenied == null && joinConfirmed ? (
          <>
            <p className={detailStyles}>
              {lobby.players.length} {lobby.players.length === 1 ? 'joueur' : 'joueurs'}
            </p>
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
              onRotateCode={handleRotateCode}
              isRotating={isRotating}
            />
          </>
        ) : null}

        {(lobby.state === 'IN_PROGRESS' || lobby.state === 'COMPLETED')
          && lobby.game
          && gridPuzzle ? (
          <InGameView
            puzzle={gridPuzzle}
            startedAt={lobby.game.startedAt}
            frozenAtMs={lobby.state === 'COMPLETED' ? view.durationMs ?? 0 : undefined}
            isCompleted={lobby.state === 'COMPLETED'}
            sessionId={sessionId}
            players={lobby.players}
            ownerSessionId={lobby.ownerSessionId}
            initialEntries={initialEntries}
            lockedPositions={lobby.game.lockedPositions}
            onCellChange={handleCellChange}
            subscribeToRemoteCellUpdates={subscribeToRemoteCellUpdates}
            onLocalFocusChange={handleLocalFocusChange}
            subscribeToRemotePresence={subscribeToRemotePresence}
            playersBySessionId={playersBySessionId}
          />
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

// In-game view shared by `IN_PROGRESS` and `COMPLETED`. Mirrors the
// solo route's chrome (player roster + toolbar → grid panel) but
// drops the local validation flow: per AsyncAPI's `GameLetterCell`,
// the server intentionally omits the canonical answer from every
// puzzle frame (it would let any client cheat). `useValidation`
// would therefore see `cell.answer === undefined` everywhere and
// silently no-op — leaving Vérifier + progress as broken affordances.
//
// Authoritative win = the server's `gameSolved` event. When that
// arrives the route flips `isCompleted` and we paint EVERY letter
// cell validated so the grid lights up sage to match the modal.
interface InGameViewProps {
  readonly puzzle: Puzzle;
  readonly startedAt: string;
  readonly frozenAtMs?: number;
  readonly isCompleted: boolean;
  readonly sessionId: SessionId;
  readonly players: Lobby['players'];
  readonly ownerSessionId: SessionId;
  readonly initialEntries: ReadonlyArray<{ row: number; column: number; letter: string }>;
  readonly lockedPositions: ReadonlyArray<GamePosition>;
  readonly onCellChange: (row: number, col: number, letter: string | null) => void;
  readonly subscribeToRemoteCellUpdates: (handler: (event: GameEvent) => void) => () => void;
  readonly onLocalFocusChange: (
    position: Position | null,
    direction: 'across' | 'down' | null,
  ) => void;
  readonly subscribeToRemotePresence: (handler: (event: GameEvent) => void) => () => void;
  readonly playersBySessionId: ReadonlyMap<SessionId, Player>;
}

function InGameView({
  puzzle,
  startedAt,
  frozenAtMs,
  isCompleted,
  sessionId,
  players,
  ownerSessionId,
  initialEntries,
  lockedPositions,
  onCellChange,
  subscribeToRemoteCellUpdates,
  onLocalFocusChange,
  subscribeToRemotePresence,
  playersBySessionId,
}: InGameViewProps) {
  // Auto-locked cells from server `wordLocked` events (and the snapshot
  // seed for late joiners) merged with the COMPLETED-end-game cue: when
  // `gameSolved` arrives the entire grid is correct by definition (the
  // server wouldn't have fired the event otherwise) and we paint every
  // letter cell validated. Until then, only the per-word locks are sage.
  const validatedPositions = useMemo<ReadonlySet<string>>(() => {
    const set = new Set<string>();
    for (const p of lockedPositions) set.add(`${p.row},${p.column}`);
    if (isCompleted) {
      for (const cell of puzzle.cells) {
        if (cell.kind === 'letter') {
          set.add(`${cell.position.row},${cell.position.col}`);
        }
      }
    }
    return set;
  }, [isCompleted, lockedPositions, puzzle.cells]);

  // Same progress bar as solo: count of locked cells over total letter
  // cells. The denominator is fixed per puzzle; the numerator grows as
  // `wordLocked` events arrive (and jumps to total on `gameSolved`).
  const totalLetterCells = useMemo<number>(
    () => puzzle.cells.reduce((n, c) => (c.kind === 'letter' ? n + 1 : n), 0),
    [puzzle.cells],
  );

  // Multiplayer presence-state derived from the typing / idle /
  // connectionLost / presenceUpdated event stream. One subscription owns
  // the aggregation; both the roster pill (`typingSessionIds` /
  // `idleSessionIds` / `disconnectingSessionIds`) and the grid (which
  // merges typing into per-cell badges) consume the same map.
  const presenceState = usePresenceState(subscribeToRemotePresence, sessionId);
  const typingSessionIds = useMemo(() => {
    const set = new Set<SessionId>();
    for (const [sid, st] of presenceState) {
      if (st.typing) set.add(sid);
    }
    return set;
  }, [presenceState]);
  const idleSessionIds = useMemo(() => {
    const set = new Set<SessionId>();
    for (const [sid, st] of presenceState) {
      if (st.idle) set.add(sid);
    }
    return set;
  }, [presenceState]);
  const disconnectingSessionIds = useMemo(() => {
    const set = new Set<SessionId>();
    for (const [sid, st] of presenceState) {
      if (st.connectionLost) set.add(sid);
    }
    return set;
  }, [presenceState]);

  return (
    <>
      <PlayerList
        players={players}
        ownerSessionId={ownerSessionId}
        currentSessionId={sessionId}
        variant="inline"
        typingSessionIds={typingSessionIds}
        idleSessionIds={idleSessionIds}
        disconnectingSessionIds={disconnectingSessionIds}
      />
      <PuzzleToolbar
        metadata={`Partie multijoueur · ${players.length} ${players.length === 1 ? 'joueur' : 'joueurs'}`}
        timerStartedAt={startedAt}
        timerFrozenAtMs={frozenAtMs}
      />
      <div className={gridPanelStyles}>
        <Grid
          puzzle={puzzle}
          validatedPositions={validatedPositions}
          onCellChange={isCompleted ? undefined : onCellChange}
          subscribeToRemoteCellUpdates={subscribeToRemoteCellUpdates}
          initialEntries={initialEntries}
          onLocalFocusChange={isCompleted ? undefined : onLocalFocusChange}
          subscribeToRemotePresence={subscribeToRemotePresence}
          playersBySessionId={playersBySessionId}
          currentSessionId={sessionId}
          typingSessionIds={typingSessionIds}
        />
      </div>
      <ProgressBar
        value={validatedPositions.size}
        total={totalLetterCells}
      />
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
      // `code` is now first-class on the snapshot — take it from the
      // event so future server-side mutations propagate.
      return {
        ...current,
        lobby: {
          players: event.players, ownerSessionId: event.ownerSessionId,
          state: event.state, gridConfig: event.gridConfig, game: event.game,
          code: event.code,
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
          // carries the authoritative server-side set). Same posture for
          // `lockedPositions`: empty until the first correct word fills.
          game: {
            puzzle: event.puzzle,
            entries: [],
            lockedPositions: [],
            startedAt: event.startedAt,
            completedAt: null,
          },
        },
      };
    case 'wordLocked': {
      // Append the just-locked positions to the cumulative set on the
      // active session. Dedupe so a stray duplicate from a server
      // re-broadcast never inflates the array (the `validatedPositions`
      // memo is keyed by string already, but keeping the source-of-truth
      // unique is still cheaper than keeping it dirty).
      const game = current.lobby.game;
      if (!game) return current;
      const seen = new Set<string>();
      const merged: GamePosition[] = [];
      for (const p of game.lockedPositions) {
        const key = `${p.row},${p.column}`;
        if (seen.has(key)) continue;
        seen.add(key);
        merged.push(p);
      }
      for (const p of event.positions) {
        const key = `${p.row},${p.column}`;
        if (seen.has(key)) continue;
        seen.add(key);
        merged.push(p);
      }
      return {
        ...current,
        lobby: {
          ...current.lobby,
          game: { ...game, lockedPositions: merged },
        },
      };
    }
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
    hintsAllowed: gamePuzzle.hintsAllowed,
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
// CTAs (solid primary `Button`); a text link would read as secondary.
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
    <LobbyShell variant="content">
      <div className={errorActionsStyles}>
        <p className={detailStyles} role="alert">{text}</p>
        <BackHomeButton />
      </div>
    </LobbyShell>
  );
}

// French copy for a server `error` frame surfaced via the toast (i.e.
// not handled inline by the WaitingRoom pseudonym editor or the
// wrong-code join-denied banner). The server's `detail` is preferred
// when present — operators set it deliberately, and it carries the
// most specific context (e.g. "Pseudonyme déjà utilisé"). When the
// frame arrives mid Start-game flow and ships no `detail`, the copy
// pins the error to the action the user just took rather than falling
// through to a generic "Une erreur" that would re-introduce the same
// "what just broke?" ambiguity the misleading "Connexion perdue"
// banner caused before this change.
function messageForGameErrorEvent(
  event: { readonly detail?: string; readonly title: string },
  context: { readonly wasStarting: boolean },
): string {
  if (event.detail != null && event.detail.length > 0) return event.detail;
  // When the click that's in flight is a Démarrer, the start-specific
  // copy beats the server's title — "Impossible de démarrer la
  // partie" stays grounded in the action the user just took, even if
  // the server's title is more abstract ("Salon complet" right after
  // Démarrer would read as unrelated chrome).
  if (context.wasStarting) return 'Impossible de démarrer la partie. Réessayez.';
  // Otherwise prefer the server's `title`: backend error frames carry
  // French, context-specific titles ("Salon complet", "Opération
  // réservée au propriétaire", "Vous n'êtes pas membre de ce salon",
  // etc.) which are strictly more useful than the generic fallback.
  // The fallback only kicks in for malformed / blank-title frames.
  if (event.title.length > 0) return event.title;
  return 'Une erreur est survenue. Réessayez.';
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
  // Unknown error — surface to the browser console so the user (or CI
  // logs) can see the underlying cause when the generic copy is shown.
  // The fallback UI stays vague-on-purpose: the user is not equipped
  // to act on a TypeError or a parser mismatch, but a developer
  // reading devtools should be able to.
  console.error('LobbyErrorComponent: unexpected error', error);
  return <LobbyStatus role="alert" text="Une erreur est survenue. Réessayez." />;
}

export const Route = createLazyRoute('/lobby/$lobbyId')({
  component: LobbyPage,
  pendingComponent: () => <LobbyStatus role="status" text="Chargement du salon…" />,
  errorComponent: LobbyErrorComponent,
});

export interface MultiAnnounceContext {
  readonly localSessionId: string;
  readonly pseudonymBySessionId: ReadonlyMap<string, string>;
  // Reads the player's letter at (row, col) — '' when empty. Defaults
  // to a DOM query (ADR-0002 §4: cell values live in the DOM).
  readonly readLetterAt?: (row: number, col: number) => string;
}

/**
 * Map a `GameEvent` to a polite SR announcement, or `null` when no
 * announcement is appropriate (local user's own join/leave; events
 * that don't carry a meaningful SR signal).
 */
export function multiAnnouncementFor(
  event: GameEvent,
  ctx: MultiAnnounceContext,
): string | null {
  const read =
    ctx.readLetterAt ??
    ((row, col) => {
      const input = document.querySelector<HTMLInputElement>(
        `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
      );
      return input?.value ?? '';
    });

  switch (event.type) {
    case 'playerJoined': {
      if (event.sessionId === ctx.localSessionId) return null;
      return `${event.pseudonym} a rejoint la partie`;
    }
    case 'playerLeft': {
      if (event.sessionId === ctx.localSessionId) return null;
      const name = ctx.pseudonymBySessionId.get(event.sessionId) ?? 'Un joueur';
      return `${name} a quitté la partie`;
    }
    case 'gameStarted': {
      return 'La partie commence';
    }
    case 'wordLocked': {
      const word = event.positions
        .map((p) => read(p.row, p.column))
        .join('');
      if (word.length === 0) return null;
      return `mot validé : ${word}`;
    }
    default:
      return null;
  }
}
