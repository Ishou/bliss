// unknown values pass through unchanged to survive server-side enum additions before the frontend catches up

const POS_LABELS: Record<string, string> = {
  verbe_infinitif: 'Verbe (infinitif)',
  verbe_conjugue: 'Verbe (conjugué)',
  participe_passe: 'Participe passé',
  participe_present: 'Participe présent',
  nom_commun: 'Nom commun',
  nom_propre: 'Nom propre',
  adjectif: 'Adjectif',
  adverbe: 'Adverbe',
  interjection: 'Interjection',
  mot_outil: 'Mot-outil',
  sigle_abreviation: 'Sigle / abréviation',
  autre: 'Autre',
};

const CATEGORIE_LABELS: Record<string, string> = {
  chemical_symbols: 'Symboles chimiques',
  units: 'Unités',
  celestial_objects: 'Objets célestes',
  nombres: 'Nombres',
  roman_numerals: 'Chiffres romains',
  cardinal_points: 'Points cardinaux',
  cities: 'Villes',
  countries: 'Pays',
  country_codes: 'Codes pays',
  geography: 'Géographie',
  first_names: 'Prénoms',
  titles: 'Titres',
  mythology: 'Mythologie',
  abbreviations: 'Abréviations',
  etranger: 'Étranger',
  expressions: 'Expressions',
  grammar: 'Grammaire',
  interjections: 'Interjections',
  orthographe: 'Orthographe',
  animals: 'Animaux',
  body_parts: 'Parties du corps',
  senses: 'Sens',
  currencies: 'Monnaies',
  organizations: 'Organisations',
  card_game: 'Jeu de cartes',
  games: 'Jeux',
  music_notes: 'Notes de musique',
  autre: 'Autre',
  aliments: 'Aliments',
  vetements: 'Vêtements',
  mobilier_objet: 'Mobilier / objet',
  outils: 'Outils',
  transports: 'Transports',
  materiaux: 'Matériaux',
  professions: 'Professions',
  famille_relations: 'Famille / relations',
  sentiments_etats: 'Sentiments / états',
  nature_paysage: 'Nature / paysage',
  flore: 'Flore',
  meteo_climat: 'Météo / climat',
  temps_duree: 'Temps / durée',
  couleurs: 'Couleurs',
  arts: 'Arts',
};

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
