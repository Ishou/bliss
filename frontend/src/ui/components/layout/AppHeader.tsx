import { useRouterState } from '@tanstack/react-router';
import { css, cx } from 'styled-system/css';
import { Lockup } from '@/ui/components/brand';

// App-wide header — ADR-0005 §5 layout spec.
//
// Desktop (≥ 768 px): 54 px tall, lockup at a 20 px gutter on the left,
// centered nav, right edge reserved (the streak pill / avatar from the
// brief mock are not in the current scope). The active nav link uses a
// 1.5 px sage underline — solid `accent` would over-saturate the page.
//
// Mobile (< 768 px): the brief reorganises to a 44 px row with the
// lockup on the left and a hamburger on the right. The current scope
// includes neither hamburger nor mobile-only menu, so on mobile we ship
// just the lockup; nav links are hidden via `display: none` rather than
// removed from the DOM, so a future hamburger toggle can simply flip the
// container's `display: flex`.

interface NavLink {
  readonly id: string;
  readonly label: string;
  readonly href: string;
}

const NAV_LINKS: readonly NavLink[] = [
  { id: 'grilles', label: 'Grilles', href: '/' },
  { id: 'statistiques', label: 'Statistiques', href: '/statistiques' },
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

const headerInnerStyles = css({
  width: '100%',
  maxWidth: '720px',
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
  display: 'flex',
  justifyContent: 'flex-end',
  alignItems: 'center',
});

export interface AppHeaderProps {
  // Optional override for the active nav id. When omitted, the header
  // resolves the active link from the current pathname — the common
  // case for top-level pages. Pass an explicit id for routes whose URL
  // does not match a nav link's `href` but should still highlight one
  // (e.g. the lobby route still belongs to "Grilles").
  readonly activeNavId?: string;
}

// Resolve the active nav id from a pathname against `NAV_LINKS`. Exact
// match only — sub-routes don't auto-highlight a parent nav today;
// callers that need that pass `activeNavId` explicitly.
function activeIdForPath(pathname: string): string | undefined {
  return NAV_LINKS.find((link) => link.href === pathname)?.id;
}

export function AppHeader({ activeNavId }: AppHeaderProps = {}) {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const resolvedActiveId = activeNavId ?? activeIdForPath(pathname);
  return (
    <header className={headerOuterStyles} role="banner">
      <div className={headerInnerStyles}>
        <div className={lockupSlotStyles}>
        <a
          href="/"
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
        </a>
      </div>
      <nav className={navStyles} aria-label="Navigation principale">
        {NAV_LINKS.map((link) => {
          const isActive = link.id === resolvedActiveId;
          return (
            <a
              key={link.id}
              href={link.href}
              className={cx(linkBaseStyles, isActive ? linkActiveStyles : undefined)}
              aria-current={isActive ? 'page' : undefined}
            >
              {link.label}
            </a>
          );
        })}
      </nav>
        <div className={rightSlotStyles} />
      </div>
    </header>
  );
}
