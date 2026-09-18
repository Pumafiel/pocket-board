#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C
export LANG=C

# ============================================================
# PocketBoard dictionary generator V2
# ============================================================
#
# Build-time only.
#
# Frequency sources:
#
#   ES-AR:
#       FrequencyWords 2018 / OpenSubtitles
#
#   EN:
#       FrequencyWords 2018 / OpenSubtitles
#
#   DE:
#       Leipzig Corpora Collection
#       deu_news_2025_1M
#
# Hunspell remains the validity filter.
#
# Strategy:
#
#   frequency corpus
#          ↓
#   normalize frequency
#          ↓
#   Hunspell vocabulary
#          ↓
#   frequency candidates
#          ↓
#   byte-aware vocabulary selection
#          ↓
#   PocketBoard dictionary
#          ↓
#   SymSpell deletes
#
# The optimizer does NOT run multiple builds.
# It evaluates candidates in one build and selects words based on:
#
#   frequency value
#   dictionary byte cost
#   incremental delete-index byte cost
#
# The runtime dictionary format is unchanged:
#
#   #POCKETBOARD-DICT-1
#
# The delete-index format is unchanged:
#
#   #POCKETBOARD-DELETES-1
#
# ============================================================

ROOT_DIR="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/.." &&
    pwd
)"

WORK_DIR="${ROOT_DIR}/build/pocketboard-dictionaries"
OUTPUT_DIR="${ROOT_DIR}/app/src/main/assets/dictionaries"

# ============================================================
# Frequency sources
# ============================================================

FREQUENCY_BASE="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"

ES_FREQUENCY_URL="${FREQUENCY_BASE}/es/es_full.txt"
EN_FREQUENCY_URL="${FREQUENCY_BASE}/en/en_full.txt"

#
# Leipzig frequency mirror.
#
# The repository contains:
#
#   wortschatz/de/deu_news_2025_1M/
#       deu_news_2025_1M-words.txt
#
# Format:
#
#   rank<TAB>word<TAB>frequency
#
LEIPZIG_BASE="https://huggingface.co/datasets/hadung1802/mlnorm-resources/resolve/main/wortschatz/de/deu_news_2025_1M"

DE_FREQUENCY_URL="${LEIPZIG_BASE}/deu_news_2025_1M-words.txt"

# ============================================================
# Optimizer configuration
# ============================================================

#
# Maximum total size of the three runtime dictionary assets.
#
# This is intentionally generous enough for the first optimized
# generation. The build prints the exact result.
#
MAX_TOTAL_DICT_BYTES=$((4 * 1024 * 1024))

#
# Soft per-language budgets.
#
# The optimizer may stop earlier because of frequency/cost.
#
MAX_ES_BYTES=$((700 * 1024))
MAX_EN_BYTES=$((850 * 1024))
MAX_DE_BYTES=$((850 * 1024))

#
# Maximum number of candidates examined.
#
# Full FrequencyWords files can contain many low-frequency tokens.
# We don't need to hold millions of useless candidates in memory.
#
MAX_FREQUENCY_CANDIDATES=500000

#
# SymSpell configuration.
#
DELETE_MAX_DISTANCE=2
DELETE_MAX_WORD_LENGTH=24
MAX_CANDIDATES_PER_DELETE=8

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
        --max-time 600 \
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
# Download Hunspell sources
# ============================================================

echo ""
echo "============================================================"
echo " Downloading Hunspell dictionaries"
echo "============================================================"

download \
    "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/es-AR/index.dic" \
    "${ES_DIR}/index.dic"

download \
    "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/es-AR/index.aff" \
    "${ES_DIR}/index.aff"

download \
    "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/en/index.dic" \
    "${EN_DIR}/index.dic"

download \
    "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/en/index.aff" \
    "${EN_DIR}/index.aff"

download \
    "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/de/index.dic" \
    "${DE_DIR}/index.dic"

download \
    "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/de/index.aff" \
    "${DE_DIR}/index.aff"

# ============================================================
# Download frequency sources
# ============================================================

echo ""
echo "============================================================"
echo " Downloading frequency sources"
echo "============================================================"

download \
    "${ES_FREQUENCY_URL}" \
    "${ES_DIR}/frequency.txt"

download \
    "${EN_FREQUENCY_URL}" \
    "${EN_DIR}/frequency.txt"

download \
    "${DE_FREQUENCY_URL}" \
    "${DE_DIR}/frequency.txt"

# ============================================================
# Normalize FrequencyWords
# ============================================================

normalize_frequencywords() {
    local input="$1"
    local output="$2"

    python3 - \
        "${input}" \
        "${output}" \
        "${MAX_FREQUENCY_CANDIDATES}" <<'PY'
import sys

input_file = sys.argv[1]
output_file = sys.argv[2]
limit = int(sys.argv[3])

seen = set()
rows = []

with open(input_file, encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.strip()

        if not line:
            continue

        parts = line.split()

        if len(parts) < 2:
            continue

        word = parts[0].strip().lower()

        try:
            frequency = int(parts[1])
        except ValueError:
            continue

        if not word or frequency <= 0:
            continue

        if word in seen:
            continue

        seen.add(word)
        rows.append((frequency, word))

        if len(rows) >= limit:
            break

rows.sort(key=lambda item: (-item[0], item[1]))

with open(output_file, "w", encoding="utf-8") as out:
    for frequency, word in rows:
        out.write(f"{frequency}\t{word}\n")

print(f"Normalized frequency entries: {len(rows)}")
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

normalize_leipzig() {
    local input="$1"
    local output="$2"

    python3 - \
        "${input}" \
        "${output}" \
        "${MAX_FREQUENCY_CANDIDATES}" <<'PY'
import sys

input_file = sys.argv[1]
output_file = sys.argv[2]
limit = int(sys.argv[3])

rows = []
seen = set()

with open(input_file, encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.strip()

        if not line:
            continue

        parts = line.split("\t")

        if len(parts) < 3:
            continue

        try:
            rank = int(parts[0])
            word = parts[1].strip().lower()
            frequency = int(parts[2])
        except ValueError:
            continue

        if not word or frequency <= 0:
            continue

        if word in seen:
            continue

        seen.add(word)
        rows.append((frequency, rank, word))

        if len(rows) >= limit:
            break

rows.sort(key=lambda item: (-item[0], item[1], item[2]))

with open(output_file, "w", encoding="utf-8") as out:
    for frequency, rank, word in rows:
        out.write(f"{frequency}\t{word}\n")

print(f"Normalized Leipzig entries: {len(rows)}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: normalized Leipzig frequency list is empty:"
        echo "  ${input}"
        exit 1
    fi
}

normalize_frequencywords \
    "${ES_DIR}/frequency.txt" \
    "${ES_DIR}/frequency.normalized"

normalize_frequencywords \
    "${EN_DIR}/frequency.txt" \
    "${EN_DIR}/frequency.normalized"

normalize_leipzig \
    "${DE_DIR}/frequency.txt" \
    "${DE_DIR}/frequency.normalized"

# ============================================================
# Extract Hunspell base words
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
# PocketBoard whitelist
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
pibe
piba
quilombo
boludo
boluda
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
we're
we've
theyre
they've
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
EOF

# ============================================================
# Build valid frequency candidates
# ============================================================

build_candidates() {
    local language="$1"
    local frequency="$2"
    local hunspell="$3"
    local whitelist="$4"
    local output="$5"

    echo ""
    echo "============================================================"
    echo " Building candidates: ${language}"
    echo "============================================================"

    python3 - \
        "${frequency}" \
        "${hunspell}" \
        "${whitelist}" \
        "${output}" <<'PY'
import sys

frequency_file = sys.argv[1]
hunspell_file = sys.argv[2]
whitelist_file = sys.argv[3]
output_file = sys.argv[4]

valid = set()

with open(hunspell_file, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip().lower()
        if word:
            valid.add(word)

whitelist = set()

with open(whitelist_file, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip().lower()
        if word:
            whitelist.add(word)

candidates = {}

with open(frequency_file, encoding="utf-8") as f:
    for raw in f:
        parts = raw.rstrip("\n").split("\t", 1)

        if len(parts) != 2:
            continue

        try:
            frequency = int(parts[0])
        except ValueError:
            continue

        word = parts[1].strip().lower()

        if not word:
            continue

        if word not in valid:
            continue

        old = candidates.get(word)

        if old is None or frequency > old:
            candidates[word] = frequency

#
# Whitelist entries are mandatory. If a whitelist word has no
# frequency entry, give it a tiny fallback score.
#
for word in whitelist:
    if word in valid and word not in candidates:
        candidates[word] = 1

rows = [
    (frequency, word)
    for word, frequency in candidates.items()
]

rows.sort(
    key=lambda item: (-item[0], item[1])
)

with open(output_file, "w", encoding="utf-8") as out:
    for frequency, word in rows:
        out.write(f"{frequency}\t{word}\n")

print(f"Valid candidates: {len(rows)}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: no valid frequency candidates:"
        echo "  ${language}"
        exit 1
    fi
}

build_candidates \
    "es-AR" \
    "${ES_DIR}/frequency.normalized" \
    "${ES_DIR}/hunspell.words" \
    "${ES_DIR}/whitelist.txt" \
    "${ES_DIR}/candidates.txt"

build_candidates \
    "en-en" \
    "${EN_DIR}/frequency.normalized" \
    "${EN_DIR}/hunspell.words" \
    "${EN_DIR}/whitelist.txt" \
    "${EN_DIR}/candidates.txt"

build_candidates \
    "de-de" \
    "${DE_DIR}/frequency.normalized" \
    "${DE_DIR}/hunspell.words" \
    "${DE_DIR}/whitelist.txt" \
    "${DE_DIR}/candidates.txt"

# ============================================================
# SymSpell delete generation
# ============================================================

generate_deletes_for_word() {
    local word="$1"
    local distance="$2"

    python3 - "${word}" "${distance}" <<'PY'
import sys

word = sys.argv[1]
distance = int(sys.argv[2])

result = set()

def walk(current, remaining):
    if remaining <= 0:
        return

    for i in range(len(current)):
        candidate = current[:i] + current[i + 1:]

        if candidate in result:
            continue

        result.add(candidate)

        if remaining > 1:
            walk(candidate, remaining - 1)

walk(word, distance)

for item in sorted(result):
    print(item)
PY
}

# ============================================================
# Byte-aware vocabulary optimizer
# ============================================================
#
# This is intentionally conservative:
#
#   - candidates remain frequency ordered
#   - a candidate is preferred when its frequency is high
#   - delete costs are calculated incrementally
#   - whitelist words are always retained
#
# The result is generated ONCE.
#
# ============================================================

optimize_vocabulary() {
    local language="$1"
    local candidates="$2"
    local whitelist="$3"
    local output="$4"
    local budget="$5"

    echo ""
    echo "============================================================"
    echo " Optimizing vocabulary: ${language}"
    echo " Budget: ${budget} bytes"
    echo "============================================================"

    python3 - \
        "${candidates}" \
        "${whitelist}" \
        "${output}" \
        "${budget}" \
        "${DELETE_MAX_DISTANCE}" \
        "${DELETE_MAX_WORD_LENGTH}" \
        "${MAX_CANDIDATES_PER_DELETE}" <<'PY'
import sys
from collections import defaultdict

candidate_file = sys.argv[1]
whitelist_file = sys.argv[2]
output_file = sys.argv[3]
budget = int(sys.argv[4])
max_distance = int(sys.argv[5])
max_word_length = int(sys.argv[6])
max_candidates = int(sys.argv[7])

#
# UTF-8 byte cost in the actual .dict.
#
HEADER = "#POCKETBOARD-DICT-1\n"

def word_cost(word):
    return len(word.encode("utf-8")) + 1

def deletes(word, distance):
    result = set()

    def walk(current, remaining):
        if remaining <= 0:
            return

        for i in range(len(current)):
            candidate = current[:i] + current[i + 1:]

            if candidate in result:
                continue

            result.add(candidate)

            if remaining > 1:
                walk(candidate, remaining - 1)

    walk(word, distance)
    return result

#
# Read whitelist.
#
mandatory = set()

with open(whitelist_file, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip().lower()
        if word:
            mandatory.add(word)

#
# Read candidates.
#
rows = []

with open(candidate_file, encoding="utf-8") as f:
    for raw in f:
        parts = raw.rstrip("\n").split("\t", 1)

        if len(parts) != 2:
            continue

        try:
            frequency = int(parts[0])
        except ValueError:
            continue

        word = parts[1].strip().lower()

        if not word:
            continue

        rows.append((frequency, word))

#
# Candidates are already frequency ordered.
#
rows.sort(key=lambda item: (-item[0], item[1]))

selected = []
selected_set = set()

delete_buckets = defaultdict(list)
delete_bytes = 0
dictionary_bytes = len(HEADER.encode("utf-8"))

#
# A candidate's usefulness is primarily frequency.
#
# The cost is:
#
#   word bytes
#   +
#   newly required delete mappings
#
# Existing deletes are effectively free.
#
def candidate_cost(word):
    if len(word) > max_word_length:
        return word_cost(word), 0, []

    distance = 1 if len(word) <= 4 else max_distance

    new_mappings = []

    for delete in deletes(word, distance):
        bucket = delete_buckets.get(delete)

        if bucket is None:
            new_mappings.append((delete, word))
            continue

        if word in bucket:
            continue

        #
        # Existing bucket has room.
        #
        if len(bucket) < max_candidates:
            new_mappings.append((delete, word))

    incremental_delete_bytes = 0

    for delete, candidate in new_mappings:
        incremental_delete_bytes += (
            len(delete.encode("utf-8"))
            + 1
            + len(candidate.encode("utf-8"))
            + 1
        )

    return (
        word_cost(word),
        incremental_delete_bytes,
        new_mappings,
    )

#
# Mandatory words first.
#
mandatory_rows = [
    (frequency, word)
    for frequency, word in rows
    if word in mandatory
]

mandatory_rows.sort(key=lambda item: (-item[0], item[1]))

for frequency, word in mandatory_rows:
    if word in selected_set:
        continue

    dictionary_cost, delete_cost, mappings = candidate_cost(word)

    if dictionary_bytes + dictionary_cost + delete_cost > budget:
        print(
            f"WARNING: mandatory word exceeds budget: {word}",
            file=sys.stderr,
        )
        continue

    selected.append((frequency, word))
    selected_set.add(word)
    dictionary_bytes += dictionary_cost
    delete_bytes += delete_cost

    for delete, candidate in mappings:
        bucket = delete_buckets[delete]

        if len(bucket) < max_candidates and candidate not in bucket:
            bucket.append(candidate)

#
# Frequency candidates.
#
# We score candidates by frequency / incremental byte cost.
#
remaining = []

for frequency, word in rows:
    if word in selected_set:
        continue

    dictionary_cost, delete_cost, mappings = candidate_cost(word)

    total_cost = dictionary_cost + delete_cost

    if total_cost <= 0:
        continue

    score = frequency / total_cost

    remaining.append(
        (
            score,
            frequency,
            word,
            dictionary_cost,
            delete_cost,
            mappings,
        )
    )

#
# A pure frequency/cost sort is better than an arbitrary 50k cut.
#
remaining.sort(
    key=lambda item: (
        -item[0],
        -item[1],
        item[2],
    )
)

for (
    score,
    frequency,
    word,
    dictionary_cost,
    delete_cost,
    mappings,
) in remaining:

    if word in selected_set:
        continue

    total_cost = dictionary_cost + delete_cost

    if dictionary_bytes + total_cost > budget:
        continue

    selected.append((frequency, word))
    selected_set.add(word)

    dictionary_bytes += dictionary_cost
    delete_bytes += delete_cost

    for delete, candidate in mappings:
        bucket = delete_buckets[delete]

        if len(bucket) < max_candidates and candidate not in bucket:
            bucket.append(candidate)

#
# Runtime dictionary MUST remain frequency ordered.
#
selected.sort(
    key=lambda item: (-item[0], item[1])
)

with open(output_file, "w", encoding="utf-8") as out:
    for frequency, word in selected:
        out.write(word + "\n")

print(f"Selected words: {len(selected)}")
print(f"Dictionary bytes: {dictionary_bytes}")
print(f"Estimated delete bytes: {delete_bytes}")
print(f"Estimated total: {dictionary_bytes + delete_bytes}")

#
# Useful diagnostics.
#
long_words = [
    word
    for _, word in selected
    if len(word) >= 10
]

print(f"Words >= 10 chars: {len(long_words)}")

interesting = [
    word
    for word in long_words
    if word in {
        "entschuldigung",
        "wahrscheinlich",
        "möglicherweise",
        "morgen",
        "mañana",
    }
]

for word in interesting:
    print(f"Long-word check: {word}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: optimizer produced empty vocabulary: ${language}"
        exit 1
    fi
}

optimize_vocabulary \
    "es-AR" \
    "${ES_DIR}/candidates.txt" \
    "${ES_DIR}/whitelist.txt" \
    "${ES_DIR}/vocabulary.txt" \
    "${MAX_ES_BYTES}"

optimize_vocabulary \
    "en-en" \
    "${EN_DIR}/candidates.txt" \
    "${EN_DIR}/whitelist.txt" \
    "${EN_DIR}/vocabulary.txt" \
    "${MAX_EN_BYTES}"

optimize_vocabulary \
    "de-de" \
    "${DE_DIR}/candidates.txt" \
    "${DE_DIR}/whitelist.txt" \
    "${DE_DIR}/vocabulary.txt" \
    "${MAX_DE_BYTES}"

# ============================================================
# Generate dictionary assets
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

        for i in range(len(current)):
            candidate = current[:i] + current[i + 1:]

            if candidate in result:
                continue

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

        if word in bucket:
            continue

        if len(bucket) >= max_candidates:
            continue

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

    LC_ALL=C sort -t $'\t' -k1,1 -k2,2 \
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

    echo ""
    echo "${language}:"
    echo "  Delete mappings: $(tail -n +5 "${output}" | wc -l)"
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

    if ! grep \
        -Fqx \
        "${word}" \
        <(tail -n +2 "${dictionary}")
    then
        echo "WARNING: required word missing"
        echo "  Language: ${language}"
        echo "  Word: ${word}"
        return 0
    fi

    echo "OK: ${language}: ${word}"
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

    dict_bytes="$(wc -c < "${dictionary}")"
    delete_bytes="$(wc -c < "${deletes}")"
    metadata_bytes="$(wc -c < "${metadata}")"

    language_total=$(
        python3 - \
            "${dict_bytes}" \
            "${delete_bytes}" \
            "${metadata_bytes}" <<'PY'
import sys

print(
    int(sys.argv[1])
    + int(sys.argv[2])
    + int(sys.argv[3])
)
PY
    )

    TOTAL_BYTES=$((TOTAL_BYTES + language_total))

    echo ""
    echo "${language}"
    echo "  Words:          $(tail -n +2 "${dictionary}" | wc -l)"
    echo "  Dictionary:     ${dict_bytes} bytes"
    echo "  Delete index:   ${delete_bytes} bytes"
    echo "  Metadata:       ${metadata_bytes} bytes"
    echo "  Total:          ${language_total} bytes"
done

echo ""
echo "------------------------------------------------------------"
echo " TOTAL GENERATED DICTIONARY ASSETS"
echo "------------------------------------------------------------"

echo "Total: ${TOTAL_BYTES} bytes"
echo "Budget: ${MAX_TOTAL_DICT_BYTES} bytes"

if (( TOTAL_BYTES > MAX_TOTAL_DICT_BYTES )); then
    echo ""
    echo "ERROR: generated dictionary assets exceed global budget."
    echo "  Total:  ${TOTAL_BYTES}"
    echo "  Budget: ${MAX_TOTAL_DICT_BYTES}"
    exit 1
fi

echo ""
echo "============================================================"
echo " PocketBoard dictionaries generated successfully"
echo "============================================================"
