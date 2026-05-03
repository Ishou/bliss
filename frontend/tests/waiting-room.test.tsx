import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Lobby, Pseudonym, SessionId } from '@/domain/game';
import { WaitingRoom } from '@/ui/components/lobby/WaitingRoom';

// `WaitingRoom` is a pure prop-driven component (Wave H PR #16). The
// tests exercise the four user-facing flows promised by the props
// surface: render the player list with badges, gate owner-only
// controls, allow the owner to Start solo (1+ players), fire the
// rename / grid / share callbacks. No GameClient / network mocking —
// the parent route owns wiring; this suite only asserts the contract
// this component promises its caller.

const ownerSessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId;
const peerSessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;
const ownerPseudonym = 'Joueur 1234' as Pseudonym;
const peerPseudonym = 'Joueur 5678' as Pseudonym;

const baseLobby: Lobby = {
  ownerSessionId,
  players: [
    { sessionId: ownerSessionId, pseudonym: ownerPseudonym, joinedAt: '2026-05-02T15:30:00Z' },
    { sessionId: peerSessionId, pseudonym: peerPseudonym, joinedAt: '2026-05-02T15:30:01Z' },
  ],
  state: 'WAITING',
  gridConfig: { width: 7, height: 7 },
  game: null,
};

const noopProps = {
  onRename: () => {},
  onSetGridConfig: () => {},
  onStart: () => {},
  onCopyShareUrl: () => {},
};

describe('WaitingRoom — player list', () => {
  it('renders one row per player with the pseudonym text', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    const list = screen.getByRole('list', { name: /liste des joueurs/i });
    // The owner pseudonym also surfaces in the pseudonym-editor button;
    // scope the assertion to the player list to avoid the duplicate.
    expect(within(list).getByText(ownerPseudonym)).toBeInTheDocument();
    expect(within(list).getByText(peerPseudonym)).toBeInTheDocument();
  });

  it('marks the local player with a "vous" badge', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    // Exactly one "vous" badge — the row of the local player.
    expect(screen.getAllByText('vous')).toHaveLength(1);
  });

  it('marks the lobby owner with a "propriétaire" badge', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={peerSessionId} {...noopProps} />,
    );
    expect(screen.getAllByText('propriétaire')).toHaveLength(1);
  });

  it('renders empty slots up to the 8-player cap', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    // 2 players + 6 empty slots = 8 total list items in the player list.
    expect(screen.getAllByLabelText('Place libre')).toHaveLength(6);
  });
});

describe('WaitingRoom — owner-gated controls', () => {
  it('shows the grid-size picker and Start button to the owner', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    expect(screen.getByRole('group', { name: /taille de la grille/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /démarrer la partie/i })).toBeInTheDocument();
  });

  it('hides the grid-size picker and Start button from non-owners', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={peerSessionId} {...noopProps} />,
    );
    expect(screen.queryByRole('group', { name: /taille de la grille/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /démarrer la partie/i })).toBeNull();
  });
});

describe('WaitingRoom — Start button', () => {
  it('is enabled for the owner in a solo lobby (1 player) so the owner can play through the multiplayer flow alone', () => {
    const soloLobby: Lobby = { ...baseLobby, players: [baseLobby.players[0]!] };
    const onStart = vi.fn();
    render(
      <WaitingRoom lobby={soloLobby} currentSessionId={ownerSessionId} {...noopProps} onStart={onStart} />,
    );
    const startButton = screen.getByRole('button', { name: /démarrer la partie/i });
    expect(startButton).toBeEnabled();
    fireEvent.click(startButton);
    expect(onStart).toHaveBeenCalledTimes(1);
  });

  it('is enabled with 2+ players and fires onStart on click', () => {
    const onStart = vi.fn();
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} onStart={onStart} />,
    );
    const startButton = screen.getByRole('button', { name: /démarrer la partie/i });
    expect(startButton).toBeEnabled();
    fireEvent.click(startButton);
    expect(onStart).toHaveBeenCalledTimes(1);
  });
});

describe('WaitingRoom — pseudonym editor', () => {
  it('fires onRename with the trimmed value when Enter is pressed', () => {
    const onRename = vi.fn();
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} onRename={onRename} />,
    );
    // Enter edit mode by clicking the pseudonym button.
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`modifier votre pseudonyme.*${ownerPseudonym}`, 'i') }));
    const input = screen.getByLabelText(/votre pseudonyme/i) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '  Nouveau Pseudo  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onRename).toHaveBeenCalledWith('Nouveau Pseudo');
  });

  it('caps the input at MAX_PSEUDONYM_LENGTH via the maxLength HTML attribute', () => {
    // Belt-and-braces: jsdom does not enforce `maxLength` on programmatic
    // `fireEvent.change`, so the assertion is on the attribute itself
    // (which the browser DOES honour during real keystrokes / paste).
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`modifier votre pseudonyme.*${ownerPseudonym}`, 'i') }));
    const input = screen.getByLabelText(/votre pseudonyme/i) as HTMLInputElement;
    expect(input.maxLength).toBe(32);
  });

  it('does not fire onRename when the trimmed draft exceeds MAX_PSEUDONYM_LENGTH', () => {
    // Defensive guard for the paste-then-Enter path: the browser's
    // `maxLength` does NOT apply to a value set programmatically, so the
    // editor itself must refuse to commit an over-cap value.
    const onRename = vi.fn();
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} onRename={onRename} />,
    );
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`modifier votre pseudonyme.*${ownerPseudonym}`, 'i') }));
    const input = screen.getByLabelText(/votre pseudonyme/i) as HTMLInputElement;
    // 33 chars - one over the 32 cap.
    const tooLong = 'a'.repeat(33);
    fireEvent.change(input, { target: { value: tooLong } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onRename).not.toHaveBeenCalled();
  });

  it('renders the server pseudonymError inline when present', () => {
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        pseudonymError="Pseudonym must be at most 32 chars, was 33"
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`modifier votre pseudonyme.*${ownerPseudonym}`, 'i') }));
    expect(screen.getByRole('alert')).toHaveTextContent(/at most 32 chars/i);
  });

  it('calls onClearPseudonymError when the user starts typing again', () => {
    const onClearPseudonymError = vi.fn();
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        pseudonymError="Pseudonym must be at most 32 chars, was 33"
        onClearPseudonymError={onClearPseudonymError}
      />,
    );
    // Open the editor (also fires the clear so the error doesn't survive
    // the next attempt).
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`modifier votre pseudonyme.*${ownerPseudonym}`, 'i') }));
    onClearPseudonymError.mockClear();
    const input = screen.getByLabelText(/votre pseudonyme/i) as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'Alice' } });
    expect(onClearPseudonymError).toHaveBeenCalled();
  });
});

describe('WaitingRoom — grid size picker', () => {
  it('reflects the current grid config as the checked radio', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    const seven = screen.getByRole('radio', { name: '7×7' }) as HTMLInputElement;
    expect(seven.checked).toBe(true);
  });

  it('fires onSetGridConfig with (n, n) when a different size is selected', () => {
    const onSetGridConfig = vi.fn();
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        onSetGridConfig={onSetGridConfig}
      />,
    );
    fireEvent.click(screen.getByRole('radio', { name: '11×11' }));
    expect(onSetGridConfig).toHaveBeenCalledWith(11, 11);
  });
});

describe('WaitingRoom — share URL button', () => {
  it('fires onCopyShareUrl when the "Copier le lien" button is clicked', () => {
    const onCopyShareUrl = vi.fn();
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        onCopyShareUrl={onCopyShareUrl}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /copier le lien/i }));
    expect(onCopyShareUrl).toHaveBeenCalledTimes(1);
  });
});
