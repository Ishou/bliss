"""Filtres §8.3 du pipeline de validation."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

# Apostrophe typographique
APO = "’"


@dataclass(frozen=True)
class FilterResult:
    """Résultat d'un filtre : action + raison."""
    action: str           # "accept" | "reject" | "warning"
    reason: str = ""

    @property
    def is_accept(self) -> bool:
        return self.action == "accept"

    @property
    def is_reject(self) -> bool:
        return self.action == "reject"

    @property
    def is_warning(self) -> bool:
        return self.action == "warning"


# Emoji : caractères dans les plages Unicode emoji standard
EMOJI_PATTERN = re.compile(
    r"[\U0001F000-\U0001FFFF\U0001F300-\U0001F9FF"
    r"☀-➿⌀-⏿️‍]"
)
HTML_PATTERN = re.compile(r"<[^>]+>")
MARKDOWN_BOLD = re.compile(r"\*\*[^*]+\*\*")
MARKDOWN_ITALIC = re.compile(r"(?<!\*)\*(?!\*)[^*]+\*(?!\*)")
NON_PRINTABLE = re.compile(r"[\x00-\x08\x0B-\x1F\x7F]")

# Caractères autorisés (filtre 2)
ALLOWED_CHARS = re.compile(
    r"^[\w\s’'.,;:!?()«»\"\-—–]*$",
    re.UNICODE
)

# Stéréotypes IA (préfixes proscrits §6.5)
LLM_PREFIXES = [
    r"Quelqu['’]un qui",
    r"Personne qui",
    r"Action de",
    r"Fait de",
    r"Chose qui sert",
    r"Chose qui",
    r"Celui qui",
    r"Celle qui",
    r"Action consistant",
    r"Manière de",
    r"Objet qui sert",
    r"Élément faisant partie",
    r"Type de",
    r"Sorte de",
    r"Variété de",
    r"Mot désignant",
    r"Terme employé",
    r"Personnage qui",
]
LLM_PREFIX_PATTERN = re.compile(
    r"^(" + "|".join(LLM_PREFIXES) + r")\b",
    re.IGNORECASE
)

# Étiquettes catégorielles génériques (filtre 7)
ETIQUETTES_GENERIQUES = frozenset({
    "animal", "prénom", "plante", "objet", "chose", "personne",
    "mot", "verbe", "adjectif", "lieu", "outil", "terme", "élément",
    "partie", "nom commun", "nom propre",
})

# Stop-words EN (filtre 6, fallback heuristique)
EN_STOPWORDS = frozenset({
    "the", "and", "is", "of", "to", "in", "a", "an", "for", "with",
    "on", "at", "by", "from", "as", "that", "this", "are", "be",
    "or", "it", "not", "but", "have", "has", "had", "will", "would",
    "should", "could", "can", "may", "might", "do", "does", "did",
    "get", "got", "got", "make", "made", "what", "who", "where",
    "when", "why", "how", "you", "your", "they", "them",
})

# Exceptions à l'auto-référence
POS_EXCEPTION_AUTOREF = frozenset({"sigle_abreviation"})
STYLE_EXCEPTION_AUTOREF = frozenset({"cryptique_morphologique"})


def _strip_accents(text: str) -> str:
    """Supprime les diacritiques pour matching insensible aux accents."""
    nfd = unicodedata.normalize("NFD", text)
    return "".join(c for c in nfd if unicodedata.category(c) != "Mn")


def _tokenize_definition(definition: str) -> list[str]:
    """Tokenise la définition selon les séparateurs §8.3 filtre 3."""
    return [t for t in re.split(r"[\s’'\-]+", definition) if t]


def filter_1_typographiques(row: dict) -> FilterResult:
    """Filtre 1 : caractères typographiques stricts (emoji, HTML, markdown, non-printable)."""
    defi = row["definition"]
    if EMOJI_PATTERN.search(defi):
        return FilterResult("reject", "Emoji détecté")
    if HTML_PATTERN.search(defi):
        return FilterResult("reject", "Balise HTML détectée")
    if MARKDOWN_BOLD.search(defi):
        return FilterResult("reject", "Gras markdown détecté")
    if MARKDOWN_ITALIC.search(defi):
        return FilterResult("reject", "Italique markdown détecté")
    if NON_PRINTABLE.search(defi):
        return FilterResult("reject", "Caractère non imprimable")
    return FilterResult("accept")


def filter_2_caracteres_interdits(row: dict) -> FilterResult:
    """Filtre 2 : caractères hors lettres/chiffres/ponctuation standard."""
    defi = unicodedata.normalize("NFC", row["definition"])
    if ALLOWED_CHARS.match(defi):
        return FilterResult("accept")
    # Identifier le premier caractère interdit pour le message
    for c in defi:
        if not re.match(r"[\w\s’'.,;:!?()«»\"\-—–]", c,
                        re.UNICODE):
            return FilterResult(
                "reject",
                f"Caractère interdit : {c!r} (U+{ord(c):04X})"
            )
    return FilterResult("accept")


def filter_3_longueur(row: dict) -> FilterResult:
    """Filtre 3 : longueur en mots et caractères (warning > 8 mots, reject > 12 mots ou > 60 chars)."""
    defi = row["definition"]
    tokens = _tokenize_definition(defi)
    nb_mots = len(tokens)
    nb_chars = len(defi)

    if nb_mots > 12:
        return FilterResult(
            "reject", f"Trop de mots ({nb_mots} > 12)"
        )
    if nb_chars > 60:
        return FilterResult(
            "reject", f"Trop de caractères ({nb_chars} > 60)"
        )
    if nb_mots > 8:
        return FilterResult(
            "warning", f"Long ({nb_mots} mots, > 8)"
        )
    return FilterResult("accept")


def filter_4_stereotypes_ia(row: dict) -> FilterResult:
    """Filtre 4 : préfixes stéréotype IA."""
    defi = row["definition"]
    m = LLM_PREFIX_PATTERN.match(defi)
    if m:
        return FilterResult(
            "reject", f"Préfixe stéréotype IA : « {m.group(1)} »"
        )
    return FilterResult("accept")


def filter_5_auto_reference(row: dict) -> FilterResult:
    """Filtre 5 : auto-référence du mot dans la définition."""
    pos = row["pos"]
    style = row["style"]
    if pos in POS_EXCEPTION_AUTOREF:
        return FilterResult("accept", "Exception : sigle_abreviation")
    if style in STYLE_EXCEPTION_AUTOREF:
        return FilterResult(
            "accept", "Exception : cryptique_morphologique"
        )

    mot_clean = _strip_accents(row["mot"]).lower()
    def_clean = _strip_accents(row["definition"]).lower()
    pattern = re.compile(r"\b" + re.escape(mot_clean) + r"\b")
    if pattern.search(def_clean):
        return FilterResult(
            "reject",
            f"Auto-référence : « {row['mot']} » apparaît dans la définition"
        )
    return FilterResult("accept")


def filter_6_langue_fr(row: dict) -> FilterResult:
    """Filtre 6 : langue française (lingua primary, stopword heuristic fallback)."""
    defi = row["definition"]
    if not defi.strip():
        return FilterResult("accept")

    detector = _get_lingua_detector()
    if detector is not None:
        # Voie primaire : lingua
        try:
            confs = detector.compute_language_confidence_values(defi)
            from lingua import Language  # type: ignore
            fr_score = 0.0
            en_score = 0.0
            for c in confs:
                if c.language == Language.FRENCH:
                    fr_score = c.value
                elif c.language == Language.ENGLISH:
                    en_score = c.value
            if fr_score < 0.3 and en_score > 0.7:
                return FilterResult(
                    "reject",
                    f"Langue non-FR : lingua FR={fr_score:.2f} "
                    f"EN={en_score:.2f}"
                )
            return FilterResult("accept")
        except Exception as e:  # noqa: BLE001
            # En cas d'erreur lingua, fallback heuristique
            pass

    # Voie de secours : heuristique stopwords EN
    tokens = [t.lower() for t in _tokenize_definition(defi)]
    if not tokens:
        return FilterResult("accept")
    en_count = sum(1 for t in tokens if t in EN_STOPWORDS)
    if en_count == 0:
        return FilterResult("accept")
    score_fr = 1.0 - (en_count / len(tokens))
    if score_fr < 0.7:
        return FilterResult(
            "reject",
            f"Langue non-FR (fallback) : {en_count} stopwords EN / "
            f"{len(tokens)} mots (score FR {score_fr:.2f})"
        )
    return FilterResult("accept")


# Cache du détecteur lingua (construction coûteuse, à faire une fois)
_LINGUA_DETECTOR = None
_LINGUA_TRIED = False


def _get_lingua_detector():
    """Retourne le détecteur lingua-language-detector, ou None si indisponible (construit une seule fois)."""
    global _LINGUA_DETECTOR, _LINGUA_TRIED
    if _LINGUA_TRIED:
        return _LINGUA_DETECTOR
    _LINGUA_TRIED = True
    try:
        from lingua import Language, LanguageDetectorBuilder  # type: ignore
        _LINGUA_DETECTOR = (
            LanguageDetectorBuilder
            .from_languages(Language.FRENCH, Language.ENGLISH)
            .build()
        )
    except ImportError:
        _LINGUA_DETECTOR = None
    return _LINGUA_DETECTOR


def filter_7_tautologie(row: dict) -> FilterResult:
    """Filtre 7 : tautologies / définitions vides (étiquette catégorielle nue)."""
    defi = row["definition"].strip().lower()
    if defi in ETIQUETTES_GENERIQUES:
        return FilterResult(
            "reject",
            f"Étiquette catégorielle nue : « {row['definition']} »"
        )
    # Étiquette + 1 qualificatif : warning (heuristique)
    tokens = _tokenize_definition(defi)
    if len(tokens) == 2 and tokens[0] in ETIQUETTES_GENERIQUES:
        return FilterResult(
            "warning",
            f"Étiquette + qualificatif : « {row['definition']} » "
            "(discrimination potentiellement faible)"
        )
    return FilterResult("accept")


def filter_8_llm_juge_mock(row: dict, valid_pos: set[str],
                           valid_categories: set[str],
                           valid_styles: set[str]) -> FilterResult:
    """Filtre 8 : LLM-juge MOCK (cohérence métadonnées + heuristique accord)."""
    pos = row["pos"]
    cat = row["categorie"]
    style = row["style"]
    force = row["force"]

    # Validation enums (reject si invalide)
    if pos not in valid_pos:
        return FilterResult("reject", f"POS invalide : {pos!r}")
    if cat not in valid_categories:
        return FilterResult("reject", f"Catégorie invalide : {cat!r}")
    if style not in valid_styles:
        return FilterResult("reject", f"Style invalide : {style!r}")
    try:
        f_int = int(force)
        if not 1 <= f_int <= 5:
            return FilterResult(
                "reject", f"Force hors [1,5] : {force}"
            )
    except (ValueError, TypeError):
        return FilterResult("reject", f"Force non entière : {force!r}")

    mot = row["mot"]
    defi_lower = row["definition"].lower()
    mot_nfd = _strip_accents(mot).lower()

    return FilterResult("accept")


# seuil 5 chars : ne pas abaisser sans rejouer la variance check (docs/eval/clue-gen-v0.md iter7)

_STEM_LEAK_MIN = 5

# Tokens fonctionnels exclus du stem-leak (calque validate_clue.py)
_STEM_LEAK_FUNCTION_WORDS = frozenset({
    "le", "la", "les", "un", "une", "des", "du", "de", "d",
    "à", "au", "aux", "en", "dans", "sur", "sous", "par", "pour",
    "avec", "sans",
    "et", "ou", "mais", "donc", "car", "ni", "or",
    "qui", "que", "qu", "dont", "où", "quoi",
    "ce", "cet", "cette", "ces", "ceux", "celle", "celui",
    "son", "sa", "ses", "leur", "leurs", "mon", "ma", "mes",
    "ton", "ta", "tes",
    "ne", "pas", "plus", "très", "trop", "peu", "bien", "mal",
    "je", "tu", "il", "elle", "ils", "elles", "on",
    "se", "s", "me", "m", "te", "t", "nous", "vous", "lui", "y",
    "soi",
})

_STEM_LEAK_TOKEN_RE = re.compile(r"[\wÀ-ÿŒœŸ]+", re.UNICODE)


def _longest_common_prefix(a: str, b: str) -> int:
    """Retourne la longueur du plus long préfixe commun à a et b."""
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return i
    return n


def _stem_leak_match(clue: str, target_lemma: str) -> str | None:
    """Retourne le token leakant si LCP ≥ 5 OU sous-chaîne mutuelle, sinon None."""
    target = target_lemma.lower().strip()
    if len(target) < _STEM_LEAK_MIN:
        return None
    for tok in _STEM_LEAK_TOKEN_RE.findall(clue):
        tl = tok.lower()
        if tl in _STEM_LEAK_FUNCTION_WORDS or len(tl) < _STEM_LEAK_MIN:
            continue
        if tl == target:
            # Match exact géré par filter_5 (auto-référence)
            continue
        if _longest_common_prefix(target, tl) >= _STEM_LEAK_MIN:
            return tok
        if tl in target or target in tl:
            return tok
    return None


def filter_9_stem_leak(row: dict) -> FilterResult:
    """Filtre 9 : reject si un token de la définition partage un radical ≥ 5 chars avec le mot."""
    leak = _stem_leak_match(row["definition"], row["mot"])
    if leak is None:
        return FilterResult("accept")
    return FilterResult(
        "reject",
        f"stem-leak : token « {leak} » partage un radical ≥ 5 chars "
        f"avec le mot « {row['mot']} »",
    )


# closed-set by construction — extend only on a concrete logged failure

# Verbes fonctionnels exclus de la recherche de tête (calque validate_clue.py)
_PLEONASM_FUNCTION_WORDS = _STEM_LEAK_FUNCTION_WORDS

_PLEONASM_TOKEN_RE = _STEM_LEAK_TOKEN_RE

# (frozenset de lemmes-tête, regex tail) — verbatim _PLEONASM_RULES
_PLEONASM_RULES: tuple[tuple[frozenset[str], "re.Pattern[str]"], ...] = (
    # X + ensemble : X signifie déjà "joindre / réunir"
    (frozenset({
        "associer", "unir", "joindre", "rejoindre", "réunir",
        "assembler", "rassembler", "regrouper",
        "mêler",
        "lier", "relier", "souder", "fusionner", "conjuguer",
        "marier", "allier", "apparier", "coupler", "accoupler",
        "fédérer", "confédérer",
        "additionner", "ajouter",
        "enchaîner",
    }), re.compile(r"\bensemble\b", re.IGNORECASE)),
    # Directionnel : monter/grimper + en haut / vers le haut
    (frozenset({"monter", "grimper", "gravir", "ascensionner",
                "escalader"}),
     re.compile(r"\b(?:en|vers\s+le)\s+haut\b", re.IGNORECASE)),
    # Directionnel : descendre/tomber + en bas / vers le bas
    (frozenset({"descendre", "tomber", "chuter", "baisser", "abaisser"}),
     re.compile(r"\b(?:en|vers\s+le)\s+bas\b", re.IGNORECASE)),
    # Sortir + dehors / Entrer + dedans
    (frozenset({"sortir"}), re.compile(r"\bdehors\b", re.IGNORECASE)),
    (frozenset({"entrer"}), re.compile(r"\bdedans\b", re.IGNORECASE)),
    # Avancer + en avant / Reculer + en arrière
    (frozenset({"avancer", "progresser"}),
     re.compile(r"\ben\s+avant\b", re.IGNORECASE)),
    (frozenset({"reculer", "rétrograder"}),
     re.compile(r"\ben\s+arrière\b", re.IGNORECASE)),
    # Répétition : re-X + à nouveau
    (frozenset({
        "répéter", "recommencer", "refaire", "redire",
        "reprendre", "réitérer", "réessayer", "relire", "revoir",
    }), re.compile(r"\bà\s+nouveau\b", re.IGNORECASE)),
    # Anticipation : anticiper/prévoir + à l'avance
    (frozenset({"anticiper", "prévoir", "planifier", "préméditer",
                "programmer"}),
     re.compile(r"\bà\s+l[’']avance\b", re.IGNORECASE)),
    # Réciprocité + mutuellement
    (frozenset({"entraider", "s'entraider", "coopérer", "collaborer",
                "s'associer"}),
     re.compile(r"\bmutuellement\b", re.IGNORECASE)),
    # Complétude : remplir/saturer/vider + complètement
    (frozenset({"remplir", "saturer", "vider"}),
     re.compile(r"\bcomplètement\b", re.IGNORECASE)),
)


def _pleonasm_find_head(clue: str) -> str:
    """Retourne le premier token de contenu (hors mots-outils), ou ""."""
    for tok in _PLEONASM_TOKEN_RE.findall(clue):
        if tok.lower() in _PLEONASM_FUNCTION_WORDS:
            continue
        return tok
    return ""


def _pleonasm_head_matches_lemma(head: str, lemma: str) -> bool:
    """Vrai si head == lemma ou inflection plausible (stem-prefix ≥ 3 chars)."""
    h = head.lower()
    l = lemma.lower()
    if h == l:
        return True
    if l.startswith("s'") and h == l[2:]:
        return True
    stem = l
    for suf in ("oir", "er", "ir", "re"):
        if stem.endswith(suf) and len(stem) > len(suf) + 2:
            stem = stem[: -len(suf)]
            break
    if len(stem) < 3:
        return False
    return h.startswith(stem) and len(h) >= len(stem)


def _pleonasm_match(clue: str) -> str | None:
    """Retourne le tail redondant si la clue est pléonastique, sinon None."""
    head = _pleonasm_find_head(clue)
    if not head:
        return None
    head_lower = head.lower()
    for lemmas, tail_re in _PLEONASM_RULES:
        if not any(_pleonasm_head_matches_lemma(head_lower, l)
                   for l in lemmas):
            continue
        m = tail_re.search(clue)
        if m is not None:
            return m.group(0)
    return None


def filter_10_pleonasm(row: dict) -> FilterResult:
    """Filtre 10 : reject si la définition matche un patron pléonastique fermé."""
    match = _pleonasm_match(row["definition"])
    if match is None:
        return FilterResult("accept")
    return FilterResult(
        "reject",
        f"pleonasm : patron fermé matché « {match} »",
    )
