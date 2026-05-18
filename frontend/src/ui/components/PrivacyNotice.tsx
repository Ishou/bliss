// Privacy notice content (FR + EN). Hand-rendered in JSX rather than
// imported from `docs/privacy/privacy-notice.md` because the markdown
// lives outside Vite's resolution root and a runtime fetch would slow
// the route load. Manual sync at update time; the doc is the legal
// source-of-truth.
//
// Keep content here in lockstep with `docs/privacy/privacy-notice.md`.
// When the canonical doc changes, update the matching JSX block below
// in the same PR.

import { useState } from 'react';
import { Link } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import type { SessionClient } from '@/application/session/SessionClient';
import { Button } from '@/ui/components/primitives/Button';

export type PrivacyLanguage = 'fr' | 'en';

export interface PrivacyNoticeProps {
  readonly lang: PrivacyLanguage;
  readonly sessionClient: SessionClient;
}

// Article-body typography. The page chrome (header / main / footer /
// 720px cap) is owned by <ContentPage> in the consuming routes; this
// component is a pure body slotted into that shell.
//
// `& table` becomes a scrolling block-level box at narrow viewports —
// the data tables (Données traitées / Data we process) have wider
// intrinsic min-content than mobile devices and would otherwise push
// the page past the right edge. `display: block` loses true
// table-layout semantics, but the rendered visual stays intact in
// every modern engine and the overflow stops on small screens.
const articleStyles = css({
  fontFamily: 'body',
  color: 'fg',
  lineHeight: '1.6',
  '& h1': { fontSize: 'xl', fontWeight: 'bold', marginBottom: 'md' },
  '& h2': { fontSize: 'lg', fontWeight: 'semibold', marginTop: 'lg', marginBottom: 'sm' },
  '& p': { marginBottom: 'sm' },
  '& ul': { marginLeft: 'lg', marginBottom: 'sm' },
  '& li': { marginBottom: 'xs' },
  '& a': { color: 'accent', textDecoration: 'underline' },
  '& table': {
    display: 'block',
    maxWidth: '100%',
    overflowX: 'auto',
    marginBottom: 'md',
    borderCollapse: 'collapse',
  },
  '& th, & td': { border: '1px solid token(colors.border)', padding: 'sm', textAlign: 'left' },
  '& th': { fontWeight: 'semibold' },
});

const eraseSectionStyles = css({
  marginTop: 'xl',
  padding: 'lg',
  border: '2px solid token(colors.border)',
  borderRadius: '8px',
});

const eraseButtonStyles = css({ marginTop: 'sm' });

const statusOkStyles = css({ marginTop: 'sm', color: 'success', fontWeight: 'semibold' });
const statusErrStyles = css({ marginTop: 'sm', color: 'error', fontWeight: 'semibold' });

export function PrivacyNotice({ lang, sessionClient }: PrivacyNoticeProps) {
  return (
    <article className={articleStyles}>
      {lang === 'fr' ? <FrenchContent /> : <EnglishContent />}
      <EraseDataSection lang={lang} sessionClient={sessionClient} />
    </article>
  );
}

function EraseDataSection({
  lang,
  sessionClient,
}: {
  lang: PrivacyLanguage;
  sessionClient: SessionClient;
}) {
  const [status, setStatus] = useState<'idle' | 'pending' | 'success' | 'error'>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const t = lang === 'fr' ? frStrings : enStrings;

  async function handleErase() {
    if (status === 'pending') return;
    if (!window.confirm(t.confirm)) return;
    setStatus('pending');
    setErrorMessage(null);
    try {
      const sessionId = sessionClient.getSessionId();
      await sessionClient.eraseSession(sessionId);
      sessionClient.clearLocalSession();
      setStatus('success');
      // Brief pause so the user sees the confirmation, then navigate home.
      setTimeout(() => {
        window.location.href = '/';
      }, 1500);
    } catch (err) {
      // The original implementation surfaced the raw error.message,
      // which renders English / technical strings ("Failed to fetch",
      // "TypeError: Cannot read property…") to a French user. The UI
      // now shows a localised, calm-tone message regardless of the
      // underlying error shape.
      //
      // A plain `catch {}` would also swallow the error from the OTel
      // pipeline — `window.error` / `unhandledrejection` listeners
      // (PR-F.3) only see thrown / rejected values that escape every
      // catch block. `globalThis.reportError(err)` re-dispatches the
      // error through `window.onerror` synthetically so the listener
      // captures it as a `window.error` span; the UI keeps the
      // localised message and the OTel pipeline keeps full coverage.
      // Browser support: Chrome 95+, Firefox 93+, Safari 15.4+ (the
      // baseline our analytics show, plus a `typeof` guard for the
      // long tail).
      if (typeof globalThis.reportError === 'function') {
        globalThis.reportError(err);
      }
      setStatus('error');
      setErrorMessage(t.eraseFailureMessage);
    }
  }

  return (
    <section className={eraseSectionStyles} aria-labelledby="erase-heading">
      <h2 id="erase-heading">{t.eraseTitle}</h2>
      <p>{t.eraseDescription}</p>
      <Button
        variant="secondary"
        className={eraseButtonStyles}
        onClick={handleErase}
        disabled={status === 'pending' || status === 'success'}
      >
        {status === 'pending' ? t.erasing : t.eraseButton}
      </Button>
      {status === 'success' && <p className={statusOkStyles}>{t.successMessage}</p>}
      {status === 'error' && (
        <p className={statusErrStyles}>
          {t.errorPrefix}: {errorMessage}
        </p>
      )}
    </section>
  );
}

const frStrings = {
  confirm:
    'Confirmer l\'effacement ? Votre identifiant de session, votre pseudonyme et l\'historique des indices côté serveur seront supprimés immédiatement.',
  eraseTitle: 'Effacer mes données',
  eraseDescription:
    'Supprime votre identifiant de session, votre pseudonyme et toutes les demandes d\'indices enregistrées sur le serveur. Le cache local de votre progression est également supprimé. Vous serez redirigé·e vers l\'accueil après l\'effacement.',
  eraseButton: 'Effacer mes données',
  erasing: 'Effacement en cours…',
  successMessage: 'Données effacées. Redirection…',
  errorPrefix: 'Erreur',
  eraseFailureMessage: 'Échec de la suppression. Réessayez dans un instant.',
};

const enStrings = {
  confirm:
    'Confirm erase? Your session id, pseudonym, and server-side hint history will be removed immediately.',
  eraseTitle: 'Erase my data',
  eraseDescription:
    'Removes your session id, pseudonym, and every recorded hint request on the server. Your local progress cache is also removed. You will be redirected home after the erase completes.',
  eraseButton: 'Erase my data',
  erasing: 'Erasing…',
  successMessage: 'Data erased. Redirecting…',
  errorPrefix: 'Error',
  eraseFailureMessage: 'Erase failed. Try again in a moment.',
};

function FrenchContent() {
  return (
    <>
      <h1>Politique de confidentialité</h1>
      <p>
        <strong>WordSparrow</strong> (mots fléchés en ligne) collecte le strict minimum nécessaire pour
        faire fonctionner le service. Aucun compte, aucun cookie publicitaire, aucun partage avec
        des annonceurs.
      </p>
      <h2>Responsable du traitement</h2>
      <p>
        WordSparrow est exploité par Colin Auberger, contact :{' '}
        <a href="mailto:contact@wordsparrow.io">contact@wordsparrow.io</a>.
      </p>
      <h2>Données traitées</h2>
      <table>
        <thead>
          <tr>
            <th>Donnée</th>
            <th>Pourquoi</th>
            <th>Combien de temps</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              Identifiant de session (UUID v7) dans <code>localStorage</code>
            </td>
            <td>Identifier votre session, compter vos demandes d’indices</td>
            <td>Jusqu’à effacement</td>
          </tr>
          <tr>
            <td>Pseudonyme</td>
            <td>Affichage en multijoueur</td>
            <td>Jusqu’à effacement</td>
          </tr>
          <tr>
            <td>Indices demandés par grille</td>
            <td>Limiter le nombre d’indices par grille</td>
            <td>90 jours après la dernière demande</td>
          </tr>
          <tr>
            <td>
              Lettres saisies et cases validées par grille, indexées par
              identifiant de session, dans <code>localStorage</code>
            </td>
            <td>
              Reprendre une grille en cours et afficher votre progression
              dans « Anciennes grilles »
            </td>
            <td>Jusqu’à effacement</td>
          </tr>
          <tr>
            <td>
              Tour d’accueil vu (booléen) dans <code>localStorage</code>
            </td>
            <td>
              Ne pas relancer la visite guidée à chaque ouverture du jeu
            </td>
            <td>Jusqu’à effacement</td>
          </tr>
          <tr>
            <td>Mesures d’audience anonymisées (Matomo auto-hébergé)</td>
            <td>Comprendre l’usage du service pour l’améliorer</td>
            <td>13 mois (recommandation CNIL)</td>
          </tr>
        </tbody>
      </table>
      <section aria-labelledby="fr-account-heading">
        <h2 id="fr-account-heading">Compte joueur et connexion</h2>
        <p>
          <strong>Compte joueur.</strong> Si vous vous connectez via Google, nous créons un compte
          joueur avec : un identifiant interne (UUID, sans lien avec votre compte Google) ; un
          pseudonyme modifiable (par défaut : un nom d’animal aléatoire repris de votre session
          anonyme) ; la date de création et de dernière connexion. Nous ne stockons{' '}
          <strong>pas</strong> votre email, votre nom, votre photo de profil ou toute autre donnée
          de votre compte Google. Le périmètre OAuth utilisé est <code>openid</code> uniquement
          (ADR-0045).
        </p>
        <p>
          <strong>Sessions.</strong> Un cookie <code>__Host-ws_session</code> (HttpOnly, Secure,
          durée 7 jours) contient un identifiant de session opaque (UUID, pas un JWT). Il est
          révoqué à la déconnexion et supprimé lors de la suppression du compte.
        </p>
        <p>
          <strong>Sous-traitants.</strong> Lors de la connexion, Google reçoit votre choix
          d’autorisation. Aucune donnée n’est partagée en dehors du flux OAuth.
        </p>
        <p>
          <strong>Droit à l’effacement.</strong> « Supprimer mon compte » dans <code>/compte</code>{' '}
          supprime immédiatement vos données identité — pas de période de rétention, pas de
          soft-delete.
        </p>
      </section>
      <h2>Ce que nous ne collectons pas</h2>
      <ul>
        <li>Aucun compte, aucun mot de passe, aucune adresse e-mail.</li>
        <li>Aucun cookie de tracking publicitaire.</li>
        <li>Aucune adresse IP n’est conservée (Matomo l’anonymise au niveau des deux derniers octets).</li>
        <li>Aucun partage avec des régies publicitaires ou des courtiers en données.</li>
      </ul>
      <h2>Base légale</h2>
      <p>
        <strong>Intérêt légitime</strong> (RGPD article 6.1.f). La mesure d’audience est exemptée
        de consentement par la CNIL (délibération 2020-091) parce que sa configuration respecte
        les conditions de l’exemption : adresse IP anonymisée, absence de recoupement avec
        d’autres traitements, pas de transmission à des tiers, pas de profilage individuel.
      </p>
      <h2>Cookies</h2>
      <p>
        WordSparrow <strong>n’utilise pas</strong> de cookies. Le service stocke un identifiant de
        session anonyme dans le <code>localStorage</code> de votre navigateur (technologie
        distincte des cookies). Matomo fonctionne en mode sans cookie.
      </p>
      <h2>Vos droits</h2>
      <ul>
        <li>
          <strong>Accès et portabilité</strong> (articles 15 et 20) : vos données étant minimales
          et anonymes, l’essentiel est visible dans les paramètres du jeu. Pour l’historique
          d’indices, écrivez à l’adresse ci-dessus.
        </li>
        <li>
          <strong>Effacement</strong> (article 17) : utilisez le bouton ci-dessous. Les visites
          Matomo ne sont pas activement supprimées car déjà non-attribuables (hachage rotatif
          quotidien).
        </li>
        <li>
          <strong>Opposition</strong> : activez <code>Do Not Track</code> dans votre navigateur ;
          Matomo le respecte automatiquement.
        </li>
        <li>
          <strong>Réclamation</strong> : <a href="https://www.cnil.fr">CNIL</a>.
        </li>
      </ul>
      <h2>Sous-traitants</h2>
      <ul>
        <li>
          <strong>Hetzner Online GmbH</strong> (Allemagne) — hébergement.
        </li>
        <li>
          <strong>Cloudflare, Inc.</strong> (États-Unis) — DNS et CDN. Voit votre IP lors de la
          livraison des pages, traitée selon son propre Data Processing Addendum.
        </li>
      </ul>
      <p>
        <Link to="/privacy" hrefLang="en">
          English version
        </Link>
      </p>
    </>
  );
}

function EnglishContent() {
  return (
    <>
      <h1>Privacy Policy</h1>
      <p>
        <strong>WordSparrow</strong> (online French crossword puzzles) collects the minimum needed to
        run the service. No accounts, no advertising cookies, no sharing with advertisers.
      </p>
      <h2>Data controller</h2>
      <p>
        WordSparrow is operated by Colin Auberger, contact:{' '}
        <a href="mailto:contact@wordsparrow.io">contact@wordsparrow.io</a>.
      </p>
      <h2>Data we process</h2>
      <table>
        <thead>
          <tr>
            <th>Data</th>
            <th>Purpose</th>
            <th>Retention</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              Session id (UUID v7) in <code>localStorage</code>
            </td>
            <td>Identify your session, count your hint requests</td>
            <td>Until erased</td>
          </tr>
          <tr>
            <td>Pseudonym</td>
            <td>Multiplayer display</td>
            <td>Until erased</td>
          </tr>
          <tr>
            <td>Hints used per puzzle</td>
            <td>Cap hints per puzzle</td>
            <td>90 days from last request</td>
          </tr>
          <tr>
            <td>
              Letters entered and validated cells per puzzle, keyed by
              session id, in <code>localStorage</code>
            </td>
            <td>
              Resume an in-progress puzzle and show progress on
              &ldquo;Past puzzles&rdquo;
            </td>
            <td>Until erased</td>
          </tr>
          <tr>
            <td>
              Onboarding tour seen (boolean) in <code>localStorage</code>
            </td>
            <td>
              Avoid replaying the guided tour every time you open the game
            </td>
            <td>Until erased</td>
          </tr>
          <tr>
            <td>Anonymized audience metrics (self-hosted Matomo)</td>
            <td>Understand usage to improve the service</td>
            <td>13 months (CNIL guidance)</td>
          </tr>
        </tbody>
      </table>
      <section aria-labelledby="en-account-heading">
        <h2 id="en-account-heading">Player account and sign-in</h2>
        <p>
          <strong>Player account.</strong> When you sign in with Google we create a player account
          with: an internal identifier (UUID, unrelated to your Google account ID); an editable
          display name (defaulting to a random animal name carried over from your anonymous
          session); creation and last-seen timestamps. We do <strong>not</strong> store your
          email, name, profile picture, or any other Google account data. The OAuth scope is{' '}
          <code>openid</code> only (ADR-0045).
        </p>
        <p>
          <strong>Sessions.</strong> A <code>__Host-ws_session</code> cookie (HttpOnly, Secure,
          7-day lifetime) holds an opaque session ID (UUID, not a JWT). It is revoked on sign-out
          and deleted when the account is deleted.
        </p>
        <p>
          <strong>Sub-processors.</strong> During sign-in Google receives your authorisation
          choice. No data is shared outside the OAuth flow itself.
        </p>
        <p>
          <strong>Right to erasure.</strong> &ldquo;Delete my account&rdquo; in{' '}
          <code>/compte</code> immediately deletes your identity data — no retention period, no
          soft-delete.
        </p>
      </section>
      <h2>What we do not collect</h2>
      <ul>
        <li>No accounts, no passwords, no email addresses.</li>
        <li>No advertising or tracking cookies.</li>
        <li>No IP addresses retained (Matomo anonymizes the last two octets).</li>
        <li>No sharing with ad networks or data brokers.</li>
      </ul>
      <h2>Lawful basis</h2>
      <p>
        <strong>Legitimate interest</strong> (GDPR Article 6.1.f). Audience measurement is exempt
        from consent under French CNIL deliberation 2020-091 because the configuration meets the
        exemption conditions (anonymized IP, no cross-site profiling, no third-party sharing, no
        individual profiling).
      </p>
      <h2>Cookies</h2>
      <p>
        WordSparrow does <strong>not</strong> set cookies. The service stores an anonymous session
        identifier in your browser&apos;s <code>localStorage</code> (distinct from cookies). Matomo
        runs in cookieless mode.
      </p>
      <h2>Your rights</h2>
      <ul>
        <li>
          <strong>Access and portability</strong> (Articles 15, 20): your data is minimal and
          visible in game settings. For the hint history, contact the address above.
        </li>
        <li>
          <strong>Erasure</strong> (Article 17): use the button below. Matomo visits are not
          actively deleted because they are already non-attributable by design (daily-rotated
          hash).
        </li>
        <li>
          <strong>Opt-out</strong>: enable <code>Do Not Track</code> in your browser; Matomo
          honours it automatically.
        </li>
        <li>
          <strong>Complaints</strong>: file with the <a href="https://www.cnil.fr">CNIL</a>.
        </li>
      </ul>
      <h2>Sub-processors</h2>
      <ul>
        <li>
          <strong>Hetzner Online GmbH</strong> (Germany) — hosting.
        </li>
        <li>
          <strong>Cloudflare, Inc.</strong> (USA) — DNS and CDN. Sees your IP when serving pages,
          processed under its own DPA.
        </li>
      </ul>
      <p>
        <Link to="/confidentialite" hrefLang="fr">
          Version française
        </Link>
      </p>
    </>
  );
}
