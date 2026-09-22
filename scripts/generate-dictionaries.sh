#!/usr/bin/env bash

set -euo pipefail

###############################################################################
# PocketBoard dictionary generator
#
# Hunspell is used ONLY as a vocabulary source.
# It is NOT used to validate candidates word by word.
#
# Pipeline:
#
#   Hunspell .dic
#        +
#   Frequency sources
#        +
#   Curated/core vocabulary
#        ↓
#   Unicode NFC normalization
#        ↓
#   Strict token filtering
#        ↓
#   Deduplication
#        ↓
#   validated.txt
#        ↓
#   .dict
#        ↓
#   .deletes
#
# Design goals:
#   - Preserve Unicode and accents.
#   - Never strip diacritics.
#   - Preserve Argentine Spanish voseo.
#   - Keep mandatory/core vocabulary.
#   - Keep Hunspell as a source, not a validator.
#   - Generate delete keys ONLY from final dictionary words.
#   - Never feed delete keys back into vocabulary generation.
#   - Produce deterministic output.
#   - Fail early when an expected intermediate file is missing.
###############################################################################

export LANG="C.UTF-8"
export LC_ALL="C.UTF-8"

###############################################################################
# Paths
###############################################################################

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK_DIR="${ROOT_DIR}/build/pocketboard-dictionaries"
OUTPUT_DIR="${ROOT_DIR}/app/src/main/assets/dictionaries"

HUNSPELL_SOURCE_BASE="https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries"
FREQUENCY_BASE="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"
LEIPZIG_BASE="https://downloads.wortschatz-leipzig.de/corpora"

###############################################################################
# Languages
###############################################################################

LANGUAGES=(
    "es-AR"
    "en-en"
    "de-de"
)

declare -A HUNSPELL_LANGUAGE=(
    ["es-AR"]="es"
    ["en-en"]="en"
    ["de-de"]="de"
)

declare -A FREQUENCY_LANGUAGE=(
    ["es-AR"]="es"
    ["en-en"]="en"
)

###############################################################################
# Limits
###############################################################################

# Maximum number of vocabulary words retained per language.
SOURCE_MAX_ENTRIES=500000

# Delete budgets.
ES_DELETE_BUDGET=2500000
EN_DELETE_BUDGET=2500000
DE_DELETE_BUDGET=2500000

GLOBAL_DELETE_BUDGET=7500000

# Distance-2 correction is intentionally concentrated on the highest-priority
# part of the final vocabulary.
TOP_DISTANCE2_WORDS=15000

MAX_DELETE_DISTANCE=2
MAX_DELETE_WORD_LENGTH=24
MAX_CANDIDATES_PER_DELETE=3

###############################################################################
# Core Spanish Argentina vocabulary
#
# These words are added directly to the final vocabulary.
#
# IMPORTANT:
#   - Accents are intentionally preserved.
#   - Argentine voseo forms are explicitly included.
#   - "manana" is intentionally NOT included.
###############################################################################

CORE_ES_AR_WORDS=(
    "a"
    "acá"
    "ahora"
    "algo"
    "al"
    "alguien"
    "allá"
    "también"
    "bien"
    "cada"
    "casa"
    "cómo"
    "cuando"
    "cuándo"
    "debería"
    "decir"
    "decís"
    "día"
    "días"
    "donde"
    "dónde"
    "el"
    "en"
    "es"
    "estar"
    "estás"
    "este"
    "esto"
    "hacer"
    "hacés"
    "hay"
    "hola"
    "mañana"
    "más"
    "menos"
    "mismo"
    "muy"
    "necesito"
    "necesitás"
    "nunca"
    "para"
    "pasaría"
    "poder"
    "podés"
    "porque"
    "por"
    "qué"
    "querer"
    "querés"
    "saber"
    "sabés"
    "sé"
    "sentís"
    "ser"
    "si"
    "sí"
    "sobre"
    "sos"
    "tener"
    "tenés"
    "tengo"
    "tiempo"
    "todo"
    "todos"
    "tu"
    "tú"
    "una"
    "uno"
    "venir"
    "venís"
    "vos"
    "voy"
    "ya"
    "yo"
)

###############################################################################
# Core English vocabulary
###############################################################################

CORE_EN_WORDS=(
    "a"
    "about"
    "after"
    "again"
    "all"
    "also"
    "always"
    "and"
    "any"
    "are"
    "around"
    "because"
    "before"
    "being"
    "between"
    "but"
    "can"
    "could"
    "day"
    "do"
    "does"
    "done"
    "each"
    "even"
    "every"
    "for"
    "from"
    "get"
    "give"
    "go"
    "good"
    "have"
    "hello"
    "here"
    "how"
    "i"
    "if"
    "in"
    "into"
    "is"
    "it"
    "just"
    "know"
    "like"
    "make"
    "more"
    "most"
    "my"
    "need"
    "never"
    "new"
    "no"
    "not"
    "now"
    "of"
    "on"
    "one"
    "only"
    "or"
    "other"
    "our"
    "out"
    "please"
    "really"
    "right"
    "say"
    "see"
    "she"
    "should"
    "so"
    "some"
    "than"
    "that"
    "the"
    "their"
    "there"
    "they"
    "this"
    "time"
    "to"
    "today"
    "tomorrow"
    "too"
    "under"
    "up"
    "us"
    "very"
    "want"
    "was"
    "we"
    "well"
    "were"
    "what"
    "when"
    "where"
    "which"
    "who"
    "why"
    "will"
    "with"
    "would"
    "you"
    "your"
)

###############################################################################
# Core German vocabulary
###############################################################################

CORE_DE_WORDS=(
    "aber"
    "alle"
    "als"
    "also"
    "auch"
    "auf"
    "aus"
    "bei"
    "bin"
    "bis"
    "bitte"
    "da"
    "danke"
    "das"
    "dass"
    "dein"
    "der"
    "die"
    "dies"
    "diese"
    "du"
    "ein"
    "eine"
    "er"
    "es"
    "für"
    "ganz"
    "gehen"
    "gut"
    "haben"
    "hallo"
    "hier"
    "ich"
    "immer"
    "in"
    "ist"
    "ja"
    "jetzt"
    "kann"
    "kein"
    "kommen"
    "können"
    "machen"
    "man"
    "mehr"
    "mein"
    "mit"
    "morgen"
    "möglich"
    "möglicherweise"
    "müssen"
    "nach"
    "nicht"
    "noch"
    "nur"
    "oder"
    "sehr"
    "sein"
    "sie"
    "sind"
    "so"
    "schon"
    "über"
    "und"
    "uns"
    "vom"
    "von"
    "war"
    "was"
    "wenn"
    "wer"
    "wie"
    "wieder"
    "will"
    "wir"
    "wo"
    "zu"
    "zum"
    "zur"
    "entschuldigung"
    "wahrscheinlich"
)

###############################################################################
# Required commands
###############################################################################

require_command() {
    local command_name="$1"

    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo "ERROR: Required command not found: ${command_name}" >&2
        exit 1
    fi
}

for command_name in \
    curl \
    sort \
    grep \
    sed \
    tr \
    head \
    tail \
    wc \
    cut \
    tar \
    find \
    python3
do
    require_command "${command_name}"
done

###############################################################################
# Logging
###############################################################################

print_section() {
    echo
    echo "============================================================"
    echo " $1"
    echo "============================================================"
    echo
}

###############################################################################
# Download helper
###############################################################################

download_file() {
    local url="$1"
    local destination="$2"

    mkdir -p "$(dirname "${destination}")"

    echo "Downloading:"
    echo "  ${url}"
    echo "  -> ${destination}"

    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --retry 3 \
        --retry-delay 2 \
        --connect-timeout 30 \
        --max-time 300 \
        "${url}" \
        -o "${destination}"

    if [[ ! -s "${destination}" ]]; then
        echo "ERROR: Downloaded file is empty:" >&2
        echo "  ${destination}" >&2
        exit 1
    fi
}

###############################################################################
# Download Hunspell source dictionaries
###############################################################################

download_hunspell_dictionary() {
    local language="$1"
    local source_language="${HUNSPELL_LANGUAGE[${language}]}"
    local language_dir="${WORK_DIR}/${language}"

    mkdir -p "${language_dir}/hunspell"

    download_file \
        "${HUNSPELL_SOURCE_BASE}/${source_language}/index.dic" \
        "${language_dir}/hunspell/index.dic"

    download_file \
        "${HUNSPELL_SOURCE_BASE}/${source_language}/index.aff" \
        "${language_dir}/hunspell/index.aff"
}

###############################################################################
# Download FrequencyWords source
###############################################################################

download_frequency_dictionary() {
    local language="$1"
    local source_language="${FREQUENCY_LANGUAGE[${language}]}"
    local language_dir="${WORK_DIR}/${language}"

    mkdir -p "${language_dir}/frequency"

    download_file \
        "${FREQUENCY_BASE}/${source_language}/${source_language}_full.txt" \
        "${language_dir}/frequency/${source_language}_full.txt"
}

###############################################################################
# Download German Leipzig source
###############################################################################

download_german_frequency() {
    local language_dir="${WORK_DIR}/de-de"
    local archive="${language_dir}/frequency/deu_news_2025_1M.tar.gz"
    local extract_dir="${language_dir}/frequency/leipzig"

    mkdir -p "${extract_dir}"

    download_file \
        "${LEIPZIG_BASE}/deu_news_2025_1M.tar.gz" \
        "${archive}"

    tar \
        -xzf "${archive}" \
        -C "${extract_dir}"

    if ! find "${extract_dir}" -type f -print -quit | grep -q .; then
        echo "ERROR: Leipzig archive contains no usable files." >&2
        exit 1
    fi
}

###############################################################################
# Token validation
###############################################################################

normalize_and_filter() {
    local input_file="$1"
    local output_file="$2"

    python3 - "${input_file}" "${output_file}" <<'PY'
import sys
import unicodedata

source = sys.argv[1]
destination = sys.argv[2]

def valid_token(word):
    if not word:
        return False

    if len(word) > 64:
        return False

    if any(ch.isspace() for ch in word):
        return False

    if any(ord(ch) < 32 for ch in word):
        return False

    has_letter = False

    for ch in word:
        category = unicodedata.category(ch)

        if ch.isalpha():
            has_letter = True
            continue

        if category.startswith("M"):
            continue

        if ch in ("'", "’", "-"):
            continue

        return False

    return has_letter

seen = set()

with open(source, "r", encoding="utf-8", errors="ignore") as src:
    for line in src:
        word = line.strip()

        if not word:
            continue

        word = unicodedata.normalize("NFC", word)
        word = word.lower()

        if valid_token(word):
            seen.add(word)

with open(destination, "w", encoding="utf-8") as dst:
    for word in sorted(seen):
        dst.write(word + "\n")
PY
}

###############################################################################
# Extract words from Hunspell .dic
#
# Hunspell flags are removed:
#
#   word/ABC
#
# becomes:
#
#   word
#
# The .aff file is NOT used for validation.
###############################################################################

extract_hunspell_words() {
    local dictionary_file="$1"
    local output_file="$2"

    python3 - "${dictionary_file}" "${output_file}" <<'PY'
import sys
import unicodedata

source = sys.argv[1]
destination = sys.argv[2]

def valid_token(word):
    if not word:
        return False

    if len(word) > 64:
        return False

    if any(ch.isspace() for ch in word):
        return False

    if any(ord(ch) < 32 for ch in word):
        return False

    has_letter = False

    for ch in word:
        category = unicodedata.category(ch)

        if ch.isalpha():
            has_letter = True
            continue

        if category.startswith("M"):
            continue

        if ch in ("'", "’", "-"):
            continue

        return False

    return has_letter

with open(source, "r", encoding="utf-8", errors="ignore") as src:
    lines = src.readlines()

if not lines:
    raise SystemExit("ERROR: Empty Hunspell dictionary")

start = 1

# Standard Hunspell .dic files begin with an entry count.
try:
    int(lines[0].strip())
except ValueError:
    start = 0

with open(destination, "w", encoding="utf-8") as dst:
    for line in lines[start:]:
        word = line.strip()

        if not word:
            continue

        # Remove Hunspell flags.
        word = word.split("/", 1)[0]

        word = unicodedata.normalize("NFC", word)
        word = word.lower()

        if valid_token(word):
            dst.write(word + "\n")
PY
}

###############################################################################
# Normalize FrequencyWords
###############################################################################

extract_frequency_words_frequencywords() {
    local input_file="$1"
    local output_file="$2"

    python3 - "${input_file}" "${output_file}" <<'PY'
import sys
import unicodedata

source = sys.argv[1]
destination = sys.argv[2]

def valid_token(word):
    if not word:
        return False

    if len(word) > 64:
        return False

    if any(ch.isspace() for ch in word):
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

    return any(ch.isalpha() for ch in word)

with open(source, "r", encoding="utf-8", errors="ignore") as src, \
     open(destination, "w", encoding="utf-8") as dst:

    for line in src:
        parts = line.strip().split()

        if not parts:
            continue

        word = parts[0]

        word = word.split("/", 1)[0]
        word = unicodedata.normalize("NFC", word)
        word = word.lower()

        if valid_token(word):
            dst.write(word + "\n")
PY
}

###############################################################################
# Normalize Leipzig German source
###############################################################################

extract_german_leipzig_words() {
    local input_file="$1"
    local output_file="$2"

    python3 - "${input_file}" "${output_file}" <<'PY'
import sys
import unicodedata

source = sys.argv[1]
destination = sys.argv[2]

def valid_token(word):
    if not word:
        return False

    if len(word) > 64:
        return False

    if any(ch.isspace() for ch in word):
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

    return any(ch.isalpha() for ch in word)

with open(source, "r", encoding="utf-8", errors="ignore") as src, \
     open(destination, "w", encoding="utf-8") as dst:

    for line in src:
        parts = line.strip().split()

        if not parts:
            continue

        # Leipzig word-frequency files generally contain several columns.
        # Find the first valid lexical token.
        selected = None

        for part in parts:
            candidate = unicodedata.normalize("NFC", part).lower()

            if valid_token(candidate):
                selected = candidate
                break

        if selected is not None:
            dst.write(selected + "\n")
PY
}

###############################################################################
# Write core words
###############################################################################

write_core_words() {
    local language="$1"
    local output_file="$2"

    case "${language}" in
        es-AR)
            printf '%s\n' "${CORE_ES_AR_WORDS[@]}" > "${output_file}"
            ;;

        en-en)
            printf '%s\n' "${CORE_EN_WORDS[@]}" > "${output_file}"
            ;;

        de-de)
            printf '%s\n' "${CORE_DE_WORDS[@]}" > "${output_file}"
            ;;

        *)
            echo "ERROR: Unknown language: ${language}" >&2
            exit 1
            ;;
    esac
}

###############################################################################
# Build final vocabulary
###############################################################################

build_candidate_vocabulary() {
    local language="$1"

    local language_dir="${WORK_DIR}/${language}"

    local hunspell_words="${language_dir}/hunspell-words.txt"
    local frequency_words="${language_dir}/frequency-words.txt"
    local core_words="${language_dir}/core-words.txt"

    local raw_candidates="${language_dir}/raw-candidates.txt"
    local normalized_candidates="${language_dir}/normalized-candidates.txt"
    local final_vocabulary="${language_dir}/validated.txt"

    print_section "Building candidate vocabulary: ${language}"

    ###########################################################################
    # Hunspell source
    ###########################################################################

    extract_hunspell_words \
        "${language_dir}/hunspell/index.dic" \
        "${hunspell_words}"

    ###########################################################################
    # Frequency source
    ###########################################################################

    case "${language}" in
        es-AR)
            extract_frequency_words_frequencywords \
                "${language_dir}/frequency/es_full.txt" \
                "${frequency_words}"
            ;;

        en-en)
            extract_frequency_words_frequencywords \
                "${language_dir}/frequency/en_full.txt" \
                "${frequency_words}"
            ;;

        de-de)
            local leipzig_file

            leipzig_file="$(
                find "${language_dir}/frequency/leipzig" \
                    -type f \
                    -print \
                    -quit
            )"

            if [[ -z "${leipzig_file}" ]]; then
                echo "ERROR: Could not locate German Leipzig frequency file." >&2
                exit 1
            fi

            extract_german_leipzig_words \
                "${leipzig_file}" \
                "${frequency_words}"
            ;;
    esac

    ###########################################################################
    # Core vocabulary
    ###########################################################################

    write_core_words \
        "${language}" \
        "${core_words}"

    ###########################################################################
    # Combine sources
    ###########################################################################

    cat \
        "${hunspell_words}" \
        "${frequency_words}" \
        "${core_words}" \
        > "${raw_candidates}"

    echo "Candidate vocabulary:"
    wc -l < "${raw_candidates}"

    ###########################################################################
    # Normalize and deduplicate
    ###########################################################################

    normalize_and_filter \
        "${raw_candidates}" \
        "${normalized_candidates}"

    ###########################################################################
    # Frequency-first ordering
    #
    # Frequency source gets priority.
    # Hunspell source fills the remaining vocabulary.
    # Core vocabulary is always retained.
    ###########################################################################

    python3 - \
        "${frequency_words}" \
        "${hunspell_words}" \
        "${core_words}" \
        "${normalized_candidates}" \
        "${final_vocabulary}" \
        "${SOURCE_MAX_ENTRIES}" \
        <<'PY'
import sys
import unicodedata

frequency_file = sys.argv[1]
hunspell_file = sys.argv[2]
core_file = sys.argv[3]
normalized_file = sys.argv[4]
output_file = sys.argv[5]
limit = int(sys.argv[6])

def normalize(word):
    return unicodedata.normalize("NFC", word.strip()).lower()

def read_words(path):
    result = []

    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            word = normalize(line)

            if word:
                result.append(word)

    return result

frequency = read_words(frequency_file)
hunspell = read_words(hunspell_file)
core = read_words(core_file)
normalized = read_words(normalized_file)

seen = set()
ordered = []

def append_unique(words):
    for word in words:
        if word in seen:
            continue

        seen.add(word)
        ordered.append(word)

# Highest priority: frequency vocabulary.
append_unique(frequency)

# Then Hunspell source vocabulary.
append_unique(hunspell)

# Then normalized union as a safety net.
append_unique(normalized)

# Core vocabulary is mandatory.
append_unique(core)

core_set = set(core)

###############################################################################
# Apply vocabulary limit while preserving every core word.
###############################################################################

if len(ordered) > limit:
    selected = []

    for word in ordered:
        if len(selected) >= limit:
            break

        selected.append(word)

    selected_set = set(selected)

    for word in core:
        if word not in selected_set:
            selected.append(word)
            selected_set.add(word)

    ordered = selected

###############################################################################
# Final deterministic output.
#
# Keep frequency priority rather than alphabetically sorting the dictionary.
###############################################################################

with open(output_file, "w", encoding="utf-8") as out:
    for word in ordered:
        out.write(word + "\n")
PY

    ###########################################################################
    # Hard guards.
    ###########################################################################

    if [[ ! -f "${final_vocabulary}" ]]; then
        echo "ERROR: Final vocabulary was not created:" >&2
        echo "  ${final_vocabulary}" >&2
        exit 1
    fi

    if [[ ! -s "${final_vocabulary}" ]]; then
        echo "ERROR: Final vocabulary is empty:" >&2
        echo "  ${final_vocabulary}" >&2
        exit 1
    fi

    echo
    echo "Final vocabulary:"
    wc -l < "${final_vocabulary}"
}

###############################################################################
# Create PocketBoard .dict
###############################################################################

create_dictionary() {
    local language="$1"

    local language_dir="${WORK_DIR}/${language}"
    local validated="${language_dir}/validated.txt"
    local output="${OUTPUT_DIR}/${language}.dict"

    print_section "Generating dictionary: ${language}"

    if [[ ! -f "${validated}" ]]; then
        echo "ERROR: Missing final vocabulary:" >&2
        echo "  ${validated}" >&2
        exit 1
    fi

    if [[ ! -s "${validated}" ]]; then
        echo "ERROR: Final vocabulary is empty:" >&2
        echo "  ${validated}" >&2
        exit 1
    fi

    {
        printf '%s\n' '#POCKETBOARD-DICT-1'
        cat "${validated}"
    } > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: Failed to create dictionary:" >&2
        echo "  ${output}" >&2
        exit 1
    fi

    echo "Dictionary:"
    echo "  ${output}"

    echo "Words:"
    tail -n +2 "${output}" | wc -l
}

###############################################################################
# Generate delete keys
#
# IMPORTANT:
#
# Deletes are generated ONLY from the final .dict.
#
# They are NEVER added to candidate vocabulary.
# They are NEVER fed back into vocabulary generation.
###############################################################################

generate_deletes() {
    local language="$1"

    local dictionary="${OUTPUT_DIR}/${language}.dict"
    local output="${OUTPUT_DIR}/${language}.deletes"

    local budget

    case "${language}" in
        es-AR)
            budget="${ES_DELETE_BUDGET}"
            ;;

        en-en)
            budget="${EN_DELETE_BUDGET}"
            ;;

        de-de)
            budget="${DE_DELETE_BUDGET}"
            ;;

        *)
            echo "ERROR: Unknown language: ${language}" >&2
            exit 1
            ;;
    esac

    print_section "Generating delete keys: ${language}"

    if [[ ! -f "${dictionary}" ]]; then
        echo "ERROR: Dictionary missing:" >&2
        echo "  ${dictionary}" >&2
        exit 1
    fi

    python3 - \
        "${dictionary}" \
        "${output}" \
        "${budget}" \
        "${TOP_DISTANCE2_WORDS}" \
        "${MAX_DELETE_DISTANCE}" \
        "${MAX_DELETE_WORD_LENGTH}" \
        "${MAX_CANDIDATES_PER_DELETE}" \
        <<'PY'
import sys
import unicodedata

dictionary_file = sys.argv[1]
output_file = sys.argv[2]
budget = int(sys.argv[3])
top_distance2_words = int(sys.argv[4])
max_distance = int(sys.argv[5])
max_word_length = int(sys.argv[6])
max_candidates_per_word = int(sys.argv[7])

if max_distance != 2:
    raise SystemExit("ERROR: This generator currently supports distance 1/2.")

def normalize(word):
    return unicodedata.normalize("NFC", word.strip()).lower()

###############################################################################
# Read ONLY final dictionary words.
###############################################################################

words = []

with open(dictionary_file, "r", encoding="utf-8") as f:
    for line in f:
        word = line.rstrip("\n")

        if word == "#POCKETBOARD-DICT-1":
            continue

        word = normalize(word)

        if word:
            words.append(word)

###############################################################################
# Deduplicate while preserving dictionary priority.
###############################################################################

seen = set()
unique_words = []

for word in words:
    if word in seen:
        continue

    seen.add(word)
    unique_words.append(word)

###############################################################################
# Only reasonably short words are useful for deletion generation.
###############################################################################

eligible = [
    word
    for word in unique_words
    if len(word) <= max_word_length
]

###############################################################################
# Delete table.
#
# delete key -> validated target
###############################################################################

entries = {}

def add_delete(delete_key, target):
    if not delete_key:
        return False

    if delete_key == target:
        return False

    if delete_key in entries:
        return False

    if len(entries) >= budget:
        return False

    entries[delete_key] = target
    return True

###############################################################################
# Distance-1 deletes.
###############################################################################

def deletions_distance_1(word):
    result = set()

    chars = list(word)

    for index in range(len(chars)):
        result.add(
            "".join(
                chars[:index] +
                chars[index + 1:]
            )
        )

    return result

###############################################################################
# Distance-2 deletes.
###############################################################################

def deletions_distance_2(word):
    result = set()

    first = deletions_distance_1(word)

    for intermediate in first:
        result.update(
            deletions_distance_1(intermediate)
        )

    return result

###############################################################################
# Distance 2 for highest-priority vocabulary.
###############################################################################

distance2_words = eligible[:top_distance2_words]

for word in distance2_words:
    if len(entries) >= budget:
        break

    candidates = sorted(
        deletions_distance_2(word),
        key=lambda value: (len(value), value)
    )

    added = 0

    for delete_key in candidates:
        if added >= max_candidates_per_word:
            break

        if add_delete(delete_key, word):
            added += 1

###############################################################################
# Distance 1 for remaining vocabulary.
###############################################################################

if len(entries) < budget:
    for word in eligible:
        if len(entries) >= budget:
            break

        candidates = sorted(
            deletions_distance_1(word),
            key=lambda value: (len(value), value)
        )

        added = 0

        for delete_key in candidates:
            if added >= max_candidates_per_word:
                break

            if add_delete(delete_key, word):
                added += 1

###############################################################################
# Write output.
###############################################################################

with open(output_file, "w", encoding="utf-8") as out:
    out.write("#POCKETBOARD-DELETES-1\n")
    out.write("#distance=1,2\n")
    out.write("#source=dict-only\n")
    out.write("#delete<TAB>target\n")

    for delete_key, target in sorted(entries.items()):
        out.write(delete_key)
        out.write("\t")
        out.write(target)
        out.write("\n")

print(f"Delete entries: {len(entries)}")
PY

    if [[ ! -f "${output}" ]]; then
        echo "ERROR: Delete file was not created:" >&2
        echo "  ${output}" >&2
        exit 1
    fi

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: Delete file is empty:" >&2
        echo "  ${output}" >&2
        exit 1
    fi

    echo
    echo "Delete entries:"
    tail -n +5 "${output}" | wc -l
}

###############################################################################
# Validate .dict format
###############################################################################

validate_dictionary() {
    local language="$1"
    local dictionary="${OUTPUT_DIR}/${language}.dict"

    print_section "Checking dictionary: ${language}"

    if [[ ! -f "${dictionary}" ]]; then
        echo "ERROR: Missing dictionary:" >&2
        echo "  ${dictionary}" >&2
        exit 1
    fi

    if [[ "$(head -n 1 "${dictionary}")" != "#POCKETBOARD-DICT-1" ]]; then
        echo "ERROR: Invalid dictionary header:" >&2
        echo "  ${dictionary}" >&2
        exit 1
    fi

    python3 - "${dictionary}" <<'PY'
import sys
import unicodedata

path = sys.argv[1]

with open(path, "r", encoding="utf-8") as f:
    lines = f.readlines()

if not lines:
    raise SystemExit("ERROR: Empty dictionary")

if lines[0].rstrip("\n") != "#POCKETBOARD-DICT-1":
    raise SystemExit("ERROR: Invalid dictionary header")

seen = set()

for number, line in enumerate(lines[1:], start=2):
    word = line.rstrip("\n")

    if not word:
        raise SystemExit(
            f"ERROR: Empty dictionary token at line {number}"
        )

    normalized = unicodedata.normalize("NFC", word)

    if normalized != word:
        raise SystemExit(
            f"ERROR: NFC regression at line {number}: {word}"
        )

    if any(ch.isspace() for ch in word):
        raise SystemExit(
            f"ERROR: Whitespace in dictionary token at line {number}: {word}"
        )

    if word in seen:
        raise SystemExit(
            f"ERROR: Duplicate dictionary word at line {number}: {word}"
        )

    seen.add(word)

print(f"Dictionary validation OK: {len(seen)} words")
PY
}

###############################################################################
# Validate .deletes format
###############################################################################

validate_delete_format() {
    local language="$1"

    local dictionary="${OUTPUT_DIR}/${language}.dict"
    local deletes="${OUTPUT_DIR}/${language}.deletes"

    print_section "Checking deletes: ${language}"

    if [[ ! -f "${deletes}" ]]; then
        echo "ERROR: Missing delete file:" >&2
        echo "  ${deletes}" >&2
        exit 1
    fi

    python3 - "${dictionary}" "${deletes}" <<'PY'
import sys

dictionary_file = sys.argv[1]
delete_file = sys.argv[2]

###############################################################################
# Read final dictionary.
###############################################################################

dictionary_words = set()

with open(dictionary_file, "r", encoding="utf-8") as f:
    for line in f:
        word = line.rstrip("\n")

        if not word:
            continue

        if word.startswith("#"):
            continue

        dictionary_words.add(word)

###############################################################################
# Validate delete file.
###############################################################################

with open(delete_file, "r", encoding="utf-8") as f:
    lines = f.readlines()

if not lines:
    raise SystemExit("ERROR: Empty delete file")

if lines[0].rstrip("\n") != "#POCKETBOARD-DELETES-1":
    raise SystemExit("ERROR: Invalid delete header")

if len(lines) < 4:
    raise SystemExit("ERROR: Delete file metadata is incomplete")

seen_delete_keys = set()
count = 0

for number, line in enumerate(lines[4:], start=5):
    line = line.rstrip("\n")

    if not line:
        continue

    if "\t" not in line:
        raise SystemExit(
            f"ERROR: Invalid delete format at line {number}"
        )

    delete_key, target = line.split("\t", 1)

    if not delete_key:
        raise SystemExit(
            f"ERROR: Empty delete key at line {number}"
        )

    if not target:
        raise SystemExit(
            f"ERROR: Empty delete target at line {number}"
        )

    if target not in dictionary_words:
        raise SystemExit(
            f"ERROR: Delete target is not in final dictionary at "
            f"line {number}: {target}"
        )

    if delete_key in seen_delete_keys:
        raise SystemExit(
            f"ERROR: Duplicate delete key at line {number}: {delete_key}"
        )

    seen_delete_keys.add(delete_key)
    count += 1

print(f"Delete validation OK: {count} entries")
PY
}

###############################################################################
# Required vocabulary and Unicode regression checks
###############################################################################

python3 - "${OUTPUT_DIR}/es-AR.dict" "${OUTPUT_DIR}/en-en.dict" "${OUTPUT_DIR}/de-de.dict" <<'PY'
import sys
import unicodedata
from pathlib import Path

paths = {
    "es-AR": Path(sys.argv[1]),
    "en-en": Path(sys.argv[2]),
    "de-de": Path(sys.argv[3]),
}

required = {
    "es-AR": [
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
    ],
    "en-en": [
        "the",
        "have",
        "hello",
    ],
    "de-de": [
        "ich",
        "nicht",
        "morgen",
        "entschuldigung",
        "wahrscheinlich",
        "möglicherweise",
    ],
}

for language, path in paths.items():
    if not path.is_file():
        raise SystemExit(f"ERROR: Missing dictionary: {path}")

    words = set()

    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            word = line.rstrip("\n\r")

            if not word or word.startswith("#"):
                continue

            word = unicodedata.normalize("NFC", word)
            words.add(word)

    for word in required[language]:
        word = unicodedata.normalize("NFC", word)

        if word not in words:
            raise SystemExit(
                f"ERROR: Required word missing: {language}: {word}"
            )

    print(f"Required vocabulary OK: {language}")

# Spanish-specific regression guard.
es_words = set()

with paths["es-AR"].open("r", encoding="utf-8") as fh:
    for line in fh:
        word = line.rstrip("\n\r")

        if word and not word.startswith("#"):
            es_words.add(unicodedata.normalize("NFC", word))

if "manana" in es_words:
    raise SystemExit(
        "ERROR: es-AR regression: unaccented 'manana' must not be present"
    )

if "mañana" not in es_words:
    raise SystemExit(
        "ERROR: es-AR regression: accented 'mañana' must be present"
    )

print("Spanish accent regression OK: mañana present, manana absent")
PY

###############################################################################
# Explicit NFC checks.
###############################################################################

for word in (
    "mañana",
    "pasaría",
    "debería",
    "podés",
    "tenés",
    "querés",
    "hacés",
    "decís",
    "venís",
    "sentís",
    "acá",
    "cuándo",
    "dónde",
):
    if word not in es:
        raise SystemExit(
            f"ERROR: Spanish Unicode/voseo regression: {word}"
        )

print("Spanish accent and voseo checks OK")
PY
}

###############################################################################
# Global delete budget
###############################################################################

check_global_delete_budget() {
    print_section "Checking global delete budget"

    local total=0

    for language in "${LANGUAGES[@]}"; do
        local file="${OUTPUT_DIR}/${language}.deletes"

        if [[ ! -f "${file}" ]]; then
            echo "ERROR: Missing delete file:" >&2
            echo "  ${file}" >&2
            exit 1
        fi

        local count

        count=$(
            tail -n +5 "${file}" |
                grep -c $'\t' || true
        )

        total=$((total + count))
    done

    echo "Total delete entries: ${total}"
    echo "Global delete budget: ${GLOBAL_DELETE_BUDGET}"

    if (( total > GLOBAL_DELETE_BUDGET )); then
        echo "ERROR: Global delete budget exceeded." >&2
        exit 1
    fi
}

###############################################################################
# Final sanity check
###############################################################################

final_sanity_check() {
    print_section "Final sanity check"

    for language in "${LANGUAGES[@]}"; do
        local dictionary="${OUTPUT_DIR}/${language}.dict"
        local deletes="${OUTPUT_DIR}/${language}.deletes"

        if [[ ! -s "${dictionary}" ]]; then
            echo "ERROR: Dictionary is missing or empty:" >&2
            echo "  ${dictionary}" >&2
            exit 1
        fi

        if [[ ! -s "${deletes}" ]]; then
            echo "ERROR: Deletes are missing or empty:" >&2
            echo "  ${deletes}" >&2
            exit 1
        fi

        echo "${language}: OK"
    done
}

###############################################################################
# Size report
###############################################################################

size_report() {
    print_section "Generated dictionary size"

    local TOTAL_DICTIONARY_BYTES=0
    local TOTAL_DELETE_BYTES=0
    local TOTAL_METADATA_BYTES=0
    local TOTAL_GENERATED_BYTES=0

    for language in "${LANGUAGES[@]}"; do
        local dictionary="${OUTPUT_DIR}/${language}.dict"
        local deletes="${OUTPUT_DIR}/${language}.deletes"

        local dictionary_size
        local delete_size
        local metadata_size
        local language_total

        dictionary_size=$(
            wc -c < "${dictionary}"
        )

        delete_size=$(
            wc -c < "${deletes}"
        )

        # Metadata is embedded in the .deletes file.
        metadata_size=0

        language_total=$(
            (
                echo "${dictionary_size} + ${delete_size} + ${metadata_size}"
            ) |
                awk '{print $1 + $3 + $5}'
        )

        TOTAL_DICTIONARY_BYTES=$(
            (
                echo "${TOTAL_DICTIONARY_BYTES} + ${dictionary_size}"
            ) |
                awk '{print $1 + $3}'
        )

        TOTAL_DELETE_BYTES=$(
            (
                echo "${TOTAL_DELETE_BYTES} + ${delete_size}"
            ) |
                awk '{print $1 + $3}'
        )

        TOTAL_METADATA_BYTES=$(
            (
                echo "${TOTAL_METADATA_BYTES} + ${metadata_size}"
            ) |
                awk '{print $1 + $3}'
        )

        echo
        echo "${language}:"
        echo "  dictionary: ${dictionary_size} bytes"
        echo "  deletes:    ${delete_size} bytes"
        echo "  total:      ${language_total} bytes"
    done

    TOTAL_GENERATED_BYTES=$(
        (
            echo \
                "${TOTAL_DICTIONARY_BYTES} + " \
                "${TOTAL_DELETE_BYTES} + " \
                "${TOTAL_METADATA_BYTES}"
        ) |
            awk '{print $1 + $3 + $5}'
    )

    local TOTAL_MIB

    TOTAL_MIB=$(
        awk \
            -v bytes="${TOTAL_GENERATED_BYTES}" \
            'BEGIN { printf "%.2f", bytes / 1024 / 1024 }'
    )

    echo
    echo "Total:"
    echo "  dictionary: ${TOTAL_DICTIONARY_BYTES} bytes"
    echo "  deletes:    ${TOTAL_DELETE_BYTES} bytes"
    echo "  metadata:   ${TOTAL_METADATA_BYTES} bytes"
    echo "  generated:  ${TOTAL_GENERATED_BYTES} bytes"
    echo "  generated:  ${TOTAL_MIB} MiB"
}

###############################################################################
# Main
###############################################################################

print_section "PocketBoard dictionary generation"

echo "Root:"
echo "  ${ROOT_DIR}"

echo "Work directory:"
echo "  ${WORK_DIR}"

echo "Output directory:"
echo "  ${OUTPUT_DIR}"

###############################################################################
# Clean only generated working data.
###############################################################################

rm -rf "${WORK_DIR}"

mkdir -p "${WORK_DIR}"
mkdir -p "${OUTPUT_DIR}"

###############################################################################
# Download Hunspell source dictionaries.
###############################################################################

for language in "${LANGUAGES[@]}"; do
    download_hunspell_dictionary "${language}"
done

###############################################################################
# Download frequency sources.
###############################################################################

download_frequency_dictionary "es-AR"
download_frequency_dictionary "en-en"
download_german_frequency

###############################################################################
# Build vocabulary.
###############################################################################

for language in "${LANGUAGES[@]}"; do
    build_candidate_vocabulary "${language}"
done

###############################################################################
# Create final .dict assets.
###############################################################################

for language in "${LANGUAGES[@]}"; do
    create_dictionary "${language}"
done

###############################################################################
# Generate .deletes ONLY from final dictionaries.
###############################################################################

for language in "${LANGUAGES[@]}"; do
    generate_deletes "${language}"
done

###############################################################################
# Validate generated assets.
###############################################################################

for language in "${LANGUAGES[@]}"; do
    validate_dictionary "${language}"
done

for language in "${LANGUAGES[@]}"; do
    validate_delete_format "${language}"
done

check_required_words
check_global_delete_budget
final_sanity_check

###############################################################################
# Report.
###############################################################################

size_report

print_section "PocketBoard dictionary generation completed"

echo "Generated assets:"
echo

for language in "${LANGUAGES[@]}"; do
    echo "  ${OUTPUT_DIR}/${language}.dict"
    echo "  ${OUTPUT_DIR}/${language}.deletes"
done

echo
echo "Hunspell was used only as a vocabulary source."
echo "No word-by-word Hunspell validation was performed."
echo "No Hunspell core overlay was created."
echo "Delete keys were generated only from final dictionary words."
echo
echo "Generation completed successfully."
