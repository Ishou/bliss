import { Link, useNavigate, useRouterState } from '@tanstack/react-router';
import { css, cx } from 'styled-system/css';
import { Lockup } from '@/ui/components/brand';
import { OverflowMenu } from '@/ui/components/primitives';
import { HamburgerIcon } from '@/ui/components/icons';

// App-wide header — ADR-0005 §5 layout spec.
//
// Desktop (≥ 768 px): 54 px tall, lockup at a 20 px gutter on the left,
// centered nav, right edge reserved (the streak pill / avatar from the
// brief mock are not in the current scope). The active nav link uses a
// 1.5 px sage underline — solid `accent` would over-saturate the page.
//
// Mobile (< 768 px): 44 px row with the lockup on the left and a
// hamburger on the right. The hamburger is an `OverflowMenu` whose
// items mirror the desktop nav links — selecting an item navigates via
// a plain `window.location.assign` (anchor semantics) so the active
// link logic stays a single source of truth in `NAV_LINKS`.

interface NavLink {
  readonly id: string;
  readonly label: string;
  // union makes <Link to=> and navigate({ to }) type-safe without a cast.
  readonly href: '/' | '/grilles' | '/aide';
}

const NAV_LINKS: readonly NavLink[] = [
  { id: 'accueil', label: 'Accueil', href: '/' },
  { id: 'grilles', label: 'Grilles', href: '/grilles' },
  { id: 'aide', label: 'Aide', href: '/aide' },
];

// Outer band — full-width charcoal strip with the sticky position and
// the bottom hairline. Inner band lives inside this and clamps the
// header content to the same 720 px max-width as the puzzle content
// below, so the lockup / nav / right slot align with the toolbar and
// grid edges.
const headerOuterStyles = css({
  position: 'sticky',
  top: 0,
  zIndex: 10,
  width: '100%',
  bg: 'bg',
  borderBottom: '1px solid token(colors.gridLine)',
});

// WCAG 2.4.1 — let keyboard users jump past the nav to the main content.
// Visually hidden until focused; the `_focusVisible` rule restores it as a
// pinned chip in the top-left of the viewport.
const skipLinkStyles = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  margin: '-1px',
  padding: 0,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  borderWidth: 0,
  _focusVisible: {
    position: 'fixed',
    top: '8px',
    insetInlineStart: '8px',
    width: 'auto',
    height: 'auto',
    margin: 0,
    paddingBlock: '8px',
    paddingInline: '12px',
    overflow: 'visible',
    clip: 'auto',
    whiteSpace: 'normal',
    bg: 'accent',
    color: 'bg',
    fontFamily: 'body',
    fontSize: 'sm',
    fontWeight: 'medium',
    textDecoration: 'none',
    borderRadius: 'md',
    zIndex: 100,
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const headerInnerStyles = css({
  width: '100%',
  maxWidth: 'pageMaxWidth',
  margin: '0 auto',
  // 44 px mobile / 54 px desktop, per the brief.
  height: { base: '44px', md: '54px' },
  paddingInline: { base: '16px', md: '20px' },
  display: 'grid',
  // Three-column grid keeps the centered nav truly centered regardless
  // of the lockup's measured width or any future right-side actions.
  gridTemplateColumns: '1fr auto 1fr',
  alignItems: 'center',
});

const lockupSlotStyles = css({
  display: 'flex',
  justifyContent: 'flex-start',
  alignItems: 'center',
  gap: '8px',
});

const alphaBadgeStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  fontFamily: 'body',
  fontSize: 'xxs',
  fontWeight: 'bold',
  lineHeight: 1,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'secondaryText',
  border: '1px solid token(colors.secondaryText)',
  borderRadius: 'sm',
  paddingInline: '6px',
  paddingBlock: '3px',
  userSelect: 'none',
});

const navStyles = css({
  // Hidden under the mobile breakpoint; shown as a flex row at md+. The
  // grid slot stays so the header keeps its 3-column rhythm.
  display: { base: 'none', md: 'flex' },
  alignItems: 'center',
  gap: '28px',
  // The grid centres this slot's content automatically because the slot
  // is `auto`-sized in the middle column.
});

const linkBaseStyles = css({
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'medium',
  color: 'fgMuted',
  textDecoration: 'none',
  paddingBlock: '4px',
  borderBottom: '1.5px solid transparent',
  transition: 'color 120ms ease-out, border-color 120ms ease-out',
  _hover: { color: 'fg' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
    borderRadius: '2px',
  },
});

const linkActiveStyles = css({
  color: 'fg',
  borderBottomColor: 'accent',
});

const rightSlotStyles = css({
  // Pin to grid column 3 explicitly: when the centre `<nav>` is
  // `display: none` on mobile, CSS Grid auto-placement would otherwise
  // drop this slot into column 2 (the now-empty auto column), parking
  // the hamburger in the visual middle of the header.
  gridColumnStart: 3,
  display: 'flex',
  justifyContent: 'flex-end',
  alignItems: 'center',
});

const mobileNavSlotStyles = css({
  display: { base: 'inline-flex', md: 'none' },
});

export interface AppHeaderProps {
  // Optional override for the active nav id. When omitted, the header
  // resolves the active link from the current pathname — the common
  // case for top-level pages. Pass an explicit id for routes whose URL
  // does not match a nav link's `href` but should still highlight one
  // (e.g. the lobby route still belongs to "Grilles").
  readonly activeNavId?: string;
}

// Skip-link label tracks the route family. Grid routes get the
// shorter "Aller à la grille" — describes the actual jump target,
// since the grid is the dominant content under <main>. Other routes
// fall back to the generic "Aller au contenu".
const GRID_ROUTE_PATTERNS = [/^\/grille(\/|$)/, /^\/lobby\//];

function skipLinkLabelForPath(pathname: string): string {
  return GRID_ROUTE_PATTERNS.some((re) => re.test(pathname))
    ? 'Aller à la grille'
    : 'Aller au contenu';
}

// Resolve the active nav id from a pathname against `NAV_LINKS`. Exact
// match only — sub-routes don't auto-highlight a parent nav today;
// callers that need that pass `activeNavId` explicitly.
//
// Pathname is normalized by stripping a trailing slash (except for `/`
// itself). Cloudflare Pages serves a prerendered `dist/grilles/index.html`
// for `/grilles` and canonicalizes the URL to `/grilles/` on hard refresh,
// while SPA navigation via <Link to="/grilles"> keeps the slash off.
// Without normalization the underline silently drops after a hard refresh.
export function activeIdForPath(pathname: string): string | undefined {
  const normalized = pathname.length > 1 && pathname.endsWith('/')
    ? pathname.slice(0, -1)
    : pathname;
  return NAV_LINKS.find((link) => link.href === normalized)?.id;
}

export function AppHeader({ activeNavId }: AppHeaderProps = {}) {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const resolvedActiveId = activeNavId ?? activeIdForPath(pathname);
  // <Link>/<navigate> prevents full-page reload + prerender-flash on header clicks.
  const navigate = useNavigate();
  return (
    <header className={headerOuterStyles} role="banner">
      <a href="#main-content" className={skipLinkStyles}>
        {skipLinkLabelForPath(pathname)}
      </a>
      <div className={headerInnerStyles}>
        <div className={lockupSlotStyles}>
        <Link
          to="/"
          aria-label="Accueil WordSparrow"
          className={css({
            display: 'inline-flex',
            textDecoration: 'none',
            _focusVisible: {
              outline: '2px solid token(colors.focusRing)',
              outlineOffset: '2px',
              borderRadius: '4px',
            },
          })}
        >
          {/* Mobile uses the smaller wordmark per the brief. */}
          <span className={css({ display: { base: 'inline-flex', md: 'none' } })}>
            <Lockup size="mobile" />
          </span>
          <span className={css({ display: { base: 'none', md: 'inline-flex' } })}>
            <Lockup size="desktop" />
          </span>
        </Link>
        <span className={alphaBadgeStyles} role="img" aria-label="version alpha">
          Alpha
        </span>
      </div>
      <nav className={navStyles} aria-label="Navigation principale">
        {NAV_LINKS.map((link) => {
          const isActive = link.id === resolvedActiveId;
          return (
            <Link
              key={link.id}
              to={link.href}
              className={cx(linkBaseStyles, isActive ? linkActiveStyles : undefined)}
              aria-current={isActive ? 'page' : undefined}
            >
              {link.label}
            </Link>
          );
        })}
      </nav>
        <div className={rightSlotStyles}>
          <span className={mobileNavSlotStyles}>
            <OverflowMenu
              triggerLabel="Ouvrir le menu"
              triggerIcon={<HamburgerIcon />}
              items={NAV_LINKS.map((link) => ({
                id: link.id,
                label: link.label,
                onSelect: () => {
                  void navigate({ to: link.href });
                },
              }))}
            />
          </span>
        </div>
      </div>
    </header>
  );
}
