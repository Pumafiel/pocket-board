#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C
export LANG=C

# ============================================================
# PocketBoard dictionary generator
# ============================================================
#
# Build-time only.
#
# Languages:
#   es-AR
#   en
#   de
#
# Sources:
#   wooorm/dictionaries       -> Hunspell vocabulary
#   FrequencyWords 2018       -> Spanish / English frequency
#   Leipzig Wortschatz 2025  -> German frequency
#
# IMPORTANT:
#
# The frequency lists are the PRIMARY ranking source.
# Hunspell is used as a spelling/vocabulary source, but a
# frequent word does NOT need to exist in Hunspell to be kept.
#
# This is intentional.
#
# Example:
#
#   podés
#   tenés
#   hacés
#   entschuldigung
#
# can therefore survive the selection even when the Hunspell
# dictionary does not contain the exact surface form.
#
# The runtime dictionary is kept compact.
#
# The SymSpell delete index is generated separately and is NOT
# included in the dictionary-size budget.
#
# ============================================================

ROOT_DIR="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/.." &&
    pwd
)"

WORK_DIR="${ROOT_DIR}/build/pocketboard-dictionaries"
OUTPUT_DIR="${ROOT_DIR}/app/src/main/assets/dictionaries"

WOOORM_BASE="https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries"
FREQUENCY_BASE="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"
LEIPZIG_URL="https://downloads.wortschatz-leipzig.de/corpora/deu_news_2025_1M.tar.gz"

ES_DIR="${WORK_DIR}/es-AR"
EN_DIR="${WORK_DIR}/en-en"
DE_DIR="${WORK_DIR}/de-de"

mkdir -p "${WORK_DIR}"
mkdir -p "${ES_DIR}"
mkdir -p "${EN_DIR}"
mkdir -p "${DE_DIR}"
mkdir -p "${OUTPUT_DIR}"

# ============================================================
# Size budgets
# ============================================================
#
# Global runtime dictionary target:
#
#   4 MiB total
#
# We distribute it approximately according to the amount of
# useful vocabulary available in each language.
#
# These are BYTE budgets for the plain .dict files.
#
# The delete indexes are deliberately NOT counted here because
# the runtime engine can regenerate/consume them separately
# according to the existing PocketBoard architecture.
#
# ============================================================

GLOBAL_DICT_BUDGET=$((4 * 1024 * 1024))

ES_DICT_BUDGET=$((1200 * 1024))
EN_DICT_BUDGET=$((1400 * 1024))
DE_DICT_BUDGET=$((1496 * 1024))

# Safety check.
ALLOCATED_BUDGET=$(
    (
        echo "${ES_DICT_BUDGET}"
        echo "${EN_DICT_BUDGET}"
        echo "${DE_DICT_BUDGET}"
    ) |
        awk '{sum += $1} END {print sum}'
)

if (( ALLOCATED_BUDGET > GLOBAL_DICT_BUDGET )); then
    echo "ERROR: language dictionary budgets exceed global budget."
    echo "  Allocated: ${ALLOCATED_BUDGET}"
    echo "  Global:    ${GLOBAL_DICT_BUDGET}"
    exit 1
fi

# ============================================================
# SymSpell configuration
# ============================================================

DELETE_MAX_DISTANCE=2
DELETE_MAX_WORD_LENGTH=24
MAX_CANDIDATES_PER_DELETE=8

# ============================================================
# Required commands
# ============================================================

require_command() {
    local command_name="$1"

    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo "ERROR: command not found: ${command_name}"
        exit 1
    fi
}

require_command curl
require_command sort
require_command sed
require_command tr
require_command head
require_command tail
require_command wc
require_command cut
require_command awk
require_command grep
require_command tar
require_command python3

# ============================================================
# Download helper
# ============================================================

download() {
    local url="$1"
    local destination="$2"

    echo ""
    echo "Downloading:"
    echo "  ${url}"

    curl \
        --fail \
        --location \
        --silent \
        --show-error \
        --retry 4 \
        --retry-delay 2 \
        --connect-timeout 30 \
        --max-time 300 \
        -A "PocketBoard-Build" \
        -o "${destination}" \
        "${url}"

    if [[ ! -s "${destination}" ]]; then
        echo "ERROR: empty download:"
        echo "  ${destination}"
        exit 1
    fi
}

# ============================================================
# Download Hunspell dictionaries
# ============================================================

echo ""
echo "============================================================"
echo " Downloading Hunspell dictionaries"
echo "============================================================"

download \
    "${WOOORM_BASE}/es-AR/index.dic" \
    "${ES_DIR}/index.dic"

download \
    "${WOOORM_BASE}/es-AR/index.aff" \
    "${ES_DIR}/index.aff"

download \
    "${WOOORM_BASE}/en/index.dic" \
    "${EN_DIR}/index.dic"

download \
    "${WOOORM_BASE}/en/index.aff" \
    "${EN_DIR}/index.aff"

download \
    "${WOOORM_BASE}/de/index.dic" \
    "${DE_DIR}/index.dic"

download \
    "${WOOORM_BASE}/de/index.aff" \
    "${DE_DIR}/index.aff"

# ============================================================
# Download frequency sources
# ============================================================

echo ""
echo "============================================================"
echo " Downloading frequency sources"
echo "============================================================"

download \
    "${FREQUENCY_BASE}/es/es_full.txt" \
    "${ES_DIR}/frequency.txt"

download \
    "${FREQUENCY_BASE}/en/en_full.txt" \
    "${EN_DIR}/frequency.txt"

LEIPZIG_ARCHIVE="${DE_DIR}/deu_news_2025_1M.tar.gz"
LEIPZIG_DIR="${DE_DIR}/leipzig"

download \
    "${LEIPZIG_URL}" \
    "${LEIPZIG_ARCHIVE}"

rm -rf "${LEIPZIG_DIR}"
mkdir -p "${LEIPZIG_DIR}"

echo ""
echo "============================================================"
echo " Extracting Leipzig German frequency list"
echo "============================================================"

tar \
    -xzf "${LEIPZIG_ARCHIVE}" \
    -C "${LEIPZIG_DIR}"

LEIPZIG_WORD_FILE="$(
    find "${LEIPZIG_DIR}" \
        -type f \
        \( \
            -name "*-words.txt" \
            -o \
            -name "*words.txt" \
        \) \
        | head -n 1
)"

if [[ -z "${LEIPZIG_WORD_FILE}" ]]; then
    echo "ERROR: Leipzig word list not found after extraction."
    echo ""
    echo "Extracted files:"
    find "${LEIPZIG_DIR}" -type f | head -50
    exit 1
fi

echo ""
echo "Leipzig frequency file:"
echo "  ${LEIPZIG_WORD_FILE}"

# ============================================================
# Normalize FrequencyWords
# ============================================================

normalize_frequency_words() {
    local language="$1"
    local input="$2"
    local output="$3"

    echo ""
    echo "Normalizing frequency list: ${language}"

    python3 - \
        "${input}" \
        "${output}" <<'PY'
import sys

input_file = sys.argv[1]
output_file = sys.argv[2]

seen = set()
count = 0

with open(input_file, encoding="utf-8", errors="ignore") as src:
    for raw in src:
        line = raw.strip()

        if not line:
            continue

        parts = line.split()

        if not parts:
            continue

        # FrequencyWords format:
        #
        # word frequency
        #
        # The first column is the token.
        word = parts[0].strip().lower()

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)

        with open(output_file, "a", encoding="utf-8") as out:
            out.write(word + "\n")

        count += 1

        if count >= 500000:
            break

print(f"Normalized frequency entries: {count}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: normalized frequency list is empty:"
        echo "  ${input}"
        exit 1
    fi
}

# ============================================================
# Normalize Leipzig
# ============================================================
#
# Leipzig corpus word lists can contain several columns.
#
# Depending on the corpus version, the relevant information is
# generally:
#
#   rank / word / frequency
#
# or:
#
#   word / frequency
#
# We detect the word column instead of assuming a fixed format.
#
# The resulting file is sorted by frequency descending.
#
# ============================================================

normalize_leipzig() {
    local input="$1"
    local output="$2"

    echo ""
    echo "Normalizing frequency list: de-de"

    python3 - \
        "${input}" \
        "${output}" <<'PY'
import sys
import re

input_file = sys.argv[1]
output_file = sys.argv[2]

rows = []

def is_integer(value):
    try:
        int(value)
        return True
    except Exception:
        return False

def is_float(value):
    try:
        float(value.replace(",", "."))
        return True
    except Exception:
        return False

with open(input_file, encoding="utf-8", errors="ignore") as src:
    for raw in src:
        line = raw.strip()

        if not line:
            continue

        parts = line.split()

        if len(parts) < 2:
            continue

        word = None
        frequency = None

        # ----------------------------------------------------
        # Detect the token.
        #
        # We prefer alphabetic-looking fields and reject pure
        # numeric fields.
        # ----------------------------------------------------

        for part in parts:
            candidate = part.strip().lower()

            if not candidate:
                continue

            if is_integer(candidate):
                continue

            if is_float(candidate):
                continue

            # Leipzig may contain punctuation. That is okay;
            # runtime normalization later can decide whether
            # the token is useful.
            word = candidate
            break

        if not word:
            continue

        # ----------------------------------------------------
        # Detect frequency.
        #
        # Prefer numeric columns.
        # ----------------------------------------------------

        numeric_values = []

        for part in parts:
            candidate = part.strip()

            if is_integer(candidate):
                numeric_values.append(int(candidate))
                continue

            if is_float(candidate):
                try:
                    numeric_values.append(float(candidate.replace(",", ".")))
                except Exception:
                    pass

        if numeric_values:
            # For Leipzig word lists the largest integer is
            # normally the occurrence count.
            frequency = max(numeric_values)

        if frequency is None:
            # Keep the token with a neutral score. It is still
            # useful as a fallback frequency candidate.
            frequency = 0

        rows.append((word, frequency))

# Deduplicate while keeping the highest observed frequency.
best = {}

for word, frequency in rows:
    previous = best.get(word)

    if previous is None or frequency > previous:
        best[word] = frequency

ranked = sorted(
    best.items(),
    key=lambda item: (-item[1], item[0])
)

with open(output_file, "w", encoding="utf-8") as out:
    for word, frequency in ranked[:500000]:
        out.write(word)
        out.write("\t")
        out.write(str(frequency))
        out.write("\n")

print(f"Normalized frequency entries: {min(len(ranked), 500000)}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: normalized Leipzig frequency list is empty:"
        echo "  ${input}"
        exit 1
    fi
}

normalize_frequency_words \
    "es-AR" \
    "${ES_DIR}/frequency.txt" \
    "${ES_DIR}/frequency.normalized"

normalize_frequency_words \
    "en-en" \
    "${EN_DIR}/frequency.txt" \
    "${EN_DIR}/frequency.normalized"

normalize_leipzig \
    "${LEIPZIG_WORD_FILE}" \
    "${DE_DIR}/frequency.normalized"

# ============================================================
# Extract Hunspell words
# ============================================================

extract_hunspell_words() {
    local dic="$1"
    local output="$2"

    tail -n +2 "${dic}" |
        sed 's/\r$//' |
        cut -d/ -f1 |
        sed 's/^[[:space:]]*//' |
        sed 's/[[:space:]]*$//' |
        tr '[:upper:]' '[:lower:]' |
        sed '/^[[:space:]]*$/d' |
        LC_ALL=C sort -u \
        > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: no Hunspell words extracted:"
        echo "  ${dic}"
        exit 1
    fi
}

extract_hunspell_words \
    "${ES_DIR}/index.dic" \
    "${ES_DIR}/hunspell.words"

extract_hunspell_words \
    "${EN_DIR}/index.dic" \
    "${EN_DIR}/hunspell.words"

extract_hunspell_words \
    "${DE_DIR}/index.dic" \
    "${DE_DIR}/hunspell.words"

# ============================================================
# PocketBoard fallback vocabulary
# ============================================================
#
# These are NOT mandatory.
#
# They are merely additional candidates that receive an
# artificial high frequency rank.
#
# This prevents regional forms and known important words from
# disappearing when a corpus uses another inflection/spelling.
#
# ============================================================

cat > "${ES_DIR}/additional.txt" <<'EOF'
vos
tenés
podés
querés
hacés
sabés
venís
decís
estás
sos
dás
vas
ves
oís
reís
acá
allá
che
laburo
laburar
quilombo
EOF

cat > "${EN_DIR}/additional.txt" <<'EOF'
im
i'm
ive
i've
id
i'd
ill
i'll
cant
can't
dont
don't
wont
won't
isnt
isn't
wasnt
wasn't
didnt
didn't
doesnt
doesn't
youre
you're
youve
you've
were
we're
weve
we've
theyre
they're
theyve
they've
EOF

cat > "${DE_DIR}/additional.txt" <<'EOF'
ich
du
er
sie
wir
ihr
nicht
kein
keine
einen
einem
einer
über
für
dass
daß
entschuldigung
wahrscheinlich
möglicherweise
morgen
bitte
danke
hallo
tschüss
EOF

# ============================================================
# Build candidate lists
# ============================================================

build_candidates() {
    local language="$1"
    local frequency="$2"
    local hunspell="$3"
    local additional="$4"
    local output="$5"

    local additional_ranked
    local frequency_candidates
    local hunspell_candidates

    additional_ranked="${WORK_DIR}/${language}.additional.ranked"
    frequency_candidates="${WORK_DIR}/${language}.frequency.candidates"
    hunspell_candidates="${WORK_DIR}/${language}.hunspell.candidates"

    rm -f \
        "${additional_ranked}" \
        "${frequency_candidates}" \
        "${hunspell_candidates}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Building candidates: ${language}"
    echo "============================================================"

    # --------------------------------------------------------
    # Frequency candidates
    #
    # IMPORTANT:
    # We DO NOT require Hunspell membership here.
    #
    # The corpus is the authority for frequency.
    # --------------------------------------------------------

    python3 - \
        "${frequency}" \
        "${frequency_candidates}" <<'PY'
import sys

input_file = sys.argv[1]
output_file = sys.argv[2]

seen = set()
count = 0

with open(input_file, encoding="utf-8", errors="ignore") as src, \
     open(output_file, "w", encoding="utf-8") as out:

    for raw in src:
        line = raw.strip()

        if not line:
            continue

        parts = line.split()

        if not parts:
            continue

        word = parts[0].strip().lower()

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)

        # Ignore obvious URLs/emails.
        if "://" in word:
            continue

        if "@" in word:
            continue

        # Ignore tokens made entirely from punctuation.
        if not any(ch.isalpha() for ch in word):
            continue

        out.write(word + "\n")
        count += 1

print(f"Frequency candidates: {count}")
PY

    # --------------------------------------------------------
    # Additional candidates
    #
    # These are deliberately few.
    # --------------------------------------------------------

    cat "${additional}" |
        sed 's/\r$//' |
        sed '/^[[:space:]]*$/d' |
        tr '[:upper:]' '[:lower:]' |
        LC_ALL=C sort -u \
        > "${additional_ranked}"

    # --------------------------------------------------------
    # Hunspell candidates
    #
    # These provide a fallback pool after the frequency source.
    # --------------------------------------------------------

    cp "${hunspell}" "${hunspell_candidates}"

    # --------------------------------------------------------
    # Merge.
    #
    # Order matters:
    #
    #   1. frequency list
    #   2. additional important words
    #   3. Hunspell vocabulary
    #
    # The optimizer later assigns the available byte budget.
    # --------------------------------------------------------

    {
        cat "${frequency_candidates}"
        cat "${additional_ranked}"
        cat "${hunspell_candidates}"
    } |
        LC_ALL=C awk '!seen[$0]++' \
        > "${output}"

    local valid_count
    valid_count="$(wc -l < "${output}")"

    echo "Valid candidates: ${valid_count}"
    echo "Frequency candidates: $(wc -l < "${frequency_candidates}")"
    echo "Additional candidates: $(wc -l < "${additional_ranked}")"
}

build_candidates \
    "es-AR" \
    "${ES_DIR}/frequency.normalized" \
    "${ES_DIR}/hunspell.words" \
    "${ES_DIR}/additional.txt" \
    "${ES_DIR}/candidates.txt"

build_candidates \
    "en-en" \
    "${EN_DIR}/frequency.normalized" \
    "${EN_DIR}/hunspell.words" \
    "${EN_DIR}/additional.txt" \
    "${EN_DIR}/candidates.txt"

build_candidates \
    "de-de" \
    "${DE_DIR}/frequency.normalized" \
    "${DE_DIR}/hunspell.words" \
    "${DE_DIR}/additional.txt" \
    "${DE_DIR}/candidates.txt"

# ============================================================
# Vocabulary optimizer
# ============================================================
#
# We optimize by BYTES rather than an arbitrary word count.
#
# The candidate list is already frequency ordered.
#
# We therefore take the most frequent words that fit in the
# language budget.
#
# Additional words are promoted immediately after the frequency
# list and before the enormous Hunspell fallback vocabulary.
#
# This is the key correction versus the previous implementation.
#
# ============================================================

optimize_vocabulary() {
    local language="$1"
    local candidates="$2"
    local frequency="$3"
    local additional="$4"
    local budget="$5"
    local output="$6"

    echo ""
    echo "============================================================"
    echo " Optimizing vocabulary: ${language}"
    echo " Budget: ${budget} bytes"
    echo "============================================================"

    python3 - \
        "${candidates}" \
        "${frequency}" \
        "${additional}" \
        "${budget}" \
        "${output}" <<'PY'
import sys

candidates_file = sys.argv[1]
frequency_file = sys.argv[2]
additional_file = sys.argv[3]
budget = int(sys.argv[4])
output_file = sys.argv[5]

# ------------------------------------------------------------
# Read frequency rank.
# ------------------------------------------------------------

frequency_rank = {}

with open(frequency_file, encoding="utf-8", errors="ignore") as f:
    rank = 0

    for raw in f:
        parts = raw.strip().split()

        if not parts:
            continue

        word = parts[0].lower()

        if word not in frequency_rank:
            frequency_rank[word] = rank

        rank += 1

# ------------------------------------------------------------
# Additional words receive a very high priority.
#
# This does NOT mean they are forced into the dictionary.
# They simply get preference over low-frequency fallback words.
# ------------------------------------------------------------

additional_set = set()

with open(additional_file, encoding="utf-8", errors="ignore") as f:
    for raw in f:
        word = raw.strip().lower()

        if word:
            additional_set.add(word)

# ------------------------------------------------------------
# Candidate list.
# ------------------------------------------------------------

words = []

with open(candidates_file, encoding="utf-8", errors="ignore") as f:
    for raw in f:
        word = raw.strip().lower()

        if not word:
            continue

        if word in words:
            continue

        words.append(word)

# ------------------------------------------------------------
# Ranking:
#
# Frequency corpus first.
#
# Additional words get an artificial rank before fallback
# Hunspell words, but after genuine corpus frequency ordering.
#
# This is important:
#
# "entschuldigung" does not lose merely because it is long.
# Its corpus rank is respected.
# ------------------------------------------------------------

FALLBACK_BASE = len(frequency_rank) + 1000000

def sort_key(word):
    if word in frequency_rank:
        return (0, frequency_rank[word], len(word), word)

    if word in additional_set:
        return (1, 0, len(word), word)

    return (2, FALLBACK_BASE, len(word), word)

words.sort(key=sort_key)

# ------------------------------------------------------------
# Select by actual UTF-8 byte size.
#
# Dictionary format is:
#
#   #POCKETBOARD-DICT-1\n
#   word\n
#
# ------------------------------------------------------------

header = "#POCKETBOARD-DICT-1\n".encode("utf-8")

used = len(header)
selected = []

for word in words:
    encoded = (word + "\n").encode("utf-8")

    if used + len(encoded) > budget:
        continue

    selected.append(word)
    used += len(encoded)

with open(output_file, "w", encoding="utf-8") as out:
    out.write("#POCKETBOARD-DICT-1\n")

    for word in selected:
        out.write(word)
        out.write("\n")

print(f"Selected words: {len(selected)}")
print(f"Dictionary bytes: {used}")

long_words = sum(
    1 for word in selected
    if len(word) >= 10
)

print(f"Words >= 10 chars: {long_words}")

checks = [
    "mañana",
    "tenés",
    "podés",
    "hacés",
    "entschuldigung",
    "wahrscheinlich",
    "möglicherweise",
]

for word in checks:
    if word in selected:
        print(f"WORD INCLUDED: {word}")
    else:
        print(f"WORD NOT INCLUDED: {word}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: optimized vocabulary is empty: ${language}"
        exit 1
    fi
}

optimize_vocabulary \
    "es-AR" \
    "${ES_DIR}/candidates.txt" \
    "${ES_DIR}/frequency.normalized" \
    "${ES_DIR}/additional.txt" \
    "${ES_DICT_BUDGET}" \
    "${ES_DIR}/vocabulary.txt"

optimize_vocabulary \
    "en-en" \
    "${EN_DIR}/candidates.txt" \
    "${EN_DIR}/frequency.normalized" \
    "${EN_DIR}/additional.txt" \
    "${EN_DICT_BUDGET}" \
    "${EN_DIR}/vocabulary.txt"

optimize_vocabulary \
    "de-de" \
    "${DE_DIR}/candidates.txt" \
    "${DE_DIR}/frequency.normalized" \
    "${DE_DIR}/additional.txt" \
    "${DE_DICT_BUDGET}" \
    "${DE_DIR}/vocabulary.txt"

# ============================================================
# Copy dictionary assets
# ============================================================

generate_dictionary() {
    local language="$1"
    local vocabulary="$2"
    local output="$3"

    cp "${vocabulary}" "${output}"

    echo ""
    echo "${language}:"
    echo "  Words: $(tail -n +2 "${output}" | wc -l)"
    echo "  Size:  $(du -h "${output}" | cut -f1)"
}

generate_dictionary \
    "es-AR" \
    "${ES_DIR}/vocabulary.txt" \
    "${OUTPUT_DIR}/es-AR.dict"

generate_dictionary \
    "en-en" \
    "${EN_DIR}/vocabulary.txt" \
    "${OUTPUT_DIR}/en-en.dict"

generate_dictionary \
    "de-de" \
    "${DE_DIR}/vocabulary.txt" \
    "${OUTPUT_DIR}/de-de.dict"

# ============================================================
# Generate SymSpell delete index
# ============================================================

generate_delete_index() {
    local language="$1"
    local dictionary="$2"
    local output="$3"

    local pairs
    local grouped

    pairs="${WORK_DIR}/${language}.deletes.pairs"
    grouped="${WORK_DIR}/${language}.deletes.grouped"

    rm -f \
        "${pairs}" \
        "${grouped}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Generating SymSpell index: ${language}"
    echo "============================================================"

    python3 - \
        "${dictionary}" \
        "${pairs}" \
        "${DELETE_MAX_DISTANCE}" \
        "${DELETE_MAX_WORD_LENGTH}" \
        "${MAX_CANDIDATES_PER_DELETE}" <<'PY'
import sys

dictionary = sys.argv[1]
output = sys.argv[2]
max_distance = int(sys.argv[3])
max_length = int(sys.argv[4])
max_candidates = int(sys.argv[5])

def deletes(word, distance):
    result = set()

    def walk(current, remaining):
        if remaining <= 0:
            return

        seen_local = set()

        for i in range(len(current)):
            candidate = current[:i] + current[i + 1:]

            if candidate in seen_local:
                continue

            seen_local.add(candidate)
            result.add(candidate)

            if remaining > 1:
                walk(candidate, remaining - 1)

    walk(word, distance)
    return result

words = []

with open(dictionary, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip()

        if not word or word.startswith("#"):
            continue

        words.append(word)

candidates = {}

for word in words:
    if len(word) > max_length:
        continue

    distance = 1 if len(word) <= 4 else max_distance

    for delete in deletes(word, distance):
        bucket = candidates.setdefault(delete, [])

        if len(bucket) >= max_candidates:
            continue

        if word not in bucket:
            bucket.append(word)

with open(output, "w", encoding="utf-8") as out:
    for delete in sorted(candidates):
        for word in candidates[delete]:
            out.write(delete)
            out.write("\t")
            out.write(word)
            out.write("\n")

print(f"Delete keys: {len(candidates)}")
print(f"Mappings: {sum(len(v) for v in candidates.values())}")
PY

    if [[ ! -s "${pairs}" ]]; then
        echo "ERROR: empty delete index: ${language}"
        exit 1
    fi

    LC_ALL=C sort \
        -t $'\t' \
        -k1,1 \
        -k2,2 \
        "${pairs}" |
        LC_ALL=C uniq \
        > "${grouped}"

    {
        echo "#POCKETBOARD-DELETES-1"
        echo "#MAX_DISTANCE=${DELETE_MAX_DISTANCE}"
        echo "#MAX_WORD_LENGTH=${DELETE_MAX_WORD_LENGTH}"
        echo "#MAX_CANDIDATES=${MAX_CANDIDATES_PER_DELETE}"
        cat "${grouped}"
    } > "${output}"

    local mapping_count
    mapping_count="$(tail -n +5 "${output}" | wc -l)"

    echo ""
    echo "${language}:"
    echo "  Delete mappings: ${mapping_count}"
    echo "  Size: $(du -h "${output}" | cut -f1)"
}

generate_delete_index \
    "es-AR" \
    "${OUTPUT_DIR}/es-AR.dict" \
    "${OUTPUT_DIR}/es-AR.deletes"

generate_delete_index \
    "en-en" \
    "${OUTPUT_DIR}/en-en.dict" \
    "${OUTPUT_DIR}/en-en.deletes"

generate_delete_index \
    "de-de" \
    "${OUTPUT_DIR}/de-de.dict" \
    "${OUTPUT_DIR}/de-de.deletes"

# ============================================================
# Minimal metadata
# ============================================================

generate_metadata() {
    local language="$1"
    local aff="$2"
    local output="$3"

    {
        echo "#POCKETBOARD-META-2"

        grep -E '^TRY([[:space:]]|$)' \
            "${aff}" || true

        grep -E '^REP([[:space:]]|$)' \
            "${aff}" || true

        grep -E '^KEY([[:space:]]|$)' \
            "${aff}" || true

    } > "${output}"

    echo "${language} metadata: $(du -h "${output}" | cut -f1)"
}

generate_metadata \
    "es-AR" \
    "${ES_DIR}/index.aff" \
    "${OUTPUT_DIR}/es-AR.meta"

generate_metadata \
    "en-en" \
    "${EN_DIR}/index.aff" \
    "${OUTPUT_DIR}/en-en.meta"

generate_metadata \
    "de-de" \
    "${DE_DIR}/index.aff" \
    "${OUTPUT_DIR}/de-de.meta"

# ============================================================
# Sanity checks
# ============================================================

check_word() {
    local language="$1"
    local word="$2"
    local dictionary="${OUTPUT_DIR}/${language}.dict"

    if grep \
        -Fqx \
        "${word}" \
        <(tail -n +2 "${dictionary}")
    then
        echo "OK: ${language}: ${word}"
    else
        echo "WARNING: required word missing"
        echo "  Language: ${language}"
        echo "  Word: ${word}"
    fi
}

echo ""
echo "============================================================"
echo " Sanity checks"
echo "============================================================"

check_word "es-AR" "mañana"
check_word "es-AR" "vos"
check_word "es-AR" "tenés"
check_word "es-AR" "podés"
check_word "es-AR" "hacés"
check_word "es-AR" "acá"

check_word "en-en" "the"
check_word "en-en" "have"
check_word "en-en" "hello"

check_word "de-de" "ich"
check_word "de-de" "nicht"
check_word "de-de" "morgen"
check_word "de-de" "entschuldigung"
check_word "de-de" "wahrscheinlich"
check_word "de-de" "möglicherweise"

# ============================================================
# Final size report
# ============================================================

echo ""
echo "============================================================"
echo " PocketBoard dictionary build summary"
echo "============================================================"

TOTAL_DICT_BYTES=0
TOTAL_ASSET_BYTES=0

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do
    dictionary="${OUTPUT_DIR}/${language}.dict"
    deletes="${OUTPUT_DIR}/${language}.deletes"
    metadata="${OUTPUT_DIR}/${language}.meta"

    dictionary_size="$(wc -c < "${dictionary}")"
    delete_size="$(wc -c < "${deletes}")"
    metadata_size="$(wc -c < "${metadata}")"

    total_size=$((dictionary_size + delete_size + metadata_size))

    TOTAL_DICT_BYTES=$((TOTAL_DICT_BYTES + dictionary_size))
    TOTAL_ASSET_BYTES=$((TOTAL_ASSET_BYTES + total_size))

    echo ""
    echo "${language}"
    echo "  Words:          $(tail -n +2 "${dictionary}" | wc -l)"
    echo "  Dictionary:     ${dictionary_size} bytes"
    echo "  Delete index:   ${delete_size} bytes"
    echo "  Metadata:       ${metadata_size} bytes"
    echo "  Total:          ${total_size} bytes"
done

echo ""
echo "------------------------------------------------------------"
echo " RUNTIME DICTIONARY BUDGET"
echo "------------------------------------------------------------"

echo "Dictionary bytes: ${TOTAL_DICT_BYTES}"
echo "Budget:           ${GLOBAL_DICT_BUDGET}"

if (( TOTAL_DICT_BYTES > GLOBAL_DICT_BUDGET )); then
    echo ""
    echo "ERROR: runtime dictionaries exceed global budget."
    echo "  Total:  ${TOTAL_DICT_BYTES}"
    echo "  Budget: ${GLOBAL_DICT_BUDGET}"
    exit 1
fi

echo "Status: OK"

echo ""
echo "------------------------------------------------------------"
echo " TOTAL GENERATED ASSETS"
echo "------------------------------------------------------------"

echo "Total including SymSpell indexes:"
echo "  ${TOTAL_ASSET_BYTES} bytes"

echo ""
echo "============================================================"
echo " PocketBoard dictionaries generated successfully"
echo "============================================================"
