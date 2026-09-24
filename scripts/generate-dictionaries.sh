#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# PocketBoard dictionary generator
#
# DIRECT-SOURCE VERSION
#
# IMPORTANT
#   - NO HUNSPELL
#   - FrequencyWords is used directly
#   - Sources are downloaded at build time
#   - Unicode/NFC is preserved
#   - Spanish accents, ñ and umlauts are preserved
#   - Argentine voseo forms are explicitly retained
#   - Safe Spanish accent corrections are stored as deletes
#   - Ambiguous Spanish words are NOT automatically removed
#   - Delete targets MUST exist in the final dictionary
#   - Runtime dictionary format remains:
#         .dict
#         .deletes
#         .meta
# ============================================================

set -euo pipefail

# ============================================================
# Configuration
# ============================================================

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BUILD_DIR="${ROOT_DIR}/build/pocketboard-dictionaries"
SOURCE_DIR="${BUILD_DIR}/sources"
NORMALIZED_DIR="${BUILD_DIR}/normalized"
WORK_DIR="${BUILD_DIR}/work"

ASSET_DIR="${ROOT_DIR}/app/src/main/assets/dictionaries"

mkdir -p \
    "${BUILD_DIR}" \
    "${SOURCE_DIR}" \
    "${NORMALIZED_DIR}" \
    "${WORK_DIR}" \
    "${ASSET_DIR}"

# ------------------------------------------------------------
# Dictionary limits
# ------------------------------------------------------------

MAX_WORDS=180000
MIN_WORD_LEN=2
MAX_WORD_LEN=40

# Delete generation
DELETE_WORDS=12000
MAX_DELETES_PER_WORD=2

MAX_DELETES_PER_LANGUAGE_BYTES=1500000
MAX_DELETES_GLOBAL_BYTES=4500000
MAX_DELETE_ENTRIES=220000

# ------------------------------------------------------------
# FrequencyWords sources
# ------------------------------------------------------------

ES_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_50k.txt"
EN_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt"
DE_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_50k.txt"

ES_SOURCE="${SOURCE_DIR}/es_50k.txt"
EN_SOURCE="${SOURCE_DIR}/en_50k.txt"
DE_SOURCE="${SOURCE_DIR}/de_50k.txt"

ES_NORMALIZED="${NORMALIZED_DIR}/es-AR.normalized.txt"
EN_NORMALIZED="${NORMALIZED_DIR}/en-en.normalized.txt"
DE_NORMALIZED="${NORMALIZED_DIR}/de-de.normalized.txt"

# ============================================================
# Helpers
# ============================================================

log() {
    printf '%s\n' "$*"
}

separator() {
    printf '%s\n' "============================================================"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

# ============================================================
# Required tools
# ============================================================

require_command curl
require_command python3
require_command awk
require_command sed
require_command sort
require_command wc
require_command tr

# ============================================================
# Clean generated build data
# ============================================================

rm -rf \
    "${NORMALIZED_DIR}" \
    "${WORK_DIR}"

mkdir -p \
    "${NORMALIZED_DIR}" \
    "${WORK_DIR}"

# ============================================================
# Download sources
# ============================================================

separator
echo "Downloading dictionary sources"
separator

download_source() {
    local url="$1"
    local output="$2"

    echo "Downloading:"
    echo "  ${url}"
    echo "  -> ${output}"

    curl \
        --fail \
        --location \
        --silent \
        --show-error \
        --retry 3 \
        --retry-delay 2 \
        "${url}" \
        -o "${output}"

    [[ -s "${output}" ]] || die "Downloaded source is empty: ${output}"
}

download_source "${ES_URL}" "${ES_SOURCE}"
download_source "${EN_URL}" "${EN_SOURCE}"
download_source "${DE_URL}" "${DE_SOURCE}"

# ============================================================
# Normalize source files
#
# Output format:
#   one normalized word per line
#
# FrequencyWords files normally contain:
#   word<TAB>frequency
#
# We only use the word itself.
# ============================================================

normalize_source() {
    local input="$1"
    local output="$2"

    python3 - "${input}" "${output}" <<'PY'
import sys
import unicodedata
import re

src = sys.argv[1]
dst = sys.argv[2]

# Letters:
#   Unicode letters
#
# Allowed punctuation:
#   '
#   ’
#   -
#
# We intentionally do NOT strip accents or Unicode letters.

allowed_re = re.compile(r"^[^\W\d_]+(?:['’\-][^\W\d_]+)*$", re.UNICODE)

seen = set()

with open(src, "r", encoding="utf-8", errors="replace") as fin:
    for raw in fin:
        raw = raw.rstrip("\r\n")

        if not raw:
            continue

        # FrequencyWords format:
        # word<TAB>frequency
        word = raw.split("\t", 1)[0].strip()

        if not word:
            continue

        word = unicodedata.normalize("NFC", word)
        word = word.lower()

        if len(word) < 2 or len(word) > 40:
            continue

        if not allowed_re.fullmatch(word):
            continue

        seen.add(word)

with open(dst, "w", encoding="utf-8") as fout:
    for word in sorted(seen):
        fout.write(word + "\n")

print(f"Normalized source words: {len(seen)}")
PY
}

separator
echo "Normalizing sources"
separator

normalize_source "${ES_SOURCE}" "${ES_NORMALIZED}"
normalize_source "${EN_SOURCE}" "${EN_NORMALIZED}"
normalize_source "${DE_SOURCE}" "${DE_NORMALIZED}"

# ============================================================
# Build dictionaries
# ============================================================

build_dictionary() {
    local language="$1"
    local normalized_source="$2"

    local dict_output="${WORK_DIR}/${language}.dict"
    local ranked_output="${WORK_DIR}/${language}.ranked"
    local accent_output="${WORK_DIR}/${language}.accent"
    local meta_output="${WORK_DIR}/${language}.meta"

    separator
    echo "Building dictionary: ${language}"
    separator

    python3 - \
        "${language}" \
        "${normalized_source}" \
        "${dict_output}" \
        "${ranked_output}" \
        "${accent_output}" \
        "${meta_output}" \
        "${MAX_WORDS}" \
        "${MIN_WORD_LEN}" \
        "${MAX_WORD_LEN}" <<'PY'
import sys
import unicodedata
from collections import defaultdict

language = sys.argv[1]
source_path = sys.argv[2]
dict_path = sys.argv[3]
ranked_path = sys.argv[4]
accent_path = sys.argv[5]
meta_path = sys.argv[6]

MAX_WORDS = int(sys.argv[7])
MIN_WORD_LEN = int(sys.argv[8])
MAX_WORD_LEN = int(sys.argv[9])

# ============================================================
# Core words
#
# IMPORTANT:
#   These are words that MUST be present in the dictionary.
#
# Unaccented Spanish correction forms are intentionally NOT
# placed here. They belong to SPANISH_ACCENT_CORRECTIONS below.
# ============================================================

CORE = {
    "es-AR": {
        # Argentine / general Spanish
        "vos",
        "decime",
        "haceme",

        # Correct accented voseo forms
        "tenés",
        "podés",
        "querés",
        "sabés",
        "venís",
        "decís",
        "hacés",
        "mirás",
        "hablás",
        "comés",
        "vivís",
        "salís",
        "vení",

        # Common words
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
    },

    "en-en": {
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
    },

    "de-de": {
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
    },
}

# ============================================================
# Explicit Spanish accent corrections
#
# These are NOT dictionary words.
#
# They are correction candidates:
#
#     unaccented -> accented
#
# Only safe / intentional corrections are included here.
#
# IMPORTANT:
#   Do NOT put ambiguous pairs here:
#
#     si / sí
#     el / él
#     tu / tú
#     mi / mí
#     te / té
#     se / sé
#     de / dé
#     solo / sólo
#     aun / aún
#     mas / más
#     esta / está
#     estas / estás
#     estan / están
#
# because the unaccented forms are legitimate Spanish words
# in other contexts.
# ============================================================

SPANISH_ACCENT_CORRECTIONS = {
    # Common accent omissions
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

    # Argentine voseo
    "tenes": "tenés",
    "podes": "podés",
    "queres": "querés",
    "sabes": "sabés",
    "venis": "venís",
    "decis": "decís",
    "haces": "hacés",
    "miras": "mirás",
    "hablas": "hablás",
    "comes": "comés",
    "vivis": "vivís",
    "salis": "salís",
    "veni": "vení",
}

# ============================================================
# Valid words which happen to have an accented counterpart
#
# These MUST remain in the dictionary.
#
# Example:
#
#   sabes = valid standard Spanish form
#   sabés = voseo
#
# Therefore "sabes" must NOT be deleted automatically.
#
# The explicit correction map above is authoritative for forms
# that we deliberately want to treat as correction candidates.
# ============================================================

SPANISH_KEEP_UNACCENTED = {
    "haces",
    "comes",
    "sabes",
    "miras",
    "hablas",
}

# ============================================================
# General normalization helper
# ============================================================

def normalize(word):
    return unicodedata.normalize("NFC", word.strip().lower())

def without_diacritics(word):
    decomposed = unicodedata.normalize("NFD", word)
    return "".join(
        c for c in decomposed
        if unicodedata.category(c) != "Mn"
    )

# ============================================================
# Read source
#
# The source has already been normalized to one word per line.
# We preserve source order because FrequencyWords ordering is
# frequency-ranked.
# ============================================================

source_words = []

with open(source_path, "r", encoding="utf-8") as f:
    for line in f:
        word = normalize(line)

        if not word:
            continue

        if len(word) < MIN_WORD_LEN:
            continue

        if len(word) > MAX_WORD_LEN:
            continue

        source_words.append(word)

# Deduplicate while preserving source ranking.
seen = set()
ranked_source = []

for word in source_words:
    if word in seen:
        continue

    seen.add(word)
    ranked_source.append(word)

source_set = set(ranked_source)

# ============================================================
# Start with source words
# ============================================================

selected = set(ranked_source[:MAX_WORDS])

# Always retain CORE.
selected.update(CORE.get(language, set()))

# ============================================================
# Build explicit correction targets
#
# Targets are always dictionary words.
# ============================================================

explicit_corrections = {}

if language == "es-AR":
    for source_word, target_word in SPANISH_ACCENT_CORRECTIONS.items():
        source_word = normalize(source_word)
        target_word = normalize(target_word)

        if target_word in CORE[language] or target_word in source_set:
            explicit_corrections[source_word] = target_word

# ============================================================
# Remove unsafe / intentionally corrected Spanish candidates
#
# IMPORTANT:
#
# We do NOT perform broad:
#
#   unaccented -> accented
#
# removal.
#
# Instead:
#   1. Explicit corrections are authoritative.
#   2. Other valid words remain.
#
# This prevents:
#
#   sabes -> sabés
#   haces -> hacés
#   comes -> comés
#   miras -> mirás
#   hablas -> hablás
#
# from being incorrectly removed.
# ============================================================

removed_corrections = []

if language == "es-AR":
    for source_word, target_word in explicit_corrections.items():

        # The target must exist in the final dictionary.
        selected.add(target_word)

        # The unaccented correction candidate must NOT remain
        # in the dictionary.
        if source_word in selected:
            selected.remove(source_word)

        removed_corrections.append(
            (source_word, target_word)
        )

# ============================================================
# Keep all CORE words.
#
# This is done AFTER correction cleanup so an accidental source
# collision cannot remove an actual required CORE word.
#
# Explicit correction source words are deliberately excluded
# from this restoration.
# ============================================================

correction_sources = set(explicit_corrections.keys())

for word in CORE.get(language, set()):
    if word in correction_sources:
        continue

    selected.add(word)

# ============================================================
# Rebuild ranked list
#
# Source order is retained.
# Core-only words are appended.
# ============================================================

final_ranked = []

already_added = set()

for word in ranked_source:
    if word not in selected:
        continue

    if word in already_added:
        continue

    final_ranked.append(word)
    already_added.add(word)

# Add CORE words not present in source.
for word in sorted(CORE.get(language, set())):
    if word in correction_sources:
        continue

    if word in already_added:
        continue

    final_ranked.append(word)
    already_added.add(word)

# Enforce MAX_WORDS only for source-derived words.
#
# CORE words are never discarded.
core_words = set(CORE.get(language, set())) - correction_sources

if len(final_ranked) > MAX_WORDS:
    retained = []

    # Keep all CORE first.
    for word in final_ranked:
        if word in core_words:
            retained.append(word)

    # Fill remaining capacity from ranked source.
    remaining_capacity = max(
        0,
        MAX_WORDS - len(retained)
    )

    for word in final_ranked:
        if word in core_words:
            continue

        if len(retained) >= len(core_words) + remaining_capacity:
            break

        retained.append(word)

    final_ranked = retained

# ============================================================
# Final dictionary set
# ============================================================

final_words = set(final_ranked)

# Explicit correction targets MUST be in final dictionary.
for source_word, target_word in explicit_corrections.items():
    final_words.add(target_word)

    # Ensure target is also present in ranked list.
    if target_word not in already_added:
        final_ranked.append(target_word)
        already_added.add(target_word)

# Remove correction sources again after all additions.
for source_word in explicit_corrections:
    final_words.discard(source_word)
    final_ranked = [
        w for w in final_ranked
        if w != source_word
    ]

# ============================================================
# Safety:
# no empty words, invalid lengths, or duplicates
# ============================================================

clean_ranked = []
seen_final = set()

for word in final_ranked:
    word = normalize(word)

    if not word:
        continue

    if len(word) < MIN_WORD_LEN:
        continue

    if len(word) > MAX_WORD_LEN:
        continue

    if word in correction_sources:
        continue

    if word in seen_final:
        continue

    seen_final.add(word)
    clean_ranked.append(word)

final_ranked = clean_ranked
final_words = set(final_ranked)

# Make absolutely sure every CORE word is present.
missing_core = sorted(
    word
    for word in core_words
    if word not in final_words
)

if missing_core:
    print("ERROR: required CORE words are missing:")
    for word in missing_core:
        print(f"  {word}")
    sys.exit(1)

# Make absolutely sure explicit correction targets exist.
missing_targets = sorted(
    target
    for target in explicit_corrections.values()
    if target not in final_words
)

if missing_targets:
    print("ERROR: correction targets are missing:")
    for word in missing_targets:
        print(f"  {word}")
    sys.exit(1)

# ============================================================
# Build accent mapping
#
# Format:
#
#   source<TAB>target
#
# This file is consumed by the delete-index generator.
# ============================================================

accent_mappings = []

for source_word, target_word in sorted(explicit_corrections.items()):
    if source_word == target_word:
        continue

    if target_word not in final_words:
        continue

    accent_mappings.append(
        (source_word, target_word)
    )

# ============================================================
# Write dictionary
# ============================================================

with open(dict_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("#POCKETBOARD-DICT-1\n")

    for word in final_ranked:
        f.write(word + "\n")

# ============================================================
# Write ranked list
#
# Temporary build artifact.
# ============================================================

with open(ranked_path, "w", encoding="utf-8", newline="\n") as f:
    for word in final_ranked:
        f.write(word + "\n")

# ============================================================
# Write accent mappings
# ============================================================

with open(accent_path, "w", encoding="utf-8", newline="\n") as f:
    for source_word, target_word in accent_mappings:
        f.write(f"{source_word}\t{target_word}\n")

# ============================================================
# Metadata
# ============================================================

dictionary_bytes = len(
    open(dict_path, "rb").read()
)

metadata = [
    ("language", language),
    ("source_words", str(len(source_set))),
    ("dictionary_words", str(len(final_words))),
    ("core_words", str(len(core_words))),
    ("accent_corrections", str(len(accent_mappings))),
    ("dictionary_bytes", str(dictionary_bytes)),
]

with open(meta_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("#POCKETBOARD-META-1\n")

    for key, value in metadata:
        f.write(f"{key}={value}\n")

# ============================================================
# Build summary
# ============================================================

print(f"Source words: {len(source_set)}")
print(f"Core words:   {len(core_words)}")
print(f"Final words:  {len(final_words)}")

if language == "es-AR":
    print(f"Accent corrections: {len(accent_mappings)}")

    for source_word, target_word in removed_corrections:
        print(
            f"ACCENT CORRECTION: "
            f"{source_word} -> {target_word}"
        )
PY

    # --------------------------------------------------------
    # Display build statistics
    # --------------------------------------------------------

    echo
    echo "Dictionary generated:"
    echo "  ${dict_output}"

    echo "Words:"
    grep -v '^#' "${dict_output}" | wc -l

    echo "Dictionary bytes:"
    wc -c < "${dict_output}"

    echo
}

# ============================================================
# Build all dictionaries
# ============================================================

build_dictionary \
    "es-AR" \
    "${ES_NORMALIZED}"

build_dictionary \
    "en-en" \
    "${EN_NORMALIZED}"

build_dictionary \
    "de-de" \
    "${DE_NORMALIZED}"

# ============================================================
# Build delete indexes
#
# Delete algorithm:
#
#   For each high-frequency word:
#       remove one character at a time
#
# Example:
#
#   hello
#
# can generate:
#
#   ello
#   hllo
#
# etc.
#
# We limit the number of deletes per word.
#
# Accent correction mappings are also inserted.
# ============================================================

build_delete_index() {
    local language="$1"

    local dict_input="${WORK_DIR}/${language}.dict"
    local ranked_input="${WORK_DIR}/${language}.ranked"
    local accent_input="${WORK_DIR}/${language}.accent"

    local delete_output="${WORK_DIR}/${language}.deletes"

    separator
    echo "Building delete index: ${language}"
    separator

    python3 - \
        "${language}" \
        "${dict_input}" \
        "${ranked_input}" \
        "${accent_input}" \
        "${delete_output}" \
        "${DELETE_WORDS}" \
        "${MAX_DELETES_PER_WORD}" \
        "${MAX_DELETES_PER_LANGUAGE_BYTES}" \
        "${MAX_DELETE_ENTRIES}" <<'PY'
import sys

language = sys.argv[1]
dict_path = sys.argv[2]
ranked_path = sys.argv[3]
accent_path = sys.argv[4]
output_path = sys.argv[5]

DELETE_WORDS = int(sys.argv[6])
MAX_DELETES_PER_WORD = int(sys.argv[7])
MAX_BYTES = int(sys.argv[8])
MAX_ENTRIES = int(sys.argv[9])

# ============================================================
# Read dictionary
# ============================================================

dictionary = set()

with open(dict_path, "r", encoding="utf-8") as f:
    for line in f:
        word = line.rstrip("\r\n")

        if not word:
            continue

        if word.startswith("#"):
            continue

        dictionary.add(word)

# ============================================================
# Read ranking
# ============================================================

ranked = []

with open(ranked_path, "r", encoding="utf-8") as f:
    for line in f:
        word = line.rstrip("\r\n")

        if not word:
            continue

        if word in dictionary:
            ranked.append(word)

# ============================================================
# Mapping:
#
# delete_key -> target_word
#
# Each delete key can point to one or more valid words.
# ============================================================

mapping = {}

def add_mapping(key, target):
    if not key:
        return

    if target not in dictionary:
        return

    bucket = mapping.setdefault(key, [])

    if target in bucket:
        return

    bucket.append(target)

# ============================================================
# Generate delete candidates
# ============================================================

selected_ranked = ranked[:DELETE_WORDS]

for word in selected_ranked:

    # Do not generate a delete from extremely short words.
    if len(word) <= 2:
        continue

    count = 0

    # Generate at most MAX_DELETES_PER_WORD.
    #
    # We use the first positions for deterministic output.
    for index in range(len(word)):

        if count >= MAX_DELETES_PER_WORD:
            break

        delete_key = word[:index] + word[index + 1:]

        if not delete_key:
            continue

        add_mapping(delete_key, word)
        count += 1

# ============================================================
# Add explicit accent corrections
#
# Example:
#
#   tenes -> tenés
#
# The correction source itself is NOT required to be in the
# dictionary.
# ============================================================

with open(accent_path, "r", encoding="utf-8") as f:
    for line in f:
        line = line.rstrip("\r\n")

        if not line:
            continue

        parts = line.split("\t", 1)

        if len(parts) != 2:
            continue

        source_word, target_word = parts

        if target_word not in dictionary:
            continue

        add_mapping(source_word, target_word)

# ============================================================
# Deterministic output
# ============================================================

entries = []

for key in sorted(mapping):
    targets = mapping[key]

    for target in targets:
        entries.append((key, target))

# Limit entries.
if len(entries) > MAX_ENTRIES:
    entries = entries[:MAX_ENTRIES]

# ============================================================
# Header
# ============================================================

lines = [
    "#POCKETBOARD-DELETES-1\n"
]

current_bytes = len(lines[0].encode("utf-8"))
written = 0

for key, target in entries:
    line = f"{key}\t{target}\n"
    line_bytes = len(line.encode("utf-8"))

    if current_bytes + line_bytes > MAX_BYTES:
        break

    lines.append(line)
    current_bytes += line_bytes
    written += 1

with open(output_path, "w", encoding="utf-8", newline="\n") as f:
    f.writelines(lines)

# ============================================================
# Statistics
# ============================================================

print(f"Delete mappings: {written}")
print(f"Delete bytes:    {current_bytes}")
print(f"Delete output:   {output_path}")
PY

    echo
}

# ============================================================
# Build delete indexes
# ============================================================

build_delete_index "es-AR"
build_delete_index "en-en"
build_delete_index "de-de"

# ============================================================
# Copy final assets
# ============================================================

separator
echo "Installing dictionary assets"
separator

rm -f \
    "${ASSET_DIR}/es-AR.dict" \
    "${ASSET_DIR}/es-AR.deletes" \
    "${ASSET_DIR}/es-AR.meta" \
    "${ASSET_DIR}/en-en.dict" \
    "${ASSET_DIR}/en-en.deletes" \
    "${ASSET_DIR}/en-en.meta" \
    "${ASSET_DIR}/de-de.dict" \
    "${ASSET_DIR}/de-de.deletes" \
    "${ASSET_DIR}/de-de.meta"

cp "${WORK_DIR}/es-AR.dict" \
   "${ASSET_DIR}/es-AR.dict"

cp "${WORK_DIR}/es-AR.deletes" \
   "${ASSET_DIR}/es-AR.deletes"

cp "${WORK_DIR}/es-AR.meta" \
   "${ASSET_DIR}/es-AR.meta"

cp "${WORK_DIR}/en-en.dict" \
   "${ASSET_DIR}/en-en.dict"

cp "${WORK_DIR}/en-en.deletes" \
   "${ASSET_DIR}/en-en.deletes"

cp "${WORK_DIR}/en-en.meta" \
   "${ASSET_DIR}/en-en.meta"

cp "${WORK_DIR}/de-de.dict" \
   "${ASSET_DIR}/de-de.dict"

cp "${WORK_DIR}/de-de.deletes" \
   "${ASSET_DIR}/de-de.deletes"

cp "${WORK_DIR}/de-de.meta" \
   "${ASSET_DIR}/de-de.meta"

# ============================================================
# Validation helpers
# ============================================================

validate_dictionary_words() {
    local language="$1"
    local dictionary="${ASSET_DIR}/${language}.dict"

    shift

    separator
    echo "Checking dictionary format: ${language}"
    separator

    [[ -f "${dictionary}" ]] \
        || die "Missing dictionary: ${dictionary}"

    head -n 1 "${dictionary}" | grep -qx \
        "#POCKETBOARD-DICT-1" \
        || die "Invalid dictionary header: ${language}"

    local word

    for word in "$@"; do
        if grep -Fxq "${word}" "${dictionary}"; then
            echo "OK: ${language}: ${word}"
        else
            echo "ERROR: ${language}: missing ${word}"
            exit 1
        fi
    done
}

# ============================================================
# Validate expected dictionary words
# ============================================================

validate_dictionary_words \
    "es-AR" \
    "vos" \
    "tenés" \
    "podés" \
    "querés" \
    "sabés" \
    "venís" \
    "decís" \
    "hacés" \
    "mirás" \
    "hablás" \
    "comés" \
    "vivís" \
    "salís" \
    "vení" \
    "mañana" \
    "también" \
    "qué" \
    "cómo" \
    "cuándo" \
    "dónde" \
    "quién" \
    "porque" \
    "porqué" \
    "día" \
    "días" \
    "más" \
    "sí" \
    "está" \
    "estás" \
    "están" \
    "acá" \
    "allá" \
    "después" \
    "así" \
    "sólo" \
    "pasaría" \
    "debería" \
    "hago" \
    "hacer" \
    "veré"

validate_dictionary_words \
    "en-en" \
    "the" \
    "have" \
    "hello" \
    "world"

validate_dictionary_words \
    "de-de" \
    "ich" \
    "nicht" \
    "morgen" \
    "entschuldigung" \
    "wahrscheinlich" \
    "möglicherweise" \
    "für"

# ============================================================
# Validate Spanish false candidates
#
# These must NOT exist in the dictionary.
# ============================================================

separator
echo "Checking Spanish false candidates"
separator

ES_DICT="${ASSET_DIR}/es-AR.dict"

SPANISH_FALSE_CANDIDATES=(
    "asi"
    "aca"
    "alla"
    "despues"
    "deberia"
    "dia"
    "dias"
    "manana"
    "mananas"
    "tambien"
    "pasaria"
    "tenes"
    "podes"
    "queres"
    "sabes"
    "venis"
    "decis"
    "haces"
    "miras"
    "hablas"
    "comes"
    "vivis"
    "salis"
    "veni"
)

false_candidates_found=0

for word in "${SPANISH_FALSE_CANDIDATES[@]}"; do
    if grep -Fxq "${word}" "${ES_DICT}"; then
        echo "ERROR: unaccented false candidate remains: ${word}"
        false_candidates_found=1
    fi
done

if [[ "${false_candidates_found}" -ne 0 ]]; then
    echo
    echo "ERROR: unaccented Spanish correction candidates remain in es-AR.dict."
    exit 1
fi

echo "OK: no explicit Spanish correction candidates remain in es-AR.dict"

# ============================================================
# Validate that legitimate unaccented forms remain
#
# These are NOT errors:
#
#   haces
#   comes
#   sabes
#   miras
#   hablas
#
# They are valid Spanish forms and therefore must remain.
# ============================================================

separator
echo "Checking legitimate Spanish unaccented forms"
separator

SPANISH_LEGITIMATE_FORMS=(
    "haces"
    "comes"
    "sabes"
    "miras"
    "hablas"
)

for word in "${SPANISH_LEGITIMATE_FORMS[@]}"; do
    if grep -Fxq "${word}" "${ES_DICT}"; then
        echo "OK: legitimate form retained: ${word}"
    else
        echo "ERROR: legitimate Spanish form was removed: ${word}"
        exit 1
    fi
done

# ============================================================
# Validate accent correction mappings
# ============================================================

separator
echo "Checking Spanish accent correction mappings"
separator

ES_DELETES="${ASSET_DIR}/es-AR.deletes"

validate_accent_mapping() {
    local source="$1"
    local target="$2"

    if grep -Fqx "${source}"$'\t'"${target}" "${ES_DELETES}"; then
        echo "OK: ${source} -> ${target}"
    else
        echo "ERROR: missing correction mapping: ${source} -> ${target}"
        exit 1
    fi
}

validate_accent_mapping "asi" "así"
validate_accent_mapping "aca" "acá"
validate_accent_mapping "alla" "allá"
validate_accent_mapping "despues" "después"
validate_accent_mapping "deberia" "debería"
validate_accent_mapping "dia" "día"
validate_accent_mapping "dias" "días"
validate_accent_mapping "manana" "mañana"
validate_accent_mapping "mananas" "mañanas"
validate_accent_mapping "tambien" "también"
validate_accent_mapping "pasaria" "pasaría"

validate_accent_mapping "tenes" "tenés"
validate_accent_mapping "podes" "podés"
validate_accent_mapping "queres" "querés"
validate_accent_mapping "sabes" "sabés"
validate_accent_mapping "venis" "venís"
validate_accent_mapping "decis" "decís"
validate_accent_mapping "haces" "hacés"
validate_accent_mapping "miras" "mirás"
validate_accent_mapping "hablas" "hablás"
validate_accent_mapping "comes" "comés"
validate_accent_mapping "vivis" "vivís"
validate_accent_mapping "salis" "salís"
validate_accent_mapping "veni" "vení"

# ============================================================
# Validate delete files
# ============================================================

validate_delete_file() {
    local language="$1"

    local dictionary="${ASSET_DIR}/${language}.dict"
    local deletes="${ASSET_DIR}/${language}.deletes"

    separator
    echo "Checking delete index: ${language}"
    separator

    [[ -f "${deletes}" ]] \
        || die "Missing delete file: ${deletes}"

    head -n 1 "${deletes}" | grep -qx \
        "#POCKETBOARD-DELETES-1" \
        || die "Invalid delete header: ${language}"

    python3 - \
        "${dictionary}" \
        "${deletes}" <<'PY'
import sys

dictionary_path = sys.argv[1]
deletes_path = sys.argv[2]

dictionary = set()

with open(dictionary_path, "r", encoding="utf-8") as f:
    for line in f:
        word = line.rstrip("\r\n")

        if not word or word.startswith("#"):
            continue

        dictionary.add(word)

errors = 0
entries = 0

with open(deletes_path, "r", encoding="utf-8") as f:
    first = True

    for line_number, line in enumerate(f, 1):
        line = line.rstrip("\r\n")

        if first:
            first = False

            if line != "#POCKETBOARD-DELETES-1":
                print("ERROR: invalid delete header")
                sys.exit(1)

            continue

        if not line:
            continue

        parts = line.split("\t", 1)

        if len(parts) != 2:
            print(
                f"ERROR: malformed delete entry at line "
                f"{line_number}: {line}"
            )
            errors += 1
            continue

        source, target = parts

        if target not in dictionary:
            print(
                f"ERROR: delete target not in dictionary: "
                f"{source} -> {target}"
            )
            errors += 1

        entries += 1

if errors:
    print(f"ERROR: {errors} invalid delete entries")
    sys.exit(1)

print(f"OK: {entries} delete mappings")
PY
}

validate_delete_file "es-AR"
validate_delete_file "en-en"
validate_delete_file "de-de"

# ============================================================
# Remove temporary files from app source
#
# Only final runtime files remain in assets.
# ============================================================

rm -f \
    "${ASSET_DIR}/es-AR.ranked" \
    "${ASSET_DIR}/es-AR.accent" \
    "${ASSET_DIR}/en-en.ranked" \
    "${ASSET_DIR}/en-en.accent" \
    "${ASSET_DIR}/de-de.ranked" \
    "${ASSET_DIR}/de-de.accent"

# ============================================================
# Final size report
# ============================================================

separator
echo "PocketBoard dictionary build summary"
separator

total_bytes=0

for language in es-AR en-en de-de; do

    dict="${ASSET_DIR}/${language}.dict"
    deletes="${ASSET_DIR}/${language}.deletes"
    meta="${ASSET_DIR}/${language}.meta"

    dict_bytes="$(wc -c < "${dict}")"
    delete_bytes="$(wc -c < "${deletes}")"
    meta_bytes="$(wc -c < "${meta}")"

    word_count="$(
        grep -v '^#' "${dict}" | wc -l
    )"

    delete_count="$(
        grep -v '^#' "${deletes}" | wc -l
    )"

    language_total=$(
        python3 - \
            "${dict_bytes}" \
            "${delete_bytes}" \
            "${meta_bytes}" <<'PY'
import sys

print(
    int(sys.argv[1])
    + int(sys.argv[2])
    + int(sys.argv[3])
)
PY
    )

    total_bytes=$(
        python3 - \
            "${total_bytes}" \
            "${language_total}" <<'PY'
import sys

print(int(sys.argv[1]) + int(sys.argv[2]))
PY
    )

    echo
    echo "${language}"
    echo "  Words:           ${word_count}"
    echo "  Dictionary:      ${dict_bytes} bytes"
    echo "  Delete index:    ${delete_bytes} bytes"
    echo "  Delete mappings: ${delete_count}"
    echo "  Metadata:        ${meta_bytes} bytes"
    echo "  Total:           ${language_total} bytes"
done

separator
echo "TOTAL DICTIONARY ASSETS"
separator

echo "Total bytes: ${total_bytes}"

# ============================================================
# Global delete budget
# ============================================================

global_delete_bytes=$(
    python3 - \
        "${ASSET_DIR}/es-AR.deletes" \
        "${ASSET_DIR}/en-en.deletes" \
        "${ASSET_DIR}/de-de.deletes" <<'PY'
import sys
from pathlib import Path

total = 0

for path in sys.argv[1:]:
    total += Path(path).stat().st_size

print(total)
PY
)

global_delete_entries=$(
    python3 - \
        "${ASSET_DIR}/es-AR.deletes" \
        "${ASSET_DIR}/en-en.deletes" \
        "${ASSET_DIR}/de-de.deletes" <<'PY'
import sys

total = 0

for path in sys.argv[1:]:
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip() and not line.startswith("#"):
                total += 1

print(total)
PY
)

echo
echo "Global delete index:"
echo "  Bytes:   ${global_delete_bytes}"
echo "  Entries: ${global_delete_entries}"
echo "  Budget:  ${MAX_DELETES_GLOBAL_BYTES} bytes"
echo "  Entries: ${MAX_DELETE_ENTRIES}"

if (( global_delete_bytes > MAX_DELETES_GLOBAL_BYTES )); then
    die "Global delete index exceeds ${MAX_DELETES_GLOBAL_BYTES} bytes"
fi

if (( global_delete_entries > MAX_DELETE_ENTRIES )); then
    die "Global delete index exceeds ${MAX_DELETE_ENTRIES} entries"
fi

# ============================================================
# Per-language delete budget
# ============================================================

for language in es-AR en-en de-de; do
    delete_file="${ASSET_DIR}/${language}.deletes"
    delete_bytes="$(wc -c < "${delete_file}")"

    if (( delete_bytes > MAX_DELETES_PER_LANGUAGE_BYTES )); then
        die "${language}.deletes exceeds ${MAX_DELETES_PER_LANGUAGE_BYTES} bytes"
    fi
done

# ============================================================
# Final sanity checks
# ============================================================

separator
echo "Final sanity checks"
separator

for language in es-AR en-en de-de; do

    dict="${ASSET_DIR}/${language}.dict"
    deletes="${ASSET_DIR}/${language}.deletes"
    meta="${ASSET_DIR}/${language}.meta"

    [[ -s "${dict}" ]] \
        || die "Empty dictionary: ${dict}"

    [[ -s "${deletes}" ]] \
        || die "Empty delete index: ${deletes}"

    [[ -s "${meta}" ]] \
        || die "Empty metadata: ${meta}"

    grep -q '^#POCKETBOARD-DICT-1$' "${dict}" \
        || die "Missing dictionary header: ${language}"

    grep -q '^#POCKETBOARD-DELETES-1$' "${deletes}" \
        || die "Missing delete header: ${language}"

    echo "OK: ${language}"
done

# ============================================================
# Check that no temporary source/build artifacts were copied
# into the runtime assets directory.
# ============================================================

if find "${ASSET_DIR}" -maxdepth 1 -type f \
    \( \
        -name '*.ranked' \
        -o -name '*.accent' \
        -o -name '*.tmp' \
    \) \
    | grep -q .; then

    echo "ERROR: temporary dictionary artifacts found in assets:"
    find "${ASSET_DIR}" -maxdepth 1 -type f \
        \( \
            -name '*.ranked' \
            -o -name '*.accent' \
            -o -name '*.tmp' \
        \)
    exit 1
fi

# ============================================================
# Final result
# ============================================================

separator
echo "Dictionary generation completed successfully."
separator

echo
echo "Runtime assets:"
echo "  ${ASSET_DIR}/es-AR.dict"
echo "  ${ASSET_DIR}/es-AR.deletes"
echo "  ${ASSET_DIR}/es-AR.meta"
echo
echo "  ${ASSET_DIR}/en-en.dict"
echo "  ${ASSET_DIR}/en-en.deletes"
echo "  ${ASSET_DIR}/en-en.meta"
echo
echo "  ${ASSET_DIR}/de-de.dict"
echo "  ${ASSET_DIR}/de-de.deletes"
echo "  ${ASSET_DIR}/de-de.meta"
echo
echo "No Hunspell validation is used."
echo "Sources are downloaded at build time."
echo "Spanish explicit accent corrections are stored in .deletes."
echo "Ambiguous Spanish words are preserved."
echo "============================================================"
