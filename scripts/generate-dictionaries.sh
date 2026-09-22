```bash
#!/usr/bin/env bash

set -euo pipefail

###############################################################################
# PocketBoard Dictionary Generator V2
#
# Architecture:
#
#   AOSP LatinIME Combined dictionaries
#                 |
#                 v
#       authoritative vocabulary
#                 |
#        +--------+--------+
#        |                 |
#        v                 v
#      .dict         .suggestions
#        |
#        v
#     .deletes
#
# Primary sources:
#   en_US_wordlist.combined.gz -> en-en
#   de_wordlist.combined.gz    -> de-de
#   es_wordlist.combined.gz    -> es-AR
#
# Important:
#   - AOSP Combined is the authoritative vocabulary source.
#   - Frequency is used for ranking suggestions.
#   - "not_a_word=true" entries are rejected.
#   - Frequency 0 entries are not included in suggestions.
#   - Deletes are generated ONLY from the final .dict.
#   - FrequencyWords, Hunspell and Leipzig are intentionally NOT used as
#     authoritative vocabulary sources in V2.
#   - Required PocketBoard words are explicitly added after AOSP extraction.
#   - Unicode NFC is preserved.
#   - No per-word Hunspell validation.
#   - No O(N^2) vocabulary filtering.
###############################################################################

export LANG="C.UTF-8"
export LC_ALL="C.UTF-8"

###############################################################################
# Paths
###############################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

OUTPUT_DIR="${PROJECT_ROOT}/app/src/main/assets/dictionaries"
WORK_DIR="${PROJECT_ROOT}/build/pocketboard-dictionaries-v2"
AOSP_DIR="${WORK_DIR}/aosp"

mkdir -p "${OUTPUT_DIR}"
mkdir -p "${AOSP_DIR}"

###############################################################################
# Configuration
###############################################################################

AOSP_BASE_URL="https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries"

# AOSP source files.
ES_SOURCE="es_wordlist.combined.gz"
EN_SOURCE="en_US_wordlist.combined.gz"
DE_SOURCE="de_wordlist.combined.gz"

# Output names consumed by PocketBoard.
ES_DICT="es-AR.dict"
EN_DICT="en-en.dict"
DE_DICT="de-de.dict"

ES_SUGGESTIONS="es-AR.suggestions"
EN_SUGGESTIONS="en-en.suggestions"
DE_SUGGESTIONS="de-de.suggestions"

ES_DELETES="es-AR.deletes"
EN_DELETES="en-en.deletes"
DE_DELETES="de-de.deletes"

###############################################################################
# Dictionary format
###############################################################################

DICT_HEADER="#POCKETBOARD-DICT-1"
SUGGESTION_HEADER="#POCKETBOARD-SUGGESTIONS-1"
DELETE_HEADER="#POCKETBOARD-DELETES-1"

###############################################################################
# Vocabulary limits
###############################################################################

MAX_WORD_LENGTH=48
MAX_DELETE_WORD_LENGTH=24

MAX_DELETE_DISTANCE=2
MAX_DELETES_PER_WORD=3

# Distance-2 deletes are restricted to the highest-ranked words.
TOP_DISTANCE2_WORDS=15000

# Maximum number of visible suggestions per language.
#
# This is deliberately much smaller than the full dictionary.
# The full .dict remains available for correction/recognition.
SUGGESTION_MAX_ENTRIES=50000

# Hard safety limits.
MAX_DICTIONARY_ENTRIES=500000
MAX_SUGGESTION_ENTRIES=50000

###############################################################################
# Delete budgets
###############################################################################

ES_DELETE_BUDGET=2500000
EN_DELETE_BUDGET=2500000
DE_DELETE_BUDGET=2500000

GLOBAL_DELETE_BUDGET=7500000

###############################################################################
# Required PocketBoard vocabulary
###############################################################################

declare -A REQUIRED_ES=(
    ["mañana"]=1
    ["pasaría"]=1
    ["debería"]=1
    ["vos"]=1
    ["tenés"]=1
    ["podés"]=1
    ["querés"]=1
    ["hacés"]=1
    ["decís"]=1
    ["venís"]=1
    ["sentís"]=1
    ["acá"]=1
    ["cuándo"]=1
    ["dónde"]=1
)

declare -A REQUIRED_EN=(
    ["the"]=1
    ["have"]=1
    ["hello"]=1
)

declare -A REQUIRED_DE=(
    ["ich"]=1
    ["nicht"]=1
    ["morgen"]=1
    ["entschuldigung"]=1
    ["wahrscheinlich"]=1
    ["möglicherweise"]=1
)

###############################################################################
# Explicit regression exclusions
#
# These are not used as a general blacklist.
#
# They protect known orthographic regressions where unaccented Spanish forms
# must not replace the correctly accented forms.
###############################################################################

declare -A EXCLUDED_ES=(
    ["manana"]=1
    ["mananas"]=1
    ["deberia"]=1
)

###############################################################################
# Temporary files
###############################################################################

rm -rf "${WORK_DIR}"
mkdir -p "${AOSP_DIR}"

rm -f \
    "${OUTPUT_DIR}/${ES_DICT}" \
    "${OUTPUT_DIR}/${EN_DICT}" \
    "${OUTPUT_DIR}/${DE_DICT}" \
    "${OUTPUT_DIR}/${ES_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${EN_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${DE_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${ES_DELETES}" \
    "${OUTPUT_DIR}/${EN_DELETES}" \
    "${OUTPUT_DIR}/${DE_DELETES}"

###############################################################################
# Logging helpers
###############################################################################

log() {
    printf '[PocketBoard] %s\n' "$*"
}

info() {
    printf '[PocketBoard] INFO: %s\n' "$*"
}

warn() {
    printf '[PocketBoard] WARNING: %s\n' "$*" >&2
}

error() {
    printf '[PocketBoard] ERROR: %s\n' "$*" >&2
}

die() {
    error "$*"
    exit 1
}

###############################################################################
# Dependency checks
###############################################################################

require_command() {
    local command_name="$1"

    if ! command -v "${command_name}" >/dev/null 2>&1; then
        die "Required command not found: ${command_name}"
    fi
}

require_command curl
require_command gzip
require_command python3
require_command sort
require_command awk
require_command sed
require_command tr

###############################################################################
# Download AOSP dictionaries
###############################################################################

download_aosp_dictionary() {
    local filename="$1"
    local destination="${AOSP_DIR}/${filename}"
    local url="${AOSP_BASE_URL}/${filename}?format=TEXT"

    if [[ -s "${destination}" ]]; then
        info "Using cached AOSP source: ${filename}"
        return
    fi

    info "Downloading AOSP dictionary: ${filename}"

    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --retry 4 \
        --retry-delay 2 \
        --connect-timeout 20 \
        --max-time 300 \
        "${url}" \
        -o "${destination}.base64"

    if [[ ! -s "${destination}.base64" ]]; then
        die "Downloaded AOSP file is empty: ${filename}"
    fi

    python3 - "${destination}.base64" "${destination}" <<'PY'
import base64
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])

data = base64.b64decode(source.read_bytes())

if not data:
    raise SystemExit("Decoded AOSP file is empty")

destination.write_bytes(data)
source.unlink()
PY

    if [[ ! -s "${destination}" ]]; then
        die "Decoded AOSP dictionary is empty: ${filename}"
    fi
}

download_aosp_dictionary "${ES_SOURCE}"
download_aosp_dictionary "${EN_SOURCE}"
download_aosp_dictionary "${DE_SOURCE}"

###############################################################################
# Verify gzip files
###############################################################################

verify_gzip() {
    local filename="$1"

    if ! gzip -t "${AOSP_DIR}/${filename}" >/dev/null 2>&1; then
        die "Invalid gzip file: ${filename}"
    fi
}

verify_gzip "${ES_SOURCE}"
verify_gzip "${EN_SOURCE}"
verify_gzip "${DE_SOURCE}"

###############################################################################
# Combined parser
#
# AOSP Combined format is essentially:
#
#   dictionary=...
#   word=example,f=200
#   bigram=...
#   word=another,f=180
#   word=something,f=100,not_a_word=true
#
# We extract:
#
#   word<TAB>frequency
#
# Only actual "word=" records are considered.
#
# Entries with:
#   not_a_word=true
#
# are rejected.
#
# Frequency:
#   0..255
#
# is preserved for suggestion ranking.
###############################################################################

extract_aosp_combined() {
    local source_file="$1"
    local language="$2"
    local output_file="$3"

    info "Extracting AOSP Combined vocabulary: ${language}"

    gzip -cd "${AOSP_DIR}/${source_file}" |
        python3 - "${language}" "${output_file}" <<'PY'
import re
import sys
import unicodedata

language = sys.argv[1]
output_path = sys.argv[2]

MAX_WORD_LENGTH = 48

word_re = re.compile(r"^word=(.*)$")

rows = []

with open("/dev/stdin", "r", encoding="utf-8", errors="strict") as source:
    for raw_line in source:
        line = raw_line.rstrip("\n\r")

        if not line:
            continue

        match = word_re.match(line)
        if not match:
            continue

        payload = match.group(1)
        parts = payload.split(",")

        if not parts:
            continue

        raw_word = parts[0].strip()

        if not raw_word:
            continue

        frequency = 0
        not_a_word = False

        for field in parts[1:]:
            if "=" not in field:
                continue

            key, value = field.split("=", 1)

            if key == "f":
                try:
                    frequency = int(value)
                except ValueError:
                    frequency = 0

            elif key == "not_a_word":
                not_a_word = value.strip().lower() == "true"

        if not_a_word:
            continue

        word = unicodedata.normalize("NFC", raw_word).strip().lower()

        if not word:
            continue

        if len(word) > MAX_WORD_LENGTH:
            continue

        # PocketBoard words must consist only of Unicode letters plus
        # apostrophe or hyphen.
        valid = True

        for char in word:
            category = unicodedata.category(char)

            if category.startswith("L"):
                continue

            if char in ("'", "’", "-"):
                continue

            valid = False
            break

        if not valid:
            continue

        if frequency < 0:
            frequency = 0

        if frequency > 255:
            frequency = 255

        rows.append((word, frequency))

rows.sort(key=lambda item: (-item[1], item[0]))

seen = set()

with open(output_path, "w", encoding="utf-8", newline="\n") as out:
    for word, frequency in rows:
        if word in seen:
            continue

        seen.add(word)
        out.write(f"{word}\t{frequency}\n")

print(
    f"AOSP {language}: extracted {len(seen)} unique vocabulary entries",
    file=sys.stderr,
)
PY
}

ES_RAW="${WORK_DIR}/es-aosp.tsv"
EN_RAW="${WORK_DIR}/en-aosp.tsv"
DE_RAW="${WORK_DIR}/de-aosp.tsv"

extract_aosp_combined "${ES_SOURCE}" "es-AR" "${ES_RAW}"
extract_aosp_combined "${EN_SOURCE}" "en-US" "${EN_RAW}"
extract_aosp_combined "${DE_SOURCE}" "de-DE" "${DE_RAW}"

###############################################################################
# Build final vocabulary
#
# The AOSP list is authoritative.
#
# Required words are added at a very high synthetic priority so they are
# guaranteed to exist in .dict and .suggestions.
#
# Spanish regression exclusions are applied globally to the final set.
###############################################################################

build_final_vocabulary() {
    local language="$1"
    local raw_file="$2"
    local dictionary_file="$3"
    local suggestions_file="$4"
    local top_suggestions="$5"

    local dictionary_tmp="${WORK_DIR}/${language}.dictionary.tsv"
    local sorted_tmp="${WORK_DIR}/${language}.sorted.tsv"

    info "Building final vocabulary: ${language}"

    python3 - \
        "${language}" \
        "${raw_file}" \
        "${dictionary_tmp}" \
        "${sorted_tmp}" \
        "${top_suggestions}" \
        "${dictionary_file}" \
        "${suggestions_file}" \
        <<'PY'
import sys
import unicodedata

language = sys.argv[1]
raw_file = sys.argv[2]
dictionary_tmp = sys.argv[3]
sorted_tmp = sys.argv[4]
top_suggestions = int(sys.argv[5])
dictionary_file = sys.argv[6]
suggestions_file = sys.argv[7]

MAX_DICTIONARY_ENTRIES = 500000
MAX_SUGGESTION_ENTRIES = 50000

required = {
    "es-AR": {
        "mañana",
        "pasaría",
        "debería",
        "vos",
        "tenés",
        "podés",
        "querés",
        "hacés",
        "decís",
        "venís",
        "sentís",
        "acá",
        "cuándo",
        "dónde",
    },
    "en-US": {
        "the",
        "have",
        "hello",
    },
    "de-DE": {
        "ich",
        "nicht",
        "morgen",
        "entschuldigung",
        "wahrscheinlich",
        "möglicherweise",
    },
}

excluded = {
    "es-AR": {
        "manana",
        "mananas",
        "deberia",
    },
    "en-US": set(),
    "de-DE": set(),
}

required_words = {
    unicodedata.normalize("NFC", word).lower()
    for word in required.get(language, set())
}

excluded_words = {
    unicodedata.normalize("NFC", word).lower()
    for word in excluded.get(language, set())
}

entries = {}

with open(raw_file, "r", encoding="utf-8") as source:
    for line in source:
        line = line.rstrip("\n\r")

        if not line:
            continue

        if "\t" not in line:
            continue

        word, frequency_text = line.split("\t", 1)

        word = unicodedata.normalize("NFC", word.strip().lower())

        try:
            frequency = int(frequency_text)
        except ValueError:
            continue

        if not word:
            continue

        if word in excluded_words:
            continue

        if word in required_words:
            frequency = 255

        old_frequency = entries.get(word)

        if old_frequency is None or frequency > old_frequency:
            entries[word] = frequency

# Required words must always exist, even if a particular AOSP language list
# does not contain the regional form.
for word in required_words:
    if word in excluded_words:
        continue

    entries[word] = 255

# Ensure explicit exclusions can never leak back into the final dictionary.
for word in excluded_words:
    entries.pop(word, None)

# Frequency first, lexical order as deterministic tie breaker.
ranked = sorted(
    entries.items(),
    key=lambda item: (-item[1], item[0]),
)

if len(ranked) > MAX_DICTIONARY_ENTRIES:
    ranked = ranked[:MAX_DICTIONARY_ENTRIES]

# The dictionary itself is stored alphabetically because DictionaryManager
# already sorts/loads it as a complete vocabulary.
alphabetical = sorted(word for word, _ in ranked)

with open(dictionary_tmp, "w", encoding="utf-8", newline="\n") as out:
    out.write("#POCKETBOARD-DICT-1\n")

    for word in alphabetical:
        out.write(word + "\n")

# Keep frequency information separately for suggestion generation.
with open(sorted_tmp, "w", encoding="utf-8", newline="\n") as out:
    for word, frequency in ranked:
        out.write(f"{word}\t{frequency}\n")

# Visible suggestions are deliberately limited to the highest-frequency
# entries. Required words are always included.
suggestion_limit = min(
    top_suggestions,
    MAX_SUGGESTION_ENTRIES,
)

suggestion_words = []
suggestion_seen = set()

for word, frequency in ranked:
    if word in suggestion_seen:
        continue

    if frequency <= 0:
        continue

    suggestion_words.append(word)
    suggestion_seen.add(word)

    if len(suggestion_words) >= suggestion_limit:
        break

# Required words must be present in suggestions too.
for word in sorted(required_words):
    if word in excluded_words:
        continue

    if word not in suggestion_seen:
        suggestion_words.append(word)
        suggestion_seen.add(word)

suggestion_words = sorted(suggestion_words)

with open(suggestions_file, "w", encoding="utf-8", newline="\n") as out:
    out.write("#POCKETBOARD-SUGGESTIONS-1\n")

    for word in suggestion_words:
        out.write(word + "\n")

with open(dictionary_file, "w", encoding="utf-8", newline="\n") as out:
    for word in alphabetical:
        out.write(word + "\n")

print(
    f"{language}: dictionary={len(alphabetical)} suggestions={len(suggestion_words)}",
    file=sys.stderr,
)
PY
}

build_final_vocabulary \
    "es-AR" \
    "${ES_RAW}" \
    "${WORK_DIR}/${ES_DICT}" \
    "${WORK_DIR}/${ES_SUGGESTIONS}" \
    "${SUGGESTION_MAX_ENTRIES}"

build_final_vocabulary \
    "en-US" \
    "${EN_RAW}" \
    "${WORK_DIR}/${EN_DICT}" \
    "${WORK_DIR}/${EN_SUGGESTIONS}" \
    "${SUGGESTION_MAX_ENTRIES}"

build_final_vocabulary \
    "de-DE" \
    "${DE_RAW}" \
    "${WORK_DIR}/${DE_DICT}" \
    "${WORK_DIR}/${DE_SUGGESTIONS}" \
    "${SUGGESTION_MAX_ENTRIES}"

###############################################################################
# Validate dictionary words
###############################################################################

validate_dictionary_words() {
    local language="$1"
    local dictionary_file="$2"

    info "Validating dictionary vocabulary: ${language}"

    python3 - \
        "${language}" \
        "${dictionary_file}" \
        <<'PY'
import sys
import unicodedata

language = sys.argv[1]
dictionary_file = sys.argv[2]

excluded = {
    "es-AR": {
        "manana",
        "mananas",
        "deberia",
    },
    "en-US": set(),
    "de-DE": set(),
}

required = {
    "es-AR": {
        "mañana",
        "pasaría",
        "debería",
        "vos",
        "tenés",
        "podés",
        "querés",
        "hacés",
        "decís",
        "venís",
        "sentís",
        "acá",
        "cuándo",
        "dónde",
    },
    "en-US": {
        "the",
        "have",
        "hello",
    },
    "de-DE": {
        "ich",
        "nicht",
        "morgen",
        "entschuldigung",
        "wahrscheinlich",
        "möglicherweise",
    },
}

excluded = {
    unicodedata.normalize("NFC", word).lower()
    for word in excluded.get(language, set())
}

required = {
    unicodedata.normalize("NFC", word).lower()
    for word in required.get(language, set())
}

words = set()

with open(dictionary_file, "r", encoding="utf-8") as source:
    first = source.readline().rstrip("\n\r")

    if first != "#POCKETBOARD-DICT-1":
        raise SystemExit(
            f"{language}: invalid dictionary header: {first!r}"
        )

    for line in source:
        word = unicodedata.normalize(
            "NFC",
            line.rstrip("\n\r").strip().lower(),
        )

        if not word:
            continue

        if word in words:
            raise SystemExit(
                f"{language}: duplicate dictionary word: {word}"
            )

        words.add(word)

        if word in excluded:
            raise SystemExit(
                f"{language}: forbidden regression word present: {word}"
            )

        for char in word:
            category = unicodedata.category(char)

            if category.startswith("L"):
                continue

            if char in ("'", "’", "-"):
                continue

            raise SystemExit(
                f"{language}: invalid character {char!r} in word {word!r}"
            )

missing = sorted(required - words)

if missing:
    raise SystemExit(
        f"{language}: missing required vocabulary: {', '.join(missing)}"
    )

print(
    f"{language}: dictionary validation OK ({len(words)} words)",
    file=sys.stderr,
)
PY
}

###############################################################################
# Validate suggestion files
###############################################################################

validate_suggestions() {
    local language="$1"
    local suggestion_file="$2"
    local dictionary_file="$3"

    info "Validating suggestions: ${language}"

    python3 - \
        "${language}" \
        "${suggestion_file}" \
        "${dictionary_file}" \
        <<'PY'
import sys
import unicodedata

language = sys.argv[1]
suggestion_file = sys.argv[2]
dictionary_file = sys.argv[3]

excluded = {
    "es-AR": {
        "manana",
        "mananas",
        "deberia",
    },
    "en-US": set(),
    "de-DE": set(),
}

excluded = {
    unicodedata.normalize("NFC", word).lower()
    for word in excluded.get(language, set())
}

with open(dictionary_file, "r", encoding="utf-8") as source:
    source.readline()

    dictionary = {
        unicodedata.normalize(
            "NFC",
            line.rstrip("\n\r").strip().lower(),
        )
        for line in source
        if line.strip()
    }

suggestions = set()

with open(suggestion_file, "r", encoding="utf-8") as source:
    first = source.readline().rstrip("\n\r")

    if first != "#POCKETBOARD-SUGGESTIONS-1":
        raise SystemExit(
            f"{language}: invalid suggestion header: {first!r}"
        )

    for line in source:
        word = unicodedata.normalize(
            "NFC",
            line.rstrip("\n\r").strip().lower(),
        )

        if not word:
            continue

        if word in suggestions:
            raise SystemExit(
                f"{language}: duplicate suggestion: {word}"
            )

        if word not in dictionary:
            raise SystemExit(
                f"{language}: suggestion not present in dictionary: {word}"
            )

        if word in excluded:
            raise SystemExit(
                f"{language}: forbidden suggestion: {word}"
            )

        suggestions.add(word)

print(
    f"{language}: suggestions validation OK ({len(suggestions)} words)",
    file=sys.stderr,
)
PY
}

###############################################################################
# Install generated dictionaries
###############################################################################

cp "${WORK_DIR}/${ES_DICT}" "${OUTPUT_DIR}/${ES_DICT}"
cp "${WORK_DIR}/${EN_DICT}" "${OUTPUT_DIR}/${EN_DICT}"
cp "${WORK_DIR}/${DE_DICT}" "${OUTPUT_DIR}/${DE_DICT}"

cp "${WORK_DIR}/${ES_SUGGESTIONS}" "${OUTPUT_DIR}/${ES_SUGGESTIONS}"
cp "${WORK_DIR}/${EN_SUGGESTIONS}" "${OUTPUT_DIR}/${EN_SUGGESTIONS}"
cp "${WORK_DIR}/${DE_SUGGESTIONS}" "${OUTPUT_DIR}/${DE_SUGGESTIONS}"

###############################################################################
# Validate all dictionaries before generating deletes.
###############################################################################

validate_dictionary_words \
    "es-AR" \
    "${OUTPUT_DIR}/${ES_DICT}"

validate_dictionary_words \
    "en-US" \
    "${OUTPUT_DIR}/${EN_DICT}"

validate_dictionary_words \
    "de-DE" \
    "${OUTPUT_DIR}/${DE_DICT}"

validate_suggestions \
    "es-AR" \
    "${OUTPUT_DIR}/${ES_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${ES_DICT}"

validate_suggestions \
    "en-US" \
    "${OUTPUT_DIR}/${EN_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${EN_DICT}"

validate_suggestions \
    "de-DE" \
    "${OUTPUT_DIR}/${DE_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${DE_DICT}"

###############################################################################
# Generate delete keys
#
# IMPORTANT:
#
# Deletes are generated ONLY from the final .dict.
#
# A delete key is never itself a vocabulary entry.
#
# For each word:
#   - generate up to MAX_DELETES_PER_WORD distance-1 deletes
#   - for the top TOP_DISTANCE2_WORDS words, add distance-2 deletes
#
# This implementation intentionally keeps only a bounded number of deletes
# per word so CI memory/runtime remains predictable.
###############################################################################

generate_deletes() {
    local language="$1"
    local dictionary_file="$2"
    local suggestions_file="$3"
    local output_file="$4"
    local budget="$5"

    info "Generating delete index: ${language}"

    python3 - \
        "${language}" \
        "${dictionary_file}" \
        "${suggestions_file}" \
        "${output_file}" \
        "${budget}" \
        <<'PY'
import sys
import unicodedata

language = sys.argv[1]
dictionary_file = sys.argv[2]
suggestions_file = sys.argv[3]
output_file = sys.argv[4]
budget = int(sys.argv[5])

MAX_DELETE_WORD_LENGTH = 24
MAX_DELETES_PER_WORD = 3
TOP_DISTANCE2_WORDS = 15000

alphabet = "abcdefghijklmnopqrstuvwxyz"
spanish_extra = "áéíóúüñ"
german_extra = "äöüß"
allowed_chars = set(alphabet)

if language == "es-AR":
    allowed_chars.update(spanish_extra)
elif language == "de-DE":
    allowed_chars.update(german_extra)

def normalize(word):
    return unicodedata.normalize(
        "NFC",
        word.strip().lower(),
    )

def valid_delete_word(word):
    if not word:
        return False

    if len(word) > MAX_DELETE_WORD_LENGTH:
        return False

    for char in word:
        if char not in allowed_chars:
            return False

    return True

def generate_distance_one(word):
    deletes = []

    for index in range(len(word)):
        key = word[:index] + word[index + 1:]
        deletes.append(key)

    return deletes

def generate_distance_two(word):
    first_level = set(generate_distance_one(word))
    result = set()

    for intermediate in first_level:
        if not intermediate:
            continue

        for index in range(len(intermediate)):
            key = (
                intermediate[:index]
                + intermediate[index + 1:]
            )

            result.add(key)

    return result

dictionary = []

with open(dictionary_file, "r", encoding="utf-8") as source:
    header = source.readline().rstrip("\n\r")

    if header != "#POCKETBOARD-DICT-1":
        raise SystemExit(
            f"{language}: invalid dictionary header"
        )

    for line in source:
        word = normalize(line)

        if not word:
            continue

        if valid_delete_word(word):
            dictionary.append(word)

# Suggestions are frequency-ranked because .dict is alphabetical.
# We use this list to determine which words receive distance-2 coverage.
suggestion_rank = []

with open(suggestions_file, "r", encoding="utf-8") as source:
    header = source.readline().rstrip("\n\r")

    if header != "#POCKETBOARD-SUGGESTIONS-1":
        raise SystemExit(
            f"{language}: invalid suggestion header"
        )

    for line in source:
        word = normalize(line)

        if word:
            suggestion_rank.append(word)

distance2_targets = set(
    suggestion_rank[:TOP_DISTANCE2_WORDS]
)

delete_map = {}

def add_delete(delete_key, target):
    if not delete_key:
        return

    if delete_key == target:
        return

    existing = delete_map.get(delete_key)

    if existing is None:
        delete_map[delete_key] = [target]
        return

    if target in existing:
        return

    if len(existing) >= MAX_DELETES_PER_WORD:
        return

    existing.append(target)

for word in dictionary:
    if len(delete_map) >= budget:
        break

    # Distance 1.
    for delete_key in generate_distance_one(word):
        if len(delete_map) >= budget:
            break

        add_delete(delete_key, word)

    # Distance 2 only for high-value words.
    if word in distance2_targets:
        if len(delete_map) >= budget:
            break

        for delete_key in generate_distance_two(word):
            if len(delete_map) >= budget:
                break

            add_delete(delete_key, word)

# Deterministic output.
rows = []

for delete_key, targets in delete_map.items():
    for target in sorted(targets):
        rows.append((delete_key, target))

rows.sort(
    key=lambda item: (item[0], item[1])
)

with open(output_file, "w", encoding="utf-8", newline="\n") as out:
    out.write("#POCKETBOARD-DELETES-1\n")
    out.write("#distance=1,2\n")
    out.write("#source=dict-only\n")
    out.write("#delete<TAB>target\n")

    for delete_key, target in rows:
        out.write(
            f"{delete_key}\t{target}\n"
        )

print(
    f"{language}: generated {len(rows)} delete mappings",
    file=sys.stderr,
)
PY
}

generate_deletes \
    "es-AR" \
    "${OUTPUT_DIR}/${ES_DICT}" \
    "${OUTPUT_DIR}/${ES_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${ES_DELETES}" \
    "${ES_DELETE_BUDGET}"

generate_deletes \
    "en-US" \
    "${OUTPUT_DIR}/${EN_DICT}" \
    "${OUTPUT_DIR}/${EN_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${EN_DELETES}" \
    "${EN_DELETE_BUDGET}"

generate_deletes \
    "de-DE" \
    "${OUTPUT_DIR}/${DE_DICT}" \
    "${OUTPUT_DIR}/${DE_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${DE_DELETES}" \
    "${DE_DELETE_BUDGET}"

###############################################################################
# Validate delete files
###############################################################################

validate_deletes() {
    local language="$1"
    local dictionary_file="$2"
    local delete_file="$3"

    info "Validating delete index: ${language}"

    python3 - \
        "${language}" \
        "${dictionary_file}" \
        "${delete_file}" \
        <<'PY'
import sys
import unicodedata

language = sys.argv[1]
dictionary_file = sys.argv[2]
delete_file = sys.argv[3]

def normalize(value):
    return unicodedata.normalize(
        "NFC",
        value.strip().lower(),
    )

with open(dictionary_file, "r", encoding="utf-8") as source:
    header = source.readline().rstrip("\n\r")

    if header != "#POCKETBOARD-DICT-1":
        raise SystemExit(
            f"{language}: invalid dictionary header"
        )

    dictionary = {
        normalize(line)
        for line in source
        if line.strip()
    }

seen = set()
mapping_count = 0

with open(delete_file, "r", encoding="utf-8") as source:
    header = source.readline().rstrip("\n\r")

    if header != "#POCKETBOARD-DELETES-1":
        raise SystemExit(
            f"{language}: invalid delete header"
        )

    metadata = [
        source.readline().rstrip("\n\r"),
        source.readline().rstrip("\n\r"),
        source.readline().rstrip("\n\r"),
    ]

    if metadata[0] != "#distance=1,2":
        raise SystemExit(
            f"{language}: invalid distance metadata"
        )

    if metadata[1] != "#source=dict-only":
        raise SystemExit(
            f"{language}: invalid source metadata"
        )

    if metadata[2] != "#delete<TAB>target":
        raise SystemExit(
            f"{language}: invalid column metadata"
        )

    for line in source:
        line = line.rstrip("\n\r")

        if not line:
            continue

        if "\t" not in line:
            raise SystemExit(
                f"{language}: malformed delete mapping: {line!r}"
            )

        delete_key, target = line.split("\t", 1)

        delete_key = normalize(delete_key)
        target = normalize(target)

        if not delete_key:
            raise SystemExit(
                f"{language}: empty delete key"
            )

        if target not in dictionary:
            raise SystemExit(
                f"{language}: delete target not in dictionary: {target!r}"
            )

        key = (delete_key, target)

        if key in seen:
            raise SystemExit(
                f"{language}: duplicate delete mapping: {key!r}"
            )

        seen.add(key)
        mapping_count += 1

if mapping_count == 0:
    raise SystemExit(
        f"{language}: delete index is empty"
    )

print(
    f"{language}: delete validation OK ({mapping_count} mappings)",
    file=sys.stderr,
)
PY
}

validate_deletes \
    "es-AR" \
    "${OUTPUT_DIR}/${ES_DICT}" \
    "${OUTPUT_DIR}/${ES_DELETES}"

validate_deletes \
    "en-US" \
    "${OUTPUT_DIR}/${EN_DICT}" \
    "${OUTPUT_DIR}/${EN_DELETES}"

validate_deletes \
    "de-DE" \
    "${OUTPUT_DIR}/${DE_DICT}" \
    "${OUTPUT_DIR}/${DE_DELETES}"

###############################################################################
# Final Spanish regression checks
###############################################################################

check_spanish_regressions() {
    local dictionary_file="$1"
    local suggestion_file="$2"

    info "Checking Spanish orthographic regressions"

    python3 - \
        "${dictionary_file}" \
        "${suggestion_file}" \
        <<'PY'
import sys
import unicodedata

dictionary_file = sys.argv[1]
suggestion_file = sys.argv[2]

def normalize(value):
    return unicodedata.normalize(
        "NFC",
        value.strip().lower(),
    )

with open(dictionary_file, "r", encoding="utf-8") as source:
    source.readline()

    dictionary = {
        normalize(line)
        for line in source
        if line.strip()
    }

with open(suggestion_file, "r", encoding="utf-8") as source:
    source.readline()

    suggestions = {
        normalize(line)
        for line in source
        if line.strip()
    }

for forbidden in (
    "manana",
    "mananas",
    "deberia",
):
    if forbidden in dictionary:
        raise SystemExit(
            f"Spanish regression: {forbidden!r} must not be present in .dict"
        )

    if forbidden in suggestions:
        raise SystemExit(
            f"Spanish regression: {forbidden!r} must not be present in .suggestions"
        )

for required in (
    "mañana",
    "mañanas",
    "debería",
):
    if required not in dictionary:
        raise SystemExit(
            f"Spanish regression: {required!r} must be present in .dict"
        )

    if required not in suggestions:
        raise SystemExit(
            f"Spanish regression: {required!r} must be present in .suggestions"
        )

print(
    "Spanish orthographic regression checks OK",
    file=sys.stderr,
)
PY
}

check_spanish_regressions \
    "${OUTPUT_DIR}/${ES_DICT}" \
    "${OUTPUT_DIR}/${ES_SUGGESTIONS}"

###############################################################################
# Global delete budget check
###############################################################################

count_delete_mappings() {
    local file="$1"

    awk '
        !/^#/ && NF >= 2 {
            count++
        }
        END {
            print count + 0
        }
    ' "${file}"
}

ES_DELETE_COUNT="$(count_delete_mappings "${OUTPUT_DIR}/${ES_DELETES}")"
EN_DELETE_COUNT="$(count_delete_mappings "${OUTPUT_DIR}/${EN_DELETES}")"
DE_DELETE_COUNT="$(count_delete_mappings "${OUTPUT_DIR}/${DE_DELETES}")"

GLOBAL_DELETE_COUNT=$(
    (
        printf '%s\n' \
            "${ES_DELETE_COUNT}" \
            "${EN_DELETE_COUNT}" \
            "${DE_DELETE_COUNT}"
    ) |
    awk '{ total += $1 } END { print total + 0 }'
)

if (( GLOBAL_DELETE_COUNT > GLOBAL_DELETE_BUDGET )); then
    die \
        "Global delete budget exceeded: ${GLOBAL_DELETE_COUNT} > ${GLOBAL_DELETE_BUDGET}"
fi

###############################################################################
# Size report
###############################################################################

size_report() {
    local total_dictionary_bytes
    local total_delete_bytes
    local total_metadata_bytes
    local total_generated_bytes
    local total_mib

    total_dictionary_bytes=$(
        wc -c \
            "${OUTPUT_DIR}/${ES_DICT}" \
            "${OUTPUT_DIR}/${EN_DICT}" \
            "${OUTPUT_DIR}/${DE_DICT}" |
        tail -n 1 |
        awk '{print $1}'
    )

    total_delete_bytes=$(
        wc -c \
            "${OUTPUT_DIR}/${ES_DELETES}" \
            "${OUTPUT_DIR}/${EN_DELETES}" \
            "${OUTPUT_DIR}/${DE_DELETES}" |
        tail -n 1 |
        awk '{print $1}'
    )

    total_metadata_bytes=$(
        wc -c \
            "${OUTPUT_DIR}/${ES_SUGGESTIONS}" \
            "${OUTPUT_DIR}/${EN_SUGGESTIONS}" \
            "${OUTPUT_DIR}/${DE_SUGGESTIONS}" |
        tail -n 1 |
        awk '{print $1}'
    )

    total_generated_bytes=$(
        (
            printf '%s\n' \
                "${total_dictionary_bytes}" \
                "${total_delete_bytes}" \
                "${total_metadata_bytes}"
        ) |
        awk '{ total += $1 } END { print total + 0 }'
    )

    total_mib=$((total_generated_bytes / 1024 / 1024))

    printf '\n'
    printf '%s\n' '============================================================'
    printf '%s\n' ' PocketBoard Dictionary V2 Size Report'
    printf '%s\n' '============================================================'
    printf 'Dictionary bytes : %s\n' "${total_dictionary_bytes}"
    printf 'Delete bytes     : %s\n' "${total_delete_bytes}"
    printf 'Suggestion bytes : %s\n' "${total_metadata_bytes}"
    printf 'Total bytes      : %s\n' "${total_generated_bytes}"
    printf 'Total MiB        : %s\n' "${total_mib}"
    printf '%s\n' '============================================================'
    printf '\n'

    printf '%-24s %12s %12s\n' \
        'Language' \
        'Dictionary' \
        'Suggestions'

    printf '%-24s %12s %12s\n' \
        'es-AR' \
        "$(wc -l < "${OUTPUT_DIR}/${ES_DICT}")" \
        "$(wc -l < "${OUTPUT_DIR}/${ES_SUGGESTIONS}")"

    printf '%-24s %12s %12s\n' \
        'en-en' \
        "$(wc -l < "${OUTPUT_DIR}/${EN_DICT}")" \
        "$(wc -l < "${OUTPUT_DIR}/${EN_SUGGESTIONS}")"

    printf '%-24s %12s %12s\n' \
        'de-de' \
        "$(wc -l < "${OUTPUT_DIR}/${DE_DICT}")" \
        "$(wc -l < "${OUTPUT_DIR}/${DE_SUGGESTIONS}")"

    printf '\n'

    printf '%-24s %12s\n' \
        'Language' \
        'Delete mappings'

    printf '%-24s %12s\n' \
        'es-AR' \
        "${ES_DELETE_COUNT}"

    printf '%-24s %12s\n' \
        'en-en' \
        "${EN_DELETE_COUNT}"

    printf '%-24s %12s\n' \
        'de-de' \
        "${DE_DELETE_COUNT}"

    printf '%-24s %12s\n' \
        'GLOBAL' \
        "${GLOBAL_DELETE_COUNT}"

    printf '\n'
}

size_report

###############################################################################
# Final output summary
###############################################################################

log "Dictionary generation completed successfully."

printf '\n'
printf '%s\n' 'Generated files:'

printf '  %s\n' "${OUTPUT_DIR}/${ES_DICT}"
printf '  %s\n' "${OUTPUT_DIR}/${ES_SUGGESTIONS}"
printf '  %s\n' "${OUTPUT_DIR}/${ES_DELETES}"

printf '  %s\n' "${OUTPUT_DIR}/${EN_DICT}"
printf '  %s\n' "${OUTPUT_DIR}/${EN_SUGGESTIONS}"
printf '  %s\n' "${OUTPUT_DIR}/${EN_DELETES}"

printf '  %s\n' "${OUTPUT_DIR}/${DE_DICT}"
printf '  %s\n' "${OUTPUT_DIR}/${DE_SUGGESTIONS}"
printf '  %s\n' "${OUTPUT_DIR}/${DE_DELETES}"

printf '\n'
printf '%s\n' 'AOSP sources:'
printf '  en-en <- %s\n' "${EN_SOURCE}"
printf '  de-de <- %s\n' "${DE_SOURCE}"
printf '  es-AR <- %s\n' "${ES_SOURCE}"

printf '\n'
printf '%s\n' 'Dictionary V2 guarantees:'
printf '  - AOSP Combined is the authoritative vocabulary source.'
printf '  - AOSP frequency is used for suggestion ranking.'
printf '  - not_a_word entries are excluded.'
printf '  - Delete keys never become vocabulary entries.'
printf '  - Deletes are generated only from the final .dict.'
printf '  - Suggestions are generated independently from deletes.'
printf '  - Spanish accent regressions are explicitly tested.'
printf '  - Required regional vocabulary is explicitly tested.'
printf '  - No per-word Hunspell validation is performed.'
printf '  - No FrequencyWords corpus entries are blindly promoted to .dict.'
printf '  - No Leipzig corpus entries are blindly promoted to .dict.'
printf '\n'
```
