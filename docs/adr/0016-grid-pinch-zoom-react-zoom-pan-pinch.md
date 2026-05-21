# ADR-0016: Grid-Scoped Pinch-Zoom via react-zoom-pan-pinch

## Status

Accepted

## Context

The crossword grid needs to support mobile pinch-zoom so small-screen players
can enlarge cells for accurate letter entry.

PRs **#112** and **#113** attempted to keep the clue panel visible during
zoom by tracking `window.visualViewport` from a `requestAnimationFrame` loop
and applying a compensating `transform` to the panel element. The result was
functional but visually jittery on Android: a JS update inside `rAF` lands at
most one frame after the browser's compositor has already begun painting the
visual-viewport pan, so the panel always lagged behind by a frame.

The root cause is that native browser pinch-zoom makes the *visual viewport*
diverge from the *layout viewport*. Any element whose position is determined
by the layout viewport — including `position: sticky` — appears to drift
off-screen when the user pans after zooming. FAB-style `position: fixed`
elements do not have this problem because the browser composites them natively
against the visual viewport without a JS round-trip; `position: sticky`
elements have no equivalent native compositing path.

The conclusion: the only way to eliminate jitter without reimplementing the
browser's compositor in JS is to prevent the visual viewport from diverging
from the layout viewport in the first place. That means the *page* must never
zoom natively; zoom must be implemented inside a contained element.

## Decision

### 1. Grid-only zoom surface

The `<div role="grid">` is wrapped in `react-zoom-pan-pinch`'s
`TransformWrapper` + `TransformComponent`. This library handles all
pinch/pan touch events on the grid element, applies `transform: scale()` to
the grid's inner plane, and does not touch the rest of the DOM.

The library is `react-zoom-pan-pinch` **4.0.3** (MIT, zero runtime
dependencies, ~362 npm dependents, last published 2026-04-07). It was chosen
over hand-rolling a touch-gesture handler because: (a) it correctly handles
the multi-touch pointer-events/touch-events dual path, (b) it ships bounds
clamping and configurable min/max scale out of the box, and (c) it is
actively maintained.

Bundle cost: **+27 KB gzipped**. Accepted.

### 2. Native pinch suppression scoped to the grid element

The library sets `touch-action: none` on its `TransformWrapper` element,
preventing the browser from handling pinch events that originate inside the
grid. This is the only place where native pinch is suppressed.

**No page-level zoom suppression is applied.** Specifically:

- `body { touch-action: pan-x pan-y }` is **not** set.
- `maximum-scale=1, user-scalable=no` is **not** added to the viewport meta.

These omissions are intentional and load-bearing for WCAG compliance (see §3).

### 3. WCAG 1.4.4 compliance

WCAG 2.1 SC 1.4.4 (Resize Text, Level AA) requires that text can be resized
to 200% without the use of assistive technology and without loss of content or
functionality.

On mobile, pinch-to-zoom **is** browser zoom. There is no Ctrl/+ equivalent.
OS-level magnification features (iOS Zoom, Android Magnification) are
classified as assistive technology by WCAG and therefore do not satisfy SC
1.4.4's "without assistive technology" criterion.

Suppressing native browser zoom at the page level — via `touch-action` on
`body` or `user-scalable=no` in the viewport meta — would prevent mobile users
from enlarging the clue panel and page chrome, which is a potential WCAG AA
failure. The clue panel is the primary text surface a player reads while
filling cells; low-vision mobile users must be able to enlarge it.

By restricting suppression to the grid element, native browser zoom is
preserved for all other page content. Users can pinch the clue panel or any
other page area and the browser responds normally.

### 4. Library configuration

| Prop | Value | Why |
|---|---|---|
| `minScale` | 1 | Never zoom out below 100% — the grid already fits its container |
| `maxScale` | 4 | A single cell on a 5-col puzzle (~96 px) reaches ~384 px, enough for thumb-distance reading |
| `centerOnInit` | true | Avoids a small offset from the library's bounds-padding logic on initial paint |
| `wheel.step` | 0.05 | Mouse-wheel zooms the grid at 5 % per notch — smooth and close to trackpad pinch feel. Plain-wheel chosen over modifier-gated zoom; see the 2026-05-05 amendment below. |
| `doubleClick.disabled` | true | Prevents double-tap-to-zoom from conflicting with cell focus + cursor behavior |
| `panning.velocityDisabled` | true | No momentum — disorienting in a fixed-content puzzle |
| `panning.allowLeftClickPan` | false | Prevents desktop left-drag pan, which would conflict with future drag-to-select |

> **Update (2026-05-05):** The original ADR set `wheel.disabled: true` so
> desktop mouse-wheel scrolled the page rather than zooming the grid. PR #191
> (grid pan/zoom UX) deliberately reverses this: `wheel.step: 0.05` enables
> scroll-wheel zoom. Rationale: smooth wheel zoom is a natural complement to
> pinch zoom; 0.05 (5 % per notch) matches trackpad pinch feel where the
> library default of 0.2 is too coarse. The alternative — modifier-gated zoom
> (`activationKeys: ['Control', 'Meta']`, plain wheel = page scroll) — was
> considered and rejected: users who hover over the grid while scrolling will
> zoom it regardless; requiring a modifier key just makes the behaviour
> harder to discover without eliminating the conflict. Map and diagram widgets
> universally use this trade-off and users expect it. Accepted.

## Alternatives considered

**rAF/visualViewport tracking (PRs #112, #113).** Keeps native page zoom but
compensates for panel drift in JavaScript. Eliminated: produces one-frame
Android jitter because the JS update lands after the compositor has already
started painting the pan. Not a platform-native solution.

**Page-level pinch suppression (`touch-action: pan-x pan-y` on `body` +
`maximum-scale=1, user-scalable=no`).** Simpler to implement — the library
just works and nothing else on the page zooms. Eliminated: WCAG AA failure on
mobile (blocks native browser zoom of the clue panel; OS magnification is
assistive technology and does not substitute).

**Accepting WCAG deviation with a compensating measure.** The alternative
path allowed by the §6a reviewer: document the deviation in this ADR and
ensure clue text scales with browser font settings. Rejected in favour of full
compliance — the preferred fix requires only a few lines of CSS/HTML removal,
and the MANIFESTO.md states "Accessibility is a requirement, not a
follow-up ticket."

## Consequences

### Easier

- `position: sticky` on the clue panel works without any JavaScript. The
  layout viewport never diverges from the visual viewport at the page level,
  so there is nothing to compensate for.
- Zoom UX on the grid is correct by construction: bounds clamping, min/max
  scale, and gesture disambiguation are handled by the library.
- WCAG 1.4.4 is satisfied: native browser zoom is available for all non-grid
  page content.

### Harder

- Users cannot pinch the clue panel or page chrome to zoom only those
  elements in isolation; native browser pinch on non-grid areas zooms the
  whole page (which may shift the layout). This is the accepted trade-off for
  removing page-level suppression.
- Adding `+27 KB gzipped` to the initial bundle. Tracked against the 200 KB
  gzipped budget in ADR-0002.

### Different

- PRs **#112** and **#113** are obsolete. The problem they solved no longer
  exists once native page zoom is preserved (the panel never drifts) and grid
  zoom is handled by the library.
- The grid's `TransformWrapper` is the only element in the app that suppresses
  native touch handling. Any future touch-sensitive surface should follow the
  same pattern: scope suppression to the element, not the page.

## Amendment 2026-05-22 — keyboard-mounted exception

When the custom mobile keyboard (`MobileKeyboard`) owns input on touch-primary
devices, native browser pinch on the page chrome that sits around the grid
fights the grid's own `react-zoom-pan-pinch` zoom: a two-finger gesture that
lands partly on the grid and partly on the keyboard panel zooms both the
grid (via the library) and the page (via the browser) at once, producing a
confusing double-zoom effect. PR #586 attempted the broadest fix —
`maximum-scale=1, user-scalable=no` on the viewport meta — and was rejected
under §6a per the §3 "WCAG 1.4.4 compliance" decision above and ADR-0050
(a11y baseline). The amendment carves out a narrower exception.

### Scope of the exception

Suppression is applied via CSS `touch-action: none` on individual page-chrome
elements that surround the grid while the custom keyboard is mounted:

- the `MobileKeyboard` panel root,
- the `PuzzleToolbar` root (`role="toolbar"`).

Suppression is **not** applied to:

- the viewport meta (`maximum-scale` / `user-scalable` stay as ADR-0016 §2
  forbids),
- the document `body` or `<html>`,
- non-grid routes (the keyboard only mounts on grid routes via
  `useTouchPrimary`),
- desktop / pointer-primary devices (the keyboard is hidden on those —
  ADR-0016 §3 applies unchanged).

The page background, page header (`AppHeader`), and any area outside the
toolbar + keyboard remain natively pinch-zoomable. The grid keeps its
library-managed pinch (ADR-0016 §2).

### Why CSS `touch-action`, not viewport meta

`touch-action: none` on an element disables native gestures **only over that
element's box**. Browser zoom level (text-size and full-page zoom settings)
is unaffected — the property gates touch gesture handling, not the page-zoom
mechanism. Viewport-meta `user-scalable=no` disables zoom for the entire
document across all routes, regardless of which element the user pinches.
The CSS approach is the minimum surgical scope.

### Accessibility mitigations that remain available

The hard guarantee in ADR-0016 §3 — that the clue panel and page chrome
*outside* the suppressed elements stay natively pinch-zoomable — is
preserved. For the suppressed elements themselves, low-vision users retain:

- iOS Display & Text Size, iOS Zoom (Settings > Accessibility > Zoom),
- Android Magnification (Settings > Accessibility > Magnification),
- Browser zoom level (text-size or full-page; both are independent of
  viewport-meta scaling and of element `touch-action`).

Note: as §3 establishes, iOS Zoom and Android Magnification are assistive
technology under WCAG and do not satisfy SC 1.4.4's "without assistive
technology" criterion. Browser zoom level does remain available and
unaffected by `touch-action`. The toolbar's text is a short, fixed
metadata label (`n°142 · facile`) plus icon-buttons sized to the 44 × 44
WCAG 2.5.5 touch target; the keyboard's glyphs are single characters at
the AZERTY layout's natural size.

### Decision

The exception is accepted as a real deviation from ADR-0016 §3's
"no page-level zoom suppression" stance, scoped via CSS to two elements
that exist only in the keyboard-mounted, grid-route, touch-primary
configuration. Re-evaluate the deviation if either (a) `PuzzleToolbar` or
`MobileKeyboard` start carrying long-form text content, or (b) the
suppressed elements ever render on non-grid routes.
