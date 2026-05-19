import { Tour } from '@ark-ui/react/tour';

// 6-step solo onboarding tour (5 on mobile — the zoom step is desktop-
// only because GridZoomControls is hidden via `display: { base: 'none',
// md: 'flex' }` and pinch-zoom on mobile is intuitive enough that we
// don't dedicate a step to teach it).
//
// Steps query the live DOM at activation time so the tour stays
// decoupled from the puzzle's rendered layout (no need to bake
// "tutorial cell" metadata into the domain `Puzzle` type).
//
// Step 3 reuses step 2's target on purpose: the spotlight points at
// the same definition cell while the description shifts focus to the
// arrow semantics. Per-arrow targeting can come as a follow-up if UX
// research asks for it.

const TOOLBAR_SELECTOR = '[role="toolbar"][aria-label="Outils de la grille"]';
const DEFINITION_CELL_SELECTOR = '[data-cell-kind="definition"]';
const HINT_BUTTON_SELECTOR = '[aria-label^="Indice ("]';
const ZOOM_CONTROLS_SELECTOR = '[role="group"][aria-label="Zoom controls"]';
const PROGRESS_BAR_SELECTOR = '[data-testid="puzzle-progress"]';

const queryFirst = (selector: string): HTMLElement | null =>
  document.querySelector<HTMLElement>(selector);

export interface BuildStepsOptions {
  /** True when the viewport is desktop (≥ 768 px). Used to skip the
   * zoom step on mobile, where `GridZoomControls` is hidden. */
  readonly isDesktop: boolean;
}

const NEXT_PREV_ACTIONS: Tour.StepAction[] = [
  { label: 'Précédent', action: 'prev' },
  { label: 'Suivant', action: 'next' },
];

export function buildSoloTourSteps({
  isDesktop,
}: BuildStepsOptions): Tour.StepDetails[] {
  const steps: Tour.StepDetails[] = [
    {
      id: 'welcome',
      type: 'dialog',
      title: 'Bienvenue',
      description:
        'Quelques secondes pour découvrir comment jouer aux mots fléchés. Vous pouvez passer le tour à tout moment.',
      placement: 'center',
      backdrop: true,
      actions: [{ label: 'Suivant', action: 'next' }],
    },
    {
      id: 'clue-cells',
      type: 'tooltip',
      title: "Cases d'indices",
      description:
        'Les cases roses contiennent les définitions du mot à trouver.',
      target: () => queryFirst(DEFINITION_CELL_SELECTOR),
      placement: 'bottom',
      arrow: true,
      backdrop: true,
      actions: NEXT_PREV_ACTIONS,
    },
    {
      id: 'arrows',
      type: 'tooltip',
      title: 'Suivez les flèches',
      description:
        'Une petite flèche indique la direction et la première case de la réponse — → pour les mots horizontaux, ↓ pour les verticaux.',
      target: () => queryFirst(DEFINITION_CELL_SELECTOR),
      placement: 'bottom',
      arrow: true,
      backdrop: true,
      actions: NEXT_PREV_ACTIONS,
    },
    {
      id: 'hints',
      type: 'tooltip',
      title: 'Coup de pouce',
      description:
        "Bloqué·e sur un mot ? Le bouton d'indice révèle la première lettre du mot actuel — un nombre limité par grille.",
      target: () => queryFirst(HINT_BUTTON_SELECTOR),
      placement: 'bottom',
      arrow: true,
      backdrop: true,
      actions: NEXT_PREV_ACTIONS,
    },
  ];

  if (isDesktop) {
    steps.push({
      id: 'zoom',
      type: 'tooltip',
      title: 'Ajuster le zoom',
      description:
        'Trois boutons sous la grille pour zoomer ou revenir à la vue de départ. La molette de la souris ajuste aussi le zoom — pratique pour confirmer une lettre serrée.',
      target: () => queryFirst(ZOOM_CONTROLS_SELECTOR),
      placement: 'top',
      arrow: true,
      backdrop: true,
      actions: NEXT_PREV_ACTIONS,
    });
  }

  steps.push({
    id: 'validation',
    type: 'tooltip',
    title: 'Progression et validation',
    description:
      'La barre de progression suit votre avancée. Chaque mot est validé automatiquement quand vous le complétez — pas besoin de confirmer.',
    target: () => queryFirst(PROGRESS_BAR_SELECTOR),
    placement: 'top',
    arrow: true,
    backdrop: true,
    actions: [
      { label: 'Précédent', action: 'prev' },
      { label: 'Terminer', action: 'dismiss' },
    ],
  });

  return steps;
}

// Selector exports kept for tests so they can sync targets with the
// rendered DOM without duplicating the strings.
export const TOUR_TARGET_SELECTORS = {
  toolbar: TOOLBAR_SELECTOR,
  definitionCell: DEFINITION_CELL_SELECTOR,
  hintButton: HINT_BUTTON_SELECTOR,
  zoomControls: ZOOM_CONTROLS_SELECTOR,
  progressBar: PROGRESS_BAR_SELECTOR,
} as const;
