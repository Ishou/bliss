// unknown values pass through unchanged to survive server-side enum additions before the frontend catches up

const POS_LABELS: Record<string, string> = {
  verbe_infinitif: 'Verbe (infinitif)',
  participe_passe: 'Participe passé',
  participe_present: 'Participe présent',
  nom_commun: 'Nom commun',
  nom_propre: 'Nom propre',
  adjectif: 'Adjectif',
  adverbe: 'Adverbe',
  interjection: 'Interjection',
  mot_outil: 'Mot-outil',
  sigle_abreviation: 'Sigle / abréviation',
  polyvalent: 'Polyvalent',
  autre: 'Autre',
};

// Source of truth for the POS <select> in the correction panel; order matches the Pos enum.
export const POS_OPTIONS: ReadonlyArray<string> = Object.keys(POS_LABELS);

const CATEGORIE_LABELS: Record<string, string> = {
  personne: 'Personne',
  faune_flore: 'Faune / flore',
  geographie: 'Géographie',
  meteo: 'Météo',
  objet: 'Objet',
  nourriture: 'Nourriture',
  corps: 'Corps',
  culture: 'Culture',
  histoire: 'Histoire',
  jeu: 'Jeu',
  sport: 'Sport',
  religion: 'Religion',
  societe: 'Société',
  science: 'Science',
  conceptuel: 'Conceptuel',
  langue: 'Langue',
  action: 'Action',
  qualificatif: 'Qualificatif',
  autre: 'Autre',
};

// Source of truth for the editable target-category multi-select; order matches the Categorie enum.
export const CATEGORIE_OPTIONS: ReadonlyArray<string> = Object.keys(CATEGORIE_LABELS);

const STYLE_LABELS: Record<string, string> = {
  definition_directe: 'Définition directe',
  periphrase: 'Périphrase',
  metonymie: 'Métonymie',
  fonction_role: 'Fonction / rôle',
  calembour: 'Calembour',
  culturel: 'Culturel',
  cryptique: 'Cryptique',
  cryptique_morphologique: 'Cryptique morphologique',
  technique: 'Technique',
};

export function posLabel(pos: string): string {
  return POS_LABELS[pos] ?? pos;
}

export function categorieLabel(categorie: string): string {
  return CATEGORIE_LABELS[categorie] ?? categorie;
}

export function styleLabel(style: string): string {
  return STYLE_LABELS[style] ?? style;
}

export const LOCK_BANNER_TEXT =
  'Campagne en pause — un nouveau lot est en cours d’entraînement. Revenez bientôt pour la suite.';
