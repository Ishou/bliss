// Lazy half of the `/mentions-legales` route. The eager half
// (`./mentions-legales.tsx`) keeps the route definition + `head()` so
// prerender surfaces per-route metadata without waiting for this chunk.

import { createLazyRoute, Link } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { ContentPage } from '@/ui/components/layout';

const articleStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'md',
  '& h1': {
    fontSize: { base: 'xl', md: 'display' },
    letterSpacing: '-0.02em',
    margin: 0,
  },
  '& h2': {
    fontSize: 'lg',
    marginTop: 'lg',
    marginBottom: 0,
  },
  '& p': {
    fontSize: 'body',
    lineHeight: 1.6,
    margin: 0,
  },
  '& a': {
    color: 'accent',
    _hover: { textDecoration: 'underline' },
  },
  '& strong': { fontWeight: 'semibold' },
});

function LegalNoticePage() {
  return (
    <ContentPage>
      <article className={articleStyles}>
        <h1>Mentions légales</h1>

        <h2>Éditeur</h2>
        <p>
          WordSparrow est édité par <strong>Colin Auberger</strong>,
          contact :{' '}
          <a href="mailto:contact@wordsparrow.io">
            contact@wordsparrow.io
          </a>
          .
        </p>

        <h2>Hébergement</h2>
        <p>
          Le service est hébergé par{' '}
          <strong>Hetzner Online GmbH</strong>, Industriestr. 25,
          91710 Gunzenhausen, Allemagne (
          <a
            href="https://www.hetzner.com"
            target="_blank"
            rel="noopener noreferrer"
          >
            hetzner.com
          </a>
          ). La diffusion du contenu statique et la résolution DNS
          sont assurées par <strong>Cloudflare, Inc.</strong>, 101
          Townsend St, San Francisco, CA 94107, États-Unis (
          <a
            href="https://www.cloudflare.com"
            target="_blank"
            rel="noopener noreferrer"
          >
            cloudflare.com
          </a>
          ).
        </p>

        <h2>Propriété intellectuelle</h2>
        <p>
          Le code source de WordSparrow est publié sous licence
          FSL-1.1-MIT (Functional Source License 1.1, Apache MIT
          future). Voir le dépôt public du projet pour les conditions
          d&apos;utilisation, de modification et de redistribution.
        </p>

        <h2>Données personnelles</h2>
        <p>
          Le traitement des données personnelles est décrit dans la{' '}
          <Link to="/confidentialite">politique de confidentialité</Link>.
        </p>

        <h2>Signaler un problème</h2>
        <p>
          Pour signaler un contenu illicite, un bug ou une faille de
          sécurité, écrivez à{' '}
          <a href="mailto:contact@wordsparrow.io">
            contact@wordsparrow.io
          </a>
          .
        </p>
      </article>
    </ContentPage>
  );
}

export const Route = createLazyRoute('/mentions-legales')({
  component: LegalNoticePage,
});
