import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// The /sondage surface was renamed to /contribuer. The legacy aliases used
// to be client-side beforeLoad redirect routes, but shipping a top-level
// 404.html turned off Cloudflare Pages' SPA fallback they relied on, so the
// redirects now live server-side in public/_redirects as 308s. This asserts
// the rules exist and rewrite to the canonical /contribuer targets.
const REDIRECTS = readFileSync(
  resolve(__dirname, '../public/_redirects'),
  'utf8',
);

function ruleFor(source: string): { target: string; status: string } | null {
  for (const line of REDIRECTS.split('\n')) {
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#')) continue;
    const [from, to, status] = trimmed.split(/\s+/);
    if (from === source) return { target: to!, status: status! };
  }
  return null;
}

describe('legacy sondage redirects (_redirects)', () => {
  it('redirects /sondage to /contribuer with 308', () => {
    expect(ruleFor('/sondage')).toEqual({ target: '/contribuer', status: '308' });
  });

  it('redirects /sondage/pairs to /contribuer/pairs with 308', () => {
    expect(ruleFor('/sondage/pairs')).toEqual({
      target: '/contribuer/pairs',
      status: '308',
    });
  });

  it('redirects the trailing-slash forms to the canonical /contribuer targets', () => {
    expect(ruleFor('/sondage/')).toEqual({ target: '/contribuer', status: '308' });
    expect(ruleFor('/sondage/pairs/')).toEqual({
      target: '/contribuer/pairs',
      status: '308',
    });
  });

  it('orders /sondage/pairs before /sondage so the more specific rule wins', () => {
    const lines = REDIRECTS.split('\n').map((l) => l.trim());
    const pairsIdx = lines.findIndex((l) => l.startsWith('/sondage/pairs '));
    const bareIdx = lines.findIndex((l) => l.startsWith('/sondage '));
    expect(pairsIdx).toBeGreaterThanOrEqual(0);
    expect(bareIdx).toBeGreaterThanOrEqual(0);
    expect(pairsIdx).toBeLessThan(bareIdx);
  });
});
