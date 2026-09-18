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
# Sources:
#   wooorm/dictionaries
#   FrequencyWords 2018 / OpenSubtitles
#   Leipzig Corpora - German
#
# Strategy:
#
#   Frequency sources
#       ↓
#   normalized ranked vocabulary
#       ↓
#   PocketBoard candidate vocabulary
#       ↓
#   budget-aware vocabulary selection
#       ↓
#   compact runtime dictionary
#       ↓
#   SymSpell symmetric deletes
#
# IMPORTANT:
#
# Frequency is the primary signal.
# Hunspell is NOT used as a hard filter.
#
# The whitelist is NOT mandatory.
# It only adds additional candidates when they are useful.
#
# The optimizer decides what actually fits in the budget.
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

# Leipzig German 1M news corpus.
LEIPZIG_URL="https://downloads.wortschatz-leipzig.de/corpora/deu_news_2025_1M.tar.gz"

# ============================================================
# Global limits
# ============================================================

SOURCE_MAX_WORDS=500000

# Global generated-assets budget.
GLOBAL_BUDGET=4194304

# Approximate language allocation.
ES_BUDGET=1258291
EN_BUDGET=1468006
DE_BUDGET=1468007

# ============================================================
# SymSpell configuration
# ============================================================

DELETE_MAX_DISTANCE=2

# Do not generate deletes for extremely long words.
DELETE_MAX_WORD_LENGTH=24

# Distance 2 is expensive.
# Long words only get distance 1.
DISTANCE_2_MAX_WORD_LENGTH=10

# Keep only the most frequent candidates for a delete key.
MAX_CANDIDATES_PER_DELETE=4

# ============================================================
# Directories
# ============================================================

ES_DIR="${WORK_DIR}/es-AR"
EN_DIR="${WORK_DIR}/en-en"
DE_DIR="${WORK_DIR}/de-de"

mkdir -p "${WORK_DIR}"
mkdir -p "${ES_DIR}"
mkdir -p "${EN_DIR}"
mkdir -p "${DE_DIR}"
mkdir -p "${OUTPUT_DIR}"

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
require_command tar
require_command python3

# ============================================================
# Download
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

download \
    "${LEIPZIG_URL}" \
    "${DE_DIR}/leipzig.tar.gz"

# ============================================================
# Extract Leipzig German frequency list
# ============================================================

echo ""
echo "============================================================"
echo " Extracting Leipzig German frequency list"
echo "============================================================"

LEIPZIG_EXTRACT_DIR="${DE_DIR}/leipzig"

rm -rf "${LEIPZIG_EXTRACT_DIR}"
mkdir -p "${LEIPZIG_EXTRACT_DIR}"

tar \
    -xzf "${DE_DIR}/leipzig.tar.gz" \
    -C "${LEIPZIG_EXTRACT_DIR}"

#
# The Leipzig archive contains several files.
# Find the *_words.txt file instead of assuming a complete
# hard-coded path.
#

LEIPZIG_WORD_FILE="$(
    find "${LEIPZIG_EXTRACT_DIR}" \
        -type f \
        \( \
            -name "*-words.txt" \
            -o \
            -name "*_words.txt" \
        \) \
        -print \
        | head -n 1
)"

if [[ -z "${LEIPZIG_WORD_FILE}" ]]; then

    echo ""
    echo "ERROR: Leipzig word-frequency file not found."

    echo ""
    echo "Archive contents:"

    find "${LEIPZIG_EXTRACT_DIR}" \
        -type f \
        -print

    exit 1
fi

echo ""
echo "Leipzig frequency file:"
echo "  ${LEIPZIG_WORD_FILE}"

cp \
    "${LEIPZIG_WORD_FILE}" \
    "${DE_DIR}/frequency.txt"

# ============================================================
# Normalize frequency lists
# ============================================================

normalize_frequency() {
    local input="$1"
    local output="$2"

    python3 - \
        "${input}" \
        "${output}" \
        "${SOURCE_MAX_WORDS}" <<'PY'
import sys
import re

input_file = sys.argv[1]
output_file = sys.argv[2]
max_words = int(sys.argv[3])

seen = set()
entries = []

#
# Unicode letters plus apostrophe/hyphenated forms.
#
valid_word = re.compile(
    r"^[^\W\d_]+(?:['’\-][^\W\d_]+)*$",
    re.UNICODE
)


def parse_frequency_word(raw):

    parts = raw.strip().split()

    if not parts:
        return None

    word = parts[0].strip().lower()

    if not word:
        return None

    word = word.lstrip("\ufeff")

    if not word:
        return None

    if not valid_word.match(word):
        return None

    if len(word) > 32:
        return None

    return word


with open(
    input_file,
    encoding="utf-8",
    errors="ignore"
) as source:

    for raw in source:

        word = parse_frequency_word(raw)

        if word is None:
            continue

        if word in seen:
            continue

        seen.add(word)
        entries.append(word)

        if len(entries) >= max_words:
            break


with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

    for rank, word in enumerate(
        entries,
        start=1
    ):
        out.write(
            f"{rank}\t{word}\n"
        )


print(
    f"Normalized frequency entries: {len(entries)}"
)
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: normalized frequency list is empty:"
        echo "  ${input}"
        exit 1
    fi
}

normalize_frequency \
    "${ES_DIR}/frequency.txt" \
    "${ES_DIR}/frequency.normalized"

normalize_frequency \
    "${EN_DIR}/frequency.txt" \
    "${EN_DIR}/frequency.normalized"

normalize_frequency \
    "${DE_DIR}/frequency.txt" \
    "${DE_DIR}/frequency.normalized"

# ============================================================
# PocketBoard additional vocabulary
# ============================================================
#
# These words are NOT mandatory.
#
# They are only additional candidates if they are not already
# present in the frequency source.
#
# ============================================================

cat > "${ES_DIR}/whitelist.txt" <<'EOF'
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
pibe
piba
remera
celu
EOF

cat > "${EN_DIR}/whitelist.txt" <<'EOF'
i
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
hello
thanks
thank
please
EOF

cat > "${DE_DIR}/whitelist.txt" <<'EOF'
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
bitte
danke
hallo
EOF

# ============================================================
# Build candidates
# ============================================================

build_candidates() {
    local language="$1"
    local frequency="$2"
    local whitelist="$3"
    local output="$4"

    echo ""
    echo "============================================================"
    echo " Building candidates: ${language}"
    echo "============================================================"

    python3 - \
        "${frequency}" \
        "${whitelist}" \
        "${output}" <<'PY'
import sys

frequency_file = sys.argv[1]
whitelist_file = sys.argv[2]
output_file = sys.argv[3]

rows = []
seen = set()

#
# Frequency source.
#
with open(
    frequency_file,
    encoding="utf-8"
) as f:

    for raw in f:

        parts = raw.rstrip("\n").split(
            "\t",
            1
        )

        if len(parts) != 2:
            continue

        try:
            rank = int(parts[0])
        except ValueError:
            continue

        word = parts[1].strip().lower()

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)

        rows.append(
            (
                rank,
                word
            )
        )


frequency_count = len(rows)

#
# Additional words receive ranks after the frequency list.
# They are therefore candidates, not guaranteed selections.
#
whitelist_count = 0

next_rank = (
    max(
        (
            rank
            for rank, _
            in rows
        ),
        default=0
    )
    + 1
)

with open(
    whitelist_file,
    encoding="utf-8"
) as f:

    for raw in f:

        word = raw.strip().lower()

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)

        rows.append(
            (
                next_rank,
                word
            )
        )

        whitelist_count += 1
        next_rank += 1


rows.sort(
    key=lambda item: (
        item[0],
        item[1]
    )
)


with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

    for rank, word in rows:

        out.write(
            f"{rank}\t{word}\n"
        )


print(
    f"Valid candidates: {len(rows)}"
)

print(
    f"Frequency candidates: {frequency_count}"
)

print(
    f"Additional candidates: {whitelist_count}"
)
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: candidate list is empty:"
        echo "  ${language}"
        exit 1
    fi
}

build_candidates \
    "es-AR" \
    "${ES_DIR}/frequency.normalized" \
    "${ES_DIR}/whitelist.txt" \
    "${ES_DIR}/candidates.txt"

build_candidates \
    "en-en" \
    "${EN_DIR}/frequency.normalized" \
    "${EN_DIR}/whitelist.txt" \
    "${EN_DIR}/candidates.txt"

build_candidates \
    "de-de" \
    "${DE_DIR}/frequency.normalized" \
    "${DE_DIR}/whitelist.txt" \
    "${DE_DIR}/candidates.txt"

# ============================================================
# Budget-aware vocabulary optimizer
# ============================================================

optimize_vocabulary() {
    local language="$1"
    local candidates="$2"
    local output="$3"
    local budget="$4"

    echo ""
    echo "============================================================"
    echo " Optimizing vocabulary: ${language}"
    echo " Budget: ${budget} bytes"
    echo "============================================================"

    python3 - \
        "${candidates}" \
        "${output}" \
        "${budget}" \
        "${DELETE_MAX_DISTANCE}" \
        "${DELETE_MAX_WORD_LENGTH}" \
        "${DISTANCE_2_MAX_WORD_LENGTH}" \
        "${MAX_CANDIDATES_PER_DELETE}" <<'PY'
import sys
from collections import defaultdict

candidate_file = sys.argv[1]
output_file = sys.argv[2]
budget = int(sys.argv[3])
max_distance = int(sys.argv[4])
max_word_length = int(sys.argv[5])
distance_2_max_length = int(sys.argv[6])
max_candidates = int(sys.argv[7])

HEADER = "#POCKETBOARD-DICT-1\n"


def utf8_len(value):
    return len(value.encode("utf-8"))


def generate_deletes(word, distance):

    result = set()

    def walk(current, remaining):

        if remaining <= 0:
            return

        for i in range(len(current)):

            candidate = (
                current[:i]
                + current[i + 1:]
            )

            if candidate in result:
                continue

            result.add(candidate)

            if remaining > 1:

                walk(
                    candidate,
                    remaining - 1
                )

    walk(
        word,
        distance
    )

    return result


def distance_for_word(word):

    length = len(word)

    if length <= 4:
        return 1

    if length <= distance_2_max_length:
        return min(
            2,
            max_distance
        )

    return 1


rows = []

with open(
    candidate_file,
    encoding="utf-8"
) as f:

    for raw in f:

        parts = raw.rstrip("\n").split(
            "\t",
            1
        )

        if len(parts) != 2:
            continue

        try:
            rank = int(parts[0])
        except ValueError:
            continue

        word = parts[1].strip().lower()

        if not word:
            continue

        rows.append(
            (
                rank,
                word
            )
        )


rows.sort(
    key=lambda item: (
        item[0],
        item[1]
    )
)


selected = []
selected_set = set()

delete_buckets = defaultdict(list)

dictionary_bytes = len(
    HEADER.encode("utf-8")
)

delete_bytes = 0


for rank, word in rows:

    if word in selected_set:
        continue

    dictionary_cost = (
        utf8_len(word)
        + 1
    )

    mappings = []

    if len(word) <= max_word_length:

        distance = distance_for_word(
            word
        )

        for delete in generate_deletes(
            word,
            distance
        ):

            bucket = delete_buckets.get(
                delete,
                []
            )

            if word in bucket:
                continue

            if len(bucket) >= max_candidates:
                continue

            cost = (
                utf8_len(delete)
                + 1
                + utf8_len(word)
                + 1
            )

            mappings.append(
                (
                    delete,
                    word,
                    cost
                )
            )


    delete_cost = sum(
        cost
        for _, _, cost in mappings
    )

    total_cost = (
        dictionary_cost
        + delete_cost
    )

    #
    # Prefer a word without its deletes over dropping
    # a frequent word completely.
    #
    if (
        dictionary_bytes
        + total_cost
        > budget
    ):

        if (
            dictionary_bytes
            + dictionary_cost
            <= budget
        ):

            selected.append(
                (
                    rank,
                    word
                )
            )

            selected_set.add(word)

            dictionary_bytes += (
                dictionary_cost
            )

        continue


    selected.append(
        (
            rank,
            word
        )
    )

    selected_set.add(word)

    dictionary_bytes += (
        dictionary_cost
    )

    delete_bytes += (
        delete_cost
    )

    for delete, candidate, cost in mappings:

        bucket = delete_buckets[
            delete
        ]

        if (
            len(bucket)
            < max_candidates
            and candidate not in bucket
        ):

            bucket.append(
                candidate
            )


selected.sort(
    key=lambda item: (
        item[0],
        item[1]
    )
)


selected_words = {
    word
    for _, word in selected
}


with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

    for _, word in selected:

        out.write(
            word
            + "\n"
        )


print(
    f"Selected words: {len(selected)}"
)

print(
    f"Dictionary bytes: {dictionary_bytes}"
)

print(
    f"Estimated delete bytes: {delete_bytes}"
)

print(
    f"Estimated total: "
    f"{dictionary_bytes + delete_bytes}"
)

print(
    f"Words >= 10 chars: "
    f"{sum(1 for _, w in selected if len(w) >= 10)}"
)

for word in [
    "mañana",
    "tenés",
    "podés",
    "hacés",
    "entschuldigung",
    "wahrscheinlich",
    "möglicherweise"
]:

    if word in selected_words:

        print(
            f"WORD INCLUDED: {word}"
        )

    else:

        print(
            f"WORD NOT INCLUDED: {word}"
        )
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: optimizer produced empty vocabulary:"
        echo "  ${language}"
        exit 1
    fi
}

optimize_vocabulary \
    "es-AR" \
    "${ES_DIR}/candidates.txt" \
    "${ES_DIR}/vocabulary.txt" \
    "${ES_BUDGET}"

optimize_vocabulary \
    "en-en" \
    "${EN_DIR}/candidates.txt" \
    "${EN_DIR}/vocabulary.txt" \
    "${EN_BUDGET}"

optimize_vocabulary \
    "de-de" \
    "${DE_DIR}/candidates.txt" \
    "${DE_DIR}/vocabulary.txt" \
    "${DE_BUDGET}"

# ============================================================
# Generate runtime dictionaries
# ============================================================

generate_dictionary() {
    local language="$1"
    local vocabulary="$2"
    local output="$3"

    {
        echo "#POCKETBOARD-DICT-1"
        cat "${vocabulary}"
    } > "${output}"

    echo ""
    echo "${language}:"
    echo "  Words: $(wc -l < "${vocabulary}")"
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
# Generate compact SymSpell delete index
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
        "${DISTANCE_2_MAX_WORD_LENGTH}" \
        "${MAX_CANDIDATES_PER_DELETE}" <<'PY'
import sys

dictionary = sys.argv[1]
output = sys.argv[2]
max_distance = int(sys.argv[3])
max_length = int(sys.argv[4])
distance_2_max_length = int(sys.argv[5])
max_candidates = int(sys.argv[6])


def deletes(word, distance):

    result = set()

    def walk(current, remaining):

        if remaining <= 0:
            return

        for i in range(len(current)):

            candidate = (
                current[:i]
                + current[i + 1:]
            )

            if candidate in result:
                continue

            result.add(candidate)

            if remaining > 1:

                walk(
                    candidate,
                    remaining - 1
                )

    walk(
        word,
        distance
    )

    return result


def distance_for_word(word):

    if len(word) <= 4:
        return 1

    if len(word) <= distance_2_max_length:
        return min(
            2,
            max_distance
        )

    return 1


words = []

with open(
    dictionary,
    encoding="utf-8"
) as f:

    for raw in f:

        word = raw.strip()

        if not word:
            continue

        if word.startswith("#"):
            continue

        words.append(word)


candidates = {}


for word in words:

    if len(word) > max_length:
        continue

    distance = distance_for_word(
        word
    )

    for delete in deletes(
        word,
        distance
    ):

        bucket = candidates.setdefault(
            delete,
            []
        )

        if word in bucket:
            continue

        if len(bucket) >= max_candidates:
            continue

        bucket.append(word)


with open(
    output,
    "w",
    encoding="utf-8"
) as out:

    for delete in sorted(candidates):

        for word in candidates[delete]:

            out.write(
                delete
            )

            out.write("\t")

            out.write(word)

            out.write("\n")


print(
    f"Delete keys: {len(candidates)}"
)

print(
    f"Mappings: "
    f"{sum(len(v) for v in candidates.values())}"
)
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
        echo "#DISTANCE_2_MAX_WORD_LENGTH=${DISTANCE_2_MAX_WORD_LENGTH}"
        echo "#MAX_CANDIDATES=${MAX_CANDIDATES_PER_DELETE}"
        cat "${grouped}"
    } > "${output}"

    echo ""
    echo "${language}:"
    echo "  Delete mappings: $(tail -n +6 "${output}" | wc -l)"
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
# Metadata
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
        <(
            tail -n +2 "${dictionary}"
        )
    then
        echo "OK: ${language}: ${word}"
    else
        echo "WARNING: required/common word not selected"
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

# ============================================================
# Final size report
# ============================================================

echo ""
echo "============================================================"
echo " PocketBoard dictionary build summary"
echo "============================================================"

TOTAL_BYTES=0

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    dictionary="${OUTPUT_DIR}/${language}.dict"
    deletes="${OUTPUT_DIR}/${language}.deletes"
    metadata="${OUTPUT_DIR}/${language}.meta"

    dictionary_size="$(
        wc -c < "${dictionary}"
    )"

    delete_size="$(
        wc -c < "${deletes}"
    )"

    metadata_size="$(
        wc -c < "${metadata}"
    )"

    language_total=$(
        (
            dictionary_size
            + delete_size
            + metadata_size
        )
    )

    TOTAL_BYTES=$(
        (
            TOTAL_BYTES
            + language_total
        )
    )

    echo ""
    echo "${language}"
    echo "  Words:          $(tail -n +2 "${dictionary}" | wc -l)"
    echo "  Dictionary:     ${dictionary_size} bytes"
    echo "  Delete index:   ${delete_size} bytes"
    echo "  Metadata:       ${metadata_size} bytes"
    echo "  Total:          ${language_total} bytes"
done

echo ""
echo "------------------------------------------------------------"
echo " TOTAL GENERATED DICTIONARY ASSETS"
echo "------------------------------------------------------------"

echo "Total:  ${TOTAL_BYTES} bytes"
echo "Budget: ${GLOBAL_BUDGET} bytes"

if [[ "${TOTAL_BYTES}" -gt "${GLOBAL_BUDGET}" ]]; then

    echo ""
    echo "ERROR: generated dictionary assets exceed global budget."
    echo "  Total:  ${TOTAL_BYTES}"
    echo "  Budget: ${GLOBAL_BUDGET}"

    exit 1
fi

echo ""
echo "============================================================"
echo " PocketBoard dictionaries generated successfully"
echo "============================================================"
