# ADR-0034: Accessibility baseline (WCAG 2.2 AA + screen-reader playability)

## Status

Accepted

## Context

> **ADR-0001 §7 deviation note:** This ADR is merged in the same PR as
> the implementation. The design direction (tooling choices, WCAG tag
> set, severity policy) was established in the `feat/a11y-foundation`
> design document prior to any implementation commit and reviewed as
> part of the branch planning phase. The ADR was authored after the
> implementation to capture decisions already made. This is a documented
> one-off; future a11y ADRs must be merged before their implementation
> PRs per ADR-0001 §7.

The manifesto requires WCAG AA accessibility as a non-negotiable
baseline ("Accessibility is a requirement (WCAG AA minimum), not a
follow-up ticket"). Prior to this ADR the codebase had:

- one e2e a11y test (`frontend/e2e/a11y.spec.ts`) covering the home
  route only, with strict zero-violation enforcement on WCAG 2.0 A/AA;
- no component-level a11y assertion;
- no severity policy — any violation, regardless of impact, failed CI;
- no documented target screen reader, target locale, or roadmap for
  the screen-reader-playable mots-fléchés grid.

The mots-fléchés grid is the hardest a11y surface in the product (2D
navigation, French clues that arrive on bent arrow cells, validation
events, multiplayer presence). A baseline that doesn't address SR
playability is compliance theatre.

## Decision

### 1. Audit tooling

- **`@axe-core/playwright`** runs against representative routes in the
  existing Playwright e2e harness. Reuses the existing browser; no new
  CI job. Routes scanned: grille (puzzle grid), not-found, multi
  waiting room. More routes (solo error state, end-game modal) added
  as follow-up PRs.
- **`vitest-axe`** matcher available to component tests via
  `frontend/src/test/a11y.ts`. Used as components get fixed in
  follow-up work.

Rejected: Lighthouse CI (overlaps axe), pa11y (axe-based, redundant),
`eslint-plugin-jsx-a11y` (false-positive heavy on ark-ui's render-prop
pattern).

### 2. WCAG tag set

`['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa']`

WCAG 2.2 AA is the highest stable tier; including 2.1 explicitly is
defensive against future axe-core defaults.

### 3. Severity policy

| `impact` | gate |
|---|---|
| `critical`, `serious` | **fail** the test |
| `moderate` | log to stdout, do not fail |
| `minor`, `null`, `undefined` | ignored |

Rationale: starting strict on `serious`+ avoids drift; `moderate` is
loose initially so the policy lands without bundling fixes (those
arrive in PR-A2). This ADR commits to **ratcheting `moderate` to fail
within 60 days** of this ADR's merge — reviewed at the next quarterly
manifesto check.

### 4. Disabled rules

None at the time of this ADR. If a rule must be disabled, the ADR is
amended with the rule id, the scope (CSS selector or component path),
and the justification.

### 5. Target screen reader and locale

- **Canonical SR**: VoiceOver on macOS (matches the development
  environment).
- **Primary locale**: French.
- iOS VoiceOver and NVDA/JAWS deferred — call out in test reports if
  found broken; not a CI gate.

### 6. Manual VoiceOver checklist

Run on macOS Safari + Chrome with VO active. Pass/fail per step:

1. Cold load on `/`: Tab reaches the skip link first; activating it
   lands focus on the grid main landmark.
2. Tab again enters the first non-validated cell; VO announces
   *« <n>ème lettre : vide »* (or the letter).
3. Arrow-Right moves to the next cell within the same word; VO
   announces only the cell, no clue.
4. Arrow-Down crosses into a new word; VO announces clue + direction +
   length + slot pattern, exactly once.
5. Type a wrong letter, trigger validation; assertive channel
   interrupts with *« erreur dans le mot ... »*.
6. Reveal a hint; polite channel announces *« lettre <X> révélée,
   position <n> »*.
7. Complete a word; polite channel announces *« mot validé : <WORD> »*.
8. Open end-game modal; focus moves inside; Esc closes; focus returns
   to opener.
9. Reload puzzle; focus stays on body; *« Nouvelle grille chargée »*
   announced.
10. Multi room: another browser joins → polite *« <name> a rejoint la
    partie »*. Disconnect → *« <name> s'est déconnectée »*. Other
    player completes a word → *« <name> a complété un mot »*.
11. axe e2e on the same routes returns zero `serious`/`critical`
    violations.

The PR that ships the screen-reader work (PR-B3 in the
`feat/a11y-foundation` epic) attaches the run output to its
description.

### 7. Deferred items

Tracked here so they don't fall off the radar:

- Settings UI for a11y preferences (verbose multiplayer toggle, slot-
  pattern mute, re-announce hotkey). Until shipped, VoiceOver's native
  *VO+Z* (repeat last phrase) is the documented re-announce path.
- iOS VoiceOver and NVDA/JAWS testing.
- Cognitive accessibility (simpler copy, dyslexia font).
- End-game modal route in axe scan.
- Solo error-state route in axe scan.

## Consequences

- Harder to merge SR-breaking changes — CI now gates on `serious`+
  WCAG 2.2 AA violations on three routes.
- The 60-day `moderate` ratchet creates a forcing function for fixing
  `moderate` findings, not letting them accumulate.
- A future settings PR unblocks user-controlled muting of
  multiplayer announcements.
- Any relaxation of this ADR (rule disables, severity downgrades, tag
  removals) requires an amendment PR.
