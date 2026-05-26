import { describe, expect, it } from 'vitest';
import { categorieLabel, posLabel, styleLabel } from '@/ui/components/sondage/labels';

describe('sondage label maps', () => {
  describe('posLabel', () => {
    it.each([
      ['verbe_infinitif', 'Verbe (infinitif)'],
      ['verbe_conjugue', 'Verbe (conjugué)'],
      ['participe_passe', 'Participe passé'],
      ['participe_present', 'Participe présent'],
      ['nom_commun', 'Nom commun'],
      ['nom_propre', 'Nom propre'],
      ['adjectif', 'Adjectif'],
      ['adverbe', 'Adverbe'],
      ['interjection', 'Interjection'],
      ['mot_outil', 'Mot-outil'],
      ['sigle_abreviation', 'Sigle / abréviation'],
      ['autre', 'Autre'],
    ])('maps %s to %s', (wire, label) => {
      expect(posLabel(wire)).toBe(label);
    });

    it('passes unknown values through unchanged', () => {
      expect(posLabel('nouveau_enum_pas_encore_traduit')).toBe('nouveau_enum_pas_encore_traduit');
    });
  });

  describe('categorieLabel', () => {
    it.each([
      ['chemical_symbols', 'Symboles chimiques'],
      ['units', 'Unités'],
      ['celestial_objects', 'Objets célestes'],
      ['nombres', 'Nombres'],
      ['roman_numerals', 'Chiffres romains'],
      ['cardinal_points', 'Points cardinaux'],
      ['cities', 'Villes'],
      ['countries', 'Pays'],
      ['country_codes', 'Codes pays'],
      ['geography', 'Géographie'],
      ['first_names', 'Prénoms'],
      ['titles', 'Titres'],
      ['mythology', 'Mythologie'],
      ['abbreviations', 'Abréviations'],
      ['etranger', 'Étranger'],
      ['expressions', 'Expressions'],
      ['grammar', 'Grammaire'],
      ['interjections', 'Interjections'],
      ['orthographe', 'Orthographe'],
      ['animals', 'Animaux'],
      ['body_parts', 'Parties du corps'],
      ['senses', 'Sens'],
      ['currencies', 'Monnaies'],
      ['organizations', 'Organisations'],
      ['card_game', 'Jeu de cartes'],
      ['games', 'Jeux'],
      ['music_notes', 'Notes de musique'],
      ['autre', 'Autre'],
      ['aliments', 'Aliments'],
      ['vetements', 'Vêtements'],
      ['mobilier_objet', 'Mobilier / objet'],
      ['outils', 'Outils'],
      ['transports', 'Transports'],
      ['materiaux', 'Matériaux'],
      ['professions', 'Professions'],
      ['famille_relations', 'Famille / relations'],
      ['sentiments_etats', 'Sentiments / états'],
      ['nature_paysage', 'Nature / paysage'],
      ['flore', 'Flore'],
      ['meteo_climat', 'Météo / climat'],
      ['temps_duree', 'Temps / durée'],
      ['couleurs', 'Couleurs'],
      ['arts', 'Arts'],
    ])('maps %s to %s', (wire, label) => {
      expect(categorieLabel(wire)).toBe(label);
    });

    it('passes unknown values through unchanged', () => {
      expect(categorieLabel('future_category')).toBe('future_category');
    });
  });

  describe('styleLabel', () => {
    it.each([
      ['definition_directe', 'Définition directe'],
      ['periphrase', 'Périphrase'],
      ['metonymie', 'Métonymie'],
      ['fonction_role', 'Fonction / rôle'],
      ['calembour', 'Calembour'],
      ['culturel', 'Culturel'],
      ['cryptique', 'Cryptique'],
      ['cryptique_morphologique', 'Cryptique morphologique'],
      ['technique', 'Technique'],
    ])('maps %s to %s', (wire, label) => {
      expect(styleLabel(wire)).toBe(label);
    });

    it('passes unknown values through unchanged', () => {
      expect(styleLabel('nouveau_style')).toBe('nouveau_style');
    });
  });
});
