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
// Overflow-resilience invariant (ADR-0036 §6): any grid/flex container
// inside a page shell MUST use minmax(0, 1fr) on grid tracks and
// minWidth: 0 on flex children with intrinsic-size content. Enforced
// empirically by frontend/e2e/page-shell-overflow.spec.ts at 320/375/768.

import * as React from 'react';
import { css, cx } from 'styled-system/css';
import { HerbierCorner } from '../decorations/HerbierCorner';
import { AppHeader } from './AppHeader';
import { Footer } from './Footer';

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
  // Anchor for absolutely-positioned <HerbierCorner> decorations
  // (ADR-0043 §5). ViewportPage's variant intentionally omits this —
  // the /grille and lobby playing surfaces stay clean.
  position: 'relative',
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

// pan-y suppresses pinch while preserving vertical pan (pull-to-refresh) — ADR-0016 keyboard-mounted exception.
const viewportMainSuppressTouchStyles = css({ touchAction: 'pan-y' });

const contentWrapperStyles = css({
  width: '100%',
  maxWidth: 'pageMaxWidth',
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingBlock: { base: '16px', md: '24px' },
  display: 'flex',
  flexDirection: 'column',
  gap: 'lg',
});

const viewportWrapperStyles = css({
  width: '100%',
  maxWidth: 'pageMaxWidth',
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingTop: { base: '12px', md: '20px' },
  // Bottom reserves space for the fixed MobileKeyboard panel (only mounted on touch-primary); falls back to symmetric chrome padding when absent.
  paddingBottom: { base: 'var(--mobile-kb-height, 12px)', md: 'var(--mobile-kb-height, 20px)' },
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
  // Removes the footer so its natural height doesn't squeeze the grid panel's flex:1 slot when a fixed MobileKeyboard already occludes it visually.
  readonly hideFooter?: boolean;
  readonly children: React.ReactNode;
}

// Private — never exported from layout/index.ts.
function PageChrome({
  headerActiveNavId,
  mainClassName,
  skipLink,
  hideFooter,
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
      {hideFooter ? null : <Footer />}
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
      {/* Asymmetric-diagonal placement (top-left + bottom-right) reads as
       * editorial margin notes rather than a boxy frame. ViewportPage
       * intentionally omits decorations — the grille / lobby playing
       * surfaces stay clean per ADR-0043 §5. */}
      <HerbierCorner corner="top-left" />
      <HerbierCorner corner="bottom-right" />
      <div className={contentWrapperStyles}>{children}</div>
    </PageChrome>
  );
}

export interface ViewportPageProps {
  readonly headerActiveNavId?: string;
  readonly skipLink?: { readonly label: string; readonly onActivate: () => void };
  // Opt-in `touch-action: pan-y` on <main> for the keyboard-mounted grid routes — ADR-0016 amendment 2026-05-22.
  readonly suppressTouchAction?: boolean;
  // Removes the footer so its natural height doesn't squeeze the grid panel's flex:1 slot when a fixed MobileKeyboard already occludes it visually.
  readonly hideFooter?: boolean;
  readonly children: React.ReactNode;
}

export function ViewportPage({
  headerActiveNavId,
  skipLink,
  suppressTouchAction,
  hideFooter,
  children,
}: ViewportPageProps) {
  const mainClassName = cx(
    viewportMainStyles,
    suppressTouchAction && viewportMainSuppressTouchStyles,
  );
  return (
    <PageChrome
      headerActiveNavId={headerActiveNavId}
      mainClassName={mainClassName}
      skipLink={skipLink}
      hideFooter={hideFooter}
    >
      <div className={viewportWrapperStyles}>{children}</div>
    </PageChrome>
  );
}
