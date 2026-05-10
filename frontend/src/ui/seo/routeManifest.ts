// Single source of truth for SEO-relevant routes. Imported by:
//   - each indexable route file (for its head() data)
//   - frontend/scripts/generate-sitemap.ts (for sitemap.xml)
//   - frontend/scripts/prerender.ts (for the prerender pass)
//   - frontend/tests/seo-*.test.ts (for assertions)
//
// Adding an indexable route is a one-touch change here.

export const SITE_BASE_URL = 'https://wordsparrow.io';

export interface IndexableRoute {
  readonly path: string;
  readonly title: string;
  readonly description: string;
}

export const INDEXABLE_ROUTES: ReadonlyArray<IndexableRoute> = [
  {
    path: '/',
    title: 'WordSparrow — mots fléchés français en ligne',
    description:
      'Jouez aux mots fléchés en français, en solo ou en multijoueur. Gratuit, sans inscription.',
  },
  {
    path: '/grille',
    title: 'Grille du jour — WordSparrow',
    description: 'Résolvez la grille de mots fléchés du jour, en français.',
  },
  {
    path: '/aide',
    title: 'Aide — WordSparrow',
    description:
      'Comment jouer aux mots fléchés sur WordSparrow : règles, astuces, raccourcis.',
  },
  {
    path: '/mentions-legales',
    title: 'Mentions légales — WordSparrow',
    description:
      'Mentions légales et informations éditoriales de WordSparrow.',
  },
  {
    path: '/confidentialite',
    title: 'Confidentialité — WordSparrow',
    description: 'Politique de confidentialité de WordSparrow.',
  },
];

// Routes that exist but must NOT be indexed. They get a `noindex,follow`
// meta + Disallow in robots.txt. Path is the TanStack route pattern, not
// a concrete URL — `$lobbyId` and `$code` are TanStack params.
export const EXCLUDED_ROUTES: ReadonlyArray<string> = [
  '/privacy',
  '/lobby/$lobbyId',
  '/join/$code',
];

export const DEFAULT_OG_IMAGE = `${SITE_BASE_URL}/og-default.png`;
