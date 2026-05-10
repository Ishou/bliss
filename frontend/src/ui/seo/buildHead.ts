// Per-route head builder for indexable and excluded routes.
//
// Returns the TanStack Router `head()` return shape: a `{ meta, links }`
// object whose entries are passed verbatim to the document <head> via
// the <HeadContent /> component mounted in __root.tsx.
//
// One source of truth for the canonical URL, OG tags, Twitter card,
// and the optional `noindex` flag. Tested in seo-build-head.test.ts.

import { DEFAULT_OG_IMAGE } from './routeManifest';

export interface BuildHeadInput {
  readonly title: string;
  readonly description: string;
  readonly canonical: string;
  readonly noindex?: boolean;
}

export interface RouteHead {
  readonly meta: Array<Record<string, string>>;
  readonly links: Array<Record<string, string>>;
}

export function buildHead(input: BuildHeadInput): RouteHead {
  const { title, description, canonical, noindex = false } = input;

  const meta: Array<Record<string, string>> = [
    { title },
    { name: 'description', content: description },
    { property: 'og:title', content: title },
    { property: 'og:description', content: description },
    { property: 'og:url', content: canonical },
    { property: 'og:type', content: 'website' },
    { property: 'og:site_name', content: 'WordSparrow' },
    { property: 'og:locale', content: 'fr_FR' },
    { property: 'og:image', content: DEFAULT_OG_IMAGE },
    { name: 'twitter:card', content: 'summary_large_image' },
    { name: 'twitter:title', content: title },
    { name: 'twitter:description', content: description },
    { name: 'twitter:image', content: DEFAULT_OG_IMAGE },
  ];

  if (noindex) {
    meta.push({ name: 'robots', content: 'noindex,follow' });
  }

  return {
    meta,
    links: [{ rel: 'canonical', href: canonical }],
  };
}
