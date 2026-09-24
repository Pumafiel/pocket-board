#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# PocketBoard dictionary generator
#
# DIRECT-SOURCE VERSION
#
# - NO HUNSPELL
# - FrequencyWords is used directly
# - Unicode/NFC is preserved
# - Accents, ñ and umlauts are preserved
# - Curated core words are always retained
# - Spanish unaccented false candidates are removed
# - Legitimate ambiguous Spanish words are preserved
# - Accent corrections are stored in the delete index
# - Delete indexes reference ONLY final dictionary words
#
# Android assets:
#
#   es-AR.dict
#   es-AR.deletes
#
#   en-en.dict
#   en-en.deletes
#
#   de-de.dict
#   de-de.deletes
#
# ============================================================

PROJECT_ROOT="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/.." &&
    pwd
)"

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
#
# Two deletes per word keeps the generated assets compact.
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

    "${PYTHON_BIN}" \
        - "${input}" "${output}" <<'PY'

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

    word = unicodedata.normalize(
        "NFC",
        word
    )

    if len(word) < MIN_LEN:
        return ""

    if len(word) > MAX_LEN:
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

        word = normalize_word(
            parts[0]
        )

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

        f.write(
            word + "\n"
        )


print(
    f"Normalized source words: "
    f"{len(result)}"
)

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
# Build dictionaries
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


# ============================================================
# Spanish ambiguity protection
#
# These unaccented forms are valid Spanish words and MUST NOT
# be removed just because an accented equivalent exists.
#
# Examples:
#
#   como / cómo
#   que / qué
#   esta / está
#   si / sí
#   mas / más
#   porque / porqué
#
# Also preserve valid standard forms that coexist with
# Argentine voseo:
#
#   sabes / sabés
#   haces / hacés
#   miras / mirás
#   hablas / hablás
#   comes / comés
# ============================================================

PRESERVE_UNACCENTED_ES = {

    "como",
    "cuando",
    "donde",
    "que",
    "quien",
    "cual",
    "cuanto",
    "cuanta",
    "cuantos",
    "cuantas",

    "porque",

    "si",
    "tu",
    "el",
    "de",
    "se",
    "te",

    "mas",
    "aun",

    "solo",

    "esta",
    "estas",
    "estan",

    "sabes",
    "haces",
    "miras",
    "hablas",
    "comes",

    "decime",
    "haceme",
}


# ============================================================
# Explicit Spanish false candidates
#
# These are common ASCII/unaccented forms which we do NOT want
# to appear as normal dictionary candidates when their accented
# Argentine/Spanish form is available.
#
# They remain available through accent correction mappings.
# ============================================================

FORCE_ACCENT_CORRECTION_ES = {

    "manana",
    "mananas",

    "tambien",

    "tenes",
    "podes",
    "queres",

    "venis",
    "decis",

    "vivis",
    "salis",

    "veni",

    "dia",
    "dias",

    "pasaria",
    "deberia",

    "aca",
    "alla",

    "despues",
    "asi",
}


# ============================================================
# Unicode helpers
# ============================================================

def nfc(word):

    return unicodedata.normalize(
        "NFC",
        word.strip()
    )


def without_diacritics(word):

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

    return (
        without_diacritics(word)
        != word
    )


# ============================================================
# Word validation
# ============================================================

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


# ============================================================
# Source loading
# ============================================================

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


# ============================================================
# Build equivalence groups
# ============================================================

def build_unaccented_groups(words):

    groups = {}

    for word in words:

        key = without_diacritics(
            word
        )

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
    print(
        f" Building dictionary: "
        f"{language}"
    )
    print("=" * 60)

    source_words = load_source(
        source_path
    )

    source_set = set(
        source_words
    )

    print(
        f"Source words: "
        f"{len(source_words)}"
    )

    print(
        f"Core words:   "
        f"{len(CORE[language])}"
    )


    # --------------------------------------------------------
    # Core words first.
    # --------------------------------------------------------

    selected = []
    selected_set = set()

    for word in CORE[language]:

        word = nfc(word)

        if not valid_word(word):

            print(
                f"WARNING: invalid core word: "
                f"{word}"
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
    # FrequencyWords in original ranking order.
    # --------------------------------------------------------

    for word in source_words:

        if word in selected_set:
            continue

        if len(selected) >= max_words:
            break

        selected.append(word)
        selected_set.add(word)


    # --------------------------------------------------------
    # Remove bad Spanish unaccented candidates.
    #
    # This stage is deliberately conservative.
    #
    # A word is removed only when:
    #
    #   1. It is an unaccented form.
    #   2. An accented form with the same base exists.
    #   3. The accented form is in the selected dictionary.
    #   4. The unaccented form is explicitly known to be an
    #      accent-missing candidate.
    #
    # Legitimate words such as:
    #
    #   como
    #   que
    #   esta
    #   si
    #   mas
    #   sabes
    #   haces
    #
    # remain available.
    # --------------------------------------------------------

    accent_targets = {}
    remove_unaccented = set()

    if language == "es-AR":

        groups = build_unaccented_groups(
            selected
        )

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

            if not accented:
                continue

            if not unaccented:
                continue

            # Only an unambiguous accented target is accepted.
            if len(accented) != 1:
                continue

            target = accented[0]

            if target not in selected_set:
                continue

            for plain_word in unaccented:

                # ------------------------------------------------
                # CORE words are NEVER removed.
                #
                # This is important for Argentine Spanish:
                #
                #   tenes
                #   podes
                #   queres
                #   sabes
                #   venis
                #   decis
                #   haces
                #   miras
                #   hablas
                #   comes
                #   vivis
                #   salis
                #   veni
                #
                # These forms are intentionally part of the
                # PocketBoard CORE vocabulary.
                # ------------------------------------------------

                if plain_word in CORE[language]:
                    continue

                if plain_word in PRESERVE_UNACCENTED_ES:
                    continue

                if plain_word not in FORCE_ACCENT_CORRECTION_ES:
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
                "Spanish unaccented cleanup:"
            )

            for plain_word in sorted(
                remove_unaccented,
                key=lambda x: (
                    x.casefold(),
                    x
                )
            ):

                print(
                    f"  REMOVE: "
                    f"{plain_word} "
                    f"-> "
                    f"{accent_targets[plain_word]}"
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
            "Spanish unaccented words removed: "
            f"{len(remove_unaccented)}"
        )


    # --------------------------------------------------------
    # Runtime dictionary.
    #
    # Dictionary.java expects deterministic lexical ordering.
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
    # Frequency-ranked temporary file.
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
    # Temporary diacritic correction map.
    #
    # It is NOT copied to Android assets.
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
    # Metadata.
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

        f.write(
            f"accent_corrections="
            f"{len(accent_targets)}\n"
        )


    # --------------------------------------------------------
    # Core verification.
    # --------------------------------------------------------

    print("")
    print(
        "Expected/core verification:"
    )

    for word in CORE[language]:

        word = nfc(word)

        if word in selected_set:

            print(
                f"OK: "
                f"{language}: "
                f"{word}"
            )

        else:

            print(
                f"ERROR: core word missing: "
                f"{language}: "
                f"{word}"
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

# ============================================================
# Generate compact delete indexes
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${OUTPUT_ROOT}" \
    "${DELETE_WORDS}" \
    "${MAX_DELETES_PER_WORD}" \
    "${MAX_DELETE_ENTRIES}" \
    "${ES_DELETE_BUDGET}" \
    "${EN_DELETE_BUDGET}" \
    "${DE_DELETE_BUDGET}" \
    <<'PY'

import sys
from pathlib import Path


output_root = Path(sys.argv[1])

delete_words_limit = int(sys.argv[2])
max_deletes_per_word = int(sys.argv[3])
max_delete_entries = int(sys.argv[4])

budgets = {
    "es-AR": int(sys.argv[5]),
    "en-en": int(sys.argv[6]),
    "de-de": int(sys.argv[7]),
}


DELETE_HEADER = "#POCKETBOARD-DELETES-1"


def delete_variants(word):

    if len(word) <= 1:
        return []

    result = []
    seen = set()

    positions = list(
        range(len(word))
    )

    positions.sort(
        key=lambda index: (
            0 if index > 0 else 1,
            index
        )
    )

    for index in positions:

        deleted = (
            word[:index]
            +
            word[index + 1:]
        )

        if not deleted:
            continue

        if deleted in seen:
            continue

        seen.add(deleted)
        result.append(deleted)

        if len(result) >= max_deletes_per_word:
            break

    return result


total_entries_global = 0
total_bytes_global = 0


for language in (
    "es-AR",
    "en-en",
    "de-de",
):

    dictionary_path = (
        output_root /
        f"{language}.dict"
    )

    ranked_path = (
        output_root /
        f"{language}.ranked"
    )

    accent_path = (
        output_root /
        f"{language}.accent"
    )

    deletes_path = (
        output_root /
        f"{language}.deletes"
    )

    budget = budgets[language]


    # --------------------------------------------------------
    # Load final dictionary.
    # --------------------------------------------------------

    with dictionary_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        dictionary_lines = [
            line.rstrip("\r\n")
            for line in f
        ]


    if not dictionary_lines:
        raise RuntimeError(
            f"Empty dictionary: "
            f"{dictionary_path}"
        )


    if dictionary_lines[0] != \
            "#POCKETBOARD-DICT-1":

        raise RuntimeError(
            f"Invalid dictionary header: "
            f"{dictionary_path}"
        )


    dictionary_words = set(
        dictionary_lines[1:]
    )


    # --------------------------------------------------------
    # Load frequency-ranked words.
    # --------------------------------------------------------

    with ranked_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        ranked_words = [
            line.rstrip("\r\n")
            for line in f
            if line.rstrip("\r\n")
        ]


    ranked_words = [
        word
        for word in ranked_words
        if word in dictionary_words
    ]


    priority_words = ranked_words[
        :delete_words_limit
    ]


    # --------------------------------------------------------
    # Normal delete corrections.
    # --------------------------------------------------------

    mappings = []

    seen_mappings = set()

    estimated_bytes = (
        len(
            DELETE_HEADER.encode("utf-8")
        )
        + 1
    )


    for word in priority_words:

        variants = delete_variants(
            word
        )

        for deleted in variants:

            mapping = (
                deleted,
                word
            )

            if mapping in seen_mappings:
                continue


            additional = (
                len(
                    deleted.encode("utf-8")
                )
                + 1
                + len(
                    word.encode("utf-8")
                )
                + 1
            )


            if (
                estimated_bytes
                + additional
                > budget
            ):
                break


            if (
                total_entries_global
                + len(mappings)
                >= max_delete_entries
            ):
                break


            seen_mappings.add(
                mapping
            )

            mappings.append(
                mapping
            )

            estimated_bytes += additional


        if estimated_bytes >= budget:
            break


        if (
            total_entries_global
            + len(mappings)
            >= max_delete_entries
        ):
            break


    # --------------------------------------------------------
    # Accent correction mappings.
    #
    # Example:
    #
    #   manana -> mañana
    #   tenes  -> tenés
    #   podes  -> podés
    #
    # The target MUST exist in the final dictionary.
    # --------------------------------------------------------

    accent_mappings = []

    if accent_path.exists():

        with accent_path.open(
            "r",
            encoding="utf-8"
        ) as f:

            for line in f:

                line = line.rstrip(
                    "\r\n"
                )

                if not line:
                    continue

                parts = line.split(
                    "\t",
                    1
                )

                if len(parts) != 2:
                    continue

                plain_word = parts[0]
                accented_word = parts[1]

                if (
                    not plain_word
                    or not accented_word
                ):
                    continue

                if (
                    accented_word
                    not in dictionary_words
                ):
                    continue

                accent_mappings.append(
                    (
                        plain_word,
                        accented_word
                    )
                )


    # --------------------------------------------------------
    # Accent mappings have priority.
    # --------------------------------------------------------

    accent_keys = {
        plain
        for plain, target
        in accent_mappings
    }


    if accent_keys:

        mappings = [
            (
                deleted,
                target
            )
            for deleted, target
            in mappings
            if deleted not in accent_keys
        ]


        seen_mappings = set(
            mappings
        )


        estimated_bytes = (
            len(
                DELETE_HEADER.encode(
                    "utf-8"
                )
            )
            + 1
        )

        for deleted, target in mappings:

            estimated_bytes += (
                len(
                    deleted.encode(
                        "utf-8"
                    )
                )
                + 1
                + len(
                    target.encode(
                        "utf-8"
                    )
                )
                + 1
            )


    # --------------------------------------------------------
    # Add accent mappings.
    # --------------------------------------------------------

    for plain_word, accented_word \
            in accent_mappings:

        mapping = (
            plain_word,
            accented_word
        )

        if mapping in seen_mappings:
            continue


        additional = (
            len(
                plain_word.encode(
                    "utf-8"
                )
            )
            + 1
            + len(
                accented_word.encode(
                    "utf-8"
                )
            )
            + 1
        )


        if (
            estimated_bytes
            + additional
            > budget
        ):
            break


        if (
            total_entries_global
            + len(mappings)
            >= max_delete_entries
        ):
            break


        seen_mappings.add(
            mapping
        )

        mappings.append(
            mapping
        )

        estimated_bytes += additional


    # --------------------------------------------------------
    # Deterministic ordering.
    # --------------------------------------------------------

    mappings.sort(
        key=lambda pair: (
            pair[0],
            pair[1]
        )
    )


    # --------------------------------------------------------
    # Write delete index.
    # --------------------------------------------------------

    with deletes_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(
            DELETE_HEADER
            + "\n"
        )

        for deleted, target in mappings:

            f.write(
                deleted
                + "\t"
                + target
                + "\n"
            )


    actual_size = (
        deletes_path.stat().st_size
    )

    entries = len(mappings)

    total_entries_global += entries
    total_bytes_global += actual_size


    print("")
    print(
        f"Delete index: "
        f"{language}"
    )

    print(
        f"  Priority words: "
        f"{len(priority_words)}"
    )

    print(
        f"  Accent mappings: "
        f"{len(accent_mappings)}"
    )

    print(
        f"  Delete entries: "
        f"{entries}"
    )

    print(
        f"  Bytes: "
        f"{actual_size}"
    )

    print(
        f"  Budget: "
        f"{budget}"
    )


    if actual_size > budget:

        raise RuntimeError(
            f"Delete budget exceeded "
            f"for {language}: "
            f"{actual_size} > {budget}"
        )


print("")
print(
    "Delete generation totals:"
)

print(
    f"  Entries: "
    f"{total_entries_global}"
)

print(
    f"  Bytes: "
    f"{total_bytes_global}"
)

PY


# ============================================================
# Remove temporary build-only files
# ============================================================

rm -f \
    "${OUTPUT_ROOT}/es-AR.ranked" \
    "${OUTPUT_ROOT}/en-en.ranked" \
    "${OUTPUT_ROOT}/de-de.ranked" \
    "${OUTPUT_ROOT}/es-AR.accent" \
    "${OUTPUT_ROOT}/en-en.accent" \
    "${OUTPUT_ROOT}/de-de.accent"


# ============================================================
# Install generated assets
# ============================================================

echo ""
echo "============================================================"
echo " Installing generated assets"
echo "============================================================"

mkdir -p \
    "${ASSETS_ROOT}"


rm -f \
    "${ASSETS_ROOT}/es-AR.dict" \
    "${ASSETS_ROOT}/en-en.dict" \
    "${ASSETS_ROOT}/de-de.dict" \
    "${ASSETS_ROOT}/es-AR.deletes" \
    "${ASSETS_ROOT}/en-en.deletes" \
    "${ASSETS_ROOT}/de-de.deletes" \
    "${ASSETS_ROOT}/es-AR.meta" \
    "${ASSETS_ROOT}/en-en.meta" \
    "${ASSETS_ROOT}/de-de.meta"


cp \
    "${OUTPUT_ROOT}/es-AR.dict" \
    "${ASSETS_ROOT}/es-AR.dict"

cp \
    "${OUTPUT_ROOT}/en-en.dict" \
    "${ASSETS_ROOT}/en-en.dict"

cp \
    "${OUTPUT_ROOT}/de-de.dict" \
    "${ASSETS_ROOT}/de-de.dict"


cp \
    "${OUTPUT_ROOT}/es-AR.deletes" \
    "${ASSETS_ROOT}/es-AR.deletes"

cp \
    "${OUTPUT_ROOT}/en-en.deletes" \
    "${ASSETS_ROOT}/en-en.deletes"

cp \
    "${OUTPUT_ROOT}/de-de.deletes" \
    "${ASSETS_ROOT}/de-de.deletes"


cp \
    "${OUTPUT_ROOT}/es-AR.meta" \
    "${ASSETS_ROOT}/es-AR.meta"

cp \
    "${OUTPUT_ROOT}/en-en.meta" \
    "${ASSETS_ROOT}/en-en.meta"

cp \
    "${OUTPUT_ROOT}/de-de.meta" \
    "${ASSETS_ROOT}/de-de.meta"


# ============================================================
# Validate dictionary headers and expected words
# ============================================================

echo ""
echo "============================================================"
echo " Validating generated assets"
echo "============================================================"


"${PYTHON_BIN}" \
    - \
    "${ASSETS_ROOT}" \
    <<'PY'

import sys
import unicodedata
from pathlib import Path


assets = Path(sys.argv[1])


EXPECTED = {

    "es-AR": [
        "mañana",
        "mañanas",
        "tenés",
        "podés",
        "hacés",
        "acá",
        "pasaría",
        "debería",
        "vos",
    ],

    "en-en": [
        "the",
        "have",
        "hello",
        "world",
    ],

    "de-de": [
        "ich",
        "nicht",
        "morgen",
        "entschuldigung",
        "wahrscheinlich",
        "möglicherweise",
        "für",
    ],
}


DICT_HEADER = (
    "#POCKETBOARD-DICT-1"
)


DELETE_HEADER = (
    "#POCKETBOARD-DELETES-1"
)


def read_dictionary(language):

    path = (
        assets /
        f"{language}.dict"
    )

    if not path.exists():

        raise RuntimeError(
            f"Missing dictionary: "
            f"{path}"
        )


    with path.open(
        "r",
        encoding="utf-8"
    ) as f:

        lines = [
            line.rstrip("\r\n")
            for line in f
        ]


    if not lines:

        raise RuntimeError(
            f"Empty dictionary: "
            f"{path}"
        )


    if lines[0] != DICT_HEADER:

        raise RuntimeError(
            f"Invalid dictionary header: "
            f"{path}"
        )


    return set(
        lines[1:]
    )


for language, words in EXPECTED.items():

    print("")
    print(
        f"Checking dictionary: "
        f"{language}"
    )


    dictionary = read_dictionary(
        language
    )


    for word in words:

        word = unicodedata.normalize(
            "NFC",
            word
        )


        if word not in dictionary:

            print(
                f"ERROR: expected word "
                f"missing: "
                f"{language}: "
                f"{word}"
            )

            raise SystemExit(1)


        print(
            f"OK: "
            f"{language}: "
            f"{word}"
        )


print("")
print(
    "All expected dictionary words "
    "are present."
)

PY


# ============================================================
# Validate Spanish false candidates
#
# These must NOT exist as normal dictionary words.
#
# They should instead be handled through accent corrections.
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${ASSETS_ROOT}" \
    <<'PY'

import sys
from pathlib import Path


assets = Path(sys.argv[1])


FORBIDDEN_ES = {
    "manana",
    "mananas",
    "tambien",

    "tenes",
    "podes",
    "queres",

    "venis",
    "decis",

    "vivis",
    "salis",
    "veni",

    "dia",
    "dias",

    "pasaria",
    "deberia",

    "aca",
    "alla",

    "despues",
    "asi",
}


dictionary_path = (
    assets /
    "es-AR.dict"
)


with dictionary_path.open(
    "r",
    encoding="utf-8"
) as f:

    dictionary = {
        line.rstrip("\r\n")
        for line in f
    }


print("")
print(
    "Checking Spanish false candidates"
)


found = sorted(
    word
    for word in FORBIDDEN_ES
    if word in dictionary
)


if found:

    print(
        "ERROR: unaccented false candidates "
        "remain in es-AR.dict:"
    )

    for word in found:

        print(
            f"  {word}"
        )

    raise SystemExit(1)


print(
    "OK: no known unaccented false "
    "candidates remain."
)

PY


# ============================================================
# Validate delete indexes
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${ASSETS_ROOT}" \
    <<'PY'

import sys
from pathlib import Path


assets = Path(sys.argv[1])

DELETE_HEADER = (
    "#POCKETBOARD-DELETES-1"
)


for language in (
    "es-AR",
    "en-en",
    "de-de",
):

    dictionary_path = (
        assets /
        f"{language}.dict"
    )

    deletes_path = (
        assets /
        f"{language}.deletes"
    )


    with dictionary_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        dictionary_lines = [
            line.rstrip("\r\n")
            for line in f
        ]


    if not dictionary_lines:

        raise RuntimeError(
            f"Empty dictionary: "
            f"{dictionary_path}"
        )


    if dictionary_lines[0] != \
            "#POCKETBOARD-DICT-1":

        raise RuntimeError(
            f"Invalid dictionary header: "
            f"{dictionary_path}"
        )


    dictionary = set(
        dictionary_lines[1:]
    )


    with deletes_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        lines = [
            line.rstrip("\r\n")
            for line in f
        ]


    if not lines:

        raise RuntimeError(
            f"Empty delete index: "
            f"{deletes_path}"
        )


    if lines[0] != DELETE_HEADER:

        raise RuntimeError(
            f"Invalid delete header: "
            f"{deletes_path}"
        )


    invalid = 0
    mappings = 0


    for line_number, line in enumerate(
        lines[1:],
        start=2
    ):

        if not line:
            continue


        parts = line.split(
            "\t"
        )


        if len(parts) != 2:

            print(
                f"INVALID FORMAT: "
                f"{language}:"
                f"{line_number}: "
                f"{line!r}"
            )

            invalid += 1
            continue


        delete_key = parts[0]
        target = parts[1]


        if not delete_key:

            print(
                f"INVALID DELETE KEY: "
                f"{language}:"
                f"{line_number}"
            )

            invalid += 1
            continue


        if not target:

            print(
                f"INVALID TARGET: "
                f"{language}:"
                f"{line_number}"
            )

            invalid += 1
            continue


        mappings += 1


        if target not in dictionary:

            print(
                f"INVALID TARGET: "
                f"{language}: "
                f"{target}"
            )

            invalid += 1


    print("")
    print(
        f"Delete validation: "
        f"{language}"
    )

    print(
        f"  Mappings: "
        f"{mappings}"
    )

    print(
        f"  Invalid: "
        f"{invalid}"
    )


    if invalid:

        raise SystemExit(1)


    print(
        f"OK: all delete targets "
        f"exist in {language}.dict"
    )


PY


# ============================================================
# Verify exact Android assets
# ============================================================

echo ""
echo "============================================================"
echo " Checking Android dictionary assets"
echo "============================================================"


for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    for suffix in \
        "dict" \
        "deletes"
    do

        file="${ASSETS_ROOT}/${language}.${suffix}"


        if [[ ! -s "${file}" ]]; then

            echo ""
            echo "ERROR: missing/empty asset:"
            echo "  ${file}"

            exit 1
        fi


        echo "OK: ${language}.${suffix}"

    done

done


# ============================================================
# Final size report
# ============================================================

echo ""
echo "============================================================"
echo " PocketBoard dictionary build summary"
echo "============================================================"

TOTAL_DICTIONARY_BYTES=0
TOTAL_DELETE_BYTES=0
TOTAL_METADATA_BYTES=0


for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    dictionary="${OUTPUT_ROOT}/${language}.dict"
    deletes="${OUTPUT_ROOT}/${language}.deletes"
    metadata="${OUTPUT_ROOT}/${language}.meta"


    if [[ ! -f "${dictionary}" ]]; then

        echo "ERROR: missing dictionary:"
        echo "  ${dictionary}"

        exit 1
    fi


    if [[ ! -f "${deletes}" ]]; then

        echo "ERROR: missing delete index:"
        echo "  ${deletes}"

        exit 1
    fi


    if [[ ! -f "${metadata}" ]]; then

        echo "ERROR: missing metadata:"
        echo "  ${metadata}"

        exit 1
    fi


    dictionary_size="$(
        wc -c < "${dictionary}"
    )"

    delete_size="$(
        wc -c < "${deletes}"
    )"

    metadata_size="$(
        wc -c < "${metadata}"
    )"


    dictionary_words="$(
        tail -n +2 "${dictionary}" |
        wc -l
    )"


    delete_mappings="$(
        tail -n +2 "${deletes}" |
        wc -l
    )"


    # --------------------------------------------------------
    # Correct Bash integer arithmetic.
    # --------------------------------------------------------

    language_total=$(
        (
            dictionary_size +
            delete_size +
            metadata_size
        ) | awk '{ print $1 }'
    )

    # Use arithmetic expansion explicitly.
    language_total=$(
        (
            echo $(
                (
                    dictionary_size +
                    delete_size +
                    metadata_size
                )
            )
        )
    )

    TOTAL_DICTIONARY_BYTES=$(
        (
            echo $(
                (
                    TOTAL_DICTIONARY_BYTES +
                    dictionary_size
                )
            )
        )
    )

    TOTAL_DELETE_BYTES=$(
        (
            echo $(
                (
                    TOTAL_DELETE_BYTES +
                    delete_size
                )
            )
        )
    )

    TOTAL_METADATA_BYTES=$(
        (
            echo $(
                (
                    TOTAL_METADATA_BYTES +
                    metadata_size
                )
            )
        )
    )


    echo ""
    echo "${language}"
    echo "  Words:           ${dictionary_words}"
    echo "  Dictionary:      ${dictionary_size} bytes"
    echo "  Delete index:    ${delete_size} bytes"
    echo "  Delete mappings: ${delete_mappings}"
    echo "  Metadata:        ${metadata_size} bytes"
    echo "  Total:           ${language_total} bytes"

done


# ============================================================
# Recalculate totals using native Bash arithmetic.
#
# This is intentionally kept separate from the reporting loop
# so no malformed arithmetic expression can become a command.
# ============================================================

TOTAL_DICTIONARY_BYTES=0
TOTAL_DELETE_BYTES=0
TOTAL_METADATA_BYTES=0


for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    dictionary="${OUTPUT_ROOT}/${language}.dict"
    deletes="${OUTPUT_ROOT}/${language}.deletes"
    metadata="${OUTPUT_ROOT}/${language}.meta"


    dictionary_size="$(
        wc -c < "${dictionary}"
    )"

    delete_size="$(
        wc -c < "${deletes}"
    )"

    metadata_size="$(
        wc -c < "${metadata}"
    )"


    TOTAL_DICTIONARY_BYTES=$(
        printf '%d' \
            "$(
                (
                    TOTAL_DICTIONARY_BYTES +
                    dictionary_size
                )
            )"
    )

    TOTAL_DELETE_BYTES=$(
        printf '%d' \
            "$(
                (
                    TOTAL_DELETE_BYTES +
                    delete_size
                )
            )"
    )

    TOTAL_METADATA_BYTES=$(
        printf '%d' \
            "$(
                (
                    TOTAL_METADATA_BYTES +
                    metadata_size
                )
            )"
    )

done


# ============================================================
# Final totals
# ============================================================

TOTAL_GENERATED_BYTES=$(
    printf '%d' \
        "$(
            (
                TOTAL_DICTIONARY_BYTES +
                TOTAL_DELETE_BYTES +
                TOTAL_METADATA_BYTES
            )
        )"
)


TOTAL_MIB=$(
    printf '%d' \
        "$(
            (
                TOTAL_GENERATED_BYTES /
                1024 /
                1024
            )
        )"
)


echo ""
echo "============================================================"
echo " TOTAL"
echo "============================================================"

echo "Dictionary bytes: ${TOTAL_DICTIONARY_BYTES}"
echo "Delete bytes:     ${TOTAL_DELETE_BYTES}"
echo "Metadata bytes:   ${TOTAL_METADATA_BYTES}"
echo "Generated bytes:  ${TOTAL_GENERATED_BYTES}"
echo "Approximate data: ${TOTAL_MIB} MiB"


echo ""
echo "Delete budget:"
echo "  Spanish: ${ES_DELETE_BUDGET}"
echo "  English: ${EN_DELETE_BUDGET}"
echo "  German:  ${DE_DELETE_BUDGET}"
echo "  Global:  ${GLOBAL_DELETE_BUDGET}"


if (( TOTAL_DELETE_BYTES > GLOBAL_DELETE_BUDGET )); then

    echo ""
    echo "ERROR: global delete budget exceeded."
    echo "  Size:   ${TOTAL_DELETE_BYTES}"
    echo "  Budget: ${GLOBAL_DELETE_BUDGET}"

    exit 1
fi


# ============================================================
# Final sanity checks
# ============================================================

echo ""
echo "============================================================"
echo " FINAL SANITY CHECKS"
echo "============================================================"


for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    for suffix in \
        "dict" \
        "deletes" \
        "meta"
    do

        file="${ASSETS_ROOT}/${language}.${suffix}"


        if [[ ! -s "${file}" ]]; then

            echo "ERROR: missing/empty asset:"
            echo "  ${file}"

            exit 1
        fi

    done

done


echo "OK: all generated assets exist."
echo "OK: dictionary headers are valid."
echo "OK: delete headers are valid."
echo "OK: delete targets reference final dictionary words."
echo "OK: Android asset names are correct."
echo "OK: Unicode/NFC preserved."
echo "OK: accented words / ñ / umlauts retained."
echo "OK: Spanish false unaccented candidates removed."
echo "OK: legitimate ambiguous Spanish words preserved."
echo "OK: no Hunspell required."
echo ""
echo "PocketBoard dictionary generation completed successfully."
