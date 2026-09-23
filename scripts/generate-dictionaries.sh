```bash
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
#   - Deletes are generated ONLY from:
#       * curated core words
#       * highest-frequency source words
#   - Dictionary itself still contains the full ~50k source words
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

# ------------------------------------------------------------
# Dictionary limits
# ------------------------------------------------------------

MAX_WORDS="${MAX_WORDS:-180000}"

MIN_WORD_LEN="${MIN_WORD_LEN:-2}"
MAX_WORD_LEN="${MAX_WORD_LEN:-40}"

# ------------------------------------------------------------
# Delete index configuration
#
# The dictionary keeps all source words.
#
# The delete index does NOT need to contain every dictionary
# word. It is a typo-correction index, so we prioritize the
# most frequent words plus all curated core words.
#
# FrequencyWords files are already ordered by frequency.
# ------------------------------------------------------------

DELETE_PRIORITY_WORDS="${DELETE_PRIORITY_WORDS:-7000}"

MAX_DELETES_PER_WORD="${MAX_DELETES_PER_WORD:-96}"

ES_DELETE_BUDGET="${ES_DELETE_BUDGET:-900000}"
EN_DELETE_BUDGET="${EN_DELETE_BUDGET:-700000}"
DE_DELETE_BUDGET="${DE_DELETE_BUDGET:-1100000}"

GLOBAL_DELETE_BUDGET="${GLOBAL_DELETE_BUDGET:-2500000}"

# ------------------------------------------------------------
# Directories
# ------------------------------------------------------------

mkdir -p \
    "${SOURCE_ROOT}" \
    "${FREQUENCY_ROOT}" \
    "${WORK_ROOT}" \
    "${OUTPUT_ROOT}" \
    "${ASSETS_ROOT}"

# ------------------------------------------------------------
# FrequencyWords sources
# ------------------------------------------------------------

FREQUENCY_ES_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_50k.txt"
FREQUENCY_EN_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt"
FREQUENCY_DE_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_50k.txt"

ES_SOURCE="${SOURCE_ROOT}/es_50k.txt"
EN_SOURCE="${SOURCE_ROOT}/en_50k.txt"
DE_SOURCE="${SOURCE_ROOT}/de_50k.txt"

# ------------------------------------------------------------
# Helpers
# ------------------------------------------------------------

log() {
    printf '%s\n' "$*"
}

die() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

download_if_missing() {
    local url="$1"
    local destination="$2"

    if [[ -s "${destination}" ]]; then
        log "Source already exists: ${destination}"
        return
    fi

    log "Downloading:"
    log "  ${url}"

    "${CURL_BIN}" \
        --fail \
        --location \
        --silent \
        --show-error \
        --retry 3 \
        --retry-delay 2 \
        --output "${destination}" \
        "${url}"

    [[ -s "${destination}" ]] || die "Downloaded source is empty: ${destination}"
}

# ------------------------------------------------------------
# Normalize source
#
# FrequencyWords format:
#
#   word count
#
# Only the first whitespace-separated field is used.
#
# IMPORTANT:
#   - NFC normalization
#   - Unicode letters preserved
#   - combining marks preserved
#   - accents preserved
#   - ñ preserved
#   - umlauts preserved
#   - no ASCII transliteration
#   - no accent stripping
# ------------------------------------------------------------

normalize_source() {
    local input="$1"
    local output="$2"

    "${PYTHON_BIN}" - "${input}" "${output}" "${MIN_WORD_LEN}" "${MAX_WORD_LEN}" <<'PY'
import sys
import unicodedata

input_file = sys.argv[1]
output_file = sys.argv[2]
min_len = int(sys.argv[3])
max_len = int(sys.argv[4])

seen = set()
result = []

def valid_word(word):
    if not word:
        return False

    if len(word) < min_len or len(word) > max_len:
        return False

    for ch in word:
        category = unicodedata.category(ch)

        if category.startswith("L"):
            continue

        if category.startswith("M"):
            continue

        if ch in ("'", "’", "-"):
            continue

        return False

    return True

with open(input_file, "r", encoding="utf-8", errors="replace") as src:
    for raw_line in src:
        line = raw_line.strip()

        if not line:
            continue

        parts = line.split()

        if not parts:
            continue

        word = parts[0]

        # NFC is critical for accents, ñ and umlauts.
        word = unicodedata.normalize("NFC", word)

        if not valid_word(word):
            continue

        key = word.casefold()

        if key in seen:
            continue

        seen.add(key)
        result.append(word)

with open(output_file, "w", encoding="utf-8", newline="\n") as dst:
    for word in result:
        dst.write(word + "\n")

print(f"Normalized source words: {len(result)}")
PY
}

# ------------------------------------------------------------
# Core vocabulary
# ------------------------------------------------------------

declare -a ES_CORE=(
    "vos"
    "tenés"
    "tenes"
    "podés"
    "podes"
    "querés"
    "queres"
    "sabés"
    "sabes"
    "venís"
    "venis"
    "decís"
    "decis"
    "hacés"
    "haces"
    "mirás"
    "miras"
    "hablás"
    "hablas"
    "comés"
    "comes"
    "vivís"
    "vivis"
    "salís"
    "salis"
    "vení"
    "veni"
    "decime"
    "haceme"
    "mañana"
    "mañanas"
    "también"
    "qué"
    "cómo"
    "cuándo"
    "dónde"
    "quién"
    "porque"
    "porqué"
    "día"
    "días"
    "más"
    "sí"
    "está"
    "estás"
    "están"
    "acá"
    "allá"
    "después"
    "así"
    "sólo"
    "pasaría"
    "debería"
    "hago"
    "hacer"
    "veré"
)

declare -a EN_CORE=(
    "hello"
    "world"
    "the"
    "have"
    "this"
    "that"
    "what"
    "where"
    "when"
    "who"
    "which"
    "please"
    "thanks"
    "thank"
    "sorry"
    "tomorrow"
    "today"
)

declare -a DE_CORE=(
    "hallo"
    "welt"
    "ich"
    "nicht"
    "morgen"
    "heute"
    "bitte"
    "danke"
    "dankeschön"
    "entschuldigung"
    "wahrscheinlich"
    "möglicherweise"
    "möglich"
    "für"
    "über"
    "schön"
    "größer"
    "größe"
    "später"
    "früh"
    "früher"
)

# ------------------------------------------------------------
# Download sources
# ------------------------------------------------------------

log ""
log "============================================================"
log " Downloading FrequencyWords sources"
log "============================================================"

download_if_missing "${FREQUENCY_ES_URL}" "${ES_SOURCE}"
download_if_missing "${FREQUENCY_EN_URL}" "${EN_SOURCE}"
download_if_missing "${FREQUENCY_DE_URL}" "${DE_SOURCE}"

# ------------------------------------------------------------
# Normalize sources
# ------------------------------------------------------------

ES_NORMALIZED="${WORK_ROOT}/es-AR.source"
EN_NORMALIZED="${WORK_ROOT}/en-en.source"
DE_NORMALIZED="${WORK_ROOT}/de-de.source"

log ""
log "============================================================"
log " Normalizing sources"
log "============================================================"

normalize_source "${ES_SOURCE}" "${ES_NORMALIZED}"
normalize_source "${EN_SOURCE}" "${EN_NORMALIZED}"
normalize_source "${DE_SOURCE}" "${DE_NORMALIZED}"

# ------------------------------------------------------------
# Build dictionary
#
# The source order is frequency order.
#
# Core words are inserted first.
# Source words are then appended until MAX_WORDS.
#
# The final dictionary is sorted deterministically.
# ------------------------------------------------------------

build_dictionary() {
    local language="$1"
    local normalized_source="$2"
    local output_file="$3"
    shift 3

    local -a core_words=("$@")

    log ""
    log "============================================================"
    log " Building dictionary: ${language}"
    log "============================================================"

    "${PYTHON_BIN}" - \
        "${language}" \
        "${normalized_source}" \
        "${output_file}" \
        "${MAX_WORDS}" \
        "${MIN_WORD_LEN}" \
        "${MAX_WORD_LEN}" \
        "${core_words[@]}" <<'PY'
import sys
import unicodedata

language = sys.argv[1]
source_file = sys.argv[2]
output_file = sys.argv[3]
max_words = int(sys.argv[4])
min_len = int(sys.argv[5])
max_len = int(sys.argv[6])

core_words = sys.argv[7:]

def normalize(word):
    return unicodedata.normalize("NFC", word.strip())

def valid_word(word):
    if not word:
        return False

    if len(word) < min_len or len(word) > max_len:
        return False

    for ch in word:
        category = unicodedata.category(ch)

        if category.startswith("L"):
            continue

        if category.startswith("M"):
            continue

        if ch in ("'", "’", "-"):
            continue

        return False

    return True

# ------------------------------------------------------------
# Load source in ORIGINAL frequency order.
# ------------------------------------------------------------

source_words = []

with open(source_file, "r", encoding="utf-8") as src:
    for raw in src:
        word = normalize(raw)

        if not valid_word(word):
            continue

        source_words.append(word)

# ------------------------------------------------------------
# Build case-insensitive set while preserving Unicode.
# ------------------------------------------------------------

words_by_key = {}

# Core first.
for word in core_words:
    word = normalize(word)

    if not valid_word(word):
        continue

    key = word.casefold()

    if key not in words_by_key:
        words_by_key[key] = word

        print(f"CORE + SOURCE: {language}: {word}")

# Source words.
for word in source_words:
    key = word.casefold()

    if key in words_by_key:
        continue

    if len(words_by_key) >= max_words:
        break

    words_by_key[key] = word

# ------------------------------------------------------------
# Deterministic final ordering.
#
# casefold() gives stable ordering while retaining original
# Unicode spelling.
# ------------------------------------------------------------

words = list(words_by_key.values())
words.sort(key=lambda value: (value.casefold(), value))

# ------------------------------------------------------------
# Write dictionary.
#
# First line = word count.
# ------------------------------------------------------------

with open(output_file, "w", encoding="utf-8", newline="\n") as dst:
    dst.write(str(len(words)) + "\n")

    for word in words:
        dst.write(word + "\n")

print(f"Source words: {len(source_words)}")
print(f"Core words:   {len(core_words)}")
print(f"Final words:  {len(words)}")
PY
}

ES_DICT="${OUTPUT_ROOT}/es-AR.dict"
EN_DICT="${OUTPUT_ROOT}/en-en.dict"
DE_DICT="${OUTPUT_ROOT}/de-de.dict"

build_dictionary \
    "es-AR" \
    "${ES_NORMALIZED}" \
    "${ES_DICT}" \
    "${ES_CORE[@]}"

build_dictionary \
    "en-en" \
    "${EN_NORMALIZED}" \
    "${EN_DICT}" \
    "${EN_CORE[@]}"

build_dictionary \
    "de-de" \
    "${DE_NORMALIZED}" \
    "${DE_DICT}" \
    "${DE_CORE[@]}"

# ------------------------------------------------------------
# Generate metadata
# ------------------------------------------------------------

write_metadata() {
    local language="$1"
    local dictionary="$2"
    local source="$3"
    local output="$4"

    "${PYTHON_BIN}" - \
        "${language}" \
        "${dictionary}" \
        "${source}" \
        "${output}" <<'PY'
import sys
import unicodedata
from pathlib import Path

language = sys.argv[1]
dictionary = Path(sys.argv[2])
source = Path(sys.argv[3])
output = Path(sys.argv[4])

lines = dictionary.read_text(
    encoding="utf-8"
).splitlines()

word_count = max(0, len(lines) - 1)

unicode_words = 0

for word in lines[1:]:
    if any(ord(ch) > 127 for ch in word):
        unicode_words += 1

source_count = len(
    [
        line
        for line in source.read_text(
            encoding="utf-8"
        ).splitlines()
        if line.strip()
    ]
)

metadata = [
    f"language={language}",
    "source=FrequencyWords",
    "hunspell=false",
    f"source_words={source_count}",
    f"dictionary_words={word_count}",
    f"unicode_words={unicode_words}",
]

output.write_text(
    "\n".join(metadata) + "\n",
    encoding="utf-8"
)
PY
}

ES_META="${OUTPUT_ROOT}/es-AR.meta"
EN_META="${OUTPUT_ROOT}/en-en.meta"
DE_META="${OUTPUT_ROOT}/de-de.meta"

write_metadata \
    "es-AR" \
    "${ES_DICT}" \
    "${ES_NORMALIZED}" \
    "${ES_META}"

write_metadata \
    "en-en" \
    "${EN_DICT}" \
    "${EN_NORMALIZED}" \
    "${EN_META}"

write_metadata \
    "de-de" \
    "${DE_DICT}" \
    "${DE_NORMALIZED}" \
    "${DE_META}"

# ------------------------------------------------------------
# Delete generation
#
# IMPORTANT:
#
# We intentionally DO NOT generate deletes for all ~50k words.
#
# We use:
#   1. all core words
#   2. first DELETE_PRIORITY_WORDS source words
#
# FrequencyWords is frequency ordered, so this preserves delete
# coverage for the most useful/high-frequency vocabulary.
#
# Only one-character deletions are generated.
#
# Unicode operates by Python code point, so:
#
#   mañana
#   mañána
#   für
#   größer
#
# remain valid Unicode strings.
# ------------------------------------------------------------

generate_deletes() {
    local language="$1"
    local normalized_source="$2"
    local dictionary="$3"
    local output="$4"
    local budget="$5"
    shift 5

    local -a core_words=("$@")

    log ""
    log "============================================================"
    log " Building delete index: ${language}"
    log "============================================================"

    "${PYTHON_BIN}" - \
        "${language}" \
        "${normalized_source}" \
        "${dictionary}" \
        "${output}" \
        "${DELETE_PRIORITY_WORDS}" \
        "${MAX_DELETES_PER_WORD}" \
        "${budget}" \
        "${core_words[@]}" <<'PY'
import sys
import unicodedata
from collections import OrderedDict
from pathlib import Path

language = sys.argv[1]
source_file = Path(sys.argv[2])
dictionary_file = Path(sys.argv[3])
output_file = Path(sys.argv[4])
priority_count = int(sys.argv[5])
max_deletes_per_word = int(sys.argv[6])
budget = int(sys.argv[7])

core_words = sys.argv[8:]

def nfc(value):
    return unicodedata.normalize("NFC", value.strip())

def delete_variants(word):
    result = []

    if len(word) <= 1:
        return result

    seen = set()

    # Python strings operate on Unicode code points.
    # This preserves UTF-8/Unicode characters correctly.
    for index in range(len(word)):
        deleted = word[:index] + word[index + 1:]

        if not deleted:
            continue

        if deleted in seen:
            continue

        seen.add(deleted)
        result.append(deleted)

        if len(result) >= max_deletes_per_word:
            break

    return result

# ------------------------------------------------------------
# Read complete dictionary.
# ------------------------------------------------------------

dictionary_lines = dictionary_file.read_text(
    encoding="utf-8"
).splitlines()

dictionary_words = set(
    nfc(word)
    for word in dictionary_lines[1:]
    if word.strip()
)

# ------------------------------------------------------------
# Read source in original frequency order.
# ------------------------------------------------------------

source_words = []

for raw in source_file.read_text(
    encoding="utf-8"
).splitlines():
    word = nfc(raw)

    if not word:
        continue

    source_words.append(word)

# ------------------------------------------------------------
# Priority words:
#
# Core first.
# Then top N FrequencyWords entries.
#
# Dictionary words outside this list remain in the dictionary,
# but do not consume delete-index space.
# ------------------------------------------------------------

priority = OrderedDict()

for word in core_words:
    word = nfc(word)

    if word in dictionary_words:
        priority.setdefault(word, None)

for word in source_words:
    if word not in dictionary_words:
        continue

    if word in priority:
        continue

    priority[word] = None

    if len(priority) >= priority_count + len(core_words):
        break

priority_words = list(priority.keys())

# ------------------------------------------------------------
# Build delete map.
#
# deleted form -> target words
#
# Ordered dictionaries make the output deterministic.
# ------------------------------------------------------------

delete_map = OrderedDict()
mapping_count = 0

for word in priority_words:
    for deleted in delete_variants(word):
        targets = delete_map.setdefault(deleted, [])

        if word in targets:
            continue

        targets.append(word)
        mapping_count += 1

# ------------------------------------------------------------
# Serialize candidates in deterministic order.
#
# We enforce the actual BYTE budget here.
#
# This is important: the previous script compared the byte
# size of the finished file against a budget while generating
# many more mappings than the budget could contain.
#
# Now the budget is enforced during generation.
# ------------------------------------------------------------

lines = []

header = [
    "# PocketBoard delete index",
    f"# language={language}",
    "# source=FrequencyWords",
    "# delete_distance=1",
]

current_size = sum(
    len((line + "\n").encode("utf-8"))
    for line in header
)

selected_mappings = 0
selected_keys = 0

# Prioritize shorter deleted strings first only for deterministic
# packing. The targets themselves retain their original priority.
for deleted in sorted(delete_map.keys(), key=lambda value: (value.casefold(), value)):

    targets = delete_map[deleted]

    # Format:
    #
    # deleted<TAB>target1,target2,...
    #
    target_text = ",".join(targets)

    line = f"{deleted}\t{target_text}\n"
    line_size = len(line.encode("utf-8"))

    if current_size + line_size > budget:
        continue

    lines.append(line)
    current_size += line_size
    selected_keys += 1
    selected_mappings += len(targets)

# ------------------------------------------------------------
# Write final delete index.
# ------------------------------------------------------------

with output_file.open("w", encoding="utf-8", newline="\n") as dst:
    for line in header:
        dst.write(line + "\n")

    for line in lines:
        dst.write(line)

actual_size = output_file.stat().st_size

print(f"Delete priority words: {len(priority_words)}")
print(f"Delete keys:           {selected_keys}")
print(f"Delete mappings:       {selected_mappings}")
print(f"Delete index bytes:    {actual_size}")
print(f"Delete budget:         {budget}")

if actual_size > budget:
    print(
        f"ERROR: {language} delete index exceeds budget "
        f"({actual_size} > {budget})",
        file=sys.stderr
    )
    sys.exit(1)
PY
}

ES_DELETES="${OUTPUT_ROOT}/es-AR.deletes"
EN_DELETES="${OUTPUT_ROOT}/en-en.deletes"
DE_DELETES="${OUTPUT_ROOT}/de-de.deletes"

generate_deletes \
    "es-AR" \
    "${ES_NORMALIZED}" \
    "${ES_DICT}" \
    "${ES_DELETES}" \
    "${ES_DELETE_BUDGET}" \
    "${ES_CORE[@]}"

generate_deletes \
    "en-en" \
    "${EN_NORMALIZED}" \
    "${EN_DICT}" \
    "${EN_DELETES}" \
    "${EN_DELETE_BUDGET}" \
    "${EN_CORE[@]}"

generate_deletes \
    "de-de" \
    "${DE_NORMALIZED}" \
    "${DE_DICT}" \
    "${DE_DELETES}" \
    "${DE_DELETE_BUDGET}" \
    "${DE_CORE[@]}"

# ------------------------------------------------------------
# Validate delete index targets
# ------------------------------------------------------------

validate_delete_targets() {
    local language="$1"
    local dictionary="$2"
    local deletes="$3"

    "${PYTHON_BIN}" - \
        "${language}" \
        "${dictionary}" \
        "${deletes}" <<'PY'
import sys
from pathlib import Path

language = sys.argv[1]
dictionary_file = Path(sys.argv[2])
delete_file = Path(sys.argv[3])

dictionary_lines = dictionary_file.read_text(
    encoding="utf-8"
).splitlines()

dictionary_words = set(
    dictionary_lines[1:]
)

delete_lines = delete_file.read_text(
    encoding="utf-8"
).splitlines()

errors = 0
mapping_count = 0

for line in delete_lines[4:]:
    if not line.strip():
        continue

    if "\t" not in line:
        print(
            f"ERROR: malformed delete entry in {language}: {line}",
            file=sys.stderr
        )
        errors += 1
        continue

    deleted, targets_text = line.split("\t", 1)

    if not deleted:
        print(
            f"ERROR: empty delete key in {language}",
            file=sys.stderr
        )
        errors += 1
        continue

    targets = [
        target
        for target in targets_text.split(",")
        if target
    ]

    for target in targets:
        mapping_count += 1

        if target not in dictionary_words:
            print(
                f"ERROR: delete target not in dictionary "
                f"({language}): {target}",
                file=sys.stderr
            )
            errors += 1

if errors:
    print(
        f"ERROR: {language} delete validation failed: {errors} errors",
        file=sys.stderr
    )
    sys.exit(1)

print(
    f"Delete validation OK: {language} "
    f"({mapping_count} mappings)"
)
PY
}

log ""
log "============================================================"
log " Validating delete indexes"
log "============================================================"

validate_delete_targets \
    "es-AR" \
    "${ES_DICT}" \
    "${ES_DELETES}"

validate_delete_targets \
    "en-en" \
    "${EN_DICT}" \
    "${EN_DELETES}"

validate_delete_targets \
    "de-de" \
    "${DE_DICT}" \
    "${DE_DELETES}"

# ------------------------------------------------------------
# Validate core dictionary words
# ------------------------------------------------------------

validate_core_words() {
    local language="$1"
    local dictionary="$2"
    shift 2

    local -a expected=("$@")

    "${PYTHON_BIN}" - \
        "${language}" \
        "${dictionary}" \
        "${expected[@]}" <<'PY'
import sys
from pathlib import Path

language = sys.argv[1]
dictionary_file = Path(sys.argv[2])
expected = sys.argv[3:]

words = set()

for word in dictionary_file.read_text(
    encoding="utf-8"
).splitlines()[1:]:
    words.add(word.casefold())

missing = []

print("")
print(f"Checking dictionary format: {language}")

for word in expected:
    normalized = word.casefold()

    if normalized in words:
        print(f"OK: {language}: {word}")
    else:
        print(
            f"WARNING: {language}: missing expected word: {word}"
        )
        missing.append(word)

if missing:
    print("")
    print(
        f"WARNING: {language} is missing "
        f"{len(missing)} expected words."
    )
else:
    print(
        f"All expected words are present."
    )
PY
}

log ""
log "============================================================"
log " Validating core vocabulary"
log "============================================================"

validate_core_words \
    "es-AR" \
    "${ES_DICT}" \
    "${ES_CORE[@]}"

validate_core_words \
    "en-en" \
    "${EN_DICT}" \
    "${EN_CORE[@]}"

validate_core_words \
    "de-de" \
    "${DE_DICT}" \
    "${DE_CORE[@]}"

# ------------------------------------------------------------
# Install generated assets
# ------------------------------------------------------------

log ""
log "============================================================"
log " Installing dictionary assets"
log "============================================================"

rm -f "${ASSETS_ROOT}/es-AR.dict"
rm -f "${ASSETS_ROOT}/es-AR.deletes"
rm -f "${ASSETS_ROOT}/es-AR.meta"

rm -f "${ASSETS_ROOT}/en-en.dict"
rm -f "${ASSETS_ROOT}/en-en.deletes"
rm -f "${ASSETS_ROOT}/en-en.meta"

rm -f "${ASSETS_ROOT}/de-de.dict"
rm -f "${ASSETS_ROOT}/de-de.deletes"
rm -f "${ASSETS_ROOT}/de-de.meta"

cp "${ES_DICT}" "${ASSETS_ROOT}/es-AR.dict"
cp "${ES_DELETES}" "${ASSETS_ROOT}/es-AR.deletes"
cp "${ES_META}" "${ASSETS_ROOT}/es-AR.meta"

cp "${EN_DICT}" "${ASSETS_ROOT}/en-en.dict"
cp "${EN_DELETES}" "${ASSETS_ROOT}/en-en.deletes"
cp "${EN_META}" "${ASSETS_ROOT}/en-en.meta"

cp "${DE_DICT}" "${ASSETS_ROOT}/de-de.dict"
cp "${DE_DELETES}" "${ASSETS_ROOT}/de-de.deletes"
cp "${DE_META}" "${ASSETS_ROOT}/de-de.meta"

# ------------------------------------------------------------
# Final size summary
# ------------------------------------------------------------

TOTAL_DICTIONARY_BYTES=0
TOTAL_DELETE_BYTES=0
TOTAL_METADATA_BYTES=0

log ""
log "============================================================"
log " PocketBoard dictionary build summary"
log "============================================================"

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do
    dictionary="${OUTPUT_ROOT}/${language}.dict"
    deletes="${OUTPUT_ROOT}/${language}.deletes"
    metadata="${OUTPUT_ROOT}/${language}.meta"

    dictionary_words="$(
        tail -n +2 "${dictionary}" | wc -l
    )"

    delete_keys="$(
        tail -n +5 "${deletes}" | wc -l
    )"

    delete_mappings="$(
        "${PYTHON_BIN}" - "${deletes}" <<'PY'
import sys

count = 0

with open(sys.argv[1], "r", encoding="utf-8") as src:
    for line in src.readlines()[4:]:
        line = line.rstrip("\n")

        if not line.strip():
            continue

        if "\t" not in line:
            continue

        _, targets = line.split("\t", 1)

        if targets:
            count += len(
                [
                    target
                    for target in targets.split(",")
                    if target
                ]
            )

print(count)
PY
    )"

    dictionary_size="$(wc -c < "${dictionary}")"
    delete_size="$(wc -c < "${deletes}")"
    metadata_size="$(wc -c < "${metadata}")"

    total_size="$(
        printf '%s\n' \
            "$((dictionary_size + delete_size + metadata_size))"
    )"

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

    log ""
    log "${language}"
    log "  Words:           ${dictionary_words}"
    log "  Delete keys:     ${delete_keys}"
    log "  Delete mappings: ${delete_mappings}"
    log "  Dictionary:      ${dictionary_size} bytes"
    log "  Delete index:    ${delete_size} bytes"
    log "  Metadata:        ${metadata_size} bytes"
    log "  Total:           ${total_size} bytes"
done

TOTAL_GENERATED_BYTES=$(
    printf '%s\n' \
        "$((TOTAL_DICTIONARY_BYTES + TOTAL_DELETE_BYTES + TOTAL_METADATA_BYTES))"
)

log ""
log "============================================================"
log " TOTAL"
log "============================================================"

log "Dictionary bytes: ${TOTAL_DICTIONARY_BYTES}"
log "Delete bytes:     ${TOTAL_DELETE_BYTES}"
log "Metadata bytes:   ${TOTAL_METADATA_BYTES}"
log "Generated bytes:  ${TOTAL_GENERATED_BYTES}"

TOTAL_MIB="$(
    "${PYTHON_BIN}" - "${TOTAL_GENERATED_BYTES}" <<'PY'
import sys

value = int(sys.argv[1])

print(f"{value / 1024 / 1024:.2f} MiB")
PY
)"

log "Approximate data: ${TOTAL_MIB}"

# ------------------------------------------------------------
# Final global delete budget validation
# ------------------------------------------------------------

log ""
log "Delete budget:"
log "  Spanish: ${ES_DELETE_BUDGET}"
log "  English: ${EN_DELETE_BUDGET}"
log "  German:  ${DE_DELETE_BUDGET}"
log "  Global:  ${GLOBAL_DELETE_BUDGET}"

if (( TOTAL_DELETE_BYTES > GLOBAL_DELETE_BUDGET )); then
    log ""
    log "ERROR: global delete budget exceeded."
    log "  Size:   ${TOTAL_DELETE_BYTES}"
    log "  Budget: ${GLOBAL_DELETE_BUDGET}"
    exit 1
fi

log ""
log "Global delete budget: OK"
log "Delete bytes used:    ${TOTAL_DELETE_BYTES}"
log "Delete bytes budget:  ${GLOBAL_DELETE_BUDGET}"

# ------------------------------------------------------------
# Final per-language budget validation
# ------------------------------------------------------------

if (( $(wc -c < "${ES_DELETES}") > ES_DELETE_BUDGET )); then
    die "Spanish delete index exceeds budget."
fi

if (( $(wc -c < "${EN_DELETES}") > EN_DELETE_BUDGET )); then
    die "English delete index exceeds budget."
fi

if (( $(wc -c < "${DE_DELETES}") > DE_DELETE_BUDGET )); then
    die "German delete index exceeds budget."
fi

# ------------------------------------------------------------
# Final asset listing
# ------------------------------------------------------------

log ""
log "============================================================"
log " Generated assets"
log "============================================================"

find "${ASSETS_ROOT}" \
    -maxdepth 1 \
    -type f \
    \( \
        -name 'es-AR.*' -o \
        -name 'en-en.*' -o \
        -name 'de-de.*' \
    \) \
    -printf '%f %s bytes\n' \
    | sort

log ""
log "============================================================"
log " PocketBoard dictionary generation completed successfully"
log "============================================================"
```
