import { Tour, useTour } from '@ark-ui/react/tour';
import { useEffect, useRef } from 'react';
import type { TourSeenStore } from '@/application/tour/TourSeenStore';
import { SOLO_TOUR_STEPS } from './soloTourSteps';

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

  const tour = useTour({
    steps: SOLO_TOUR_STEPS,
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
        tourSeenStore.set(true);
        if (forcedOpenRef.current) {
          onForcedOpenConsumedRef.current();
        }
      }
    },
  });

  // Auto-open: open whenever the machine is currently closed AND the
  // user either forced it open via `?tour=1` or hasn't seen the tour
  // yet (seen flag is read live, so a dismissal mid-session flips it
  // and prevents re-open).
  useEffect(() => {
    if (tour.open) return;
    if (forcedOpen || !tourSeenStore.get()) {
      tour.start();
    }
  }, [forcedOpen, tour, tourSeenStore]);

  return tour;
}
