// Pure JSON-LD helpers for the structured-data sub-project (ADR-0035).
//
// Each helper takes a small data record and returns a JSON string ready
// to drop into the `children` of a TanStack Router `head().scripts[]`
// entry. They never touch the DOM, never read globals, and produce
// byte-stable output for the same input — so the post-build assertions
// in `seo-prerender-output.test.ts` can grep for stable substrings like
// `"@type":"FAQPage"`.
//
// Tested in `frontend/tests/seo-json-ld.test.ts`.

export interface FaqItem {
  readonly name: string;
  readonly answer: string;
}

export function faqPageJsonLd(items: ReadonlyArray<FaqItem>): string {
  return JSON.stringify({
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: items.map((item) => ({
      '@type': 'Question',
      name: item.name,
      acceptedAnswer: {
        '@type': 'Answer',
        text: item.answer,
      },
    })),
  });
}

export interface BreadcrumbItem {
  readonly name: string;
  readonly item: string;
}

export function breadcrumbJsonLd(items: ReadonlyArray<BreadcrumbItem>): string {
  return JSON.stringify({
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: items.map((entry, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      name: entry.name,
      item: entry.item,
    })),
  });
}

export interface GameJsonLdInput {
  readonly name: string;
  readonly description: string;
  readonly url: string;
}

export function gameJsonLd(input: GameJsonLdInput): string {
  return JSON.stringify({
    '@context': 'https://schema.org',
    '@type': 'Game',
    name: input.name,
    description: input.description,
    url: input.url,
    inLanguage: 'fr',
    genre: 'Word puzzle',
    gamePlatform: 'Web browser',
  });
}
