import { Tour, useTour } from '@ark-ui/react/tour';
import { useEffect, useMemo, useRef } from 'react';
import type { TourSeenStore } from '@/application/tour/TourSeenStore';
import { buildSoloTourSteps } from './soloTourSteps';

// `md` breakpoint mirror of `panda.config.ts` — defaults to Panda's
// `md = 768px`. Re-evaluated once at hook mount; we don't subscribe to
// resize events because the steps array is set on the zag machine at
// mount time and zag doesn't support steps swapping mid-tour.
const DESKTOP_MIN_PX = 768;

function detectIsDesktop(): boolean {
  if (typeof window === 'undefined') return true;
  if (typeof window.matchMedia !== 'function') {
    return window.innerWidth >= DESKTOP_MIN_PX;
  }
  return window.matchMedia(`(min-width: ${DESKTOP_MIN_PX}px)`).matches;
}

export interface UseSoloTourOptions {
  readonly tourSeenStore: TourSeenStore;
  // True when the route's search params include `?tour=1`. Auto-opens the
  // tour even if the user has already seen it (the Aide page's "Lancer
  // le tour" button navigates here with that param).
  readonly forcedOpen: boolean;
  // Called on the first status change to `dismissed | completed | skipped`
  // when `forcedOpen` was true, so the hosting route can strip `?tour=1`
  // from the URL via TanStack Router.
  readonly onForcedOpenConsumed: () => void;
}

// Wraps Ark UI's `useTour` with the bliss-specific:
//   - 4 solo steps (imported from `soloTourSteps.ts`)
//   - French translations
//   - localStorage-backed "seen" flag persisted on dismiss/complete/skip
//   - auto-open on first visit (or via `?tour=1`)
//   - `closeOnInteractOutside: false` so a stray click doesn't dismiss
//     mid-step (skip is always reachable via the explicit button).
//
// StrictMode note: in dev React simulates mount → unmount → mount, and
// zag's `useMachine` creates a fresh state machine on each mount. We
// intentionally avoid a persistent "already-started" ref guard — that
// would suppress the second mount's start() and leave the production
// browser stuck at `data-state="closed"` even though the jsdom test
// suite (no StrictMode) was green. Instead we read the live machine's
// `tour.open` and the persisted seen flag on every effect run; both
// keep the auto-open contract correct without leaking state across
// machine instances.
export function useSoloTour({
  tourSeenStore,
  forcedOpen,
  onForcedOpenConsumed,
}: UseSoloTourOptions) {
  const onForcedOpenConsumedRef = useRef(onForcedOpenConsumed);
  onForcedOpenConsumedRef.current = onForcedOpenConsumed;
  const forcedOpenRef = useRef(forcedOpen);
  forcedOpenRef.current = forcedOpen;

  // Synchronous "user dismissed during this mount" flag. Set inside
  // `onStatusChange` BEFORE the state machine completes its transition
  // and BEFORE React schedules the post-dismiss render — refs mutate
  // synchronously, so by the time the auto-open effect re-runs after
  // dismiss, this is `true` and short-circuits the gate.
  //
  // Why this exists: a previous version gated re-open on
  // `!tourSeenStore.get()` (i.e. localStorage). Real Chrome on macOS
  // reproducibly hit a race where, on dismissing a tooltip step
  // (Terminer / Passer / ESC on step ≥ 2), the auto-open effect ran
  // *before* the `set(true)` write was visible to `get()`, re-firing
  // `tour.start()` and silently re-opening the welcome step. Symptom
  // was a "stuck dim backdrop" the user could only clear with a
  // second ESC. The localStorage write is still kept below for
  // cross-reload persistence, but this in-memory ref is what
  // guarantees correctness within a single mount.
  const dismissedThisSessionRef = useRef(false);

  // Steps are derived from viewport once at mount. The zoom step is
  // desktop-only because GridZoomControls is hidden on mobile.
  const steps = useMemo(
    () => buildSoloTourSteps({ isDesktop: detectIsDesktop() }),
    [],
  );

  const tour = useTour({
    steps,
    closeOnInteractOutside: false,
    closeOnEscape: true,
    keyboardNavigation: true,
    preventInteraction: true,
    spotlightOffset: { x: 6, y: 6 },
    spotlightRadius: 10,
    translations: {
      skip: 'Passer le tour',
      nextStep: 'Suivant',
      prevStep: 'Précédent',
      close: 'Fermer',
      // zag passes `current` as a 0-indexed step number; the user-facing
      // copy reads naturally as 1-indexed.
      progressText: ({ current, total }) => `Étape ${current + 1} sur ${total}`,
    },
    onStatusChange: (details: Tour.StatusChangeDetails) => {
      if (
        details.status === 'completed' ||
        details.status === 'dismissed' ||
        details.status === 'skipped'
      ) {
        // Set the synchronous guard FIRST so the auto-open effect
        // can never re-fire `start()` after a dismiss in this mount.
        dismissedThisSessionRef.current = true;
        tourSeenStore.set(true);
        if (forcedOpenRef.current) {
          onForcedOpenConsumedRef.current();
        }
      }
    },
  });

  // Auto-open: open whenever the machine is currently closed AND the
  // user neither dismissed this session nor previously saw the tour
  // (`?tour=1` overrides the seen flag — the Aide launcher uses it).
  useEffect(() => {
    if (tour.open) return;
    if (dismissedThisSessionRef.current) return;
    if (forcedOpen || !tourSeenStore.get()) {
      tour.start();
    }
  }, [forcedOpen, tour, tourSeenStore]);

  return tour;
}
