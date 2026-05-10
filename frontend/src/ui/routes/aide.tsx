// Aide (help) page — referenced from `AppHeader.tsx` since the lobby
// landed; the route file itself is new with the onboarding tour.
//
// Eager half: keeps the route definition, `head()` (so prerender can
// surface per-route metadata without waiting for the lazy chunk), and
// the `HELP_SECTIONS` data used by FAQPage JSON-LD. The component +
// Ark UI Accordion + Panda CSS layout chrome live in `./aide.lazy`
// and are loaded on demand via `Route.lazy()`.

import { createRoute } from '@tanstack/react-router';
import {
  breadcrumbJsonLd,
  buildHead,
  faqPageJsonLd,
  INDEXABLE_ROUTES,
  SITE_BASE_URL,
} from '@/ui/seo';
import { Route as RootRoute } from './__root';

export interface HelpSection {
  readonly value: string;
  readonly label: string;
  readonly content: React.ReactNode;
  // Plain-text paraphrase of `content` used only by the FAQPage JSON-LD
  // emitted in `head()`. Kept as a sibling field rather than rendered
  // from JSX so the SEO output never depends on React-to-string —
  // crawlers see clean prose, the visible UI keeps its `<kbd>` styling.
  readonly answerText: string;
}

export const HELP_SECTIONS: ReadonlyArray<HelpSection> = [
  {
    value: 'comment-jouer',
    label: 'Comment jouer',
    content: (
      <>
        Cliquez sur une case puis tapez une lettre. Les flèches du clavier vous
        déplacent dans la grille en évitant les cases d&apos;indices. Au
        carrefour de deux mots, appuyez sur <kbd>Espace</kbd> pour basculer
        entre les deux directions.
      </>
    ),
    answerText:
      "Cliquez sur une case puis tapez une lettre. Les flèches du clavier vous déplacent dans la grille en évitant les cases d'indices. Au carrefour de deux mots, appuyez sur la barre Espace pour basculer entre les deux directions.",
  },
  {
    value: 'raccourcis-clavier',
    label: 'Raccourcis clavier',
    content: (
      <>
        Utilisez les flèches pour vous déplacer, <kbd>Espace</kbd> pour
        changer de direction à un carrefour, <kbd>Retour</kbd> pour effacer
        une lettre, et <kbd>Tab</kbd> pour passer au mot suivant.
      </>
    ),
    answerText:
      'Utilisez les flèches pour vous déplacer, Espace pour changer de direction à un carrefour, Retour pour effacer une lettre, et Tab pour passer au mot suivant.',
  },
  {
    value: 'validation-indices',
    label: 'Validation et indices',
    content: (
      <>
        Chaque mot est validé automatiquement quand toutes ses lettres sont
        correctes — la case se verrouille. Si vous bloquez, demandez un
        indice via le bouton dédié dans le bandeau (nombre limité par
        grille).
      </>
    ),
    answerText:
      'Chaque mot est validé automatiquement quand toutes ses lettres sont correctes : la case se verrouille. Si vous bloquez, demandez un indice via le bouton dédié dans le bandeau (nombre limité par grille).',
  },
  {
    value: 'multijoueur',
    label: 'Multijoueur',
    content: (
      <>
        Créez une partie multijoueur depuis la page d&apos;accueil et
        partagez le lien : tous les participants jouent la même grille en
        temps réel.
      </>
    ),
    answerText:
      "Créez une partie multijoueur depuis la page d'accueil et partagez le lien : tous les participants jouent la même grille en temps réel.",
  },
  {
    value: 'nous-contacter',
    label: 'Nous contacter',
    content: (
      <>Pour toute question, écrivez à contact@wordsparrow.io.</>
    ),
    answerText:
      'Pour toute question, écrivez à contact@wordsparrow.io.',
  },
];

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/aide',
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/aide')!;
    const base = buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/aide`,
      ogImage: `${SITE_BASE_URL}${r.ogImagePath}`,
    });
    return {
      ...base,
      scripts: [
        {
          type: 'application/ld+json',
          children: faqPageJsonLd(
            HELP_SECTIONS.map((s) => ({ name: s.label, answer: s.answerText })),
          ),
        },
        {
          type: 'application/ld+json',
          children: breadcrumbJsonLd([
            { name: 'Accueil', item: `${SITE_BASE_URL}/` },
            { name: r.title, item: `${SITE_BASE_URL}/aide` },
          ]),
        },
      ],
    };
  },
}).lazy(() => import('./aide.lazy').then((m) => m.Route));
