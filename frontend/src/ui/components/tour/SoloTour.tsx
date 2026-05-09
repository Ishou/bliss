import { Portal } from '@ark-ui/react/portal';
import { Tour, type UseTourReturn } from '@ark-ui/react/tour';
import { Button } from '@/ui/components/primitives';
import { PaginationDots } from './PaginationDots';
import {
  actionTriggerStyles,
  arrowStyles,
  arrowTipStyles,
  backdropStyles,
  contentStyles,
  descriptionStyles,
  footerRightGroupStyles,
  footerStyles,
  positionerStyles,
  progressTextStyles,
  spotlightStyles,
  titleStyles,
} from './soloTour.styles';

// Visual layer for the solo onboarding tour. Receives the live `tour`
// instance from `useSoloTour()` (the hook lives one level up in the
// route so it can read the route's search params and the router
// context).
//
// Renders `<Tour.Backdrop>`, `<Tour.Spotlight>`, and `<Tour.Positioner>`
// which Ark UI auto-positions via Floating UI. The "Passer le tour"
// button is rendered explicitly with `action: 'dismiss'` so it's always
// available regardless of the current step's `actions` array.

export interface SoloTourProps {
  readonly tour: UseTourReturn;
}

export function SoloTour({ tour }: SoloTourProps) {
  return (
    // `<Tour.Root>` is context-only — Backdrop / Spotlight /
    // Positioner render in place. The `<Portal>` re-parents them to
    // `document.body` so the tour never disrupts the route's flex
    // layout (without it, the closed-state positioner stayed in flow
    // as a 0-height flex sibling and the parent's `gap: 18px` shrank
    // the grid panel by 18 px — see `routes/index.tsx contentStyles`).
    //
    // We let Ark/zag drive the open/closed lifecycle via the `hidden`
    // HTML attribute and our `[data-state="closed"]:d_none!` Panda
    // override; do NOT gate this subtree on `tour.open` in JSX. A
    // previous attempt at `{tour.open && (<Portal>…)}` short-circuited
    // zag's machine activity cleanups (focus-trap, dismissable-branch,
    // interact-outside) by removing their target nodes from the DOM
    // before the cleanup ran, leaving stale state that surfaced as a
    // backdrop with `data-state="open"` after dismiss. Trust the
    // library lifecycle.
    <Tour.Root tour={tour}>
      <Portal>
        <Tour.Backdrop className={backdropStyles} />
        <Tour.Spotlight className={spotlightStyles} />
        <Tour.Positioner className={positionerStyles}>
          <Tour.Content className={contentStyles} data-testid="solo-tour-content">
          <Tour.ProgressText className={progressTextStyles} />
          <Tour.Title className={titleStyles} />
          <Tour.Description className={descriptionStyles} />
          <Tour.Arrow className={arrowStyles}>
            <Tour.ArrowTip className={arrowTipStyles} />
          </Tour.Arrow>
          <div className={footerStyles}>
            {/*
              `asChild` lets Ark's polymorphic factory merge the
              Tour.ActionTrigger props (onClick, role, type, focus
              behavior) onto the Bliss Button primitive so the
              tour's footer buttons match the rest of the brand
              (sage CTA, ghost/outline secondary). Without asChild
              the trigger is an unstyled <button> from
              @ark-ui/react's factory.
            */}
            <Tour.ActionTrigger
              action={{ label: 'Passer le tour', action: 'dismiss' }}
              aria-label="Passer le tour"
              asChild
            >
              <Button variant="ghost" className={actionTriggerStyles}>
                Passer le tour
              </Button>
            </Tour.ActionTrigger>
            <PaginationDots current={tour.stepIndex} total={tour.totalSteps} />
            <div className={footerRightGroupStyles}>
              <Tour.Actions>
                {(actions) =>
                  actions.map((action) => {
                    // Override aria-label so the accessible name reads
                    // the per-step label (e.g., "Terminer") instead of
                    // Ark's translation default for the action type
                    // (which is "Fermer" for dismiss, "Suivant" for
                    // next, "Précédent" for prev — fine for next/prev
                    // but wrong for the final "Terminer").
                    const variant: 'primary' | 'secondary' =
                      action.action === 'prev' ? 'secondary' : 'primary';
                    return (
                      <Tour.ActionTrigger
                        key={action.label}
                        action={action}
                        aria-label={action.label}
                        asChild
                      >
                        <Button variant={variant} className={actionTriggerStyles}>
                          {action.label}
                        </Button>
                      </Tour.ActionTrigger>
                    );
                  })
                }
              </Tour.Actions>
            </div>
          </div>
          </Tour.Content>
        </Tour.Positioner>
      </Portal>
    </Tour.Root>
  );
}
