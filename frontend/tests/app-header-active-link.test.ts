import { describe, it, expect } from 'vitest';
import { activeIdForPath } from '@/ui/components/layout/AppHeader';

// Cloudflare Pages serves `dist/grille/index.html` and canonicalizes
// the URL to `/grille/` (trailing slash) on a hard refresh, while SPA
// `<a href="/grille">` navigation gives `/grille` (no slash). The
// active-link logic must treat both as the same destination — without
// normalization, the underline silently drops after a hard refresh.
describe('activeIdForPath', () => {
  it('matches the exact NAV_LINKS href', () => {
    expect(activeIdForPath('/grille')).toBe('grille');
    expect(activeIdForPath('/aide')).toBe('aide');
  });

  it('matches the trailing-slash variant served by Cloudflare Pages', () => {
    expect(activeIdForPath('/grille/')).toBe('grille');
    expect(activeIdForPath('/aide/')).toBe('aide');
  });

  it('returns undefined for unknown paths', () => {
    expect(activeIdForPath('/')).toBeUndefined();
    expect(activeIdForPath('/lobby/abc')).toBeUndefined();
    expect(activeIdForPath('/grille/extra')).toBeUndefined();
  });
});
