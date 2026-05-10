import { describe, it, expect } from 'vitest';
import { buildHead } from '@/ui/seo/buildHead';

describe('buildHead', () => {
  const baseInput = {
    title: 'Aide — WordSparrow',
    description: 'Comment jouer aux mots fléchés sur WordSparrow.',
    canonical: 'https://wordsparrow.io/aide',
  };

  it('emits a title meta tag', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({ title: 'Aide — WordSparrow' });
  });

  it('emits a description meta tag', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({
      name: 'description',
      content: 'Comment jouer aux mots fléchés sur WordSparrow.',
    });
  });

  it('emits a canonical link', () => {
    const head = buildHead(baseInput);
    expect(head.links).toContainEqual({
      rel: 'canonical',
      href: 'https://wordsparrow.io/aide',
    });
  });

  it('emits the full OpenGraph set', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({ property: 'og:title', content: baseInput.title });
    expect(head.meta).toContainEqual({ property: 'og:description', content: baseInput.description });
    expect(head.meta).toContainEqual({ property: 'og:url', content: baseInput.canonical });
    expect(head.meta).toContainEqual({ property: 'og:type', content: 'website' });
    expect(head.meta).toContainEqual({ property: 'og:site_name', content: 'WordSparrow' });
    expect(head.meta).toContainEqual({ property: 'og:locale', content: 'fr_FR' });
  });

  it('emits the default OG image when none provided', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({
      property: 'og:image',
      content: 'https://wordsparrow.io/og-default.png',
    });
  });

  it('emits the Twitter card tags mirroring OG', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({ name: 'twitter:card', content: 'summary_large_image' });
    expect(head.meta).toContainEqual({ name: 'twitter:title', content: baseInput.title });
    expect(head.meta).toContainEqual({ name: 'twitter:description', content: baseInput.description });
    expect(head.meta).toContainEqual({
      name: 'twitter:image',
      content: 'https://wordsparrow.io/og-default.png',
    });
  });

  it('omits the robots meta by default', () => {
    const head = buildHead(baseInput);
    const robots = head.meta.find((m) => 'name' in m && m.name === 'robots');
    expect(robots).toBeUndefined();
  });

  it('emits noindex,follow when noindex is set', () => {
    const head = buildHead({ ...baseInput, noindex: true });
    expect(head.meta).toContainEqual({ name: 'robots', content: 'noindex,follow' });
  });

  it('uses the provided ogImage for og:image and twitter:image', () => {
    const head = buildHead({
      ...baseInput,
      ogImage: 'https://wordsparrow.io/og-aide.png',
    });
    expect(head.meta).toContainEqual({
      property: 'og:image',
      content: 'https://wordsparrow.io/og-aide.png',
    });
    expect(head.meta).toContainEqual({
      name: 'twitter:image',
      content: 'https://wordsparrow.io/og-aide.png',
    });
    // The default must NOT leak in when a per-route image is supplied.
    expect(head.meta).not.toContainEqual({
      property: 'og:image',
      content: 'https://wordsparrow.io/og-default.png',
    });
    expect(head.meta).not.toContainEqual({
      name: 'twitter:image',
      content: 'https://wordsparrow.io/og-default.png',
    });
  });
});
