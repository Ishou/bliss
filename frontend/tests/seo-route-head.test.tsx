import { describe, it, expect } from 'vitest';
import { INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';
import { Route as AccueilRoute } from '@/ui/routes/accueil';
import { Route as GrilleRoute } from '@/ui/routes/grille';
import { Route as AideRoute } from '@/ui/routes/aide';
import { Route as MentionsLegalesRoute } from '@/ui/routes/mentions-legales';
import { Route as ConfidentialiteRoute } from '@/ui/routes/confidentialite';
import { Route as PrivacyRoute } from '@/ui/routes/privacy';
import { Route as LobbyRoute } from '@/ui/routes/lobby.$lobbyId';
import { Route as JoinRoute } from '@/ui/routes/join.$code';

const ROUTE_BY_PATH: Record<string, { options: { head?: () => unknown } }> = {
  '/': AccueilRoute as unknown as { options: { head?: () => unknown } },
  '/grille': GrilleRoute as unknown as { options: { head?: () => unknown } },
  '/aide': AideRoute as unknown as { options: { head?: () => unknown } },
  '/mentions-legales': MentionsLegalesRoute as unknown as { options: { head?: () => unknown } },
  '/confidentialite': ConfidentialiteRoute as unknown as { options: { head?: () => unknown } },
};

interface Head {
  meta: Array<Record<string, string>>;
  links: Array<Record<string, string>>;
}

describe.each(INDEXABLE_ROUTES)('indexable route $path', (route) => {
  const tanRoute = ROUTE_BY_PATH[route.path];

  it('exposes a head() function', () => {
    expect(tanRoute?.options.head).toBeTypeOf('function');
  });

  it('emits the manifest title', () => {
    const head = tanRoute!.options.head!() as Head;
    expect(head.meta).toContainEqual({ title: route.title });
  });

  it('emits the manifest description', () => {
    const head = tanRoute!.options.head!() as Head;
    expect(head.meta).toContainEqual({
      name: 'description',
      content: route.description,
    });
  });

  it(`emits canonical = ${SITE_BASE_URL}${route.path}`, () => {
    const head = tanRoute!.options.head!() as Head;
    expect(head.links).toContainEqual({
      rel: 'canonical',
      href: `${SITE_BASE_URL}${route.path}`,
    });
  });

  it('does NOT emit a noindex robots meta', () => {
    const head = tanRoute!.options.head!() as Head;
    const robots = head.meta.find((m) => m['name'] === 'robots');
    expect(robots).toBeUndefined();
  });
});

describe('excluded routes carry noindex', () => {
  const cases: Array<[string, { options: { head?: () => unknown } }]> = [
    ['/privacy', PrivacyRoute as unknown as { options: { head?: () => unknown } }],
    ['/lobby/$lobbyId', LobbyRoute as unknown as { options: { head?: () => unknown } }],
    ['/join/$code', JoinRoute as unknown as { options: { head?: () => unknown } }],
  ];

  it.each(cases)('%s emits noindex,follow', (_path, tanRoute) => {
    const head = tanRoute.options.head!() as Head;
    expect(head.meta).toContainEqual({ name: 'robots', content: 'noindex,follow' });
  });
});
