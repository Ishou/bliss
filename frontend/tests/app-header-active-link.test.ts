import { describe, it, expect } from 'vitest';
import { activeIdForPath } from '@/ui/components/layout/AppHeader';

// The prerender emits `dist/grilles.html` so Pages serves `/grilles`
// directly (200, no redirect). `_redirects` sends `/grilles/` back to
// `/grilles` with 308. SPA `<Link to="/grilles">` navigation gives
// `/grilles` (no slash). Normalization remains as defense in depth so
// any caller that passes a trailing slash still produces the right
// active id.
describe('activeIdForPath', () => {
  it('matches the exact NAV_LINKS href', () => {
    expect(activeIdForPath('/')).toBe('accueil');
    expect(activeIdForPath('/grilles')).toBe('grilles');
    expect(activeIdForPath('/contribuer')).toBe('contribuer');
    expect(activeIdForPath('/aide')).toBe('aide');
  });

  it('matches the trailing-slash variant served by Cloudflare Pages', () => {
    expect(activeIdForPath('/grilles/')).toBe('grilles');
    expect(activeIdForPath('/contribuer/')).toBe('contribuer');
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
