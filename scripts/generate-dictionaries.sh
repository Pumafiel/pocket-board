#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# PocketBoard dictionary generator
#
# DIRECT-SOURCE VERSION
#
# - NO HUNSPELL
# - FrequencyWords is downloaded at build time
# - Unicode/NFC preserved
# - Spanish accents, ñ and German umlauts preserved
# - Explicit curated core vocabulary
# - Explicit Spanish accent corrections
# - Legitimate unaccented Spanish words are preserved
# - Delete indexes reference ONLY final dictionary words
# - Download failures are FATAL
# - Empty/invalid source files are FATAL
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
# ============================================================

DELETE_WORDS="${DELETE_WORDS:-12000}"
MAX_DELETES_PER_WORD="${MAX_DELETES_PER_WORD:-2}"

ES_DELETE_BUDGET="${ES_DELETE_BUDGET:-1500000}"
EN_DELETE_BUDGET="${EN_DELETE_BUDGET:-1500000}"
DE_DELETE_BUDGET="${DE_DELETE_BUDGET:-1500000}"

GLOBAL_DELETE_BUDGET="${GLOBAL_DELETE_BUDGET:-4500000}"

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

download_source() {
    local url="$1"
    local output="$2"

    echo ""
    echo "Downloading:"
    echo "${url}"
    echo "  -> ${output}"

    local temporary="${output}.tmp"

    rm -f "${temporary}"

    if ! "${CURL_BIN}" \
        --fail \
        --location \
        --retry 5 \
        --retry-delay 3 \
        --connect-timeout 20 \
        --max-time 120 \
        --silent \
        --show-error \
        "${url}" \
        --output "${temporary}"
    then
        rm -f "${temporary}"

        echo ""
        echo "ERROR: failed to download source:"
        echo "  ${url}"

        exit 1
    fi

    if [[ ! -s "${temporary}" ]]; then
        rm -f "${temporary}"

        echo ""
        echo "ERROR: downloaded source is empty:"
        echo "  ${url}"

        exit 1
    fi

    local line_count

    line_count="$(
        wc -l < "${temporary}"
    )"

    if (( line_count < 1000 )); then
        echo ""
        echo "ERROR: downloaded source is suspiciously small:"
        echo "  ${output}"
        echo "  Lines: ${line_count}"

        rm -f "${temporary}"

        exit 1
    fi

    mv \
        "${temporary}" \
        "${output}"

    echo "Downloaded successfully:"
    echo "  ${output}"
    echo "  Lines: ${line_count}"
}

normalize_source() {
    local input="$1"
    local output="$2"

    "${PYTHON_BIN}" \
        - \
        "${input}" \
        "${output}" \
        "${MIN_WORD_LEN}" \
        "${MAX_WORD_LEN}" \
        <<'PY'

import sys
import unicodedata

src = sys.argv[1]
dst = sys.argv[2]

MIN_LEN = int(sys.argv[3])
MAX_LEN = int(sys.argv[4])


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
    errors="strict"
) as f:

    for line in f:
        parts = line.strip().split()

        if not parts:
            continue

        word = normalize_word(parts[0])

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)
        result.append(word)


if len(result) < 1000:
    raise RuntimeError(
        f"Normalized source unexpectedly small: "
        f"{len(result)} words"
    )


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
echo " Downloading dictionary sources"
echo "============================================================"

download_source \
    "${FREQUENCY_ES_URL}" \
    "${ES_SOURCE}"

download_source \
    "${FREQUENCY_EN_URL}" \
    "${EN_SOURCE}"

download_source \
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

        # Canonical accented voseo forms.
        #
        # IMPORTANT:
        # The unaccented variants are NOT CORE because they are
        # explicitly removed later by accent cleanup.
        "tenés",
        "podés",
        "querés",
        "sabés",
        "venís",
        "decís",

        "hacés",
        "haces",

        "mirás",
        "miras",

        "hablás",
        "hablas",

        "comés",
        "comes",

        "vivís",
        "salís",

        "vení",

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
# Explicit Spanish correction candidates
#
# These are forms that must NOT remain as independent
# dictionary candidates when their accented canonical form
# exists.
# ============================================================

SPANISH_ACCENT_CORRECTIONS = {

    "asi": "así",
    "aca": "acá",
    "alla": "allá",
    "despues": "después",

    "deberia": "debería",

    "dia": "día",
    "dias": "días",

    "manana": "mañana",
    "mananas": "mañanas",

    "tambien": "también",

    "pasaria": "pasaría",

    # Voseo without written accent.
    "tenes": "tenés",
    "podes": "podés",
    "queres": "querés",
    "sabes": "sabés",
    "venis": "venís",
    "decis": "decís",

    # Imperative voseo.
    "veni": "vení",

    # Present voseo forms.
    "vivis": "vivís",
    "salis": "salís",
}

# ============================================================
# Legitimate unaccented Spanish words
# ============================================================

SPANISH_LEGITIMATE_UNACCENTED = {

    "haces",
    "miras",
    "hablas",
    "comes",
}

# ============================================================
# Unicode helpers
# ============================================================

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

    if len(result) < 1000:
        raise RuntimeError(
            f"Source unexpectedly small: "
            f"{path}: {len(result)} words"
        )

    return result


sources = {

    "es-AR": es_source,
    "en-en": en_source,
    "de-de": de_source,
}


for language, source_path in sources.items():

    print("")
    print("=" * 60)
    print(
        f" Building dictionary: {language}"
    )
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
    # Start with core vocabulary.
    # --------------------------------------------------------

    selected = []
    selected_set = set()

    for word in CORE[language]:

        word = nfc(word)

        if not valid_word(word):
            raise RuntimeError(
                f"Invalid core word: {word}"
            )

        if word not in selected_set:

            selected.append(word)
            selected_set.add(word)

        if word in source_set:

            print(
                f"CORE + SOURCE: {language}: {word}"
            )

        else:

            print(
                f"CORE ONLY:    {language}: {word}"
            )

    # --------------------------------------------------------
    # Add FrequencyWords in ranking order.
    # --------------------------------------------------------

    for word in source_words:

        if word in selected_set:
            continue

        if len(selected) >= max_words:
            break

        selected.append(word)
        selected_set.add(word)

    # --------------------------------------------------------
    # Spanish explicit accent cleanup.
    # --------------------------------------------------------

    accent_targets = {}

    if language == "es-AR":

        for plain_word, accented_word in \
                SPANISH_ACCENT_CORRECTIONS.items():

            plain_word = nfc(
                plain_word
            )

            accented_word = nfc(
                accented_word
            )

            # Target must exist in final vocabulary.
            if accented_word not in selected_set:
                continue

            # Legitimate words such as "haces" and "comes"
            # are never removed.
            if plain_word in \
                    SPANISH_LEGITIMATE_UNACCENTED:

                continue

            if plain_word not in selected_set:
                continue

            accent_targets[
                plain_word
            ] = accented_word

        if accent_targets:

            print("")
            print(
                "Explicit Spanish accent cleanup:"
            )

            for plain_word in sorted(
                accent_targets,
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
                if word not in accent_targets
            ]

            selected_set = set(
                selected
            )

    # --------------------------------------------------------
    # Verify explicit corrections.
    # --------------------------------------------------------

    if language == "es-AR":

        for plain_word, accented_word in \
                SPANISH_ACCENT_CORRECTIONS.items():

            if (
                plain_word
                in SPANISH_LEGITIMATE_UNACCENTED
            ):
                continue

            if (
                plain_word
                in selected_set
            ):

                raise RuntimeError(
                    "Spanish false candidate remains: "
                    f"{plain_word}"
                )

            if accented_word not in selected_set:

                raise RuntimeError(
                    "Accent correction target missing: "
                    f"{plain_word} -> {accented_word}"
                )

    # --------------------------------------------------------
    # Runtime dictionary.
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
    # Explicit correction map.
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
            f"source_words={len(source_words)}\n"
        )

        f.write(
            f"core_words={len(CORE[language])}\n"
        )

        f.write(
            f"dictionary_words={len(runtime_words)}\n"
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

        f.write(
            f"accent_corrections={len(accent_targets)}\n"
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

        if word not in selected_set:

            raise RuntimeError(
                f"Core word missing: "
                f"{language}: {word}"
            )

        print(
            f"OK: {language}: {word}"
        )

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

DELETE_HEADER = (
    "#POCKETBOARD-DELETES-1"
)


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
            f"Empty dictionary: {dictionary_path}"
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

    mappings = []
    seen_mappings = set()

    estimated_bytes = (
        len(
            DELETE_HEADER.encode("utf-8")
        )
        + 1
    )

    # --------------------------------------------------------
    # Normal delete corrections.
    # --------------------------------------------------------

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
    # Explicit Spanish accent mappings.
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

                if not plain_word:
                    continue

                if not accented_word:
                    continue

                if accented_word \
                        not in dictionary_words:
                    continue

                accent_mappings.append(
                    (
                        plain_word,
                        accented_word
                    )
                )

    # Accent correction takes precedence over
    # generic delete mapping for the same key.

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
            for deleted, target in mappings
            if deleted not in accent_keys
        ]

        seen_mappings = set(
            mappings
        )

        estimated_bytes = (
            len(
                DELETE_HEADER.encode("utf-8")
            )
            + 1
        )

        for deleted, target in mappings:

            estimated_bytes += (
                len(
                    deleted.encode("utf-8")
                )
                + 1
                + len(
                    target.encode("utf-8")
                )
                + 1
            )

    # Add explicit accent mappings.

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
                plain_word.encode("utf-8")
            )
            + 1
            + len(
                accented_word.encode("utf-8")
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

    mappings.sort(
        key=lambda pair: (
            pair[0],
            pair[1]
        )
    )

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
        f"Delete index: {language}"
    )

    print(
        f"  Priority words: {len(priority_words)}"
    )

    print(
        f"  Accent mappings: {len(accent_mappings)}"
    )

    print(
        f"  Delete entries: {entries}"
    )

    print(
        f"  Bytes: {actual_size}"
    )

    print(
        f"  Budget: {budget}"
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
    f"  Entries: {total_entries_global}"
)

print(
    f"  Bytes: {total_bytes_global}"
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
# Validate dictionaries
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

from pathlib import Path


assets = Path(sys.argv[1])

DICT_HEADER = (
    "#POCKETBOARD-DICT-1"
)

DELETE_HEADER = (
    "#POCKETBOARD-DELETES-1"
)

EXPECTED = {

    "es-AR": [

        "vos",

        "tenés",
        "podés",
        "querés",
        "sabés",
        "venís",
        "decís",

        "hacés",
        "haces",

        "mirás",
        "miras",

        "hablás",
        "hablas",

        "comés",
        "comes",

        "vivís",
        "salís",

        "vení",

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

        "the",
        "have",
        "hello",
        "world",

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


def read_dictionary(language):

    path = (
        assets /
        f"{language}.dict"
    )

    if not path.exists():

        raise RuntimeError(
            f"Missing dictionary: {path}"
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
            f"Empty dictionary: {path}"
        )

    if lines[0] != DICT_HEADER:

        raise RuntimeError(
            f"Invalid dictionary header: {path}"
        )

    return set(
        lines[1:]
    )


for language, expected in EXPECTED.items():

    print("")
    print(
        f"Checking dictionary format: "
        f"{language}"
    )

    dictionary = read_dictionary(
        language
    )

    for word in expected:

        if word not in dictionary:

            raise RuntimeError(
                f"Expected word missing: "
                f"{language}: {word}"
            )

        print(
            f"OK: {language}: {word}"
        )


print("")
print(
    "All expected dictionary words "
    "are present."
)

PY

# ============================================================
# Validate Spanish false candidates
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${ASSETS_ROOT}" \
    <<'PY'

import sys

from pathlib import Path


assets = Path(sys.argv[1])

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


FALSE_CANDIDATES = {

    "asi",
    "aca",
    "alla",
    "despues",

    "deberia",

    "dia",
    "dias",

    "manana",
    "mananas",

    "tambien",

    "pasaria",

    "tenes",
    "podes",
    "queres",
    "sabes",
    "venis",
    "decis",

    "veni",

    "vivis",
    "salis",
}


LEGITIMATE = {

    "haces",
    "miras",
    "hablas",
    "comes",
}


remaining = sorted(
    FALSE_CANDIDATES
    & dictionary
)


if remaining:

    print("")
    print(
        "ERROR: unaccented false candidates "
        "remain in es-AR.dict:"
    )

    for word in remaining:

        print(
            f"  {word}"
        )

    raise SystemExit(1)


print("")
print(
    "OK: no explicit Spanish correction "
    "candidates remain in es-AR.dict"
)


missing_legitimate = sorted(
    word
    for word in LEGITIMATE
    if word not in dictionary
)


if missing_legitimate:

    print("")
    print(
        "ERROR: legitimate Spanish forms "
        "were removed:"
    )

    for word in missing_legitimate:

        print(
            f"  {word}"
        )

    raise SystemExit(1)


print("")
print(
    "OK: legitimate Spanish unaccented "
    "forms are preserved"
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
                f"{language}:{line_number}: "
                f"{line!r}"
            )

            invalid += 1
            continue

        delete_key = parts[0]
        target = parts[1]

        if not delete_key:

            print(
                f"INVALID DELETE KEY: "
                f"{language}:{line_number}"
            )

            invalid += 1
            continue

        if not target:

            print(
                f"INVALID TARGET: "
                f"{language}:{line_number}"
            )

            invalid += 1
            continue

        if target not in dictionary:

            print(
                f"INVALID TARGET: "
                f"{language}: "
                f"{target}"
            )

            invalid += 1
            continue

        mappings += 1

    print("")
    print(
        f"Delete validation: {language}"
    )

    print(
        f"  Mappings: {mappings}"
    )

    print(
        f"  Invalid: {invalid}"
    )

    if invalid:

        raise SystemExit(1)

    print(
        f"OK: all delete targets "
        f"exist in {language}.dict"
    )

PY

# ============================================================
# Verify Android assets
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
        "deletes" \
        "meta"
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

    language_total=$(
        (
            dictionary_size +
            delete_size +
            metadata_size
        )
    )

    TOTAL_DICTIONARY_BYTES=$(
        (
            TOTAL_DICTIONARY_BYTES +
            dictionary_size
        )
    )

    TOTAL_DELETE_BYTES=$(
        (
            TOTAL_DELETE_BYTES +
            delete_size
        )
    )

    TOTAL_METADATA_BYTES=$(
        (
            TOTAL_METADATA_BYTES +
            metadata_size
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

TOTAL_GENERATED_BYTES=$(
    (
        TOTAL_DICTIONARY_BYTES +
        TOTAL_DELETE_BYTES +
        TOTAL_METADATA_BYTES
    )
)

TOTAL_MIB=$(
    (
        TOTAL_GENERATED_BYTES /
        1024 /
        1024
    )
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
echo "OK: explicit Spanish corrections validated."
echo "OK: legitimate Spanish unaccented forms preserved."
echo "OK: Android asset names are correct."
echo "OK: Unicode/NFC preserved."
echo "OK: accented words / ñ / umlauts retained."
echo "OK: no Hunspell required."
echo ""
echo "PocketBoard dictionary generation completed successfully."
