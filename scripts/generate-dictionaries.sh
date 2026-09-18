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
#   en-en
#   de-de
#
# Sources:
#   wooorm/dictionaries       -> Hunspell validation
#   FrequencyWords 2018       -> Spanish / English frequency
#   Leipzig Wortschatz 2025   -> German frequency
#
# IMPORTANT:
#
# The runtime correction/suggestion engine already exists.
# This script ONLY prepares optimized runtime dictionaries.
#
# We deliberately separate:
#
#   1. vocabulary selection
#   2. correction/delete index selection
#
# A word can therefore be:
#
#   - included in the dictionary
#   - NOT included in the correction index
#
# This is intentional.
#
# Example:
#
#   entschuldigung
#
# may be an important German vocabulary word even though
# generating every possible SymSpell delete for it is expensive.
#
# The dictionary is optimized for real-world frequency.
# The delete index is optimized independently for usefulness.
#
# Runtime formats remain compatible:
#
#   #POCKETBOARD-DICT-1
#   #POCKETBOARD-META-1
#   #POCKETBOARD-DELETES-1
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
LEIPZIG_BASE="https://downloads.wortschatz-leipzig.de/corpora"

ES_DIR="${WORK_DIR}/es-AR"
EN_DIR="${WORK_DIR}/en-en"
DE_DIR="${WORK_DIR}/de-de"

mkdir -p "${WORK_DIR}"
mkdir -p "${ES_DIR}"
mkdir -p "${EN_DIR}"
mkdir -p "${DE_DIR}"
mkdir -p "${OUTPUT_DIR}"


# ============================================================
# Runtime dictionary budgets
# ============================================================
#
# These are BYTE budgets, not word-count limits.
#
# This is intentional:
#
#   frequent short words use little space
#   frequent long words use more space
#
# Therefore the selection is based on the actual resulting
# dictionary size rather than an arbitrary "50k words".
#
# Total target:
#
#   1.2 MB ES
#   1.4 MB EN
#   1.4 MB DE
#
# Total:
#
#   4.0 MB
#
# This leaves room in the APK for the rest of the application.
#
# ============================================================

ES_DICTIONARY_BUDGET=1228800
EN_DICTIONARY_BUDGET=1433600
DE_DICTIONARY_BUDGET=1433600

GLOBAL_DICTIONARY_BUDGET=4194304


# ============================================================
# Delete-index budgets
# ============================================================
#
# IMPORTANT:
#
# The old generator created tens/hundreds of MB because it
# generated distance-2 deletes for practically every word.
#
# The runtime already performs the error/suggestion logic.
#
# We therefore keep only the most useful correction mappings.
#
# These budgets are deliberately small.
#
# ============================================================

ES_DELETE_BUDGET=700000
EN_DELETE_BUDGET=700000
DE_DELETE_BUDGET=850000

GLOBAL_DELETE_BUDGET=2250000


# ============================================================
# Delete generation policy
# ============================================================
#
# The most frequent words receive the strongest correction
# coverage.
#
# TOP_DISTANCE2_WORDS:
#
#   top N frequent words may receive distance 2 deletes.
#
# After that:
#
#   distance 1 only.
#
# This prevents the combinatorial explosion caused by distance 2
# on the entire vocabulary.
#
# There is intentionally NO general "long words are bad" rule.
#
# Long frequent words can be indexed.
#
# ============================================================

TOP_DISTANCE2_WORDS=25000

MAX_DELETE_DISTANCE=2

MAX_DELETE_WORD_LENGTH=32

# Number of candidates retained for a single delete key.
#
# Candidates are processed in frequency order, therefore the
# first candidates are the most frequent words.
#
MAX_CANDIDATES_PER_DELETE=4


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
require_command grep
require_command sed
require_command tr
require_command head
require_command tail
require_command wc
require_command cut
require_command awk
require_command python3
require_command tar


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


# ------------------------------------------------------------
# Leipzig German
# ------------------------------------------------------------

LEIPZIG_ARCHIVE="${DE_DIR}/deu_news_2025_1M.tar.gz"
LEIPZIG_DIR="${DE_DIR}/leipzig"

download \
    "${LEIPZIG_BASE}/deu_news_2025_1M.tar.gz" \
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

LEIPZIG_WORDS_FILE="$(
    find "${LEIPZIG_DIR}" \
        -type f \
        -name '*-words.txt' \
        | head -n 1
)"

if [[ -z "${LEIPZIG_WORDS_FILE}" ]]; then
    echo "ERROR: Leipzig word-frequency file not found."
    find "${LEIPZIG_DIR}" -maxdepth 4 -type f -print
    exit 1
fi

echo ""
echo "Leipzig frequency file:"
echo "  ${LEIPZIG_WORDS_FILE}"


# ============================================================
# Normalize frequency lists
# ============================================================
#
# FrequencyWords format:
#
#   word count
#
# Leipzig format can contain:
#
#   rank word frequency
#
# We only need the word ordering.
#
# The output is:
#
#   one normalized word per line
#
# in frequency order.
#
# ============================================================

normalize_frequency_frequencywords() {
    local input="$1"
    local output="$2"

    echo ""
    echo "Normalizing frequency list:"
    echo "  ${input}"

    sed 's/\r$//' "${input}" |
        python3 -c '
import sys

seen = set()

for raw in sys.stdin:
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
    print(word)
' |
        head -n 500000 \
        > "${output}"

    local count
    count="$(wc -l < "${output}")"

    echo "Normalized frequency entries: ${count}"

    if [[ "${count}" -eq 0 ]]; then
        echo "ERROR: normalized frequency list is empty:"
        echo "  ${input}"
        exit 1
    fi
}


normalize_frequency_leipzig() {
    local input="$1"
    local output="$2"

    echo ""
    echo "Normalizing Leipzig frequency list:"
    echo "  ${input}"

    #
    # Leipzig word lists normally contain:
    #
    #   rank  word  frequency
    #
    # We deliberately detect the word column instead of assuming
    # a single fixed format.
    #

    python3 - \
        "${input}" \
        "${output}" <<'PY'
import sys

source = sys.argv[1]
destination = sys.argv[2]

seen = set()
entries = []

with open(source, encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.strip()

        if not line:
            continue

        parts = line.split()

        if len(parts) < 2:
            continue

        #
        # Leipzig word lists use numeric rank/frequency fields.
        # Find the first non-numeric token.
        #
        word = None

        for part in parts:
            token = part.strip()

            if not token:
                continue

            try:
                float(token.replace(",", "."))
                continue
            except ValueError:
                word = token
                break

        if word is None:
            continue

        word = word.lower()

        if word in seen:
            continue

        seen.add(word)
        entries.append(word)

        if len(entries) >= 500000:
            break

with open(destination, "w", encoding="utf-8") as out:
    for word in entries:
        out.write(word + "\n")

print(f"Normalized Leipzig entries: {len(entries)}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: normalized Leipzig frequency list is empty:"
        echo "  ${input}"
        exit 1
    fi
}


normalize_frequency_frequencywords \
    "${ES_DIR}/frequency.txt" \
    "${ES_DIR}/frequency.normalized"

normalize_frequency_frequencywords \
    "${EN_DIR}/frequency.txt" \
    "${EN_DIR}/frequency.normalized"

normalize_frequency_leipzig \
    "${LEIPZIG_WORDS_FILE}" \
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
# PocketBoard regional / common whitelist
# ============================================================
#
# These are NOT mandatory words.
#
# They are simply additional candidates.
#
# The final vocabulary is still controlled by the byte budget.
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
wahrscheinlich
möglicherweise
EOF


# ============================================================
# Build candidate vocabulary
# ============================================================
#
# The candidate file retains FREQUENCY ORDER.
#
# We do NOT impose a word-count limit.
#
# The final selector below works against a BYTE budget.
#
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

with open(hunspell_file, encoding="utf-8") as f:
    valid = {
        line.strip().lower()
        for line in f
        if line.strip()
    }

frequency_words = []

with open(frequency_file, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip().lower()

        if not word:
            continue

        if word not in valid:
            continue

        frequency_words.append(word)

seen = set()
result = []

for word in frequency_words:
    if word in seen:
        continue

    seen.add(word)
    result.append(word)

with open(whitelist_file, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip().lower()

        if not word:
            continue

        if word not in valid:
            continue

        if word in seen:
            continue

        seen.add(word)
        result.append(word)

with open(output_file, "w", encoding="utf-8") as out:
    for word in result:
        out.write(word + "\n")

print(f"Valid frequency candidates: {len(frequency_words)}")
print(f"Final candidates: {len(result)}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: candidate vocabulary is empty:"
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
# Select vocabulary by BYTE budget
# ============================================================
#
# The candidate list is frequency-ranked.
#
# Therefore we simply walk it from most frequent to least
# frequent and stop when the dictionary byte budget is reached.
#
# IMPORTANT:
#
# We do not reject long words merely because they are long.
#
# A long frequent word can therefore be selected.
#
# ============================================================

select_vocabulary() {
    local language="$1"
    local candidates="$2"
    local budget="$3"
    local output="$4"

    echo ""
    echo "============================================================"
    echo " Optimizing vocabulary: ${language}"
    echo " Budget: ${budget} bytes"
    echo "============================================================"

    python3 - \
        "${candidates}" \
        "${budget}" \
        "${output}" <<'PY'
import sys

source = sys.argv[1]
budget = int(sys.argv[2])
output = sys.argv[3]

header = "#POCKETBOARD-DICT-1\n"
used = len(header.encode("utf-8"))

selected = []
selected_set = set()

with open(source, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip()

        if not word:
            continue

        if word in selected_set:
            continue

        encoded = (word + "\n").encode("utf-8")

        if used + len(encoded) > budget:
            break

        selected.append(word)
        selected_set.add(word)
        used += len(encoded)

with open(output, "w", encoding="utf-8") as out:
    out.write(header)

    for word in selected:
        out.write(word + "\n")

print(f"Selected words: {len(selected)}")
print(f"Dictionary bytes: {used}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: selected dictionary is empty:"
        echo "  ${language}"
        exit 1
    fi

    local count
    count="$(tail -n +2 "${output}" | wc -l)"

    local size
    size="$(wc -c < "${output}")"

    echo " Final words: ${count}"
    echo " Final bytes: ${size}"

    if (( size > budget )); then
        echo "ERROR: dictionary exceeded budget:"
        echo "  ${language}"
        exit 1
    fi

    #
    # Useful diagnostics.
    #

    echo ""
    echo " Long frequent words retained:"

    grep -E '^.{10,}$' \
        <(tail -n +2 "${output}") |
        head -n 10 |
        while IFS= read -r word
        do
            echo "  ${word}"
        done || true
}


select_vocabulary \
    "es-AR" \
    "${ES_DIR}/candidates.txt" \
    "${ES_DICTIONARY_BUDGET}" \
    "${OUTPUT_DIR}/es-AR.dict"

select_vocabulary \
    "en-en" \
    "${EN_DIR}/candidates.txt" \
    "${EN_DICTIONARY_BUDGET}" \
    "${OUTPUT_DIR}/en-en.dict"

select_vocabulary \
    "de-de" \
    "${DE_DIR}/candidates.txt" \
    "${DE_DICTIONARY_BUDGET}" \
    "${OUTPUT_DIR}/de-de.dict"


# ============================================================
# Generate optimized Symmetric Delete index
# ============================================================
#
# IMPORTANT DIFFERENCE FROM THE OLD GENERATOR:
#
# We DO NOT generate deletes for the entire dictionary.
#
# Words are processed in frequency order.
#
# Top words:
#
#   distance 2
#
# Remaining words:
#
#   distance 1
#
# Each delete key keeps only the most frequent candidates.
#
# The total serialized output is strictly limited by the
# per-language byte budget.
#
# This keeps the runtime format unchanged:
#
#   delete<TAB>word
#
# ============================================================

generate_delete_index() {
    local language="$1"
    local dictionary="$2"
    local output="$3"
    local budget="$4"

    local words_file
    local pairs_file
    local grouped_file

    words_file="${WORK_DIR}/${language}.delete.words"
    pairs_file="${WORK_DIR}/${language}.delete.pairs"
    grouped_file="${WORK_DIR}/${language}.delete.grouped"

    rm -f \
        "${words_file}" \
        "${pairs_file}" \
        "${grouped_file}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Generating optimized correction index: ${language}"
    echo " Budget: ${budget} bytes"
    echo " Distance 2 words: top ${TOP_DISTANCE2_WORDS}"
    echo " Max candidates/delete: ${MAX_CANDIDATES_PER_DELETE}"
    echo "============================================================"

    tail -n +2 "${dictionary}" > "${words_file}"

    python3 - \
        "${words_file}" \
        "${pairs_file}" \
        "${budget}" \
        "${TOP_DISTANCE2_WORDS}" \
        "${MAX_DELETE_DISTANCE}" \
        "${MAX_DELETE_WORD_LENGTH}" \
        "${MAX_CANDIDATES_PER_DELETE}" <<'PY'
import sys

words_file = sys.argv[1]
output_file = sys.argv[2]
budget = int(sys.argv[3])
top_distance2_words = int(sys.argv[4])
max_distance = int(sys.argv[5])
max_word_length = int(sys.argv[6])
max_candidates = int(sys.argv[7])


def deletes(word, distance):
    result = set()

    if distance <= 0:
        return result

    current_level = {word}

    for _ in range(distance):
        next_level = set()

        for current in current_level:
            if not current:
                continue

            for i in range(len(current)):
                candidate = current[:i] + current[i + 1:]

                if candidate:
                    result.add(candidate)
                    next_level.add(candidate)

        current_level = next_level

    return result


words = []

with open(words_file, encoding="utf-8") as f:
    for raw in f:
        word = raw.strip()

        if not word:
            continue

        words.append(word)


#
# delete -> ordered list of words
#
# Because words are processed in frequency order, the first
# candidates are always the most frequent ones.
#
buckets = {}

#
# We use a conservative estimate while constructing the index.
# The final serialized size is checked again after sorting.
#
estimated_bytes = 0

header_bytes = (
    "#POCKETBOARD-DELETES-1\n"
    f"#MAX_DISTANCE={max_distance}\n"
    f"#MAX_WORD_LENGTH={max_word_length}\n"
    f"#MAX_CANDIDATES={max_candidates}\n"
).encode("utf-8")

estimated_bytes = len(header_bytes)

accepted_words = 0
accepted_mappings = 0

for rank, word in enumerate(words):

    if len(word) > max_word_length:
        continue

    #
    # Top frequent words get distance 2.
    # Everything else gets distance 1.
    #
    distance = 2 if rank < top_distance2_words else 1

    generated = deletes(word, distance)

    #
    # Deterministic order.
    #
    for delete in sorted(generated):

        bucket = buckets.get(delete)

        if bucket is None:
            bucket = []
            buckets[delete] = bucket

        #
        # A delete key only retains the most frequent candidates.
        #
        if word in bucket:
            continue

        if len(bucket) >= max_candidates:
            continue

        line = f"{delete}\t{word}\n".encode("utf-8")

        #
        # Reserve space before accepting the mapping.
        #
        if estimated_bytes + len(line) > budget:
            continue

        bucket.append(word)
        estimated_bytes += len(line)
        accepted_mappings += 1

    accepted_words += 1

#
# Write sorted deterministic output.
#
with open(output_file, "w", encoding="utf-8") as out:

    out.write("#POCKETBOARD-DELETES-1\n")
    out.write(f"#MAX_DISTANCE={max_distance}\n")
    out.write(f"#MAX_WORD_LENGTH={max_word_length}\n")
    out.write(f"#MAX_CANDIDATES={max_candidates}\n")

    for delete in sorted(buckets):
        for word in buckets[delete]:
            out.write(delete)
            out.write("\t")
            out.write(word)
            out.write("\n")

print(f"Indexed vocabulary words: {accepted_words}")
print(f"Delete keys: {len(buckets)}")
print(f"Mappings: {accepted_mappings}")
print(f"Estimated bytes: {estimated_bytes}")
PY

    if [[ ! -s "${pairs_file}" ]]; then
        echo "ERROR: optimized delete index produced no mappings:"
        echo "  ${language}"
        exit 1
    fi

    #
    # The Python generator already creates deterministic order.
    # Keep a separate grouped file for final validation.
    #

    LC_ALL=C sort -t $'\t' -k1,1 -k2,2 -u \
        "${pairs_file}" \
        > "${grouped_file}"

    {
        echo "#POCKETBOARD-DELETES-1"
        echo "#MAX_DISTANCE=${MAX_DELETE_DISTANCE}"
        echo "#MAX_WORD_LENGTH=${MAX_DELETE_WORD_LENGTH}"
        echo "#MAX_CANDIDATES=${MAX_CANDIDATES_PER_DELETE}"
        cat "${grouped_file}"
    } > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: delete index is empty:"
        echo "  ${language}"
        exit 1
    fi

    local size
    size="$(wc -c < "${output}")"

    local pair_count
    pair_count="$(
        tail -n +5 "${output}" |
        wc -l
    )"

    local delete_count
    delete_count="$(
        tail -n +5 "${output}" |
        cut -f1 |
        LC_ALL=C uniq |
        wc -l
    )"

    echo ""
    echo "${language}:"
    echo "  Delete keys:       ${delete_count}"
    echo "  Word mappings:     ${pair_count}"
    echo "  Delete index bytes: ${size}"

    if (( size > budget )); then
        echo ""
        echo "ERROR: delete index exceeded budget:"
        echo "  Language: ${language}"
        echo "  Size:     ${size}"
        echo "  Budget:   ${budget}"
        exit 1
    fi
}


generate_delete_index \
    "es-AR" \
    "${OUTPUT_DIR}/es-AR.dict" \
    "${OUTPUT_DIR}/es-AR.deletes" \
    "${ES_DELETE_BUDGET}"

generate_delete_index \
    "en-en" \
    "${OUTPUT_DIR}/en-en.dict" \
    "${OUTPUT_DIR}/en-en.deletes" \
    "${EN_DELETE_BUDGET}"

generate_delete_index \
    "de-de" \
    "${OUTPUT_DIR}/de-de.dict" \
    "${OUTPUT_DIR}/de-de.deletes" \
    "${DE_DELETE_BUDGET}"


# ============================================================
# Generate metadata
# ============================================================

generate_metadata() {
    local language="$1"
    local aff="$2"
    local output="$3"

    {
        echo "#POCKETBOARD-META-1"

        echo "# REP"
        grep -E '^REP([[:space:]]|$)' \
            "${aff}" || true

        echo "# KEY"
        grep -E '^KEY([[:space:]]|$)' \
            "${aff}" || true

        echo "# TRY"
        grep -E '^TRY([[:space:]]|$)' \
            "${aff}" || true

        echo "# PHONE"
        grep -E '^PHONE([[:space:]]|$)' \
            "${aff}" || true

        echo "# ph"
        grep -E '^ph:' \
            "${aff}" || true

        echo "# NOSUGGEST"
        grep -E '^NOSUGGEST([[:space:]]|$)' \
            "${aff}" || true

        echo "# SUBSTANDARD"
        grep -E '^SUBSTANDARD([[:space:]]|$)' \
            "${aff}" || true

    } > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: metadata generation failed:"
        echo "  ${language}"
        exit 1
    fi

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
# Required word checks
# ============================================================
#
# These checks are NON-FATAL.
#
# They are diagnostics only.
#
# A word missing from the dictionary is reported, but does not
# fail the build.
#
# This is important because frequency selection is the actual
# authority.
#
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
        echo "WARNING: word not selected"
        echo "  Language: ${language}"
        echo "  Word: ${word}"
        return 0
    fi

    echo "OK: ${language}: ${word}"
}


echo ""
echo "============================================================"
echo " Word diagnostics"
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
# Global validation
# ============================================================

validate_generated_asset() {
    local file="$1"
    local expected_header="$2"

    if [[ ! -s "${file}" ]]; then
        echo "ERROR: generated asset is empty:"
        echo "  ${file}"
        exit 1
    fi

    local header

    header="$(
        head -n 1 "${file}" |
        tr -d '\r'
    )"

    if [[ "${header}" != "${expected_header}" ]]; then
        echo "ERROR: invalid generated asset header:"
        echo "  ${file}"
        echo "Expected: ${expected_header}"
        echo "Found: ${header}"
        exit 1
    fi
}


echo ""
echo "============================================================"
echo " Validating generated assets"
echo "============================================================"

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    validate_generated_asset \
        "${OUTPUT_DIR}/${language}.dict" \
        "#POCKETBOARD-DICT-1"

    validate_generated_asset \
        "${OUTPUT_DIR}/${language}.meta" \
        "#POCKETBOARD-META-1"

    validate_generated_asset \
        "${OUTPUT_DIR}/${language}.deletes" \
        "#POCKETBOARD-DELETES-1"

    echo "OK: ${language}"
done


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

    dictionary="${OUTPUT_DIR}/${language}.dict"
    deletes="${OUTPUT_DIR}/${language}.deletes"
    metadata="${OUTPUT_DIR}/${language}.meta"

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

    TOTAL_DICTIONARY_BYTES=$(
        ((TOTAL_DICTIONARY_BYTES + dictionary_size))
    )

    TOTAL_DELETE_BYTES=$(
        ((TOTAL_DELETE_BYTES + delete_size))
    )

    TOTAL_METADATA_BYTES=$(
        ((TOTAL_METADATA_BYTES + metadata_size))
    )

    echo ""
    echo "${language}"
    echo "  Words:          ${dictionary_words}"
    echo "  Dictionary:     ${dictionary_size} bytes"
    echo "  Delete index:   ${delete_size} bytes"
    echo "  Delete mappings:${delete_mappings}"
    echo "  Metadata:       ${metadata_size} bytes"
    echo "  Total:          $((dictionary_size + delete_size + metadata_size)) bytes"
done


TOTAL_GENERATED_BYTES=$(
    (
        TOTAL_DICTIONARY_BYTES +
        TOTAL_DELETE_BYTES +
        TOTAL_METADATA_BYTES
    )
)


echo ""
echo "============================================================"
echo " RUNTIME DICTIONARY BUDGET"
echo "============================================================"

echo "Dictionary bytes: ${TOTAL_DICTIONARY_BYTES}"
echo "Budget:           ${GLOBAL_DICTIONARY_BUDGET}"

if (( TOTAL_DICTIONARY_BYTES > GLOBAL_DICTIONARY_BUDGET )); then
    echo ""
    echo "ERROR: dictionaries exceed global dictionary budget."
    echo "  Total:  ${TOTAL_DICTIONARY_BYTES}"
    echo "  Budget: ${GLOBAL_DICTIONARY_BUDGET}"
    exit 1
fi

echo "Status: OK"


echo ""
echo "============================================================"
echo " CORRECTION INDEX BUDGET"
echo "============================================================"

echo "Delete bytes: ${TOTAL_DELETE_BYTES}"
echo "Budget:       ${GLOBAL_DELETE_BUDGET}"

if (( TOTAL_DELETE_BYTES > GLOBAL_DELETE_BUDGET )); then
    echo ""
    echo "ERROR: correction indexes exceed global delete budget."
    echo "  Total:  ${TOTAL_DELETE_BYTES}"
    echo "  Budget: ${GLOBAL_DELETE_BUDGET}"
    exit 1
fi

echo "Status: OK"


echo ""
echo "============================================================"
echo " TOTAL GENERATED DICTIONARY ASSETS"
echo "============================================================"

echo "Total:"
echo "  ${TOTAL_GENERATED_BYTES} bytes"

echo ""
echo "Dictionary assets:"
echo "  ${TOTAL_DICTIONARY_BYTES} bytes"

echo ""
echo "Correction indexes:"
echo "  ${TOTAL_DELETE_BYTES} bytes"

echo ""
echo "Metadata:"
echo "  ${TOTAL_METADATA_BYTES} bytes"


echo ""
echo "============================================================"
echo " PocketBoard dictionaries generated successfully"
echo "============================================================"
