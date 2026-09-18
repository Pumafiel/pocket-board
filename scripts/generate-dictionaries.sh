#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C
export LANG=C

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

mkdir -p "${ES_DIR}"
mkdir -p "${EN_DIR}"
mkdir -p "${DE_DIR}"
mkdir -p "${OUTPUT_DIR}"


# ============================================================
# DELETE INDEX BUDGETS
# ============================================================

ES_DELETE_BUDGET=690000
EN_DELETE_BUDGET=690000
DE_DELETE_BUDGET=840000


GLOBAL_DELETE_BUDGET=2250000


# ============================================================
# VOCABULARY SOURCE
# ============================================================
#
# No arbitrary final dictionary size.
#
# The frequency source determines the vocabulary.
#
# Hunspell validates the words.
#
# The resulting word count is a RESULT, not a target.
#
# ============================================================

SOURCE_MAX_ENTRIES=250000


# ============================================================
# DELETE POLICY
# ============================================================

TOP_DISTANCE2_WORDS=12000

MAX_DELETE_DISTANCE=2

MAX_DELETE_WORD_LENGTH=32

MAX_CANDIDATES_PER_DELETE=3


# ============================================================
# REQUIRED COMMANDS
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
require_command python3
require_command tar
require_command find


# ============================================================
# DOWNLOAD
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
# HUNSPELL DICTIONARIES
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
# FREQUENCY SOURCES
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


# ============================================================
# LEIPZIG GERMAN
# ============================================================

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
        -name '*-words.txt' |
        head -n 1
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
# NORMALIZE FREQUENCYWORDS
# ============================================================
#
# IMPORTANT:
#
# Do NOT pipe Python into head.
#
# With set -euo pipefail, head can close the pipe while Python
# is still writing, producing BrokenPipeError.
#
# Python therefore performs the limit itself.
#
# ============================================================

normalize_frequency_frequencywords() {
    local input="$1"
    local output="$2"

    echo ""
    echo "Normalizing frequency list:"
    echo "  ${input}"

    python3 - \
        "${input}" \
        "${output}" \
        "${SOURCE_MAX_ENTRIES}" <<'PY'
import sys

source = sys.argv[1]
destination = sys.argv[2]
maximum_entries = int(sys.argv[3])

seen = set()
count = 0

with open(
    source,
    encoding="utf-8",
    errors="replace"
) as f, open(
    destination,
    "w",
    encoding="utf-8"
) as out:

    for raw in f:
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

        out.write(word + "\n")

        count += 1

        if count >= maximum_entries:
            break

print(f"Normalized frequency entries: {count}")
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: normalized frequency list is empty:"
        echo "  ${input}"
        exit 1
    fi

    echo "Entries: $(wc -l < "${output}")"
}


# ============================================================
# NORMALIZE LEIPZIG
# ============================================================

normalize_frequency_leipzig() {
    local input="$1"
    local output="$2"

    echo ""
    echo "Normalizing Leipzig frequency list:"
    echo "  ${input}"

    python3 - \
        "${input}" \
        "${output}" \
        "${SOURCE_MAX_ENTRIES}" <<'PY'
import sys

source = sys.argv[1]
destination = sys.argv[2]
maximum_entries = int(sys.argv[3])

seen = set()
entries = []

with open(
    source,
    encoding="utf-8",
    errors="replace"
) as f:

    for raw in f:
        line = raw.strip()

        if not line:
            continue

        parts = line.split()

        if len(parts) < 2:
            continue

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

        if len(entries) >= maximum_entries:
            break

with open(
    destination,
    "w",
    encoding="utf-8"
) as out:

    for word in entries:
        out.write(word + "\n")

print(
    f"Normalized Leipzig entries: {len(entries)}"
)
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
# EXTRACT HUNSPELL WORDS
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
# IMPORTANT EVERYDAY WORDS
# ============================================================
#
# These are NOT a word-count limit.
#
# They guarantee useful everyday/regional/orthographic forms
# that may not rank highly enough in a generic corpus.
#
# Accents and umlauts are deliberately preserved.
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
ahí
aquí
cómo
cuándo
cuánto
cuánta
cuántos
cuántas
qué
quién
quiénes
dónde
adónde
también
más
sí
tú
él
día
días
mañana
año
años
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
mir
mich
dir
dich
uns
euch
nicht
kein
keine
keinen
keinem
keiner
einen
einem
einer
über
für
mit
von
zum
zur
dass
daß
wie
was
wer
wann
wo
warum
heute
morgen
gestern
entschuldigung
wahrscheinlich
möglicherweise
natürlich
wirklich
schon
noch
sehr
bitte
danke
hallo
tschüss
EOF


# ============================================================
# BUILD CANDIDATE VOCABULARY
# ============================================================

build_candidates() {
    local language="$1"
    local frequency="$2"
    local hunspell="$3"
    local whitelist="$4"
    local output="$5"

    echo ""
    echo "============================================================"
    echo " Building everyday vocabulary: ${language}"
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


with open(
    hunspell_file,
    encoding="utf-8"
) as f:

    valid = {
        line.strip().lower()
        for line in f
        if line.strip()
    }


seen = set()
result = []


# ------------------------------------------------------------
# Frequency-ranked vocabulary
# ------------------------------------------------------------

with open(
    frequency_file,
    encoding="utf-8"
) as f:

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


# ------------------------------------------------------------
# Explicit everyday / regional / orthographic vocabulary
# ------------------------------------------------------------

with open(
    whitelist_file,
    encoding="utf-8"
) as f:

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


with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

    for word in result:
        out.write(word + "\n")


print(
    f"Everyday vocabulary candidates: {len(result)}"
)
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
# WRITE RUNTIME DICTIONARIES
# ============================================================
#
# There is deliberately NO dictionary byte budget here.
#
# The vocabulary is determined by:
#
#   frequency source
#   +
#   Hunspell validation
#   +
#   explicit everyday vocabulary
#
# ============================================================

write_dictionary() {
    local language="$1"
    local candidates="$2"
    local output="$3"

    echo ""
    echo "============================================================"
    echo " Writing runtime vocabulary: ${language}"
    echo "============================================================"

    {
        echo "#POCKETBOARD-DICT-1"
        cat "${candidates}"
    } > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: dictionary is empty:"
        echo "  ${language}"
        exit 1
    fi

    echo "Words: $(tail -n +2 "${output}" | wc -l)"
    echo "Bytes: $(wc -c < "${output}")"
}


write_dictionary \
    "es-AR" \
    "${ES_DIR}/candidates.txt" \
    "${OUTPUT_DIR}/es-AR.dict"

write_dictionary \
    "en-en" \
    "${EN_DIR}/candidates.txt" \
    "${OUTPUT_DIR}/en-en.dict"

write_dictionary \
    "de-de" \
    "${DE_DIR}/candidates.txt" \
    "${OUTPUT_DIR}/de-de.dict"
# ============================================================
# GENERATE OPTIMIZED SYMMETRIC DELETE INDEX
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
    echo " Distance 2 words: ${TOP_DISTANCE2_WORDS}"
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

                candidate = (
                    current[:i] +
                    current[i + 1:]
                )

                if candidate:
                    result.add(candidate)
                    next_level.add(candidate)

        current_level = next_level

    return result


words = []

with open(
    words_file,
    encoding="utf-8"
) as f:

    for raw in f:

        word = raw.strip()

        if word:
            words.append(word)


#
# delete -> most frequent candidate words
#

buckets = {}


header = (
    "#POCKETBOARD-DELETES-1\n"
    f"#MAX_DISTANCE={max_distance}\n"
    f"#MAX_WORD_LENGTH={max_word_length}\n"
    f"#MAX_CANDIDATES={max_candidates}\n"
)

used_bytes = len(
    header.encode("utf-8")
)

accepted_mappings = 0


for rank, word in enumerate(words):

    if len(word) > max_word_length:
        continue

    #
    # Dictionary order is frequency order.
    #
    # Only the most frequent words receive distance 2.
    #

    distance = (
        2
        if rank < top_distance2_words
        else 1
    )

    generated = deletes(
        word,
        distance
    )

    for delete in sorted(generated):

        bucket = buckets.get(delete)

        if bucket is None:
            bucket = []
            buckets[delete] = bucket

        if word in bucket:
            continue

        if len(bucket) >= max_candidates:
            continue

        line = (
            f"{delete}\t{word}\n"
            .encode("utf-8")
        )

        if used_bytes + len(line) > budget:
            continue

        bucket.append(word)

        used_bytes += len(line)
        accepted_mappings += 1


#
# Deterministic serialization.
#

with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

    out.write(header)

    for delete in sorted(buckets):

        for word in buckets[delete]:

            out.write(delete)
            out.write("\t")
            out.write(word)
            out.write("\n")


print(
    f"Delete keys: {len(buckets)}"
)

print(
    f"Mappings: {accepted_mappings}"
)

print(
    f"Bytes: {used_bytes}"
)
PY

    if [[ ! -s "${pairs_file}" ]]; then
        echo "ERROR: optimized delete index produced no mappings:"
        echo "  ${language}"
        exit 1
    fi


    #
    # Deterministic final ordering.
    #

    LC_ALL=C sort \
        -t $'\t' \
        -k1,1 \
        -k2,2 \
        -u \
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


    local mappings
    mappings="$(
        tail -n +5 "${output}" |
        wc -l
    )"


    local keys
    keys="$(
        tail -n +5 "${output}" |
        cut -f1 |
        LC_ALL=C uniq |
        wc -l
    )"


    echo ""
    echo "${language}:"
    echo "  Delete keys: ${keys}"
    echo "  Mappings:    ${mappings}"
    echo "  Bytes:       ${size}"


    if (( size > budget )); then

        echo ""
        echo "ERROR: delete index exceeded budget."
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
# METADATA
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
# WORD DIAGNOSTICS
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

        echo "WARNING: word not selected"
        echo "  Language: ${language}"
        echo "  Word: ${word}"

    fi
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
# VALIDATION
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
# FINAL SIZE REPORT
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


    dictionary_size="$(
        wc -c < "${dictionary}"
    )"

    delete_size="$(
        wc -c < "${deletes}"
    )"

    metadata_size="$(
        wc -c < "${metadata}"
    )"


    dictionary_words="$(
        tail -n +2 "${dictionary}" |
        wc -l
    )"


    delete_mappings="$(
        tail -n +5 "${deletes}" |
        wc -l
    )"


    TOTAL_DICTIONARY_BYTES=$(
        (
            TOTAL_DICTIONARY_BYTES +
            dictionary_size
        )
    )


    TOTAL_DELETE_BYTES=$(
        (
            TOTAL_DELETE_BYTES +
            delete_size
        )
    )


    TOTAL_METADATA_BYTES=$(
        (
            TOTAL_METADATA_BYTES +
            metadata_size
        )
    )


    echo ""
    echo "${language}"
    echo "  Words:           ${dictionary_words}"
    echo "  Dictionary:      ${dictionary_size} bytes"
    echo "  Delete index:    ${delete_size} bytes"
    echo "  Delete mappings: ${delete_mappings}"
    echo "  Metadata:        ${metadata_size} bytes"
    echo "  Total:           $((dictionary_size + delete_size + metadata_size)) bytes"

done


TOTAL_GENERATED_BYTES=$(
    (
        TOTAL_DICTIONARY_BYTES +
        TOTAL_DELETE_BYTES +
        TOTAL_METADATA_BYTES
    )
)


# ============================================================
# DELETE INDEX GLOBAL BUDGET
# ============================================================

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


# ============================================================
# TOTAL
# ============================================================

echo ""
echo "============================================================"
echo " TOTAL GENERATED ASSETS"
echo "============================================================"

echo "Total:"
echo "  ${TOTAL_GENERATED_BYTES} bytes"

echo ""
echo "Vocabulary:"
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
