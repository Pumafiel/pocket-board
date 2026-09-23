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
#
# SIZE GOAL:
#   Keep generated dictionary assets reasonably small so the
#   final PocketBoard APK remains around the intended ~25 MB.
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
#
# IMPORTANT:
#
# The previous implementation generated every possible
# one-character deletion for approximately 150,000 words.
#
# That created ~19 MB of delete data.
#
# For PocketBoard we deliberately keep the delete index small.
#
# Only the most useful/high-frequency words receive delete
# mappings, and only a limited number of delete variants are
# generated per word.
# ============================================================

DELETE_WORDS="${DELETE_WORDS:-12000}"

MAX_DELETES_PER_WORD="${MAX_DELETES_PER_WORD:-2}"

GLOBAL_DELETE_BUDGET="${GLOBAL_DELETE_BUDGET:-4500000}"

ES_DELETE_BUDGET="${ES_DELETE_BUDGET:-1500000}"
EN_DELETE_BUDGET="${EN_DELETE_BUDGET:-1500000}"
DE_DELETE_BUDGET="${DE_DELETE_BUDGET:-1500000}"

# Hard safety limit.
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

    # Preserve Unicode and normalize to NFC.
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

with open(src, "r", encoding="utf-8", errors="replace") as f:
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
# Build final dictionaries
#
# FrequencyWords order is preserved internally.
#
# This is important because FrequencyWords is frequency-ranked.
# We use that order later when selecting delete-index words.
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
    # Core words first.
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
    # Runtime dictionary is sorted deterministically.
    #
    # Frequency order is saved separately for delete generation.
    # --------------------------------------------------------

    runtime_words = sorted(
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
    f.write("#POCKETBOARD-DICT-1\n")

    for word in runtime_words:
        f.write(word + "\n")


    # Save frequency-ranked order for delete generation.
    ranking_path = output_root / f"{language}.ranked"

    with ranking_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

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
        f.write(f"dictionary_words={len(runtime_words)}\n")

        unicode_words = sum(
            any(ord(ch) > 127 for ch in word)
            for word in runtime_words
        )

        f.write(f"unicode_words={unicode_words}\n")

    # --------------------------------------------------------
    # Expected/core verification
    # --------------------------------------------------------

    print("")
    print("Expected/core verification:")

    for word in CORE[language]:

        word = nfc(word)

        if word in selected_set:
            print(f"OK: {language}: {word}")
        else:
            print(
                f"ERROR: core word missing: "
                f"{language}: {word}"
            )
            raise SystemExit(1)

    print("")
    print(f"Dictionary words: {len(runtime_words)}")
    print(f"Output: {dictionary_path}")

PY

# ============================================================
# Generate compact delete indexes
#
# IMPORTANT:
#
# The previous implementation generated deletes for every word
# in every dictionary.
#
# That was the reason the delete data grew to ~19.5 MB.
#
# This implementation:
#
#   1. Uses only the highest-frequency DELETE_WORDS words.
#   2. Generates at most MAX_DELETES_PER_WORD deletes.
#   3. Stops when the per-language byte budget is reached.
#   4. Stops at MAX_DELETE_ENTRIES globally.
#   5. Uses only final dictionary words as targets.
#
# The dictionary itself is NOT reduced.
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


def delete_variants(word):
    """
    Generate a small deterministic set of one-character
    deletion variants.

    The first variants are retained because they are the
    cheapest/highest-priority candidates for typo correction.
    """

    if len(word) <= 1:
        return []

    result = []

    # Prefer deleting characters from the interior/end first.
    positions = list(range(len(word)))

    # Deterministic order.
    positions.sort(
        key=lambda i: (
            0 if i > 0 else 1,
            i
        )
    )

    seen = set()

    for i in positions:

        deleted = word[:i] + word[i + 1:]

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

    dictionary_path = output_root / f"{language}.dict"
    ranked_path = output_root / f"{language}.ranked"
    deletes_path = output_root / f"{language}.deletes"

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

    dictionary_words = set(dictionary_lines[1:])

    with ranked_path.open(
        "r",
        encoding="utf-8"
    ) as f:

        ranked_words = [
            line.rstrip("\r\n")
            for line in f
            if line.rstrip("\r\n")
        ]

    # Only final dictionary words can become targets.
    ranked_words = [
        word
        for word in ranked_words
        if word in dictionary_words
    ]

    priority_words = ranked_words[:delete_words_limit]

    # delete -> list of dictionary targets
    delete_map = {}

    entries = 0
    estimated_bytes = len(language) + 1

    for word in priority_words:

        variants = delete_variants(word)

        for deleted in variants:

            targets = delete_map.setdefault(
                deleted,
                []
            )

            if word in targets:
                continue

            # Estimate the serialized line size before adding it.
            #
            # deleted + tab + target + newline
            additional = (
                len(deleted.encode("utf-8"))
                + 1
                + len(word.encode("utf-8"))
                + 1
            )

            # If this individual mapping would exceed the language
            # budget, stop adding more mappings.
            if estimated_bytes + additional > budget:
                break

            # Global safety limit.
            if total_entries_global + entries >= max_delete_entries:
                break

            targets.append(word)

            entries += 1
            estimated_bytes += additional

        if estimated_bytes >= budget:
            break

        if total_entries_global + entries >= max_delete_entries:
            break

    # Remove empty keys.
    delete_map = {
        key: value
        for key, value in delete_map.items()
        if value
    }

    with deletes_path.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as f:

        f.write(f"{language}\n")
        f.write("source=final-dictionary\n")
        f.write(f"priority_words={len(priority_words)}\n")
        f.write(f"delete_entries={entries}\n")

        for deleted in sorted(delete_map):

            targets = delete_map[deleted]

            f.write(
                deleted
                + "\t"
                + " ".join(targets)
                + "\n"
            )

    actual_size = deletes_path.stat().st_size

    total_entries_global += entries
    total_bytes_global += actual_size

    print("")
    print(f"Delete index: {language}")
    print(f"  Priority words: {len(priority_words)}")
    print(f"  Delete keys:    {len(delete_map)}")
    print(f"  Delete entries: {entries}")
    print(f"  Bytes:          {actual_size}")
    print(f"  Budget:         {budget}")

    if actual_size > budget:
        raise RuntimeError(
            f"Delete budget exceeded for {language}: "
            f"{actual_size} > {budget}"
        )

print("")
print("Delete generation totals:")
print(f"  Entries: {total_entries_global}")
print(f"  Bytes:   {total_bytes_global}")

PY

# ============================================================
# Remove temporary ranking files from generated assets.
#
# They are build-only files and must NEVER be packaged into APK.
# ============================================================

rm -f \
    "${OUTPUT_ROOT}/es-AR.ranked" \
    "${OUTPUT_ROOT}/en-en.ranked" \
    "${OUTPUT_ROOT}/de-de.ranked"

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
# Validate dictionary contents
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
# Validate delete targets
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

    # Header is not a word.
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

            # Four metadata/header lines.
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
    print(f"  Mappings:        {mappings}")
    print(f"  Invalid targets: {invalid}")

    if invalid:
        raise SystemExit(1)

    print(
        f"OK: all delete targets exist "
        f"in {language}.dict"
    )

PY

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

    dictionary_size="$(wc -c < "${dictionary}")"
    delete_size="$(wc -c < "${deletes}")"
    metadata_size="$(wc -c < "${metadata}")"

    dictionary_words="$(
        tail -n +2 "${dictionary}" |
        wc -l
    )"

    delete_mappings="$(
        tail -n +5 "${deletes}" |
        wc -l
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
echo "OK: Hunspell was NOT used."
echo "OK: FrequencyWords was used directly."
echo "OK: Unicode/NFC preserved."
echo "OK: accented words / ñ / umlauts retained."
echo "OK: delete targets reference final dictionary words."
echo ""
echo "PocketBoard dictionary generation completed successfully."
