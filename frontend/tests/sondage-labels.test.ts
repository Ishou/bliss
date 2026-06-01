import { describe, expect, it } from 'vitest';
import { categorieLabel, posLabel, styleLabel } from '@/ui/components/sondage/labels';

describe('sondage label maps', () => {
  describe('posLabel', () => {
    it.each([
      ['verbe_infinitif', 'Verbe (infinitif)'],
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
      ['personne', 'Personne'],
      ['faune_flore', 'Faune / flore'],
      ['geographie', 'Géographie'],
      ['meteo', 'Météo'],
      ['objet', 'Objet'],
      ['nourriture', 'Nourriture'],
      ['corps', 'Corps'],
      ['culture', 'Culture'],
      ['histoire', 'Histoire'],
      ['jeu', 'Jeu'],
      ['sport', 'Sport'],
      ['religion', 'Religion'],
      ['societe', 'Société'],
      ['science', 'Science'],
      ['conceptuel', 'Conceptuel'],
      ['langue', 'Langue'],
      ['action', 'Action'],
      ['qualificatif', 'Qualificatif'],
      ['autre', 'Autre'],
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
