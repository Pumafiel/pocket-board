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
#   - AOSP frequency is used for suggestion ranking.
#   - "not_a_word=true" entries are rejected.
#   - Frequency 0 entries are not included in suggestions.
#   - Deletes are generated ONLY from the final .dict.
#   - FrequencyWords, Hunspell and Leipzig are NOT used as authoritative
#     vocabulary sources in V2.
#   - Required PocketBoard words are explicitly added.
#   - Unicode NFC is preserved.
#   - No per-word Hunspell validation is performed.
#   - No O(N^2) vocabulary filtering is performed.
#   - The script is deterministic.
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

ES_SOURCE="es_wordlist.combined.gz"
EN_SOURCE="en_US_wordlist.combined.gz"
DE_SOURCE="de_wordlist.combined.gz"

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

TOP_DISTANCE2_WORDS=15000

SUGGESTION_MAX_ENTRIES=50000

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
    ["mañanas"]=1
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
# Logging
###############################################################################

log() {
    printf '%s\n' "[PocketBoard] $*"
}

info() {
    printf '%s\n' "[PocketBoard] INFO: $*"
}

warn() {
    printf '%s\n' "[PocketBoard] WARNING: $*" >&2
}

error() {
    printf '%s\n' "[PocketBoard] ERROR: $*" >&2
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
require_command awk
require_command sed
require_command sort
require_command tr
require_command wc
require_command tail

###############################################################################
# Download AOSP dictionary
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

    python3 - \
        "${destination}.base64" \
        "${destination}" \
        <<'PY'
import base64
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])

try:
    data = base64.b64decode(
        source.read_bytes(),
        validate=True,
    )
except Exception as exc:
    raise SystemExit(
        f"Unable to decode AOSP base64 payload: {exc}"
    )

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

    info "Checking gzip integrity: ${filename}"

    if ! gzip -t "${AOSP_DIR}/${filename}" >/dev/null 2>&1; then
        die "Invalid gzip file: ${filename}"
    fi
}

verify_gzip "${ES_SOURCE}"
verify_gzip "${EN_SOURCE}"
verify_gzip "${DE_SOURCE}"

###############################################################################
# Extract AOSP Combined vocabulary
#
# IMPORTANT:
#
# Do NOT pipe gzip into Python while also using a Python here-document.
#
# The previous implementation did:
#
#   gzip -cd file.gz | python3 ... <<'PY'
#
# In that construction Python stdin is occupied by the here-document.
# Therefore Python never received the gzip output and extracted zero words.
#
# Python now opens the gzip file directly.
###############################################################################

extract_aosp_combined() {
    local source_file="$1"
    local language="$2"
    local output_file="$3"

    info "Extracting AOSP Combined vocabulary: ${language}"

    python3 - \
        "${AOSP_DIR}/${source_file}" \
        "${language}" \
        "${output_file}" \
        <<'PY'
import gzip
import re
import sys
import unicodedata

source_path = sys.argv[1]
language = sys.argv[2]
output_path = sys.argv[3]

MAX_WORD_LENGTH = 48

word_re = re.compile(r"^word=(.*)$")

entries = {}

def normalize_word(value):
    return unicodedata.normalize(
        "NFC",
        value.strip().lower(),
    )

def valid_word(word):
    if not word:
        return False

    if len(word) > MAX_WORD_LENGTH:
        return False

    for char in word:
        category = unicodedata.category(char)

        if category.startswith("L"):
            continue

        if char in ("'", "’", "-"):
            continue

        return False

    return True

with gzip.open(
    source_path,
    "rt",
    encoding="utf-8",
    errors="strict",
    newline="",
) as source:

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

            key = key.strip()
            value = value.strip()

            if key == "f":
                try:
                    frequency = int(value)
                except ValueError:
                    frequency = 0

            elif key == "not_a_word":
                not_a_word = value.lower() == "true"

        if not_a_word:
            continue

        word = normalize_word(raw_word)

        if not valid_word(word):
            continue

        if frequency < 0:
            frequency = 0

        if frequency > 255:
            frequency = 255

        old_frequency = entries.get(word)

        if old_frequency is None or frequency > old_frequency:
            entries[word] = frequency

ranked = sorted(
    entries.items(),
    key=lambda item: (-item[1], item[0]),
)

with open(
    output_path,
    "w",
    encoding="utf-8",
    newline="\n",
) as out:

    for word, frequency in ranked:
        out.write(
            f"{word}\t{frequency}\n"
        )

print(
    f"AOSP {language}: extracted {len(entries)} unique vocabulary entries",
    file=sys.stderr,
)

if not entries:
    raise SystemExit(
        f"AOSP {language}: extraction returned zero vocabulary entries"
    )
PY
}

ES_RAW="${WORK_DIR}/es-aosp.tsv"
EN_RAW="${WORK_DIR}/en-aosp.tsv"
DE_RAW="${WORK_DIR}/de-aosp.tsv"

extract_aosp_combined \
    "${ES_SOURCE}" \
    "es-AR" \
    "${ES_RAW}"

extract_aosp_combined \
    "${EN_SOURCE}" \
    "en-US" \
    "${EN_RAW}"

extract_aosp_combined \
    "${DE_SOURCE}" \
    "de-DE" \
    "${DE_RAW}"

###############################################################################
# Build final vocabulary
###############################################################################

build_final_vocabulary() {
    local language="$1"
    local raw_file="$2"
    local dictionary_file="$3"
    local suggestions_file="$4"
    local top_suggestions="$5"

    local dictionary_tmp="${WORK_DIR}/${language}.dictionary.tsv"
    local ranked_tmp="${WORK_DIR}/${language}.ranked.tsv"

    info "Building final vocabulary: ${language}"

    python3 - \
        "${language}" \
        "${raw_file}" \
        "${dictionary_tmp}" \
        "${ranked_tmp}" \
        "${top_suggestions}" \
        "${dictionary_file}" \
        "${suggestions_file}" \
        <<'PY'
import sys
import unicodedata

language = sys.argv[1]
raw_file = sys.argv[2]
dictionary_tmp = sys.argv[3]
ranked_tmp = sys.argv[4]
top_suggestions = int(sys.argv[5])
dictionary_file = sys.argv[6]
suggestions_file = sys.argv[7]

MAX_DICTIONARY_ENTRIES = 500000
MAX_SUGGESTION_ENTRIES = 50000

required = {
    "es-AR": {
        "mañana",
        "mañanas",
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

def normalize(value):
    return unicodedata.normalize(
        "NFC",
        value.strip().lower(),
    )

required_words = {
    normalize(word)
    for word in required.get(language, set())
}

excluded_words = {
    normalize(word)
    for word in excluded.get(language, set())
}

entries = {}

with open(
    raw_file,
    "r",
    encoding="utf-8",
) as source:

    for line in source:
        line = line.rstrip("\n\r")

        if not line:
            continue

        if "\t" not in line:
            continue

        word, frequency_text = line.split("\t", 1)

        word = normalize(word)

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

###############################################################################
# Add mandatory PocketBoard vocabulary.
###############################################################################

for word in required_words:
    if word not in excluded_words:
        entries[word] = 255

###############################################################################
# Apply exclusions again after required-word insertion.
###############################################################################

for word in excluded_words:
    entries.pop(word, None)

###############################################################################
# Rank vocabulary by frequency.
###############################################################################

ranked = sorted(
    entries.items(),
    key=lambda item: (-item[1], item[0]),
)

if len(ranked) > MAX_DICTIONARY_ENTRIES:
    ranked = ranked[:MAX_DICTIONARY_ENTRIES]

###############################################################################
# Build final alphabetical dictionary.
###############################################################################

alphabetical = sorted(
    word
    for word, _ in ranked
)

with open(
    dictionary_file,
    "w",
    encoding="utf-8",
    newline="\n",
) as out:

    out.write("#POCKETBOARD-DICT-1\n")

    for word in alphabetical:
        out.write(
            word + "\n"
        )

###############################################################################
# Preserve frequency-ranked vocabulary for later stages.
###############################################################################

with open(
    ranked_tmp,
    "w",
    encoding="utf-8",
    newline="\n",
) as out:

    for word, frequency in ranked:
        out.write(
            f"{word}\t{frequency}\n"
        )

###############################################################################
# Build visible suggestion dictionary.
#
# Frequency 0 entries are not suggested.
###############################################################################

suggestion_limit = min(
    top_suggestions,
    MAX_SUGGESTION_ENTRIES,
)

suggestion_words = []
suggestion_seen = set()

for word, frequency in ranked:
    if frequency <= 0:
        continue

    if word in suggestion_seen:
        continue

    suggestion_words.append(word)
    suggestion_seen.add(word)

    if len(suggestion_words) >= suggestion_limit:
        break

###############################################################################
# Mandatory words are always visible suggestions.
###############################################################################

for word in sorted(required_words):
    if word in excluded_words:
        continue

    if word not in suggestion_seen:
        suggestion_words.append(word)
        suggestion_seen.add(word)

suggestion_words = sorted(suggestion_words)

with open(
    suggestions_file,
    "w",
    encoding="utf-8",
    newline="\n",
) as out:

    out.write("#POCKETBOARD-SUGGESTIONS-1\n")

    for word in suggestion_words:
        out.write(
            word + "\n"
        )

###############################################################################
# Safety checks.
###############################################################################

if not alphabetical:
    raise SystemExit(
        f"{language}: final dictionary is empty"
    )

if not suggestion_words:
    raise SystemExit(
        f"{language}: suggestion dictionary is empty"
    )

print(
    f"{language}: dictionary={len(alphabetical)} "
    f"suggestions={len(suggestion_words)}",
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
# Validate dictionary vocabulary
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

excluded_map = {
    "es-AR": {
        "manana",
        "mananas",
        "deberia",
    },
    "en-US": set(),
    "de-DE": set(),
}

required_map = {
    "es-AR": {
        "mañana",
        "mañanas",
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

def normalize(value):
    return unicodedata.normalize(
        "NFC",
        value.strip().lower(),
    )

excluded = {
    normalize(word)
    for word in excluded_map.get(language, set())
}

required = {
    normalize(word)
    for word in required_map.get(language, set())
}

words = set()

with open(
    dictionary_file,
    "r",
    encoding="utf-8",
) as source:

    header = source.readline().rstrip("\n\r")

    if header != "#POCKETBOARD-DICT-1":
        raise SystemExit(
            f"{language}: invalid dictionary header: {header!r}"
        )

    for line in source:
        word = normalize(line)

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
                f"{language}: invalid character {char!r} "
                f"in word {word!r}"
            )

missing = sorted(
    required - words
)

if missing:
    raise SystemExit(
        f"{language}: missing required vocabulary: "
        f"{', '.join(missing)}"
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

excluded_map = {
    "es-AR": {
        "manana",
        "mananas",
        "deberia",
    },
    "en-US": set(),
    "de-DE": set(),
}

required_map = {
    "es-AR": {
        "mañana",
        "mañanas",
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

def normalize(value):
    return unicodedata.normalize(
        "NFC",
        value.strip().lower(),
    )

excluded = {
    normalize(word)
    for word in excluded_map.get(language, set())
}

required = {
    normalize(word)
    for word in required_map.get(language, set())
}

with open(
    dictionary_file,
    "r",
    encoding="utf-8",
) as source:

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

suggestions = set()

with open(
    suggestion_file,
    "r",
    encoding="utf-8",
) as source:

    header = source.readline().rstrip("\n\r")

    if header != "#POCKETBOARD-SUGGESTIONS-1":
        raise SystemExit(
            f"{language}: invalid suggestion header"
        )

    for line in source:
        word = normalize(line)

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

missing_required = sorted(
    required - suggestions
)

if missing_required:
    raise SystemExit(
        f"{language}: required words missing from suggestions: "
        f"{', '.join(missing_required)}"
    )

print(
    f"{language}: suggestions validation OK ({len(suggestions)} words)",
    file=sys.stderr,
)
PY
}

###############################################################################
# Install generated dictionaries
###############################################################################

cp \
    "${WORK_DIR}/${ES_DICT}" \
    "${OUTPUT_DIR}/${ES_DICT}"

cp \
    "${WORK_DIR}/${EN_DICT}" \
    "${OUTPUT_DIR}/${EN_DICT}"

cp \
    "${WORK_DIR}/${DE_DICT}" \
    "${OUTPUT_DIR}/${DE_DICT}"

cp \
    "${WORK_DIR}/${ES_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${ES_SUGGESTIONS}"

cp \
    "${WORK_DIR}/${EN_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${EN_SUGGESTIONS}"

cp \
    "${WORK_DIR}/${DE_SUGGESTIONS}" \
    "${OUTPUT_DIR}/${DE_SUGGESTIONS}"

###############################################################################
# Validate dictionaries before delete generation
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
# Deletes are generated ONLY from the final .dict.
#
# The budget is applied to TOTAL mappings, not merely unique delete keys.
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

alphabet = set(
    "abcdefghijklmnopqrstuvwxyz"
)

spanish_extra = set(
    "áéíóúüñ"
)

german_extra = set(
    "äöüß"
)

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
    result = set()

    for index in range(len(word)):
        key = (
            word[:index]
            + word[index + 1:]
        )

        if key:
            result.add(key)

    return result

def generate_distance_two(word):
    result = set()

    first_level = generate_distance_one(word)

    for intermediate in first_level:
        for index in range(len(intermediate)):
            key = (
                intermediate[:index]
                + intermediate[index + 1:]
            )

            if key:
                result.add(key)

    return result

###############################################################################
# Load final dictionary.
###############################################################################

dictionary = []

with open(
    dictionary_file,
    "r",
    encoding="utf-8",
) as source:

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

###############################################################################
# Load suggestion ranking.
#
# The suggestion file is already ordered by frequency before being sorted
# alphabetically for its final output. Since the final .suggestions file is
# alphabetic, we cannot use it as a frequency ranking source.
#
# Therefore distance-2 coverage is selected deterministically from the first
# TOP_DISTANCE2_WORDS valid dictionary words by lexical order.
#
# This keeps generation deterministic and bounded.
###############################################################################

suggestion_words = []

with open(
    suggestions_file,
    "r",
    encoding="utf-8",
) as source:

    header = source.readline().rstrip("\n\r")

    if header != "#POCKETBOARD-SUGGESTIONS-1":
        raise SystemExit(
            f"{language}: invalid suggestion header"
        )

    for line in source:
        word = normalize(line)

        if not word:
            continue

        if valid_delete_word(word):
            suggestion_words.append(word)

distance2_targets = set(
    suggestion_words[:TOP_DISTANCE2_WORDS]
)

###############################################################################
# Delete map.
#
# Each delete key can point to at most MAX_DELETES_PER_WORD targets.
###############################################################################

delete_map = {}
mapping_count = 0

def add_delete(delete_key, target):
    global mapping_count

    if not delete_key:
        return False

    if delete_key == target:
        return False

    if not valid_delete_word(delete_key):
        return False

    existing = delete_map.get(delete_key)

    if existing is None:
        if mapping_count >= budget:
            return False

        delete_map[delete_key] = [target]
        mapping_count += 1
        return True

    if target in existing:
        return False

    if len(existing) >= MAX_DELETES_PER_WORD:
        return False

    if mapping_count >= budget:
        return False

    existing.append(target)
    mapping_count += 1

    return True

###############################################################################
# Generate distance-1 and distance-2 deletes.
###############################################################################

for word in dictionary:
    if mapping_count >= budget:
        break

    for delete_key in generate_distance_one(word):
        if mapping_count >= budget:
            break

        add_delete(
            delete_key,
            word,
        )

    if mapping_count >= budget:
        break

    if word in distance2_targets:
        for delete_key in generate_distance_two(word):
            if mapping_count >= budget:
                break

            add_delete(
                delete_key,
                word,
            )

###############################################################################
# Deterministic output.
###############################################################################

rows = []

for delete_key, targets in delete_map.items():
    for target in sorted(targets):
        rows.append(
            (delete_key, target)
        )

rows.sort(
    key=lambda item: (
        item[0],
        item[1],
    )
)

with open(
    output_file,
    "w",
    encoding="utf-8",
    newline="\n",
) as out:

    out.write(
        "#POCKETBOARD-DELETES-1\n"
    )

    out.write(
        "#distance=1,2\n"
    )

    out.write(
        "#source=dict-only\n"
    )

    out.write(
        "#delete<TAB>target\n"
    )

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
    "${OUTPUT_DIR}/${DE_DELETES}" \
    "${OUTPUT_DIR}/${DE_DELETES}" \
    "${DE_DELETE_BUDGET}"
```
