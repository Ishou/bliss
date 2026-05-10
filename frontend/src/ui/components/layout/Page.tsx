// Page-shell layout primitive — ADR-0036.
//
// Two public components share a private <PageChrome> that owns the
// <AppHeader> + <main id="main-content" tabIndex={-1}> + <Footer> wiring.
// The skip-link target is rendered here exclusively; consumers must NOT
// own their own <main>.
//
// Variant choice:
//   ContentPage  — children scroll past the viewport; footer pins to the
//                  viewport bottom on short content. Use for content
//                  pages (Accueil, Aide, legal, privacy).
//   ViewportPage — <main> absorbs leftover viewport height (flex:1 1 0;
//                  minHeight:0). Use when an inner flex child must
//                  expand to fill the remaining height (Grille, Lobby).
//
// Overflow-resilience invariant (ADR-0036 §5): any grid/flex container
// inside a page shell MUST use minmax(0, 1fr) on grid tracks and
// minWidth: 0 on flex children with intrinsic-size content. Enforced
// empirically by frontend/e2e/page-shell-overflow.spec.ts at 320/375/768.

import * as React from 'react';
import { css } from 'styled-system/css';
import { AppHeader } from './AppHeader';
import { Footer } from './Footer';

// Single source of truth for the content cap. Consumed by AppHeader,
// Footer, PrivacyNotice, and both page variants below.
export const PAGE_MAX_WIDTH = '720px';

const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  color: 'fg',
  fontFamily: 'body',
});

// Content variant: <main> grows but never shrinks. Combined with the
// flex column page above, this pins the footer to the viewport bottom
// on short content while never compressing tall content.
const contentMainStyles = css({
  flex: '1 0 auto',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  width: '100%',
  bg: 'bg',
});

// Viewport variant: <main> absorbs leftover height (flex:1 1 0;
// minHeight:0) so an inner flex child (e.g. the grid panel) can shrink
// to fit. minHeight:0 is required — flex items default to a min-content
// floor, and a tall grid panel would otherwise pump <main> past 100dvh.
const viewportMainStyles = css({
  flex: '1 1 0',
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  width: '100%',
  bg: 'bg',
});

const contentWrapperStyles = css({
  width: '100%',
  maxWidth: PAGE_MAX_WIDTH,
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingBlock: { base: '16px', md: '24px' },
  display: 'flex',
  flexDirection: 'column',
  gap: 'lg',
});

const viewportWrapperStyles = css({
  width: '100%',
  maxWidth: PAGE_MAX_WIDTH,
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingBlock: { base: '12px', md: '20px' },
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: { base: '12px', md: '18px' },
  flex: '1 1 0',
  minHeight: 0,
});

// Same visually-hidden-on-blur, pinned-chip-on-focus pattern as the
// AppHeader skip link. Rendered inside <main> so a keyboard user who
// already activated the header skip link can Tab once more and jump
// straight into the route's primary affordance (e.g. the puzzle grid),
// bypassing toolbars.
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

interface PageChromeProps {
  readonly headerActiveNavId?: string;
  readonly mainClassName: string;
  readonly skipLink?: { readonly label: string; readonly onActivate: () => void };
  readonly children: React.ReactNode;
}

// Private — never exported from layout/index.ts.
function PageChrome({
  headerActiveNavId,
  mainClassName,
  skipLink,
  children,
}: PageChromeProps) {
  return (
    <div className={pageStyles}>
      <AppHeader activeNavId={headerActiveNavId} />
      <main id="main-content" tabIndex={-1} className={mainClassName}>
        {skipLink ? (
          <a
            href="#main-content"
            className={skipLinkStyles}
            onClick={(e) => {
              e.preventDefault();
              skipLink.onActivate();
            }}
          >
            {skipLink.label}
          </a>
        ) : null}
        {children}
      </main>
      <Footer />
    </div>
  );
}

export interface ContentPageProps {
  readonly headerActiveNavId?: string;
  readonly children: React.ReactNode;
}

export function ContentPage({ headerActiveNavId, children }: ContentPageProps) {
  return (
    <PageChrome
      headerActiveNavId={headerActiveNavId}
      mainClassName={contentMainStyles}
    >
      <div className={contentWrapperStyles}>{children}</div>
    </PageChrome>
  );
}

export interface ViewportPageProps {
  readonly headerActiveNavId?: string;
  readonly skipLink?: { readonly label: string; readonly onActivate: () => void };
  readonly children: React.ReactNode;
}

export function ViewportPage({
  headerActiveNavId,
  skipLink,
  children,
}: ViewportPageProps) {
  return (
    <PageChrome
      headerActiveNavId={headerActiveNavId}
      mainClassName={viewportMainStyles}
      skipLink={skipLink}
    >
      <div className={viewportWrapperStyles}>{children}</div>
    </PageChrome>
  );
}
