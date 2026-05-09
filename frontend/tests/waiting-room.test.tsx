import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
  code: 'A2B3C4',
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
    // Picker is now an Ark `ToggleGroup` (single-select). Zag's
    // toggle-group state machine still emits `role="radiogroup"` on
    // the root and `role="radio"` on each item when `multiple={false}`,
    // so the assertion shape stays a11y-aligned with the previous
    // RadioGroup primitive — the difference is purely visual: each
    // option is a real `<button>` chip with a pressed/unpressed state
    // rather than a hidden `<input type="radio">`.
    expect(screen.getByRole('radiogroup', { name: /taille de la grille/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /démarrer la partie/i })).toBeInTheDocument();
  });

  it('hides the grid-size picker and Start button from non-owners', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={peerSessionId} {...noopProps} />,
    );
    expect(screen.queryByRole('radiogroup', { name: /taille de la grille/i })).toBeNull();
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

describe('WaitingRoom — readonly PinInput + eye toggle (ADR-0027)', () => {
  // The code surface in the WaitingRoom is the same `PinInput`
  // primitive the Accueil uses, in `readOnly` mode. Same fixed slot
  // widths regardless of mask state — no layout shift between mask
  // and reveal — and the eye toggle is the single visibility
  // affordance.

  it('renders the code as readOnly PinInput slots, masked by default', () => {
    const { container } = render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    const slots = container.querySelectorAll<HTMLInputElement>('input[data-part="input"]');
    expect(slots).toHaveLength(6);
    for (const slot of slots) {
      expect(slot.readOnly).toBe(true);
      // Streamer-safe default: masked at first paint.
      expect(slot.getAttribute('data-mask')).toBe('true');
    }
  });

  it('reveals the code via the eye toggle and re-masks on second click', () => {
    const { container } = render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    const showToggle = screen.getByRole('button', { name: /afficher le code/i });
    expect(showToggle).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(showToggle);
    const masked = container.querySelectorAll<HTMLInputElement>('input[data-part="input"][data-mask="true"]');
    expect(masked).toHaveLength(0);
    const hideToggle = screen.getByRole('button', { name: /masquer le code/i });
    expect(hideToggle).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(hideToggle);
    const remasked = container.querySelectorAll<HTMLInputElement>('input[data-part="input"][data-mask="true"]');
    expect(remasked).toHaveLength(6);
  });

  it('does not render the code surface when lobby.code is null (legacy lobbies)', () => {
    const noCodeLobby: Lobby = { ...baseLobby, code: null };
    const { container } = render(
      <WaitingRoom lobby={noCodeLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    expect(container.querySelector('input[data-part="input"]')).toBeNull();
    expect(screen.queryByRole('button', { name: /afficher le code/i })).toBeNull();
  });
});

describe('WaitingRoom — grid size picker', () => {
  it('reflects the current grid config as the pressed toggle', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    // Ark `ToggleGroup.Item` is a real `<button role="radio">` — the
    // pressed state surfaces via `aria-checked`, not the input `.checked`
    // field (there is no underlying `<input>` to read).
    const seven = screen.getByRole('radio', { name: '7×7' });
    expect(seven).toHaveAttribute('aria-checked', 'true');
    const five = screen.getByRole('radio', { name: '5×5' });
    expect(five).toHaveAttribute('aria-checked', 'false');
  });

  it('fires onSetGridConfig with (n, n) when a different size is selected', async () => {
    const onSetGridConfig = vi.fn();
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        onSetGridConfig={onSetGridConfig}
      />,
    );
    // Items are real `<button>` elements (no hidden `<input>`). jsdom does
    // not focus a button on `fireEvent.click`, and zag's toggle-group
    // ignores click events on un-focused items — so call the native
    // `HTMLElement.click()` after focusing, matching the helper called
    // out in the frontend playbook.
    const item = screen.getByRole('radio', { name: '11×11' }) as HTMLButtonElement;
    await act(async () => { item.focus(); item.click(); });
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

  describe('inline copy feedback', () => {
    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => { vi.useRealTimers(); });

    it('shows "Lien copié !" right after the click and hides it after ~2s', () => {
      render(
        <WaitingRoom
          lobby={baseLobby}
          currentSessionId={ownerSessionId}
          {...noopProps}
        />,
      );
      // No feedback visible before the click.
      expect(screen.queryByText(/lien copié/i)).toBeNull();

      fireEvent.click(screen.getByRole('button', { name: /copier le lien/i }));

      // The status node uses role="status" + aria-live so assistive
      // tech announces it; assert presence + the role at the same time.
      const status = screen.getByRole('status');
      expect(status).toHaveTextContent(/lien copié/i);

      // After the 2s timer the feedback is gone again.
      act(() => { vi.advanceTimersByTime(2000); });
      expect(screen.queryByText(/lien copié/i)).toBeNull();
    });
  });
});

describe('WaitingRoom — Start button loading state', () => {
  it('disables the button and flips the label to "Démarrage…" when isStarting is true', () => {
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        isStarting
      />,
    );
    // The label change moves the accessible name; query by the new
    // label so we assert both the visible copy and the disabled state.
    const button = screen.getByRole('button', { name: /démarrage…/i });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByRole('button', { name: /démarrer la partie/i })).toBeNull();
  });

  it('renders the default label and is enabled when isStarting is false (default)', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    const button = screen.getByRole('button', { name: /démarrer la partie/i });
    expect(button).toBeEnabled();
    expect(button).not.toHaveAttribute('aria-busy');
  });
});

describe('WaitingRoom — code rotation (ADR-0029)', () => {
  // Owner-only "Régénérer le code" button next to the readonly PIN.

  it('renders the "Régénérer le code" button only for the owner', () => {
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        onRotateCode={() => {}}
      />,
    );
    expect(screen.getByRole('button', { name: /régénérer le code/i })).toBeInTheDocument();
  });

  it('hides the "Régénérer le code" button from non-owners', () => {
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={peerSessionId}
        {...noopProps}
        onRotateCode={() => {}}
      />,
    );
    expect(screen.queryByRole('button', { name: /régénérer le code/i })).toBeNull();
  });

  it('fires onRotateCode when the owner clicks the button', () => {
    const onRotateCode = vi.fn();
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        onRotateCode={onRotateCode}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /régénérer le code/i }));
    expect(onRotateCode).toHaveBeenCalledTimes(1);
  });

  it('disables the button and flips the label while isRotating is true', () => {
    render(
      <WaitingRoom
        lobby={baseLobby}
        currentSessionId={ownerSessionId}
        {...noopProps}
        onRotateCode={() => {}}
        isRotating
      />,
    );
    const button = screen.getByRole('button', { name: /régénération…/i });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByRole('button', { name: /régénérer le code/i })).toBeNull();
  });
});

describe('WaitingRoom — player row alignment', () => {
  it('places the pseudonym before the badge group inside each row', () => {
    render(
      <WaitingRoom lobby={baseLobby} currentSessionId={ownerSessionId} {...noopProps} />,
    );
    const ownerRow = screen.getAllByTestId('player-row')[0]!;
    const nameEl = within(ownerRow).getByText(ownerPseudonym);
    const badgesEl = within(ownerRow).getByText(/vous/i);
    // Screen-reader order: pseudonym announces before the badge group.
    expect(
      nameEl.compareDocumentPosition(badgesEl) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    // Owner row also shows the "propriétaire" badge.
    expect(within(ownerRow).getByText(/propriétaire/i)).toBeInTheDocument();
  });
});
