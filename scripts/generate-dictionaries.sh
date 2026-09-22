#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# PocketBoard dictionary generator
#
# Design goals:
#   - Unicode/NFC-safe vocabulary
#   - NEVER strip accents/diacritics
#   - frequency-first vocabulary
#   - curated mandatory/core vocabulary
#   - Hunspell .dic is the lexical source, NOT a per-word validator
#   - strict token validation
#   - explicit es-AR support, including voseo
#   - generate .dict only from validated/accepted vocabulary
#   - generate edit-distance deletes ONLY from words present in .dict
#   - distance-1 deletes broadly
#   - distance-2 deletes concentrated on high-value words
#   - deterministic output
#   - bounded memory where practical
###############################################################################

SCRIPT_NAME="PocketBoard dictionary generator"

###############################################################################
# Configuration
###############################################################################

ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

DATA_DIR="${DATA_DIR:-${ROOT_DIR}/dictionary-data}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/generated-dictionaries}"

# Maximum number of normal dictionary entries.
MAX_WORDS="${MAX_WORDS:-180000}"

# Number of high-frequency/core words receiving distance-2 deletes.
DIST2_WORDS="${DIST2_WORDS:-12000}"

# Maximum number of deletes retained per source word.
MAX_DELETES_PER_WORD="${MAX_DELETES_PER_WORD:-96}"

# Maximum total delete entries.
MAX_DELETE_ENTRIES="${MAX_DELETE_ENTRIES:-2500000}"

# Minimum accepted token length.
MIN_WORD_LEN="${MIN_WORD_LEN:-2}"

# Maximum accepted token length.
MAX_WORD_LEN="${MAX_WORD_LEN:-40}"

PYTHON_BIN="${PYTHON_BIN:-python3}"

###############################################################################
# Locale configuration
###############################################################################

LANGUAGES=(
    "es-AR"
    "en"
    "de"
)

###############################################################################
# Expected input layout
#
# dictionary-data/
#
#   es-AR/
#       dictionary.dic
#       frequency.txt
#       core.txt
#
#   en/
#       dictionary.dic
#       frequency.txt
#       core.txt
#
#   de/
#       dictionary.dic
#       frequency.txt
#       core.txt
#
# Hunspell .dic:
#   one lexical entry per line
#
# frequency.txt:
#   either:
#       word
#   or:
#       word frequency
#
# core.txt:
#   one mandatory word per line
#
###############################################################################

###############################################################################
# Utilities
###############################################################################

die() {
    echo
    echo "ERROR: $*" >&2
    exit 1
}

log() {
    printf '%s\n' "$*"
}

separator() {
    printf '\n============================================================\n'
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        die "Required command not found: $1"
}

###############################################################################
# Basic checks
###############################################################################

require_command "$PYTHON_BIN"
require_command "sort"
require_command "awk"
require_command "sed"
require_command "mktemp"

mkdir -p "$OUT_DIR"

###############################################################################
# Temporary workspace
###############################################################################

TMP_DIR="$(mktemp -d)"

cleanup() {
    rm -rf "$TMP_DIR"
}

trap cleanup EXIT INT TERM

###############################################################################
# Python helper
#
# IMPORTANT:
# All arguments are explicitly passed.
# This fixes the previous:
#
#   IndexError: list index out of range
#
# caused by Python expecting arguments that Bash never supplied.
###############################################################################

build_language() {
    local lang="$1"
    local frequency="$2"
    local core="$3"
    local hunspell="$4"
    local output="$5"

    separator
    log "Building dictionary: ${lang}"
    separator

    mkdir -p "$output"

    "$PYTHON_BIN" - \
        "$frequency" \
        "$core" \
        "$hunspell" \
        "$output" \
        "$MAX_WORDS" \
        "$DIST2_WORDS" \
        "$MAX_DELETES_PER_WORD" \
        "$MAX_DELETE_ENTRIES" \
        "$MIN_WORD_LEN" \
        "$MAX_WORD_LEN" \
        "$lang" <<'PY'
import sys
import os
import re
import unicodedata
from collections import defaultdict

###############################################################################
# Explicit arguments
###############################################################################

if len(sys.argv) < 11:
    raise SystemExit(
        "Internal error: expected frequency, core, hunspell, output, "
        "MAX_WORDS, DIST2_WORDS, MAX_DELETES_PER_WORD, "
        "MAX_DELETE_ENTRIES, MIN_WORD_LEN, MAX_WORD_LEN and language"
    )

frequency_path = sys.argv[1]
core_path = sys.argv[2]
hunspell_path = sys.argv[3]
output_dir = sys.argv[4]

MAX_WORDS = int(sys.argv[5])
DIST2_WORDS = int(sys.argv[6])
MAX_DELETES_PER_WORD = int(sys.argv[7])
MAX_DELETE_ENTRIES = int(sys.argv[8])
MIN_WORD_LEN = int(sys.argv[9])
MAX_WORD_LEN = int(sys.argv[10])
LANGUAGE = sys.argv[11]

###############################################################################
# Unicode / token rules
###############################################################################

def nfc(value):
    return unicodedata.normalize("NFC", value)

def is_valid_token(word):
    """
    Strict lexical token validation.

    We deliberately DO NOT:
      - lowercase accented characters away
      - strip accents
      - ASCII-fold
      - transliterate
      - replace ñ
      - replace ü
      - replace á/é/í/ó/ú
    """

    if not word:
        return False

    word = nfc(word).strip()

    if not word:
        return False

    if len(word) < MIN_WORD_LEN:
        return False

    if len(word) > MAX_WORD_LEN:
        return False

    # No whitespace.
    if any(ch.isspace() for ch in word):
        return False

    # No control characters.
    if any(unicodedata.category(ch).startswith("C") for ch in word):
        return False

    # Remove Hunspell flags / morphological syntax before this function.
    #
    # Accepted:
    #   letters
    #   combining marks
    #   apostrophe
    #   hyphen
    #
    # Requiring at least one alphabetic Unicode character.
    if not any(ch.isalpha() for ch in word):
        return False

    for ch in word:
        category = unicodedata.category(ch)

        if ch.isalpha():
            continue

        if category.startswith("M"):
            continue

        if ch in ("'", "’", "-"):
            continue

        return False

    return True

###############################################################################
# Hunspell parser
###############################################################################

def parse_hunspell_word(line):
    """
    Parse a .dic lexical line.

    Handles:
      - Hunspell header
      - flags after /
      - morphology after whitespace

    We intentionally use the .dic as the lexical source and do NOT invoke
    hunspell -a for every candidate.
    """

    line = line.rstrip("\r\n")

    if not line:
        return None

    line = line.lstrip("\ufeff")

    # First line can be the number of entries.
    if line.isdigit():
        return None

    # Remove morphology / annotations.
    line = line.split(None, 1)[0]

    if not line:
        return None

    # Hunspell flags begin after slash.
    if "/" in line:
        line = line.split("/", 1)[0]

    line = nfc(line.strip())

    if not is_valid_token(line):
        return None

    return line

###############################################################################
# Frequency parser
###############################################################################

frequency = {}

if os.path.exists(frequency_path):
    with open(
        frequency_path,
        "r",
        encoding="utf-8",
        errors="replace"
    ) as fh:
        for raw in fh:
            raw = raw.strip()

            if not raw or raw.startswith("#"):
                continue

            parts = raw.split()

            if not parts:
                continue

            word = nfc(parts[0])

            if not is_valid_token(word):
                continue

            score = 0

            if len(parts) >= 2:
                try:
                    score = float(parts[1])
                except ValueError:
                    score = 0

            frequency[word] = score

###############################################################################
# Core / mandatory vocabulary
###############################################################################

core = []

if os.path.exists(core_path):
    with open(
        core_path,
        "r",
        encoding="utf-8",
        errors="replace"
    ) as fh:
        for raw in fh:
            raw = raw.strip()

            if not raw or raw.startswith("#"):
                continue

            # Core files are one token per line.
            word = nfc(raw.split()[0])

            if is_valid_token(word):
                core.append(word)

###############################################################################
# Read Hunspell lexical source
###############################################################################

hunspell_words = set()

with open(
    hunspell_path,
    "r",
    encoding="utf-8",
    errors="replace"
) as fh:
    for raw in fh:
        word = parse_hunspell_word(raw)

        if word is not None:
            hunspell_words.add(word)

###############################################################################
# Explicit ES-AR additions
#
# These are useful for the keyboard even if a particular dictionary source
# does not expose every inflected/voseo form.
###############################################################################

if LANGUAGE == "es-AR":
    es_ar_core = {
        # Voseo
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

        # Important accented forms / common words
        "mañana",
        "también",
        "qué",
        "cómo",
        "cuándo",
        "dónde",
        "quién",
        "porque",
        "porqué",
        "por qué",
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
    }

    # Do not intentionally introduce "manana" as a replacement for "mañana".
    #
    # If an unaccented spelling exists in the external dictionary it is not
    # automatically promoted merely because an accented form exists.
    core.extend(es_ar_core)

###############################################################################
# Candidate construction
#
# Priority:
#   1. core / mandatory words
#   2. frequency
#   3. remaining Hunspell words
###############################################################################

accepted = set()

# Mandatory/core words:
for word in core:
    word = nfc(word)

    if is_valid_token(word):
        accepted.add(word)

# Frequency words only if they are present in the lexical source OR are
# explicitly mandatory/core.
frequency_candidates = []

for word, score in frequency.items():
    word = nfc(word)

    if not is_valid_token(word):
        continue

    if word in hunspell_words or word in accepted:
        frequency_candidates.append((score, word))

# Highest frequency first.
frequency_candidates.sort(
    key=lambda item: (-item[0], item[1])
)

for _, word in frequency_candidates:
    if len(accepted) >= MAX_WORDS:
        break

    accepted.add(word)

# Fill remaining capacity from Hunspell source deterministically.
if len(accepted) < MAX_WORDS:
    remaining = sorted(
        word for word in hunspell_words
        if word not in accepted
    )

    for word in remaining:
        if len(accepted) >= MAX_WORDS:
            break

        accepted.add(word)

###############################################################################
# Final strict filtering
###############################################################################

words = sorted(
    word
    for word in accepted
    if is_valid_token(word)
)

# Keep deterministic limit.
if len(words) > MAX_WORDS:
    # Mandatory/core words must survive the limit.
    mandatory = {
        nfc(w)
        for w in core
        if is_valid_token(nfc(w))
    }

    mandatory &= set(words)

    frequency_order = [
        word
        for _, word in frequency_candidates
        if word in set(words)
    ]

    result = []
    seen = set()

    for word in sorted(mandatory):
        if word not in seen:
            result.append(word)
            seen.add(word)

    for word in frequency_order:
        if len(result) >= MAX_WORDS:
            break

        if word not in seen:
            result.append(word)
            seen.add(word)

    if len(result) < MAX_WORDS:
        for word in words:
            if len(result) >= MAX_WORDS:
                break

            if word not in seen:
                result.append(word)
                seen.add(word)

    words = result

###############################################################################
# Output paths
###############################################################################

dict_path = os.path.join(output_dir, f"{LANGUAGE}.dict")
delete_path = os.path.join(output_dir, f"{LANGUAGE}.deletes")
validated_path = os.path.join(output_dir, f"{LANGUAGE}.validated.txt")
stats_path = os.path.join(output_dir, f"{LANGUAGE}.stats")

###############################################################################
# validated.txt
###############################################################################

with open(
    validated_path,
    "w",
    encoding="utf-8",
    newline="\n"
) as fh:
    for word in words:
        fh.write(word)
        fh.write("\n")

###############################################################################
# .dict
#
# PocketBoard dictionary runtime format:
# one normalized token per line.
###############################################################################

with open(
    dict_path,
    "w",
    encoding="utf-8",
    newline="\n"
) as fh:
    for word in words:
        fh.write(word)
        fh.write("\n")

###############################################################################
# Delete generation
###############################################################################

def generate_deletes(word, max_deletes):
    """
    Generate edit-distance-1 deletes.

    A delete is produced by removing one Unicode code point.

    We do not add delete strings to the dictionary.
    They exist only in the delete index.
    """

    result = set()

    chars = list(word)

    for i in range(len(chars)):
        candidate = "".join(chars[:i] + chars[i + 1:])

        if len(candidate) < MIN_WORD_LEN - 1:
            continue

        result.add(candidate)

        if len(result) >= max_deletes:
            break

    return result

###############################################################################
# Build word set for delete validation
###############################################################################

word_set = set(words)

###############################################################################
# Distance-1 deletes for all dictionary words
###############################################################################

delete_to_words = defaultdict(set)

for word in words:
    deletes = generate_deletes(
        word,
        MAX_DELETES_PER_WORD
    )

    for delete in deletes:
        # Delete target must not itself become a dictionary entry through
        # this process. It remains only an index key.
        delete_to_words[delete].add(word)

###############################################################################
# Distance-2 coverage
#
# Only high-value words receive second-order deletes.
#
# High-value order:
#   core first
#   then frequency
###############################################################################

high_value = []
seen_high = set()

for word in core:
    word = nfc(word)

    if word in word_set and word not in seen_high:
        high_value.append(word)
        seen_high.add(word)

for _, word in frequency_candidates:
    if word in word_set and word not in seen_high:
        high_value.append(word)
        seen_high.add(word)

if len(high_value) < DIST2_WORDS:
    for word in words:
        if word not in seen_high:
            high_value.append(word)
            seen_high.add(word)

high_value = high_value[:DIST2_WORDS]

###############################################################################
# Generate distance-2 deletes
###############################################################################

for word in high_value:
    first_level = generate_deletes(
        word,
        MAX_DELETES_PER_WORD
    )

    local_second_level = set()

    for d1 in first_level:
        if not d1:
            continue

        second = generate_deletes(
            d1,
            MAX_DELETES_PER_WORD
        )

        local_second_level.update(second)

        # Keep memory bounded per word.
        if len(local_second_level) >= MAX_DELETES_PER_WORD:
            break

    for delete in local_second_level:
        delete_to_words[delete].add(word)

###############################################################################
# Deterministic delete index
###############################################################################

# Remove invalid / useless keys.
clean_delete_to_words = {}

for delete, targets in delete_to_words.items():

    if not delete:
        continue

    if len(delete) > MAX_WORD_LEN:
        continue

    # Targets MUST be actual .dict words.
    targets = {
        word
        for word in targets
        if word in word_set
    }

    if not targets:
        continue

    clean_delete_to_words[delete] = targets

###############################################################################
# Global delete budget
###############################################################################

delete_items = list(clean_delete_to_words.items())

# Prefer keys with fewer targets because they are more compact and useful.
delete_items.sort(
    key=lambda item: (
        len(item[1]),
        item[0]
    )
)

if len(delete_items) > MAX_DELETE_ENTRIES:
    delete_items = delete_items[:MAX_DELETE_ENTRIES]

###############################################################################
# Write .deletes
#
# Format:
#
# delete<TAB>word1,word2,...
#
# Only words that actually exist in .dict are emitted.
###############################################################################

with open(
    delete_path,
    "w",
    encoding="utf-8",
    newline="\n"
) as fh:

    for delete, targets in sorted(delete_items):
        targets = sorted(
            word
            for word in targets
            if word in word_set
        )

        if not targets:
            continue

        fh.write(delete)
        fh.write("\t")
        fh.write(",".join(targets))
        fh.write("\n")

###############################################################################
# Regression checks
###############################################################################

def require_word(word):
    if word not in word_set:
        raise SystemExit(
            f"Regression failure for {LANGUAGE}: "
            f"required word missing from .dict: {word!r}"
        )

if LANGUAGE == "es-AR":
    # Critical accented form.
    require_word("mañana")

    # Common voseo forms.
    require_word("tenés")
    require_word("podés")
    require_word("querés")

    # Verify NFC.
    if nfc("mañana") != "mañana":
        raise SystemExit(
            "Regression failure: NFC normalization broken for mañana"
        )

    # Explicitly ensure the canonical accented form exists.
    #
    # We do NOT automatically create "manana" from "mañana".
    if "mañana" not in word_set:
        raise SystemExit(
            "Regression failure: mañana is absent"
        )

###############################################################################
# Delete integrity validation
###############################################################################

# Check every emitted target belongs to .dict.
with open(
    delete_path,
    "r",
    encoding="utf-8",
    errors="strict"
) as fh:

    for line_number, raw in enumerate(fh, 1):
        raw = raw.rstrip("\r\n")

        if not raw:
            continue

        if "\t" not in raw:
            raise SystemExit(
                f"Delete-format failure at line {line_number}: "
                f"missing TAB"
            )

        delete, targets_raw = raw.split("\t", 1)

        if not delete:
            raise SystemExit(
                f"Delete-format failure at line {line_number}: "
                f"empty delete"
            )

        if not targets_raw:
            raise SystemExit(
                f"Delete-format failure at line {line_number}: "
                f"empty target list"
            )

        for target in targets_raw.split(","):
            if target not in word_set:
                raise SystemExit(
                    f"Delete-integrity failure at line {line_number}: "
                    f"{target!r} is not present in .dict"
                )

###############################################################################
# Statistics
###############################################################################

with open(
    stats_path,
    "w",
    encoding="utf-8",
    newline="\n"
) as fh:

    fh.write(f"language={LANGUAGE}\n")
    fh.write(f"hunspell_source_words={len(hunspell_words)}\n")
    fh.write(f"frequency_words={len(frequency)}\n")
    fh.write(f"core_words={len(core)}\n")
    fh.write(f"dictionary_words={len(words)}\n")
    fh.write(f"delete_entries={len(delete_items)}\n")
    fh.write(f"distance2_words={len(high_value)}\n")
    fh.write(f"max_words={MAX_WORDS}\n")
    fh.write(f"dist2_words_limit={DIST2_WORDS}\n")

###############################################################################
# Console summary
###############################################################################

print(f"Language                  : {LANGUAGE}")
print(f"Hunspell source words     : {len(hunspell_words):,}")
print(f"Frequency words           : {len(frequency):,}")
print(f"Core words                : {len(core):,}")
print(f"Dictionary words          : {len(words):,}")
print(f"Delete entries            : {len(delete_items):,}")
print(f"Distance-2 words          : {len(high_value):,}")
print(f"Output                    : {dict_path}")
print(f"Deletes                   : {delete_path}")
print(f"Validated                 : {validated_path}")
print(f"Stats                     : {stats_path}")
PY
}

###############################################################################
# Input validation
###############################################################################

for lang in "${LANGUAGES[@]}"; do

    LANG_DIR="${DATA_DIR}/${lang}"

    FREQUENCY="${LANG_DIR}/frequency.txt"
    CORE="${LANG_DIR}/core.txt"
    HUNSPELL="${LANG_DIR}/dictionary.dic"

    [[ -f "$HUNSPELL" ]] ||
        die "Missing Hunspell dictionary: $HUNSPELL"

    [[ -f "$FREQUENCY" ]] ||
        die "Missing frequency file: $FREQUENCY"

    [[ -f "$CORE" ]] ||
        die "Missing core file: $CORE"

done

###############################################################################
# Build all languages
###############################################################################

for lang in "${LANGUAGES[@]}"; do

    LANG_DIR="${DATA_DIR}/${lang}"

    FREQUENCY="${LANG_DIR}/frequency.txt"
    CORE="${LANG_DIR}/core.txt"
    HUNSPELL="${LANG_DIR}/dictionary.dic"

    LANGUAGE_OUT="${OUT_DIR}/${lang}"

    build_language \
        "$lang" \
        "$FREQUENCY" \
        "$CORE" \
        "$HUNSPELL" \
        "$LANGUAGE_OUT"

done

###############################################################################
# Final global checks
###############################################################################

separator
log "Final validation"
separator

for lang in "${LANGUAGES[@]}"; do

    LANGUAGE_OUT="${OUT_DIR}/${lang}"

    DICT="${LANGUAGE_OUT}/${lang}.dict"
    DELETES="${LANGUAGE_OUT}/${lang}.deletes"
    VALIDATED="${LANGUAGE_OUT}/${lang}.validated.txt"

    [[ -s "$DICT" ]] ||
        die "${lang}: .dict was not generated"

    [[ -s "$DELETES" ]] ||
        die "${lang}: .deletes was not generated"

    [[ -s "$VALIDATED" ]] ||
        die "${lang}: validated vocabulary was not generated"

    # .dict and validated.txt must contain the same vocabulary.
    cmp -s "$DICT" "$VALIDATED" ||
        die "${lang}: .dict and validated.txt differ"

    # No blank lines.
    if grep -n '^$' "$DICT" >/dev/null 2>&1; then
        die "${lang}: blank line found in .dict"
    fi

    # No obvious whitespace corruption.
    if grep -n '[[:space:]]' "$DICT" >/dev/null 2>&1; then
        die "${lang}: whitespace found inside .dict"
    fi

done

separator
log "Dictionary generation completed successfully."
separator

log "Output directory:"
log "  ${OUT_DIR}"

log
log "Generated languages:"
for lang in "${LANGUAGES[@]}"; do
    log "  - ${lang}"
done

log
log "Important:"
log "  Hunspell was used as the lexical source."
log "  No per-word 'hunspell -a' validation was performed."
log "  Deletes were generated only from words present in .dict."
log "  Distance-2 deletes were limited to high-value words."
log "  Unicode/NFC and accents were preserved."
log "  es-AR voseo/core regressions were checked."
