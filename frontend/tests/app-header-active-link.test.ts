import { describe, it, expect } from 'vitest';
import { activeIdForPath } from '@/ui/components/layout/AppHeader';

// Cloudflare Pages serves `dist/grilles/index.html` and canonicalizes
// the URL to `/grilles/` (trailing slash) on a hard refresh, while SPA
// `<a href="/grilles">` navigation gives `/grilles` (no slash). The
// active-link logic must treat both as the same destination — without
// normalization, the underline silently drops after a hard refresh.
describe('activeIdForPath', () => {
  it('matches the exact NAV_LINKS href', () => {
    expect(activeIdForPath('/')).toBe('accueil');
    expect(activeIdForPath('/grilles')).toBe('grilles');
    expect(activeIdForPath('/aide')).toBe('aide');
  });

  it('matches the trailing-slash variant served by Cloudflare Pages', () => {
    expect(activeIdForPath('/grilles/')).toBe('grilles');
    expect(activeIdForPath('/aide/')).toBe('aide');
  });

  it('returns undefined for unknown paths', () => {
    // /grille (the playing surface) is reachable via the Accueil cards
    // but no longer has a nav entry; nothing should be underlined.
    expect(activeIdForPath('/grille')).toBeUndefined();
    expect(activeIdForPath('/grille/')).toBeUndefined();
    expect(activeIdForPath('/lobby/abc')).toBeUndefined();
    expect(activeIdForPath('/grilles/extra')).toBeUndefined();
  });
});
