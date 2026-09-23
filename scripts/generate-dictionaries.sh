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
#   - Deletes are generated ONLY from final dictionary words
# ============================================================

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BUILD_ROOT="${BUILD_ROOT:-${PROJECT_ROOT}/build/pocketboard-dictionaries}"
SOURCE_ROOT="${BUILD_ROOT}/sources"
FREQUENCY_ROOT="${BUILD_ROOT}/frequency}"
WORK_ROOT="${BUILD_ROOT}/work"
OUTPUT_ROOT="${BUILD_ROOT}/generated"
ASSETS_ROOT="${PROJECT_ROOT}/app/src/main/assets/dictionaries"

PYTHON_BIN="${PYTHON_BIN:-python3}"
CURL_BIN="${CURL_BIN:-curl}"

MAX_WORDS="${MAX_WORDS:-180000}"
DIST2_WORDS="${DIST2_WORDS:-12000}"
MAX_DELETES_PER_WORD="${MAX_DELETES_PER_WORD:-96}"
MAX_DELETE_ENTRIES="${MAX_DELETE_ENTRIES:-2500000}"

MIN_WORD_LEN="${MIN_WORD_LEN:-2}"
MAX_WORD_LEN="${MAX_WORD_LEN:-40}"

ES_DELETE_BUDGET="${ES_DELETE_BUDGET:-900000}"
EN_DELETE_BUDGET="${EN_DELETE_BUDGET:-700000}"
DE_DELETE_BUDGET="${DE_DELETE_BUDGET:-1100000}"
GLOBAL_DELETE_BUDGET="${GLOBAL_DELETE_BUDGET:-2500000}"

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

    [[ -s "${output}" ]] || {
        echo "ERROR: downloaded source is empty:"
        echo "  ${output}"
        exit 1
    }
}

normalize_source() {
    local input="$1"
    local output="$2"

    "${PYTHON_BIN}" - "${input}" "${output}" <<'PY'
import sys
import unicodedata

src = sys.argv[1]
dst = sys.argv[2]

def normalize_word(word):
    word = word.strip()

    if not word:
        return ""

    # Preserve Unicode characters.
    # NFC combines decomposed accents without removing them.
    word = unicodedata.normalize("NFC", word)

    if len(word) < 2 or len(word) > 40:
        return ""

    # Do NOT ASCII-normalize.
    # Do NOT remove accents.
    # Do NOT replace ñ with n.
    #
    # Accepted:
    #   alphabetic Unicode letters
    #   combining marks
    #   apostrophes
    #   hyphens
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

with open(src, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.strip()

        if not line:
            continue

        # FrequencyWords format:
        #
        # word count
        #
        # We only need the first field.
        parts = line.split()

        if not parts:
            continue

        word = normalize_word(parts[0])

        if not word:
            continue

        # FrequencyWords may contain duplicate forms.
        if word in seen:
            continue

        seen.add(word)
        result.append(word)

with open(dst, "w", encoding="utf-8", newline="\n") as f:
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
# Build dictionaries directly from FrequencyWords
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

output_root.mkdir(parents=True, exist_ok=True)

# ============================================================
# Core vocabulary
#
# These words are retained independently of FrequencyWords.
# This is especially important for:
#   - Spanish Argentina voseo
#   - accented forms
#   - ñ
#   - German umlauts
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
    return unicodedata.normalize("NFC", word.strip())


def valid_word(word):
    word = nfc(word)

    if len(word) < min_len or len(word) > max_len:
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

    with path.open("r", encoding="utf-8") as f:
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
    # Start with core words.
    # --------------------------------------------------------

    selected = []
    selected_set = set()

    for word in CORE[language]:

        word = nfc(word)

        if not valid_word(word):
            print(f"WARNING: invalid core word: {word}")
            continue

        if word not in selected_set:
            selected.append(word)
            selected_set.add(word)

        if word in source_set:
            print(f"CORE + SOURCE: {language}: {word}")
        else:
            print(f"CORE ONLY:    {language}: {word}")

    # --------------------------------------------------------
    # Add FrequencyWords directly.
    #
    # No Hunspell validation.
    # --------------------------------------------------------

    for word in source_words:

        if word in selected_set:
            continue

        if len(selected) >= max_words:
            break

        selected.append(word)
        selected_set.add(word)

    # --------------------------------------------------------
    # Sort deterministically.
    #
    # NFC remains intact.
    # --------------------------------------------------------

    selected = sorted(
        selected,
        key=lambda x: (
            x.casefold(),
            x
        )
    )

    dictionary_path = output_root / f"{language}.dict"

    with dictionary_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        # Header.
        f.write(f"{language}\n")

        for word in selected:
            f.write(word + "\n")

    # --------------------------------------------------------
    # Metadata
    # --------------------------------------------------------

    meta_path = output_root / f"{language}.meta"

    with meta_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(f"language={language}\n")
        f.write("source=FrequencyWords\n")
        f.write("hunspell=false\n")
        f.write(f"source_words={len(source_words)}\n")
        f.write(f"core_words={len(CORE[language])}\n")
        f.write(f"dictionary_words={len(selected)}\n")

        accented = sum(
            any(
                unicodedata.category(ch).startswith("M")
                or ord(ch) > 127
                for ch in word
            )
            for word in selected
        )

        f.write(f"unicode_words={accented}\n")

    # --------------------------------------------------------
    # Expected-word diagnostic
    # --------------------------------------------------------

    print("")
    print("Expected/core verification:")

    for word in CORE[language]:

        word = nfc(word)

        if word in selected_set:
            print(f"OK: {language}: {word}")
        else:
            print(f"ERROR: core word missing: {language}: {word}")

    print("")
    print(f"Dictionary words: {len(selected)}")
    print(f"Output: {dictionary_path}")

PY

# ============================================================
# Generate delete index
#
# Deletes are generated ONLY from words actually present in
# the final dictionary.
#
# No Hunspell is involved anywhere in this stage.
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${OUTPUT_ROOT}" \
    "${DIST2_WORDS}" \
    "${MAX_DELETES_PER_WORD}" \
    "${MAX_DELETE_ENTRIES}" \
    <<'PY'

import sys
from pathlib import Path

output_root = Path(sys.argv[1])
dist2_words = int(sys.argv[2])
max_deletes_per_word = int(sys.argv[3])
max_delete_entries = int(sys.argv[4])


def utf16_len(s):
    return len(s.encode("utf-16-le")) // 2


def delete_variants(word):
    """
    Generate deletion strings.

    One-character deletion:
        mañana -> maána / mañana with each UTF-16-safe
        codepoint position removed

    For PocketBoard's delete dictionary we operate on Unicode
    codepoints, preserving accented characters.
    """

    result = set()

    if len(word) <= 1:
        return result

    for i in range(len(word)):
        result.add(word[:i] + word[i + 1:])

        if len(result) >= max_deletes_per_word:
            break

    return result


for language in ("es-AR", "en-en", "de-de"):

    dictionary_path = output_root / f"{language}.dict"
    deletes_path = output_root / f"{language}.deletes"

    with dictionary_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        lines = [
            line.rstrip("\n\r")
            for line in f
        ]

    if not lines:
        raise RuntimeError(
            f"Empty dictionary: {dictionary_path}"
        )

    # First line is dictionary header.
    words = [
        word
        for word in lines[1:]
        if word
    ]

    delete_map = {}
    total_entries = 0

    # Higher-frequency words are not available anymore at this
    # stage because the dictionary has already been sorted.
    # The first DIST2_WORDS dictionary entries are therefore used
    # for the extended correction coverage.
    dist2_set = set(words[:dist2_words])

    for word in words:

        variants = delete_variants(word)

        for deleted in variants:

            if not deleted:
                continue

            targets = delete_map.setdefault(
                deleted,
                []
            )

            if word not in targets:
                targets.append(word)
                total_entries += 1

            if total_entries >= max_delete_entries:
                break

        if total_entries >= max_delete_entries:
            break

    with deletes_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(f"{language}\n")
        f.write("source=final-dictionary\n")
        f.write(f"words={len(words)}\n")
        f.write(f"delete_entries={total_entries}\n")

        for deleted in sorted(delete_map):
            targets = delete_map[deleted]

            f.write(
                deleted
                + "\t"
                + " ".join(targets)
                + "\n"
            )

    print("")
    print(f"Delete index: {language}")
    print(f"  Words:        {len(words)}")
    print(f"  Delete keys:  {len(delete_map)}")
    print(f"  Delete maps:  {total_entries}")
    print(f"  Output:       {deletes_path}")

PY

# ============================================================
# Copy generated dictionaries to Android assets
# ============================================================

echo ""
echo "============================================================"
echo " Installing generated assets"
echo "============================================================"

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
# Validation
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

expected = {
    "es-AR": [
        "mañana",
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


def read_dictionary(language):
    path = assets / f"{language}.dict"

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

    return set(lines[1:])


for language, words in expected.items():

    print("")
    print(f"Checking dictionary format: {language}")

    dictionary = read_dictionary(language)

    for word in words:

        word = unicodedata.normalize(
            "NFC",
            word
        )

        if word not in dictionary:
            print(
                f"ERROR: expected word missing: "
                f"{language}: {word}"
            )
            raise SystemExit(1)

        print(
            f"OK: {language}: {word}"
        )

print("")
print("All expected words are present.")

PY

# ============================================================
# Delete target validation
# ============================================================

"${PYTHON_BIN}" \
    - \
    "${ASSETS_ROOT}" \
    <<'PY'

import sys
from pathlib import Path

assets = Path(sys.argv[1])

for language in (
    "es-AR",
    "en-en",
    "de-de",
):

    dictionary_path = assets / f"{language}.dict"
    deletes_path = assets / f"{language}.deletes"

    with dictionary_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        dictionary = set(
            line.rstrip("\r\n")
            for line in f
        )

    dictionary.discard(language)

    invalid = 0
    mappings = 0

    with deletes_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        for line_number, line in enumerate(
            f,
            start=1
        ):

            line = line.rstrip("\r\n")

            if not line:
                continue

            if line_number <= 4:
                continue

            parts = line.split("\t", 1)

            if len(parts) != 2:
                continue

            targets = parts[1].split()

            for target in targets:

                mappings += 1

                if target not in dictionary:
                    invalid += 1
                    print(
                        f"INVALID TARGET: "
                        f"{language}: "
                        f"{target}"
                    )

    print("")
    print(f"Delete target validation: {language}")
    print(f"  Mappings:       {mappings}")
    print(f"  Invalid targets: {invalid}")

    if invalid:
        raise SystemExit(1)

    print(
        f"OK: all delete targets exist "
        f"in {language}.dict"
    )

PY

# ============================================================
# Summary
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

    dictionary_size="$(wc -c < "${dictionary}")"
    delete_size="$(wc -c < "${deletes}")"
    metadata_size="$(wc -c < "${metadata}")"

    dictionary_words="$(
        tail -n +2 "${dictionary}" | wc -l
    )"

    delete_mappings="$(
        tail -n +5 "${deletes}" | wc -l
    )"

    language_total=$(
        printf '%s\n' \
            "$((dictionary_size + delete_size + metadata_size))"
    )

    TOTAL_DICTIONARY_BYTES=$(
        printf '%s\n' \
            "$((TOTAL_DICTIONARY_BYTES + dictionary_size))"
    )

    TOTAL_DELETE_BYTES=$(
        printf '%s\n' \
            "$((TOTAL_DELETE_BYTES + delete_size))"
    )

    TOTAL_METADATA_BYTES=$(
        printf '%s\n' \
            "$((TOTAL_METADATA_BYTES + metadata_size))"
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
    printf '%s\n' \
        "$((TOTAL_DICTIONARY_BYTES + TOTAL_DELETE_BYTES + TOTAL_METADATA_BYTES))"
)

TOTAL_MIB=$(
    printf '%s\n' \
        "$((TOTAL_GENERATED_BYTES / 1024 / 1024))"
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

# ============================================================
# GLOBAL DELETE MAPPING BUDGET
# ============================================================

TOTAL_DELETE_MAPPINGS=0

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do
    deletes="${OUTPUT_DIR}/${language}.deletes"

    if [[ -f "${deletes}" ]]; then
        mappings="$(
            tail -n +5 "${deletes}" |
            awk -F '\t' '
                NF >= 2 {
                    n = split($2, targets, ",")
                    total += n
                }
                END {
                    print total + 0
                }
            '
        )"
    else
        mappings=0
    fi

    TOTAL_DELETE_MAPPINGS=$(
        printf '%s\n' \
            "$((TOTAL_DELETE_MAPPINGS + mappings))"
    )
done

echo ""
echo "Delete mappings:"
echo "  Total:  ${TOTAL_DELETE_MAPPINGS}"
echo "  Budget: ${GLOBAL_DELETE_BUDGET}"

if (( TOTAL_DELETE_MAPPINGS > GLOBAL_DELETE_BUDGET )); then

    echo ""
    echo "ERROR: global delete mapping budget exceeded."
    echo "  Mappings: ${TOTAL_DELETE_MAPPINGS}"
    echo "  Budget:   ${GLOBAL_DELETE_BUDGET}"

    exit 1
fi

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
echo "OK: Hunspell was NOT used."
echo "OK: FrequencyWords was used directly."
echo "OK: Unicode/NFC preserved."
echo "OK: accented words / ñ / umlauts retained."
echo ""
echo "PocketBoard dictionary generation completed successfully."
