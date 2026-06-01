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
  // Path (root-relative, with leading `/`) of the per-route OpenGraph
  // image under `frontend/public/`. The full URL is built as
  // `${SITE_BASE_URL}${ogImagePath}` and used by `buildHead` for
  // `og:image` and `twitter:image`.
  readonly ogImagePath: string;
}

export const INDEXABLE_ROUTES: ReadonlyArray<IndexableRoute> = [
  {
    path: '/',
    title: 'WordSparrow — mots fléchés français en ligne',
    description:
      'Jouez aux mots fléchés en français, en solo ou en multijoueur. Gratuit, sans inscription.',
    ogImagePath: '/og-accueil.png',
  },
  {
    path: '/grille',
    title: 'Grille du jour — WordSparrow',
    description: 'Résolvez la grille de mots fléchés du jour, en français.',
    ogImagePath: '/og-grille.png',
  },
  {
    path: '/grilles',
    title: 'Anciennes grilles — WordSparrow',
    description:
      'Toutes les grilles passées de WordSparrow, avec votre progression.',
    // Reuses the Accueil OG image until a dedicated archive asset ships.
    ogImagePath: '/og-accueil.png',
  },
  {
    path: '/aide',
    title: 'Aide — WordSparrow',
    description:
      'Comment jouer aux mots fléchés sur WordSparrow : règles, astuces, raccourcis.',
    ogImagePath: '/og-aide.png',
  },
  {
    path: '/mentions-legales',
    title: 'Mentions légales — WordSparrow',
    description:
      'Mentions légales et informations éditoriales de WordSparrow.',
    ogImagePath: '/og-mentions-legales.png',
  },
  {
    path: '/confidentialite',
    title: 'Confidentialité — WordSparrow',
    description: 'Politique de confidentialité de WordSparrow.',
    ogImagePath: '/og-confidentialite.png',
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

export interface PrerenderRoute {
  readonly path: string;
  readonly title: string;
}

// Noindex routes still prerendered so Cloudflare Pages serves the route's own shell, not the home shell.
export const NOINDEX_PRERENDER_ROUTES: ReadonlyArray<PrerenderRoute> = [
  { path: '/contribuer', title: 'Campagne — WordSparrow' },
  { path: '/contribuer/pairs', title: 'Campagne par paires — WordSparrow' },
  { path: '/compte', title: 'Mon compte — WordSparrow' },
];

export const DEFAULT_OG_IMAGE = `${SITE_BASE_URL}/og-default.png`;
