// Aide (help) page — referenced from `AppHeader.tsx` since the lobby
// landed; the route file itself is new with the onboarding tour.
//
// Layout: a "Lancer le tour d'accueil" launcher card at the top, then
// an Ark UI Accordion with six sections drawn from the brief mock.
// Native-feeling because Ark wraps `@zag-js/accordion` (focus, ARIA,
// expand/collapse animation hooks all included).

import { Accordion } from '@ark-ui/react/accordion';
import { createRoute, useNavigate } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { Button } from '@/ui/components/primitives';
import { AppHeader, Footer } from '@/ui/components/layout';
import { buildHead, INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

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

interface HelpSection {
  readonly value: string;
  readonly label: string;
  readonly content: React.ReactNode;
}

const HELP_SECTIONS: ReadonlyArray<HelpSection> = [
  {
    value: 'comment-jouer',
    label: 'Comment jouer',
    content: (
      <>
        Cliquez sur une case puis tapez une lettre. Les flèches du clavier vous
        déplacent dans la grille en évitant les cases d&apos;indices. Au
        carrefour de deux mots, appuyez sur <kbd>Espace</kbd> pour basculer
        entre les deux directions.
      </>
    ),
  },
  {
    value: 'raccourcis-clavier',
    label: 'Raccourcis clavier',
    content: (
      <>
        Utilisez les flèches pour vous déplacer, <kbd>Espace</kbd> pour
        changer de direction à un carrefour, <kbd>Retour</kbd> pour effacer
        une lettre, et <kbd>Tab</kbd> pour passer au mot suivant.
      </>
    ),
  },
  {
    value: 'validation-indices',
    label: 'Validation et indices',
    content: (
      <>
        Chaque mot est validé automatiquement quand toutes ses lettres sont
        correctes — la case se verrouille. Si vous bloquez, demandez un
        indice via le bouton dédié dans le bandeau (nombre limité par
        grille).
      </>
    ),
  },
  {
    value: 'multijoueur',
    label: 'Multijoueur',
    content: (
      <>
        Créez une partie multijoueur depuis la page d&apos;accueil et
        partagez le lien : tous les participants jouent la même grille en
        temps réel.
      </>
    ),
  },
  {
    value: 'nous-contacter',
    label: 'Nous contacter',
    content: (
      <>Pour toute question, écrivez à contact@wordsparrow.io.</>
    ),
  },
];

function AidePage() {
  const navigate = useNavigate();
  return (
    <div className={pageStyles}>
      <AppHeader />
      <main id="main-content" tabIndex={-1} className={mainStyles}>
        <div className={contentStyles}>
          <h1 className={headingStyles}>Aide</h1>

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

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/aide',
  component: AidePage,
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/aide')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/aide`,
    });
  },
});
