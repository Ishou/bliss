// Lazy half of the `/aide` route. The eager half (`./aide.tsx`) keeps
// `head()` and the `HELP_SECTIONS` data (used by FAQPage JSON-LD).
// Everything below — the Ark UI Accordion, Panda CSS classes, layout
// chrome — is loaded on demand the first time the route is matched.

import { Accordion } from '@ark-ui/react/accordion';
import { createLazyRoute, useNavigate } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { Button } from '@/ui/components/primitives';
import { AppHeader, Footer } from '@/ui/components/layout';
import { HELP_SECTIONS } from './aide';

const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  color: 'fg',
  fontFamily: 'body',
});

const mainStyles = css({
  flex: '1 1 0',
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  width: '100%',
  bg: 'bg',
});

const contentStyles = css({
  width: '100%',
  maxWidth: '720px',
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingBlock: { base: '16px', md: '24px' },
  display: 'flex',
  flexDirection: 'column',
  gap: 'lg',
});

const headingStyles = css({
  fontSize: 'xl',
  fontWeight: 'semibold',
  margin: 0,
});

const tourCardStyles = css({
  bg: 'surface',
  borderRadius: 'md',
  padding: 'lg',
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  border: '1px solid token(colors.border)',
});

const tourCardEyebrowStyles = css({
  fontSize: 'xs',
  color: 'fgMuted',
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  margin: 0,
});

const tourCardTitleStyles = css({
  fontSize: 'lg',
  fontWeight: 'semibold',
  margin: 0,
});

const tourCardActionStyles = css({
  alignSelf: 'flex-start',
  marginTop: 'sm',
});

const accordionRootStyles = css({
  bg: 'surface',
  borderRadius: 'md',
  border: '1px solid token(colors.border)',
  overflow: 'hidden',
});

const accordionItemStyles = css({
  borderBottom: '1px solid token(colors.border)',
  _last: { borderBottom: 'none' },
});

const accordionTriggerStyles = css({
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  width: '100%',
  paddingInline: 'lg',
  paddingBlock: 'md',
  bg: 'transparent',
  border: 'none',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'body',
  fontWeight: 'semibold',
  cursor: 'pointer',
  textAlign: 'left',
  _hover: { bg: 'surfaceElevated' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '-2px',
  },
});

const accordionIndicatorStyles = css({
  display: 'inline-flex',
  transition: 'transform 200ms ease-out',
  '&[data-state=open]': { transform: 'rotate(180deg)' },
});

const accordionContentStyles = css({
  paddingInline: 'lg',
  paddingBlock: 'md',
  fontSize: 'body',
  color: 'fg',
  opacity: 0.85,
  lineHeight: 1.6,
  '& kbd': {
    fontFamily: 'mono',
    fontSize: 'xs',
    bg: 'surfaceElevated',
    border: '1px solid token(colors.border)',
    borderRadius: '4px',
    paddingInline: '6px',
    paddingBlock: '2px',
  },
});

function AidePage() {
  const navigate = useNavigate();
  return (
    <div className={pageStyles}>
      <AppHeader />
      <main id="main-content" tabIndex={-1} className={mainStyles}>
        <div className={contentStyles}>
          <h1 className={headingStyles}>Comment jouer aux mots fléchés en ligne</h1>

          <section className={tourCardStyles} aria-labelledby="tour-launcher-title">
            <p className={tourCardEyebrowStyles}>Tour d&apos;accueil</p>
            <h2 id="tour-launcher-title" className={tourCardTitleStyles}>
              Lancer le tour d&apos;accueil
            </h2>
            <p>
              Une visite guidée de quelques secondes pour comprendre la
              grille, les indices et la validation automatique.
            </p>
            <Button
              variant="primary"
              className={tourCardActionStyles}
              onClick={() => {
                void navigate({ to: '/grille', search: { tour: 1 } });
              }}
            >
              Lancer le tour
            </Button>
          </section>

          <Accordion.Root
            className={accordionRootStyles}
            collapsible
            multiple={false}
          >
            {HELP_SECTIONS.map((section) => (
              <Accordion.Item
                key={section.value}
                value={section.value}
                className={accordionItemStyles}
              >
                <Accordion.ItemTrigger className={accordionTriggerStyles}>
                  <span>{section.label}</span>
                  <Accordion.ItemIndicator className={accordionIndicatorStyles}>
                    ⌄
                  </Accordion.ItemIndicator>
                </Accordion.ItemTrigger>
                <Accordion.ItemContent className={accordionContentStyles}>
                  {section.content}
                </Accordion.ItemContent>
              </Accordion.Item>
            ))}
          </Accordion.Root>
        </div>
      </main>
      <Footer />
    </div>
  );
}

export const Route = createLazyRoute('/aide')({
  component: AidePage,
});
