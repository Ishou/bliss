#!/usr/bin/env python3
"""Pipeline principal : orchestre filtres §8.3 + normalisations §8.4."""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent.parent

from . import filters as F  # noqa: E402
from . import normalizers as N  # noqa: E402
from .llm_judge_mock import juge_mock  # noqa: E402


VALID_POS = {
    "verbe_infinitif", "verbe_conjugue", "participe_passe",
    "participe_present", "nom_commun", "nom_propre", "adjectif",
    "adverbe", "interjection", "mot_outil", "sigle_abreviation",
    "autre",
}

VALID_CATEGORIES = {
    "abbreviations", "animals", "autre", "body_parts", "card_game",
    "cardinal_points", "celestial_objects", "chemical_symbols",
    "cities", "countries", "country_codes", "currencies", "etranger",
    "expressions", "first_names", "games", "geography", "grammar",
    "interjections", "music_notes", "mythology", "nombres",
    "organizations", "orthographe", "roman_numerals", "senses",
    "titles", "units",
    "aliments", "vetements", "mobilier_objet", "outils", "transports",
    "materiaux", "professions", "famille_relations", "sentiments_etats",
    "nature_paysage", "flore", "meteo_climat", "temps_duree",
    "couleurs", "arts",
}

# Convention ASCII pour le gold pilote (cf. style_guide §4)
VALID_STYLES = {
    "definition_directe", "periphrase", "metonymie", "fonction_role",
    "calembour", "culturel", "cryptique", "cryptique_morphologique",
    "technique",
}

# Pipeline §8.3 — filtres 1-10 dans l'ordre
PIPELINE_FILTERS = [
    ("filter_1_typographiques", F.filter_1_typographiques, False),
    ("filter_2_caracteres_interdits", F.filter_2_caracteres_interdits, False),
    ("filter_3_longueur", F.filter_3_longueur, False),
    ("filter_4_stereotypes_ia", F.filter_4_stereotypes_ia, False),
    ("filter_5_auto_reference", F.filter_5_auto_reference, False),
    ("filter_6_langue_fr", F.filter_6_langue_fr, False),
    ("filter_7_tautologie", F.filter_7_tautologie, False),
    # filter_8 a besoin des enums valides, traité spécialement
    ("filter_8_llm_juge_mock", F.filter_8_llm_juge_mock, True),
    ("filter_9_stem_leak", F.filter_9_stem_leak, False),
    ("filter_10_pleonasm", F.filter_10_pleonasm, False),
]


def traiter_ligne(row: dict) -> dict:
    """Applique tous les filtres §8.3 puis les normalisations §8.4 sur une ligne."""
    reasons: list[str] = []
    warnings: list[str] = []
    rejected = False
    filter_traces: dict[str, dict] = {}

    # === Filtres §8.3 ===
    for name, fn, needs_enums in PIPELINE_FILTERS:
        if needs_enums:
            result = fn(row, VALID_POS, VALID_CATEGORIES, VALID_STYLES)
        else:
            result = fn(row)

        filter_traces[name] = {
            "action": result.action,
            "reason": result.reason,
        }

        if result.is_reject:
            rejected = True
            reasons.append(f"{name}: {result.reason}")
        elif result.is_warning:
            warnings.append(f"{name}: {result.reason}")

    # === Normalisations §8.4 (seulement si pas rejeté) ===
    normalizations_applied: list[str] = []
    def_norm = row["definition"]
    if not rejected:
        def_norm, normalizations_applied = N.normaliser_tout(row["definition"])

    # Statut final
    if rejected:
        status = "reject"
    elif warnings:
        status = "accept_with_warning"
    else:
        status = "accept"

    return {
        **row,
        "pipeline_status": status,
        "pipeline_reasons": ";".join(reasons) if reasons else (
            ";".join(warnings) if warnings else ""
        ),
        "pipeline_normalizations": ",".join(normalizations_applied),
        "definition_normalisee": def_norm,
        "_traces": filter_traces,
        "_warnings": warnings,
    }


def charger_csv(path: Path) -> list[dict]:
    """Charge un CSV ; / et retourne une liste de dicts."""
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f, delimiter=";")
        return list(reader)


def ecrire_csv_sortie(rows: list[dict], path: Path) -> None:
    """Écrit le CSV de sortie avec colonnes additionnelles."""
    if not rows:
        return
    # Schéma : colonnes originales + 3 colonnes pipeline
    base_cols = ["mot", "definition", "pos", "categorie", "style",
                 "force", "longueur", "source", "meta"]
    extra_cols = ["pipeline_status", "pipeline_reasons",
                  "pipeline_normalizations"]
    header = base_cols + extra_cols

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";", quoting=csv.QUOTE_MINIMAL,
                       lineterminator="\n")
        w.writerow(header)
        for r in rows:
            row_out = [r.get(c, "") for c in header]
            w.writerow(row_out)


def afficher_console(rows: list[dict], stats: dict) -> None:
    """Affiche les résultats du pipeline en console."""
    print("=" * 72)
    print("PIPELINE — résultats sur le gold pilote")
    print("=" * 72)
    print(f"\n>> Lignes traitées : {stats['total']}")
    print(f"   Accept (sans warning) : {stats['accept']}")
    print(f"   Accept avec warning   : {stats['accept_with_warning']}")
    print(f"   Reject                : {stats['reject']}")

    print("\n>> Stats par filtre §8.3 :")
    for name in [n for n, _, _ in PIPELINE_FILTERS]:
        c = stats["par_filtre"][name]
        if c["reject"] == 0 and c["warning"] == 0:
            print(f"   {name:35s}  accept × {c['accept']} (clean)")
        else:
            print(f"   {name:35s}  accept × {c['accept']}, "
                  f"warning × {c['warning']}, reject × {c['reject']}")

    print("\n>> Stats normalisations §8.4 (lignes affectées) :")
    if not stats["normalizations"]:
        print("   Aucune normalisation appliquée (tout déjà propre).")
    else:
        for norm_name, count in sorted(stats["normalizations"].items(),
                                       key=lambda kv: -kv[1]):
            print(f"   {norm_name:35s}  {count} lignes")

    # Cas problématiques
    problemes = [r for r in rows if r["pipeline_status"] != "accept"]
    print(f"\n>> Cas problématiques ({len(problemes)}) :")
    if not problemes:
        print("   Aucun. Gold pilote propre ✓")
    else:
        for r in problemes[:10]:
            print(f"   [{r['pipeline_status']}] {r['mot']:10s} "
                  f"« {r['definition']} »")
            print(f"      → {r['pipeline_reasons']}")
        if len(problemes) > 10:
            print(f"   ... et {len(problemes) - 10} autres")


def generer_rapport(rows: list[dict], stats: dict,
                    input_path: Path) -> str:
    """Construit le rapport markdown."""
    lines: list[str] = []
    w = lines.append

    w("# Rapport pipeline — gold pilote v1")
    w("")
    w("## Métadonnées")
    w("")
    w(f"- Date du test : {date.today().isoformat()}")
    w(f"- Fichier d'entrée : `{input_path.relative_to(ROOT)}`")
    w(f"- Pipeline : §8.3 (8 filtres) + §8.4 (8 normalisations)")
    w("- Filtre 6 (langue) : lingua-language-detector (primary) + "
      "stopwords EN (fallback si lingua indisponible)")
    w("")

    # Résumé exécutif
    w("## Résumé exécutif")
    w("")
    nb_reject = stats["reject"]
    nb_warning = stats["accept_with_warning"]
    nb_accept = stats["accept"]
    total = stats["total"]
    if nb_reject == 0 and nb_warning == 0:
        w(f"Le gold pilote ({total} lignes) passe la pipeline sans "
          "aucun reject ni warning. La base est propre et la pipeline "
          "calibrée juste. Validation à 100 %.")
    elif nb_reject == 0:
        w(f"Le gold pilote ({total} lignes) passe la pipeline sans "
          f"reject. {nb_warning} ligne(s) en warning à examiner. "
          "La base est globalement propre.")
    else:
        w(f"Le gold pilote ({total} lignes) génère **{nb_reject} "
          f"reject(s)** et {nb_warning} warning(s). À examiner pour "
          "déterminer si la pipeline doit être assouplie ou si le gold "
          "doit être corrigé.")
    w("")

    # Analyse par filtre
    w("## Analyse par filtre §8.3")
    w("")
    w("| Filtre | Accept | Warning | Reject |")
    w("|---|---:|---:|---:|")
    for name in [n for n, _, _ in PIPELINE_FILTERS]:
        c = stats["par_filtre"][name]
        w(f"| `{name}` | {c['accept']} | {c['warning']} | {c['reject']} |")
    w("")

    # Analyse par normalisation
    w("## Analyse par normalisation §8.4")
    w("")
    if not stats["normalizations"]:
        w("_Aucune normalisation appliquée — le gold est déjà au format "
          "canonique._")
    else:
        w("| Normalisation | Lignes affectées |")
        w("|---|---:|")
        for norm_name, count in sorted(stats["normalizations"].items(),
                                       key=lambda kv: -kv[1]):
            w(f"| `{norm_name}` | {count} |")
    w("")

    # Cas problématiques détaillés
    w("## Cas problématiques détaillés")
    w("")
    problemes = [r for r in rows if r["pipeline_status"] != "accept"]
    if not problemes:
        w("_Aucun cas problématique._ Tout le gold pilote est accepté "
          "proprement par la pipeline.")
    else:
        w("| # | Mot | Définition | Statut | Raisons |")
        w("|---|---|---|---|---|")
        for i, r in enumerate(problemes, 1):
            w(f"| {i} | {r['mot']} | « {r['definition']} » | "
              f"{r['pipeline_status']} | {r['pipeline_reasons']} |")
    w("")

    # Recommandations
    w("## Recommandations")
    w("")
    if nb_reject == 0 and nb_warning == 0:
        w("- **Pipeline validée** : peut être déployée telle quelle pour "
          "le filtrage de productions synthétiques ultérieures.")
        w("- **Gold pilote validé** : propre, utilisable comme référence "
          "few-shot et calibration LLM-juge.")
    elif nb_reject == 0 and nb_warning > 0:
        w(f"- **Pipeline calibrée correctement** : {nb_warning} "
          "warning(s) sont des cas frontière acceptables, pas des "
          "erreurs.")
        w("- **Gold pilote** : examiner les lignes en warning pour "
          "décider de les laisser telles quelles (cas légitimes) ou "
          "les retravailler.")
    else:
        w(f"- **{nb_reject} reject(s) sur du gold** : à investiguer. "
          "Soit la pipeline est trop stricte, soit le gold contient "
          "des erreurs à corriger.")
        w("- Examiner ligne par ligne et décider : ajuster la pipeline "
          "(assouplir un seuil, ajouter une exception) ou corriger le "
          "gold pour respecter §1-§6.")
    w("")

    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        default="data/seed/gold_pilot_v1.csv",
        help="Chemin du CSV d'entrée"
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Écrire les fichiers de sortie (sans, mode preview)"
    )
    args = parser.parse_args(argv)

    input_path = ROOT / args.input
    if not input_path.exists():
        print(f"ERREUR : fichier introuvable : {input_path}",
              file=sys.stderr)
        return 1

    # Chargement
    rows = charger_csv(input_path)
    print(f"Chargé : {len(rows)} lignes depuis "
          f"{input_path.relative_to(ROOT)}\n")

    # Traitement
    rows_out: list[dict] = []
    stats = {
        "total": 0,
        "accept": 0,
        "accept_with_warning": 0,
        "reject": 0,
        "par_filtre": defaultdict(lambda: Counter()),
        "normalizations": Counter(),
    }
    for row in rows:
        out = traiter_ligne(row)
        rows_out.append(out)
        stats["total"] += 1
        stats[out["pipeline_status"]] += 1
        # Traces par filtre
        for fname, trace in out["_traces"].items():
            stats["par_filtre"][fname][trace["action"]] += 1
        # Normalisations
        for n in out["pipeline_normalizations"].split(","):
            if n:
                stats["normalizations"][n] += 1

    # Affichage console
    afficher_console(rows_out, stats)

    if not args.apply:
        print("\nMode preview — relance avec --apply pour écrire le "
              "rapport et le CSV de sortie.")
        return 0

    # Écriture des sorties
    csv_out_path = ROOT / "data" / "seed" / "gold_pilot_v1_pipeline.csv"
    md_out_path = ROOT / "docs" / "pipeline_test_pilot_v1.md"

    ecrire_csv_sortie(rows_out, csv_out_path)
    print(f"\nCSV de sortie écrit : {csv_out_path.relative_to(ROOT)}")

    rapport = generer_rapport(rows_out, stats, input_path)
    md_out_path.parent.mkdir(parents=True, exist_ok=True)
    md_out_path.write_text(rapport, encoding="utf-8")
    print(f"Rapport markdown écrit : {md_out_path.relative_to(ROOT)}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
