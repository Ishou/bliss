import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

describe('public/robots.txt', () => {
  const robots = readFileSync(
    resolve(__dirname, '../public/robots.txt'),
    'utf8',
  );

  it('allows the root path', () => {
    expect(robots).toMatch(/^Allow: \/$/m);
  });

  it('disallows /lobby/', () => {
    expect(robots).toMatch(/^Disallow: \/lobby\/$/m);
  });

  it('disallows /join/', () => {
    expect(robots).toMatch(/^Disallow: \/join\/$/m);
  });

  it('disallows /privacy', () => {
    expect(robots).toMatch(/^Disallow: \/privacy$/m);
  });

  it('references the production sitemap URL', () => {
    expect(robots).toMatch(/^Sitemap: https:\/\/wordsparrow\.io\/sitemap\.xml$/m);
  });
});
