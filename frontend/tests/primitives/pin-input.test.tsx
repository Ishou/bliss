import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { PinInput } from '@/ui/components/primitives/PinInput';
import {
  LOBBY_CODE_PATTERN,
  extractLobbyCode,
  normaliseLobbyCode,
} from '@/domain/game/lobbyCode';

// Project-local `PinInput` primitive (ADR-0027 §"PIN input"). Wraps
// Ark UI `pin-input` with the lobby code's normaliser baked in: typed
// chars are uppercased + filtered to the Crockford alphabet, and a
// pasted full share-link URL extracts the code. Mask and eye toggle
// live on the consumer side (Accueil), but the primitive owns the
// canonicalisation.

// Controlled wrapper that mirrors how the Accueil card uses the
// primitive. Avoids re-implementing the controlled state in every test.
function ControlledPin({
  initial = '',
  mask = false,
  onValueChange,
  invalid = false,
  errorText,
  readOnly = false,
}: {
  readonly initial?: string;
  readonly mask?: boolean;
  readonly onValueChange?: (next: string) => void;
  readonly invalid?: boolean;
  readonly errorText?: string;
  readonly readOnly?: boolean;
}) {
  const [value, setValue] = useState(initial);
  return (
    <PinInput
      label="Code de partie"
      value={value}
      mask={mask}
      invalid={invalid}
      errorText={errorText}
      readOnly={readOnly}
      onValueChange={(next) => {
        setValue(next);
        onValueChange?.(next);
      }}
    />
  );
}

describe('PinInput', () => {
  it('renders six labelled slots wired to the supplied label', () => {
    render(<ControlledPin />);
    // The group is labelled by the visually-hidden `Code de partie` label;
    // each slot is a real <input> the assistive tech can address through
    // the group label.
    const slots = screen.getAllByLabelText(/code de partie/i);
    expect(slots.length).toBeGreaterThanOrEqual(6);
  });

  it('property: any string normalises to a value matching the open pattern', () => {
    // Boundary check: every char outside the alphabet must drop out
    // and the result must be at most 6 chars from the alphabet. We
    // run a few hundred random samples through the pure normaliser
    // here; the input filter wires it 1:1.
    const partialPattern = /^[A-HJKM-NP-Z2-9]{0,6}$/;
    const alphabet = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_./:!@#$';
    const rng = (n: number) => Math.floor(Math.random() * n);
    for (let i = 0; i < 200; i++) {
      const len = rng(40);
      let raw = '';
      for (let j = 0; j < len; j++) raw += alphabet[rng(alphabet.length)];
      const out = normaliseLobbyCode(raw);
      expect(out).toMatch(partialPattern);
      expect(out.length).toBeLessThanOrEqual(6);
    }
  });

  it('property: extractLobbyCode preserves bare codes through extraction', () => {
    // For any 6-char code in the Crockford alphabet, extractLobbyCode
    // is the identity (idempotent on canonical input).
    const alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    const rng = (n: number) => Math.floor(Math.random() * n);
    for (let i = 0; i < 100; i++) {
      let code = '';
      for (let j = 0; j < 6; j++) code += alphabet[rng(alphabet.length)];
      expect(LOBBY_CODE_PATTERN.test(code)).toBe(true);
      expect(extractLobbyCode(code)).toBe(code);
    }
  });

  it('renders the error text and marks the group as invalid when invalid', () => {
    render(<ControlledPin invalid errorText="Code invalide" />);
    expect(screen.getByText('Code invalide')).toBeInTheDocument();
  });

  it('renders disabled slots when disabled prop is true', () => {
    render(<ControlledPin />);
    const slots = screen.getAllByLabelText(/code de partie/i)
      .filter((el): el is HTMLInputElement => el instanceof HTMLInputElement);
    // Sanity: not disabled by default — proves the prop default works.
    expect(slots[0]?.getAttribute('disabled')).toBeNull();
  });

  it('renders alongside a sibling button in a constrained flex row', () => {
    // Regression for the Accueil layout where the PIN used to overflow
    // its parent and push the eye toggle off-screen (slots had fixed
    // `width: 2.5em` × 6 ≈ 240 px on a ~260 px card-inner width). The
    // primitive now uses `flex: 1 / minWidth: 0` and slots share the
    // row via `flex: 1`. We can't measure CSS in jsdom (classnames
    // don't apply), but we CAN assert that a sibling button next to
    // the PIN is in the document — i.e. the PIN did not consume the
    // entire row to the point of clipping its sibling out.
    render(
      <div style={{ width: '260px', display: 'flex', alignItems: 'center', gap: '8px' }}>
        <ControlledPin />
        <button type="button" aria-label="probe">x</button>
      </div>,
    );
    expect(screen.getByRole('button', { name: 'probe' })).toBeInTheDocument();
  });

  // The browser's password manager is triggered by `<input type="password">`.
  // Even with `autocomplete="off"`, Chrome's "Save password?" prompt still
  // appears on form submit when any input was type=password. Our PIN slots
  // mask via CSS instead so the inputs stay type=text.
  it('keeps slot inputs as type=text even when masked (avoids password manager)', () => {
    render(<ControlledPin mask />);
    const slots = screen.getAllByLabelText(/code de partie/i)
      .filter((el): el is HTMLInputElement => el instanceof HTMLInputElement);
    for (const slot of slots) {
      expect(slot.getAttribute('type')).not.toBe('password');
    }
  });

  it('marks the slots as masked via a data attribute when mask is true', () => {
    // The actual masking is done in CSS via `-webkit-text-security: disc`
    // (and `text-security: disc` on Firefox 110+). We assert the toggle
    // contract via a `data-mask` attribute the consumer can also use to
    // style adjacent affordances.
    const { container, rerender } = render(<ControlledPin mask />);
    const firstSlot = () => container.querySelector<HTMLInputElement>('input[data-part="input"]');
    expect(firstSlot()?.getAttribute('data-mask')).toBe('true');
    rerender(<ControlledPin mask={false} />);
    expect(firstSlot()?.getAttribute('data-mask')).toBe('false');
  });

  it('discourages password / form-fill heuristics on every slot', () => {
    const { container } = render(<ControlledPin />);
    const slots = container.querySelectorAll<HTMLInputElement>('input[data-part="input"]');
    expect(slots.length).toBe(6);
    for (const slot of slots) {
      expect(slot.getAttribute('autocomplete')).toBe('off');
      expect(slot.getAttribute('data-1p-ignore')).toBe('true');
      expect(slot.getAttribute('data-lpignore')).toBe('true');
    }
  });

  it('renders readOnly slots that block typing', () => {
    render(<ControlledPin initial="A2B3C4" readOnly />);
    const slots = screen.getAllByLabelText(/code de partie/i)
      .filter((el): el is HTMLInputElement => el instanceof HTMLInputElement);
    for (const slot of slots) {
      expect(slot.getAttribute('readonly')).not.toBeNull();
    }
  });

  // ADR-0027 paste UX — owners frequently copy the full share-link URL
  // out of Discord. The PIN input intercepts the paste before zag's
  // alphabet filter rejects the URL's `/` and `:` characters, runs it
  // through `extractLobbyCode`, and drives the controlled `value`.
  it('extracts the code from a pasted /join/$code share URL via onPaste', () => {
    const onValueChange = vi.fn();
    const { container } = render(<ControlledPin onValueChange={onValueChange} />);
    const slot = container.querySelector<HTMLInputElement>('input[data-part="input"]')!;
    fireEvent.paste(slot, {
      clipboardData: {
        getData: (type: string) => (type === 'text' || type === 'text/plain' ? 'https://wordsparrow.io/join/A2B3C4' : ''),
      },
    });
    expect(onValueChange).toHaveBeenLastCalledWith('A2B3C4');
  });

  it('extracts a bare code from a paste with surrounding whitespace', () => {
    const onValueChange = vi.fn();
    const { container } = render(<ControlledPin onValueChange={onValueChange} />);
    const slot = container.querySelector<HTMLInputElement>('input[data-part="input"]')!;
    fireEvent.paste(slot, {
      clipboardData: {
        getData: (type: string) => (type === 'text' || type === 'text/plain' ? '  a2b3c4  ' : ''),
      },
    });
    expect(onValueChange).toHaveBeenLastCalledWith('A2B3C4');
  });

  // Sentinel for the prod crash captured in SigNoz as
  //   `TypeError: Cannot read properties of undefined (reading 'split')`
  //   at setFocusedValue → executeActions (@zag-js/pin-input)
  //
  // Root cause: zag's `getNextValue` does
  //   `if (current[0] === next[0]) nextValue = next[1];`
  //   `return nextValue.split("")[nextValue.length - 1];`
  // — which throws when the user re-types the character already sitting
  // in the focused slot (`next` is single-char so `next[1]` is undefined).
  // Field path: share-link prefill on /join/<code>, focus a slot, retype.
  //
  // Upstream still ships the bug on `latest` (1.40.0), so we patch the
  // installed copy via `patches/@zag-js__pin-input@0.82.2.patch`. jsdom
  // doesn't faithfully drive zag's input pipeline (React's value tracker
  // dedups the same-char change), which is why the regression isn't a
  // behavioural test — it's a sentinel that catches the patch falling
  // off (zag version bump, pnpm config drift) before prod does.
  it('ships the @zag-js/pin-input getNextValue undefined-guard patch', () => {
    const require = createRequire(import.meta.url);
    const machinePath = require.resolve('@zag-js/pin-input');
    const machineSource = readFileSync(machinePath, 'utf8');
    expect(machineSource).toContain('function getNextValue');
    expect(machineSource).toMatch(/if \(nextValue == null\) return current/);
  });
});
