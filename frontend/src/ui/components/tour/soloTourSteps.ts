import { Tour } from '@ark-ui/react/tour';

// 4-step solo onboarding tour. Steps are pure data — `target()` resolvers
// query the live DOM at activation time so the tour stays decoupled
// from the puzzle's rendered layout (no need to bake "tutorial cell"
// metadata into the domain `Puzzle` type).
//
// Step 3 reuses step 2's target on purpose: the spotlight points at
// the same definition cell while the description shifts focus to the
// arrow semantics. Per-arrow targeting can come as a follow-up if UX
// research asks for it.

const TOOLBAR_SELECTOR = '[role="toolbar"][aria-label="Outils de la grille"]';
const DEFINITION_CELL_SELECTOR = '[data-cell-kind="definition"]';

const queryFirst = (selector: string): HTMLElement | null =>
  document.querySelector<HTMLElement>(selector);

export const SOLO_TOUR_STEPS: Tour.StepDetails[] = [
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
    actions: [
      { label: 'Précédent', action: 'prev' },
      { label: 'Suivant', action: 'next' },
    ],
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
    actions: [
      { label: 'Précédent', action: 'prev' },
      { label: 'Suivant', action: 'next' },
    ],
  },
  {
    id: 'banner',
    type: 'tooltip',
    title: 'Bandeau et validation',
    description:
      'Le chronomètre, la grille du jour et les actions sont en haut. Chaque mot est validé automatiquement quand vous le complétez.',
    target: () => queryFirst(TOOLBAR_SELECTOR),
    placement: 'bottom',
    arrow: true,
    backdrop: true,
    actions: [
      { label: 'Précédent', action: 'prev' },
      { label: 'Terminer', action: 'dismiss' },
    ],
  },
];
