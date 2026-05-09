import { PinInput as ArkPinInput } from '@ark-ui/react/pin-input';
import * as React from 'react';
import { forwardRef, type ClipboardEvent } from 'react';
import { css } from 'styled-system/css';
import {
  LOBBY_CODE_LENGTH,
  extractLobbyCode,
} from '@/domain/game/lobbyCode';

// Project-local PinInput primitive — wraps Ark UI's `pin-input` with
// the Crockford-alphabet normaliser baked in. Six segmented slots,
// each a real `<input>` so keyboard + a11y is delegated to Ark.
//
// Why a wrapper:
//  - The lobby join code's alphabet is project-specific (excludes
//    `0/O`, `1/I/L`); pasted full share-link URLs must extract the
//    code and fill the slots. Both behaviours live in
//    `domain/game/lobbyCode` so this primitive only owns the wiring.
//  - **Mask is CSS, not `type=password`** (ADR-0027): Ark's `mask`
//    prop emits `type=password` per slot, which triggers Chrome's
//    "Save password?" prompt on form submit — wrong UX for a join
//    code. We keep slots as `type=text` and apply `text-security:
//    disc` via a class so the rendered glyph is a bullet without
//    flagging the field as a password to the browser.
//  - **Paste is intercepted at the React level** before zag's
//    `onBeforeInput` filter rejects URL-shaped paste (which contains
//    `/`, `:`, `.` — all outside the alphabetic alphabet). The
//    consumer's controlled `value` then drives Ark.
//  - The label is visually hidden but assistive-only (the design
//    expresses the field intent via the placeholder / surrounding
//    chrome). The Ark `Label` slot still drives the accessible name
//    of the group and per-slot aria-labels.

const rootStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
  // Be flex-friendly so consumers can drop the PIN into a row next
  // to an icon button (Accueil eye toggle, WaitingRoom eye toggle)
  // and have the slots share the available width without overflowing
  // the card. `min-width: 0` is the canonical antidote to flex-item
  // intrinsic-size lock-in.
  flex: 1,
  minWidth: 0,
  // Cap the overall width so on wider containers (e.g. the
  // WaitingRoom card at ~448 px inner width) slots don't bloat to
  // ~67 px each — they'd dominate the card. The cap is 6 slots
  // × ~3 em + 5 gaps; below this the slots flex-share gracefully,
  // above it the PIN sits left-aligned at its maximum.
  maxWidth: '20em',
});

// Visually-hidden label — Ark's Label part still renders, just placed
// off-canvas so the group has an accessible name without competing
// with the placeholder for visual real-estate.
const labelStyles = css({
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

const controlStyles = css({
  display: 'flex',
  gap: 'xs',
  // Span the PIN's container so slots can flex-share the row.
  width: '100%',
});

const slotStyles = css({
  flex: 1,
  minWidth: 0,
  // No fixed width: each slot shares the row width via `flex: 1`.
  // `aspectRatio` keeps the square-ish look without locking pixels
  // (bigger viewports → bigger slots automatically).
  aspectRatio: '1',
  textAlign: 'center',
  // No padding-inline: the slot is too narrow to spare the chars any
  // edge gap. Padding-block gives a tiny vertical breathing room.
  paddingBlock: '0',
  paddingInline: '0',
  borderRadius: 'sm',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  // `md` (1.125 rem) reads cleanly in narrow slots; `lg` was the
  // pre-fix size and overflowed the Accueil card on mid-width
  // desktop layouts.
  fontSize: 'md',
  fontWeight: 'bold',
  fontVariantNumeric: 'tabular-nums',
  textTransform: 'uppercase',
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _disabled: { opacity: 0.6, cursor: 'not-allowed' },
  _placeholder: { color: 'fgMuted' },
});

// CSS-based mask — `text-security: disc` renders bullets in place of
// glyphs without flipping the input's `type` to password. Webkit /
// Blink have shipped `-webkit-text-security` since 2007; Firefox
// added `text-security` in 110 (2023). Panda CSS doesn't type these
// non-standard properties, so we apply them via an inline `style`
// prop instead of `css({...})`. For older Firefox the input renders
// the real chars — acceptable graceful degradation since the
// password-manager-avoidance is the primary win, not the visual mask.
const maskedInputStyle: React.CSSProperties = {
  WebkitTextSecurity: 'disc',
  // Firefox 110+ — non-standard but stable. Cast through `unknown`
  // so React.CSSProperties accepts the unknown key.
  ...({ textSecurity: 'disc' } as Record<string, string>),
} as React.CSSProperties;

const errorStyles = css({
  fontSize: 'sm',
  color: 'errorText',
  margin: 0,
});

export interface PinInputProps {
  readonly label: string;
  readonly value: string;
  readonly onValueChange: (next: string) => void;
  readonly mask?: boolean;
  readonly disabled?: boolean;
  readonly invalid?: boolean;
  readonly errorText?: string;
  readonly placeholder?: string;
  readonly readOnly?: boolean;
}

export const PinInput = forwardRef<HTMLDivElement, PinInputProps>(function PinInput(
  {
    label,
    value,
    onValueChange,
    mask = false,
    disabled,
    invalid,
    errorText,
    placeholder = '•',
    readOnly,
  },
  ref,
) {
  // Ark's `value` is a `string[]` (one entry per slot). Pad / truncate
  // the controlled string so React + Ark stay in sync regardless of
  // how the consumer normalised the incoming value.
  const slots = Array.from({ length: LOBBY_CODE_LENGTH }, (_, i) => value[i] ?? '');

  // Intercept paste BEFORE zag's onBeforeInput filter runs. The filter
  // rejects URL chars (`/`, `:`, `.`) under `type='alphanumeric'`, so
  // a paste of `https://wordsparrow.io/join/A2B3C4` would otherwise be
  // dropped on the floor. Reading `event.clipboardData` gives us the
  // raw text; `extractLobbyCode` peels the `/join/<code>` segment when
  // present and falls back to the canonical normaliser otherwise.
  const handlePaste = (event: ClipboardEvent<HTMLInputElement>) => {
    if (readOnly || disabled) return;
    const text = event.clipboardData?.getData('text') ?? '';
    if (text.length === 0) return;
    event.preventDefault();
    onValueChange(extractLobbyCode(text));
  };

  return (
    <ArkPinInput.Root
      ref={ref}
      value={slots}
      type="alphanumeric"
      // `mask` is intentionally NOT forwarded — we mask via CSS to keep
      // the inputs' `type` as `text`, which prevents the browser's
      // password manager from offering to save the code on form submit.
      disabled={disabled}
      invalid={invalid}
      readOnly={readOnly}
      placeholder={placeholder}
      onValueChange={(detail) => {
        // Single-char input still flows through this onValueChange. The
        // joined string passes through the canonical normaliser one
        // last time so chars zag let through (e.g. lowercase letters
        // before our render uppercases them) land canonical in state.
        onValueChange(extractLobbyCode(detail.valueAsString));
      }}
      className={rootStyles}
    >
      <ArkPinInput.Label className={labelStyles}>{label}</ArkPinInput.Label>
      <ArkPinInput.Control className={controlStyles}>
        {Array.from({ length: LOBBY_CODE_LENGTH }, (_, index) => (
          <ArkPinInput.Input
            key={index}
            index={index}
            aria-label={label}
            // Browsers volunteer to autofill / save on form submit when
            // the input looks like a credential. `autocomplete="off"`
            // + the password-manager opt-out attributes block 1Password
            // and LastPass; the type stays `text` (see top of file) so
            // Chrome / Safari don't fire their own save prompts either.
            autoComplete="off"
            data-1p-ignore="true"
            data-lpignore="true"
            data-mask={mask ? 'true' : 'false'}
            onPaste={handlePaste}
            style={mask ? maskedInputStyle : undefined}
            className={slotStyles}
          />
        ))}
      </ArkPinInput.Control>
      {invalid && errorText != null ? (
        <p className={errorStyles} role="alert">{errorText}</p>
      ) : null}
      <ArkPinInput.HiddenInput />
    </ArkPinInput.Root>
  );
});
