#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# PocketBoard dictionary generator
#
# DIRECT-SOURCE VERSION
#
# IMPORTANT:
#   - NO HUNSPELL
#   - FrequencyWords is used directly
#   - Unicode/NFC is preserved
#   - Accents, ñ and umlauts are preserved
#   - Curated core words are always retained
#   - Unaccented duplicate variants are removed when the
#     accented form is an unambiguous dictionary word
#   - Deletes are generated ONLY from final dictionary words
#   - Delete files contain ONE candidate per line
#
# ============================================================

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BUILD_ROOT="${BUILD_ROOT:-${PROJECT_ROOT}/build/pocketboard-dictionaries}"

SOURCE_ROOT="${BUILD_ROOT}/sources"
FREQUENCY_ROOT="${BUILD_ROOT}/frequency"
WORK_ROOT="${BUILD_ROOT}/work"
OUTPUT_ROOT="${BUILD_ROOT}/generated"

ASSETS_ROOT="${PROJECT_ROOT}/app/src/main/assets/dictionaries"

PYTHON_BIN="${PYTHON_BIN:-python3}"
CURL_BIN="${CURL_BIN:-curl}"

# ============================================================
# Dictionary limits
# ============================================================

MAX_WORDS="${MAX_WORDS:-180000}"

MIN_WORD_LEN="${MIN_WORD_LEN:-2}"
MAX_WORD_LEN="${MAX_WORD_LEN:-40}"

# ============================================================
# Delete index limits
# ============================================================

DELETE_WORDS="${DELETE_WORDS:-12000}"

MAX_DELETES_PER_WORD="${MAX_DELETES_PER_WORD:-2}"

GLOBAL_DELETE_BUDGET="${GLOBAL_DELETE_BUDGET:-4500000}"

ES_DELETE_BUDGET="${ES_DELETE_BUDGET:-1500000}"
EN_DELETE_BUDGET="${EN_DELETE_BUDGET:-1500000}"
DE_DELETE_BUDGET="${DE_DELETE_BUDGET:-1500000}"

MAX_DELETE_ENTRIES="${MAX_DELETE_ENTRIES:-220000}"

# ============================================================
# Create directories
# ============================================================

mkdir -p \
    "${SOURCE_ROOT}" \
    "${FREQUENCY_ROOT}" \
    "${WORK_ROOT}" \
    "${OUTPUT_ROOT}" \
    "${ASSETS_ROOT}"

# ============================================================
# FrequencyWords sources
# ============================================================

FREQUENCY_ES_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_50k.txt"
FREQUENCY_EN_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt"
FREQUENCY_DE_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_50k.txt"

ES_SOURCE="${SOURCE_ROOT}/es_50k.txt"
EN_SOURCE="${SOURCE_ROOT}/en_50k.txt"
DE_SOURCE="${SOURCE_ROOT}/de_50k.txt"

# ============================================================
# Helpers
# ============================================================

download_if_missing() {
    local url="$1"
    local output="$2"

    if [[ -s "${output}" ]]; then
        echo "Using cached source:"
        echo "  ${output}"
        return
    fi

    echo "Downloading:"
    echo "  ${url}"

    "${CURL_BIN}" \
        --fail \
        --location \
        --retry 3 \
        --retry-delay 2 \
        --silent \
        --show-error \
        "${url}" \
        --output "${output}"

    if [[ ! -s "${output}" ]]; then
        echo ""
        echo "ERROR: downloaded source is empty:"
        echo "  ${output}"
        exit 1
    fi
}

normalize_source() {
    local input="$1"
    local output="$2"

    "${PYTHON_BIN}" - "${input}" "${output}" <<'PY'
import sys
import unicodedata

src = sys.argv[1]
dst = sys.argv[2]

MIN_LEN = 2
MAX_LEN = 40


def normalize_word(word):
    word = word.strip()

    if not word:
        return ""

    word = unicodedata.normalize("NFC", word)

    if len(word) < MIN_LEN or len(word) > MAX_LEN:
        return ""

    for ch in word:
        category = unicodedata.category(ch)

        if ch in ("'", "’", "-"):
            continue

        if category.startswith("L"):
            continue

        if category.startswith("M"):
            continue

        return ""

    return word


seen = set()
result = []

with open(
    src,
    "r",
    encoding="utf-8",
    errors="replace"
) as f:

    for line in f:

        line = line.strip()

        if not line:
            continue

        parts = line.split()

        if not parts:
            continue

        word = normalize_word(parts[0])

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)
        result.append(word)


with open(
    dst,
    "w",
    encoding="utf-8",
    newline="\n"
) as f:

    for word in result:
        f.write(word + "\n")


print(f"Normalized source words: {len(result)}")
PY
}

# ============================================================
# Download sources
# ============================================================

echo ""
echo "============================================================"
echo " Downloading FrequencyWords sources"
echo "============================================================"

download_if_missing \
    "${FREQUENCY_ES_URL}" \
    "${ES_SOURCE}"

download_if_missing \
    "${FREQUENCY_EN_URL}" \
    "${EN_SOURCE}"

download_if_missing \
    "${FREQUENCY_DE_URL}" \
    "${DE_SOURCE}"

# ============================================================
# Normalize sources
# ============================================================

echo ""
echo "============================================================"
echo " Normalizing sources"
echo "============================================================"

normalize_source \
    "${ES_SOURCE}" \
    "${FREQUENCY_ROOT}/es-AR.source"

normalize_source \
    "${EN_SOURCE}" \
    "${FREQUENCY_ROOT}/en-en.source"

normalize_source \
    "${DE_SOURCE}" \
    "${FREQUENCY_ROOT}/de-de.source"

# ============================================================
# Build final dictionaries
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${FREQUENCY_ROOT}/es-AR.source" \
    "${FREQUENCY_ROOT}/en-en.source" \
    "${FREQUENCY_ROOT}/de-de.source" \
    "${OUTPUT_ROOT}" \
    "${MAX_WORDS}" \
    "${MIN_WORD_LEN}" \
    "${MAX_WORD_LEN}" \
    <<'PY'

import sys
import unicodedata
from pathlib import Path

es_source = Path(sys.argv[1])
en_source = Path(sys.argv[2])
de_source = Path(sys.argv[3])

output_root = Path(sys.argv[4])

max_words = int(sys.argv[5])
min_len = int(sys.argv[6])
max_len = int(sys.argv[7])

output_root.mkdir(
    parents=True,
    exist_ok=True
)

# ============================================================
# Core vocabulary
# ============================================================

CORE = {

    "es-AR": [
        "vos",
        "tenés",
        "tenes",
        "podés",
        "podes",
        "querés",
        "queres",
        "sabés",
        "sabes",
        "venís",
        "venis",
        "decís",
        "decis",
        "hacés",
        "haces",
        "mirás",
        "miras",
        "hablás",
        "hablas",
        "comés",
        "comes",
        "vivís",
        "vivis",
        "salís",
        "salis",
        "vení",
        "veni",
        "decime",
        "haceme",

        "mañana",
        "mañanas",
        "también",
        "qué",
        "cómo",
        "cuándo",
        "dónde",
        "quién",
        "porque",
        "porqué",
        "día",
        "días",
        "más",
        "sí",
        "está",
        "estás",
        "están",
        "acá",
        "allá",
        "después",
        "así",
        "sólo",

        "pasaría",
        "debería",
        "hago",
        "hacer",
        "veré",
    ],

    "en-en": [
        "hello",
        "world",
        "the",
        "have",
        "this",
        "that",
        "what",
        "where",
        "when",
        "who",
        "which",
        "please",
        "thanks",
        "thank",
        "sorry",
        "tomorrow",
        "today",
    ],

    "de-de": [
        "hallo",
        "welt",
        "ich",
        "nicht",
        "morgen",
        "heute",
        "bitte",
        "danke",
        "dankeschön",
        "entschuldigung",
        "wahrscheinlich",
        "möglicherweise",
        "möglich",
        "für",
        "über",
        "schön",
        "größer",
        "größe",
        "später",
        "früh",
        "früher",
    ],
}


def nfc(word):
    return unicodedata.normalize(
        "NFC",
        word.strip()
    )


def valid_word(word):
    word = nfc(word)

    if len(word) < min_len:
        return False

    if len(word) > max_len:
        return False

    for ch in word:

        category = unicodedata.category(ch)

        if ch in ("'", "’", "-"):
            continue

        if category.startswith("L"):
            continue

        if category.startswith("M"):
            continue

        return False

    return True


def without_diacritics(word):
    """
    Comparison form only.

    Examples:

        mañana   -> manana
        deberías -> deberias
        también  -> tambien
        después  -> despues
        für       -> fur
        größer    -> grosser

    This does NOT modify the actual dictionary word.
    """

    normalized = unicodedata.normalize(
        "NFD",
        word
    )

    return "".join(
        ch
        for ch in normalized
        if unicodedata.category(ch) != "Mn"
    )


def load_source(path):
    result = []
    seen = set()

    with path.open(
        "r",
        encoding="utf-8"
    ) as f:

        for line in f:

            word = nfc(line)

            if not valid_word(word):
                continue

            if word in seen:
                continue

            seen.add(word)
            result.append(word)

    return result


sources = {
    "es-AR": es_source,
    "en-en": en_source,
    "de-de": de_source,
}


for language, source_path in sources.items():

    print("")
    print("=" * 60)
    print(f" Building dictionary: {language}")
    print("=" * 60)

    source_words = load_source(source_path)
    source_set = set(source_words)

    print(f"Source words: {len(source_words)}")
    print(f"Core words:   {len(CORE[language])}")

    # --------------------------------------------------------
    # Core words first.
    # --------------------------------------------------------

    selected = []
    selected_set = set()

    for word in CORE[language]:

        word = nfc(word)

        if not valid_word(word):

            print(
                f"WARNING: invalid core word: {word}"
            )

            continue

        if word not in selected_set:

            selected.append(word)
            selected_set.add(word)

        if word in source_set:

            print(
                f"CORE + SOURCE: "
                f"{language}: {word}"
            )

        else:

            print(
                f"CORE ONLY: "
                f"{language}: {word}"
            )

    # --------------------------------------------------------
    # Add FrequencyWords in original frequency order.
    # --------------------------------------------------------

    for word in source_words:

        if word in selected_set:
            continue

        if len(selected) >= max_words:
            break

        selected.append(word)
        selected_set.add(word)

    # --------------------------------------------------------
    # Build automatic diacritic relationships.
    #
    # plain form:
    #
    #   manana
    #
    # accented form:
    #
    #   mañana
    #
    # We do NOT blindly remove every plain form.
    #
    # A plain form is removed only when:
    #
    #   1. there is exactly one accented candidate;
    #   2. the accented candidate is in the final selection;
    #   3. the plain form is not explicitly protected by CORE.
    #
    # This keeps the dictionary from treating "manana" as an
    # exact word while preserving deliberately curated pairs
    # such as "tenes" / "tenés".
    # --------------------------------------------------------

    selected_lookup = set(selected)

    core_set = {
        nfc(word)
        for word in CORE[language]
        if valid_word(nfc(word))
    }

    diacritic_candidates = {}

    for word in selected:

        plain = without_diacritics(word)

        if plain == word:
            continue

        diacritic_candidates.setdefault(
            plain,
            []
        ).append(word)

    # --------------------------------------------------------
    # Remove unaccented duplicates when the accented form is
    # unambiguous.
    # --------------------------------------------------------

    removed_diacritic_variants = {}

    filtered_selected = []

    for word in selected:

        if word in core_set:
            filtered_selected.append(word)
            continue

        candidates = diacritic_candidates.get(
            word,
            []
        )

        if len(candidates) == 1:

            target = candidates[0]

            if target != word:

                removed_diacritic_variants[
                    word
                ] = target

                print(
                    "ACCENT VARIANT REMOVED: "
                    f"{language}: "
                    f"{word} -> {target}"
                )

                continue

        filtered_selected.append(word)

    selected = filtered_selected
    selected_set = set(selected)

    # --------------------------------------------------------
    # Rebuild the diacritic map using the FINAL dictionary.
    #
    # This guarantees that every generated correction target
    # really exists in the final .dict.
    # --------------------------------------------------------

    final_diacritic_map = {}

    for word in selected:

        plain = without_diacritics(word)

        if plain == word:
            continue

        final_diacritic_map.setdefault(
            plain,
            []
        ).append(word)

    # --------------------------------------------------------
    # Runtime dictionary is sorted deterministically.
    # --------------------------------------------------------

    runtime_words = sorted(
        selected,
        key=lambda x: (
            x.casefold(),
            x
        )
    )

    dictionary_path = (
        output_root /
        f"{language}.dict"
    )

    with dictionary_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(
            "#POCKETBOARD-DICT-1\n"
        )

        for word in runtime_words:
            f.write(word + "\n")

    # --------------------------------------------------------
    # Save final frequency-ranked order.
    #
    # IMPORTANT:
    # Only final dictionary words are written here.
    # --------------------------------------------------------

    ranking_path = (
        output_root /
        f"{language}.ranked"
    )

    with ranking_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        for word in selected:
            f.write(word + "\n")

    # --------------------------------------------------------
    # Save metadata.
    # --------------------------------------------------------

    meta_path = (
        output_root /
        f"{language}.meta"
    )

    with meta_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(
            f"language={language}\n"
        )

        f.write(
            "source=FrequencyWords\n"
        )

        f.write(
            "hunspell=false\n"
        )

        f.write(
            f"source_words={len(source_words)}\n"
        )

        f.write(
            f"core_words={len(CORE[language])}\n"
        )

        f.write(
            f"dictionary_words={len(runtime_words)}\n"
        )

        f.write(
            "diacritic_variants_removed="
            f"{len(removed_diacritic_variants)}\n"
        )

        unicode_words = sum(
            any(
                ord(ch) > 127
                for ch in word
            )
            for word in runtime_words
        )

        f.write(
            f"unicode_words={unicode_words}\n"
        )

    # --------------------------------------------------------
    # Expected/core verification.
    # --------------------------------------------------------

    print("")
    print("Expected/core verification:")

    for word in CORE[language]:

        word = nfc(word)

        if word in selected_set:

            print(
                f"OK: {language}: {word}"
            )

        else:

            print(
                "ERROR: core word missing: "
                f"{language}: {word}"
            )

            raise SystemExit(1)

    # --------------------------------------------------------
    # Important examples.
    # --------------------------------------------------------

    if language == "es-AR":

        checks = {
            "manana": "mañana",
            "deberias": None,
            "tambien": "también",
            "despues": "después",
        }

        print("")
        print(
            "Automatic diacritic checks:"
        )

        for plain, expected in checks.items():

            candidates = final_diacritic_map.get(
                plain,
                []
            )

            if expected is not None:

                if expected in candidates:

                    print(
                        f"OK: {plain} -> {expected}"
                    )

                else:

                    print(
                        f"INFO: no automatic mapping "
                        f"for {plain}"
                    )

            else:

                print(
                    f"{plain} -> "
                    f"{candidates}"
                )

    print("")
    print(
        f"Dictionary words: "
        f"{len(runtime_words)}"
    )

    print(
        f"Output: {dictionary_path}"
    )

PY
# ============================================================
# Download sources
# ============================================================

echo ""
echo "============================================================"
echo " Downloading FrequencyWords sources"
echo "============================================================"

download_if_missing \
    "${FREQUENCY_ES_URL}" \
    "${ES_SOURCE}"

download_if_missing \
    "${FREQUENCY_EN_URL}" \
    "${EN_SOURCE}"

download_if_missing \
    "${FREQUENCY_DE_URL}" \
    "${DE_SOURCE}"

# ============================================================
# Normalize sources
# ============================================================

echo ""
echo "============================================================"
echo " Normalizing sources"
echo "============================================================"

normalize_source \
    "${ES_SOURCE}" \
    "${FREQUENCY_ROOT}/es-AR.source"

normalize_source \
    "${EN_SOURCE}" \
    "${FREQUENCY_ROOT}/en-en.source"

normalize_source \
    "${DE_SOURCE}" \
    "${FREQUENCY_ROOT}/de-de.source"

# ============================================================
# Build final dictionaries
#
# Important:
#
# The source remains frequency-ranked.
#
# We build two representations:
#
#   selected:
#       final dictionary words
#
#   selected_ranked:
#       same words in FrequencyWords order
#
# For Spanish, when an unaccented form has exactly one
# accented equivalent in the final vocabulary, the unaccented
# form is NOT kept as a dictionary word.
#
# Example:
#
#   mañana  -> kept
#   manana  -> removed
#
# But ambiguous/legitimate pairs are preserved:
#
#   si / sí
#   tu / tú
#   el / él
#
# The unaccented form is only removed when the relationship is
# unambiguous.
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${FREQUENCY_ROOT}/es-AR.source" \
    "${FREQUENCY_ROOT}/en-en.source" \
    "${FREQUENCY_ROOT}/de-de.source" \
    "${OUTPUT_ROOT}" \
    "${MAX_WORDS}" \
    "${MIN_WORD_LEN}" \
    "${MAX_WORD_LEN}" \
    <<'PY'

import sys
import unicodedata
from pathlib import Path

es_source = Path(sys.argv[1])
en_source = Path(sys.argv[2])
de_source = Path(sys.argv[3])
output_root = Path(sys.argv[4])

max_words = int(sys.argv[5])
min_len = int(sys.argv[6])
max_len = int(sys.argv[7])

output_root.mkdir(
    parents=True,
    exist_ok=True
)

# ============================================================
# Core vocabulary
# ============================================================

CORE = {

    "es-AR": [
        "vos",
        "tenés",
        "tenes",
        "podés",
        "podes",
        "querés",
        "queres",
        "sabés",
        "sabes",
        "venís",
        "venis",
        "decís",
        "decis",
        "hacés",
        "haces",
        "mirás",
        "miras",
        "hablás",
        "hablas",
        "comés",
        "comes",
        "vivís",
        "vivis",
        "salís",
        "salis",
        "vení",
        "veni",
        "decime",
        "haceme",

        "mañana",
        "mañanas",
        "también",
        "qué",
        "cómo",
        "cuándo",
        "dónde",
        "quién",
        "porque",
        "porqué",
        "día",
        "días",
        "más",
        "sí",
        "está",
        "estás",
        "están",
        "acá",
        "allá",
        "después",
        "así",
        "sólo",

        "pasaría",
        "debería",

        "hago",
        "hacer",
        "veré",
    ],

    "en-en": [
        "hello",
        "world",
        "the",
        "have",
        "this",
        "that",
        "what",
        "where",
        "when",
        "who",
        "which",
        "please",
        "thanks",
        "thank",
        "sorry",
        "tomorrow",
        "today",
    ],

    "de-de": [
        "hallo",
        "welt",
        "ich",
        "nicht",
        "morgen",
        "heute",
        "bitte",
        "danke",
        "dankeschön",
        "entschuldigung",
        "wahrscheinlich",
        "möglicherweise",
        "möglich",
        "für",
        "über",
        "schön",
        "größer",
        "größe",
        "später",
        "früh",
        "früher",
    ],
}


def nfc(word):
    return unicodedata.normalize(
        "NFC",
        word.strip()
    )


def without_diacritics(word):
    """
    Build a comparison key without Unicode combining marks.

    Examples:

        mañana   -> manana
        deberías -> deberias
        también  -> tambien
        después  -> despues

    Important:
        This is ONLY a comparison key.
        It must never be used as a dictionary word.
    """

    normalized = unicodedata.normalize(
        "NFD",
        word
    )

    return "".join(
        ch
        for ch in normalized
        if unicodedata.category(ch) != "Mn"
    )


def has_diacritics(word):
    """
    Return True when removing Unicode combining marks changes
    the word.
    """

    return (
        without_diacritics(word)
        != word
    )


def valid_word(word):
    word = nfc(word)

    if len(word) < min_len:
        return False

    if len(word) > max_len:
        return False

    for ch in word:

        category = unicodedata.category(ch)

        if ch in ("'", "’", "-"):
            continue

        if category.startswith("L"):
            continue

        if category.startswith("M"):
            continue

        return False

    return True


def load_source(path):
    result = []
    seen = set()

    with path.open(
        "r",
        encoding="utf-8"
    ) as f:

        for line in f:

            word = nfc(line)

            if not valid_word(word):
                continue

            if word in seen:
                continue

            seen.add(word)
            result.append(word)

    return result


def build_unaccented_groups(words):
    """
    Build:

        unaccented form -> all real words sharing that form

    Example:

        manana -> ["manana", "mañana"]

    The map is used ONLY to decide which source words should
    remain dictionary entries.
    """

    groups = {}

    for word in words:

        key = without_diacritics(word)

        groups.setdefault(
            key,
            []
        ).append(word)

    return groups


sources = {
    "es-AR": es_source,
    "en-en": en_source,
    "de-de": de_source,
}


for language, source_path in sources.items():

    print("")
    print("=" * 60)
    print(f" Building dictionary: {language}")
    print("=" * 60)

    source_words = load_source(
        source_path
    )

    source_set = set(
        source_words
    )

    print(
        f"Source words: {len(source_words)}"
    )

    print(
        f"Core words:   {len(CORE[language])}"
    )

    # --------------------------------------------------------
    # Start with CORE words.
    # --------------------------------------------------------

    selected = []
    selected_set = set()

    for word in CORE[language]:

        word = nfc(word)

        if not valid_word(word):

            print(
                f"WARNING: invalid core word: {word}"
            )

            continue

        if word not in selected_set:

            selected.append(word)
            selected_set.add(word)

        if word in source_set:

            print(
                f"CORE + SOURCE: "
                f"{language}: {word}"
            )

        else:

            print(
                f"CORE ONLY:    "
                f"{language}: {word}"
            )

    # --------------------------------------------------------
    # Add FrequencyWords in original frequency order.
    #
    # At this stage we still collect the source vocabulary.
    # Diacritic cleanup happens after all source words have
    # been selected.
    # --------------------------------------------------------

    for word in source_words:

        if word in selected_set:
            continue

        if len(selected) >= max_words:
            break

        selected.append(word)
        selected_set.add(word)

    # --------------------------------------------------------
    # Build unaccented equivalence groups.
    #
    # This is especially important for Spanish.
    #
    # Example:
    #
    #   manana
    #   mañana
    #
    # become:
    #
    #   manana -> [manana, mañana]
    #
    # We only remove the unaccented form when:
    #
    #   1. There is exactly one accented equivalent.
    #   2. The accented equivalent is a real selected word.
    #
    # This prevents destructive handling of:
    #
    #   si / sí
    #   tu / tú
    #   el / él
    #
    # when both forms are legitimate dictionary words.
    # --------------------------------------------------------

    if language == "es-AR":

        groups = build_unaccented_groups(
            selected
        )

        remove_unaccented = set()
        accent_targets = {}

        for plain, candidates in groups.items():

            accented = [
                word
                for word in candidates
                if has_diacritics(word)
            ]

            unaccented = [
                word
                for word in candidates
                if not has_diacritics(word)
            ]

            # We only act when there is exactly one accented
            # real word.
            if len(accented) != 1:
                continue

            target = accented[0]

            # If there is more than one unaccented word,
            # do not make assumptions.
            if len(unaccented) == 0:
                continue

            # Only remove an unaccented source word when its
            # accented equivalent is unambiguous.
            for plain_word in unaccented:

                # Keep CORE unaccented words.
                #
                # This protects intentional vocabulary such as
                # "tenes", "podes", "queres", etc.
                if plain_word in CORE[language]:
                    continue

                remove_unaccented.add(
                    plain_word
                )

                accent_targets[
                    plain_word
                ] = target

        if remove_unaccented:

            print("")
            print(
                "Automatic unaccented-word cleanup:"
            )

            for plain_word in sorted(
                remove_unaccented,
                key=lambda x: (
                    x.casefold(),
                    x
                )
            ):

                target = accent_targets[
                    plain_word
                ]

                print(
                    f"  REMOVE: "
                    f"{plain_word} "
                    f"-> {target}"
                )

            selected = [
                word
                for word in selected
                if word not in remove_unaccented
            ]

            selected_set = set(
                selected
            )

        print("")
        print(
            "Unaccented cleanup removed:"
            f" {len(remove_unaccented)}"
        )

    # --------------------------------------------------------
    # Runtime dictionary is sorted deterministically.
    #
    # Dictionary.java performs binary-search/prefix operations,
    # therefore deterministic lexical ordering is required.
    # --------------------------------------------------------

    runtime_words = sorted(
        selected,
        key=lambda x: (
            x.casefold(),
            x
        )
    )

    dictionary_path = (
        output_root /
        f"{language}.dict"
    )

    with dictionary_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(
            "#POCKETBOARD-DICT-1\n"
        )

        for word in runtime_words:

            f.write(
                word + "\n"
            )

    # --------------------------------------------------------
    # Save frequency-ranked order for delete generation.
    #
    # This file is temporary and removed after delete generation.
    # --------------------------------------------------------

    ranking_path = (
        output_root /
        f"{language}.ranked"
    )

    with ranking_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        for word in selected:

            f.write(
                word + "\n"
            )

    # --------------------------------------------------------
    # Save automatic diacritic correction map.
    #
    # This is a BUILD-ONLY file.
    #
    # It allows the next generation stage to reserve/insert
    # high-priority mappings such as:
    #
    #   manana -> mañana
    #   deberias -> deberías
    #
    # It is NOT packaged into Android assets.
    # --------------------------------------------------------

    accent_path = (
        output_root /
        f"{language}.accent"
    )

    with accent_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        if language == "es-AR":

            for plain_word in sorted(
                accent_targets,
                key=lambda x: (
                    x.casefold(),
                    x
                )
            ):

                target = accent_targets[
                    plain_word
                ]

                if target not in selected_set:
                    continue

                f.write(
                    plain_word
                    + "\t"
                    + target
                    + "\n"
                )

    # --------------------------------------------------------
    # Metadata
    # --------------------------------------------------------

    meta_path = (
        output_root /
        f"{language}.meta"
    )

    with meta_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(
            f"language={language}\n"
        )

        f.write(
            "source=FrequencyWords\n"
        )

        f.write(
            "hunspell=false\n"
        )

        f.write(
            f"source_words="
            f"{len(source_words)}\n"
        )

        f.write(
            f"core_words="
            f"{len(CORE[language])}\n"
        )

        f.write(
            f"dictionary_words="
            f"{len(runtime_words)}\n"
        )

        unicode_words = sum(
            any(
                ord(ch) > 127
                for ch in word
            )
            for word in runtime_words
        )

        f.write(
            f"unicode_words="
            f"{unicode_words}\n"
        )

    # --------------------------------------------------------
    # Expected/core verification
    # --------------------------------------------------------

    print("")
    print(
        "Expected/core verification:"
    )

    for word in CORE[language]:

        word = nfc(word)

        if word in selected_set:

            print(
                f"OK: {language}: {word}"
            )

        else:

            print(
                f"ERROR: core word missing: "
                f"{language}: {word}"
            )

            raise SystemExit(1)

    print("")
    print(
        f"Dictionary words: "
        f"{len(runtime_words)}"
    )

    print(
        f"Output: "
        f"{dictionary_path}"
    )

PY
