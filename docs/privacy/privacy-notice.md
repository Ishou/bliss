# Politique de confidentialité

> Source de vérité pour la page `/confidentialite` (FR) et `/privacy` (EN).
> Toute modification du traitement de données doit mettre à jour ce document
> avant ou pendant le PR qui introduit le changement.

**Dernière mise à jour :** _à compléter à la mise en ligne_

## En bref

Bliss (mots fléchés en ligne) collecte le strict minimum nécessaire pour
faire fonctionner le service. Aucun compte, aucun cookie publicitaire,
aucun partage avec des annonceurs. Vous pouvez à tout moment effacer vos
données depuis les paramètres du jeu.

## Responsable du traitement

Bliss est exploité par Colin Auberger, contact :
`colin.auberger@gmail.com`. Toute demande relative à vos données peut être
adressée à cette adresse.

## Données traitées

| Donnée | D'où elle vient | Pourquoi | Combien de temps |
|---|---|---|---|
| `sessionId` (identifiant aléatoire UUID v7) | Généré par votre navigateur, stocké dans `localStorage` | Identifier votre session pour le multijoueur et compter vos demandes d'indices | Tant que vous ne videz pas le stockage du navigateur ou ne cliquez pas sur "Effacer mes données" |
| Pseudonyme | Vous le choisissez (par défaut "Joueur xxxx") | Affichage en multijoueur | Idem |
| Indices demandés par grille | Bouton "indice" pendant une partie | Limiter le nombre d'indices par grille | 90 jours après votre dernière demande, puis suppression automatique |
| État de salon multijoueur | Salons que vous créez ou rejoignez | Synchroniser la grille en temps réel | En mémoire vive uniquement, supprimé 30 minutes après inactivité ou en quittant |
| Mesures d'audience anonymisées (Matomo auto-hébergé) | Pages visitées, événements de jeu (création de partie, indice, résolution), durée, taille de grille | Comprendre comment le service est utilisé pour l'améliorer | 13 mois (recommandation CNIL) |

### Ce que nous **ne** collectons **pas**

- Aucun compte, aucun mot de passe, aucune adresse e-mail.
- Aucun cookie de tracking publicitaire.
- Aucune adresse IP n'est conservée par l'application (Matomo
  l'anonymise au niveau des deux derniers octets avant tout stockage).
- Aucun partage avec des régies publicitaires ou des courtiers en données.

## Base légale

Les traitements ci-dessus reposent sur l'**intérêt légitime** au sens du
RGPD (article 6.1.f) : faire fonctionner un service ludique gratuit et
mesurer son audience de façon anonymisée. La mesure d'audience est
exemptée de consentement par la CNIL (délibération 2020-091) parce que
sa configuration respecte les conditions de l'exemption :

- adresse IP anonymisée,
- absence de recoupement avec d'autres traitements,
- pas de transmission à des tiers,
- pas de profilage individuel.

## Cookies

Bliss n'utilise **pas** de cookies. Le service stocke un identifiant de
session anonyme dans le `localStorage` de votre navigateur (technologie
distincte des cookies). La mesure d'audience Matomo fonctionne en mode
sans cookie.

## Vos droits

Vous disposez des droits prévus par le RGPD :

- **Droit d'accès et de portabilité** (articles 15 et 20) : vos données
  étant minimales et anonymes, l'essentiel est déjà visible dans les
  paramètres du jeu (pseudonyme, identifiant de session). Pour
  l'historique des indices, écrivez à l'adresse ci-dessus.
- **Droit à l'effacement** (article 17) : utilisez le bouton "Effacer
  mes données" dans les paramètres. L'identifiant de session, le
  pseudonyme et l'historique d'indices côté serveur sont supprimés
  immédiatement. Les visites Matomo ne sont pas activement supprimées
  parce qu'elles sont déjà non-attribuables par construction : le
  hachage rotatif quotidien empêche tout recoupement avec d'autres
  jours, et la création d'un nouvel identifiant de session local après
  l'effacement rompt le lien avec les visites du jour en cours. Les
  visites Matomo restent soumises à la fenêtre de conservation de
  13 mois sous forme agrégée et anonyme.
- **Droit d'opposition** : vous pouvez désactiver la mesure d'audience
  via le réglage `Do Not Track` de votre navigateur ; Matomo le
  respecte automatiquement.
- **Droit de réclamation** : vous pouvez saisir la CNIL (cnil.fr) si
  vous estimez que vos données ne sont pas traitées correctement.

## Sous-traitants

Bliss s'appuie sur les sous-traitants suivants. La liste à jour est
maintenue dans
[`docs/privacy/sub-processors.md`](./sub-processors.md).

- **Hetzner Online GmbH** (Allemagne) — hébergement du cluster
  Kubernetes et de la base de données.
- **Cloudflare, Inc.** (États-Unis) — DNS et diffusion de contenu
  statique. Cloudflare voit votre adresse IP lorsqu'elle livre les
  pages du jeu mais ne la transmet pas à Bliss et la traite selon son
  propre Data Processing Addendum (clauses contractuelles types).

Aucune donnée n'est transférée hors UE par Bliss elle-même ; les
serveurs sont situés en Allemagne (Hetzner). Cloudflare opère un réseau
mondial mais maintient des engagements contractuels conformes au RGPD.

## Modifications

Toute évolution de cette politique passe par une Pull Request publique
sur le dépôt du projet, avec un message de commit décrivant le
changement. Les versions précédentes sont consultables dans l'historique
Git.

---

# Privacy Policy (English)

> Reference rendering for `/privacy`. The French version is canonical;
> any divergence is a bug — open an issue.

## In short

Bliss (online French crossword puzzles) collects the minimum needed to
run the service. No accounts, no advertising cookies, no sharing with
advertisers. You can erase your data at any time from the game settings.

## Data controller

Bliss is operated by Colin Auberger, contact: `colin.auberger@gmail.com`.

## Data we process

| Data | Source | Purpose | Retention |
|---|---|---|---|
| `sessionId` (random UUID v7) | Generated by your browser, stored in `localStorage` | Identify your session for multiplayer and count your hint requests | Until you clear browser storage or click "Erase my data" |
| Pseudonym | You pick it (default "Joueur xxxx") | Multiplayer display | Same |
| Hints used per puzzle | "Hint" button during a game | Cap hints per puzzle | 90 days from your last request, then auto-deleted |
| Multiplayer lobby state | Lobbies you create or join | Real-time grid sync | In memory only, dropped 30 min after inactivity or on exit |
| Anonymized audience metrics (self-hosted Matomo) | Pages visited, game events, duration, grid size | Understand usage to improve the service | 13 months (CNIL guidance) |

### What we do **not** collect

- No accounts, no passwords, no email addresses.
- No advertising or tracking cookies.
- No IP addresses retained by the application (Matomo anonymizes the
  last two octets before any storage).
- No sharing with ad networks or data brokers.

## Lawful basis

Processing relies on **legitimate interest** (GDPR Article 6.1.f):
running a free crossword service and measuring its audience anonymously.
Audience measurement is exempt from consent under the French CNIL
deliberation 2020-091 because the configuration meets the exemption
conditions (anonymized IP, no cross-site profiling, no third-party
sharing, no individual profiling).

## Cookies

Bliss does **not** set cookies. The service stores an anonymous session
identifier in your browser's `localStorage` (a technology distinct from
cookies). Matomo audience measurement runs in cookieless mode.

## Your rights

GDPR rights:

- **Access and portability** (Articles 15, 20): your data is minimal and
  visible in game settings (pseudonym, session ID). For the hint
  history, contact the address above.
- **Erasure** (Article 17): use the "Erase my data" button in settings.
  Session ID, pseudonym, and server-side hint history are deleted
  immediately. Matomo visits are not actively deleted because they are
  already non-attributable by design — the daily-rotated hash prevents
  cross-day linkage, and a fresh local session id after the call breaks
  linkage with same-day visits. Matomo data remains under the 13-month
  retention window in aggregate, anonymous form.
  **today's** Matomo visits are removed. Visits from prior days are
  already non-attributable (the hash rotates daily; cross-day linkage is
  impossible by design) and cannot be actively deleted; they remain
  subject to the 13-month retention window.
- **Opt-out**: enable your browser's `Do Not Track` setting; Matomo
  honours it automatically.
- **Complaints**: you may file a complaint with the CNIL (cnil.fr) if
  you believe your data is mishandled.

## Sub-processors

See [`docs/privacy/sub-processors.md`](./sub-processors.md) for the
authoritative list.

- **Hetzner Online GmbH** (Germany) — Kubernetes cluster and database
  hosting.
- **Cloudflare, Inc.** (USA) — DNS and static content delivery.
  Cloudflare sees your IP address when serving game pages but does not
  forward it to Bliss and processes it under its own DPA (Standard
  Contractual Clauses).

Bliss itself does not transfer data outside the EU; servers are in
Germany (Hetzner). Cloudflare runs a global network but maintains
GDPR-compliant contractual safeguards.

## Changes

Every change to this policy goes through a public Pull Request on the
project repository, with a descriptive commit message. Previous versions
are visible in the Git history.
