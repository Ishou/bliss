import { describe, it, expect } from 'vitest';
import { faqPageJsonLd, breadcrumbJsonLd, gameJsonLd } from '@/ui/seo';

describe('faqPageJsonLd', () => {
  it('returns valid JSON parseable to a FAQPage with one Question per item', () => {
    const items = [
      { name: 'Comment jouer', answer: 'Cliquez sur une case puis tapez une lettre.' },
      { name: 'Raccourcis', answer: 'Utilisez les flèches pour vous déplacer.' },
    ];
    const json = faqPageJsonLd(items);
    const parsed = JSON.parse(json);
    expect(parsed['@context']).toBe('https://schema.org');
    expect(parsed['@type']).toBe('FAQPage');
    expect(parsed.mainEntity).toHaveLength(2);
    expect(parsed.mainEntity[0]).toEqual({
      '@type': 'Question',
      name: 'Comment jouer',
      acceptedAnswer: {
        '@type': 'Answer',
        text: 'Cliquez sur une case puis tapez une lettre.',
      },
    });
    expect(parsed.mainEntity[1].name).toBe('Raccourcis');
    expect(parsed.mainEntity[1].acceptedAnswer.text).toBe(
      'Utilisez les flèches pour vous déplacer.',
    );
  });

  it('handles a single item without trailing commas or formatting cruft', () => {
    const json = faqPageJsonLd([{ name: 'Q', answer: 'A' }]);
    expect(() => JSON.parse(json)).not.toThrow();
    const parsed = JSON.parse(json);
    expect(parsed.mainEntity).toHaveLength(1);
  });

  it('round-trips through JSON.parse + JSON.stringify byte-stable', () => {
    const items = [
      { name: 'Question 1', answer: 'Answer 1' },
      { name: 'Question 2', answer: 'Answer 2' },
      { name: 'Question 3', answer: 'Answer 3' },
    ];
    const json = faqPageJsonLd(items);
    const reparsed = JSON.stringify(JSON.parse(json));
    expect(reparsed).toBe(json);
  });
});

describe('breadcrumbJsonLd', () => {
  it('returns valid JSON with 1-indexed positions and correct fields', () => {
    const json = breadcrumbJsonLd([
      { name: 'Accueil', item: 'https://wordsparrow.io/' },
      { name: 'Aide — WordSparrow', item: 'https://wordsparrow.io/aide' },
    ]);
    const parsed = JSON.parse(json);
    expect(parsed['@context']).toBe('https://schema.org');
    expect(parsed['@type']).toBe('BreadcrumbList');
    expect(parsed.itemListElement).toHaveLength(2);
    expect(parsed.itemListElement[0]).toEqual({
      '@type': 'ListItem',
      position: 1,
      name: 'Accueil',
      item: 'https://wordsparrow.io/',
    });
    expect(parsed.itemListElement[1]).toEqual({
      '@type': 'ListItem',
      position: 2,
      name: 'Aide — WordSparrow',
      item: 'https://wordsparrow.io/aide',
    });
  });

  it('preserves order — position reflects array index +1', () => {
    const json = breadcrumbJsonLd([
      { name: 'A', item: 'https://x/a' },
      { name: 'B', item: 'https://x/b' },
      { name: 'C', item: 'https://x/c' },
    ]);
    const parsed = JSON.parse(json);
    expect(parsed.itemListElement.map((i: { position: number }) => i.position)).toEqual([
      1, 2, 3,
    ]);
  });
});

describe('gameJsonLd', () => {
  it('returns a Game JSON-LD with the canonical field set', () => {
    const json = gameJsonLd({
      name: 'WordSparrow — mots fléchés du jour',
      description: 'Grille de mots fléchés française quotidienne, jouable en ligne sans inscription.',
      url: 'https://wordsparrow.io/grille',
    });
    const parsed = JSON.parse(json);
    expect(parsed['@context']).toBe('https://schema.org');
    expect(parsed['@type']).toBe('Game');
    expect(parsed.name).toBe('WordSparrow — mots fléchés du jour');
    expect(parsed.description).toBe(
      'Grille de mots fléchés française quotidienne, jouable en ligne sans inscription.',
    );
    expect(parsed.url).toBe('https://wordsparrow.io/grille');
    expect(parsed.inLanguage).toBe('fr');
    expect(parsed.genre).toBe('Word puzzle');
    expect(parsed.gamePlatform).toBe('Web browser');
  });
});
