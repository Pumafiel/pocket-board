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
# TARGETS
# ============================================================

# Runtime dictionaries are not artificially capped.
#
# We want as much useful vocabulary as possible.
# The APK build will tell us the real compressed size.
#
# Delete indexes ARE budgeted because they grow very quickly.

ES_DELETE_BUDGET=2500000
EN_DELETE_BUDGET=2500000
DE_DELETE_BUDGET=2500000

GLOBAL_DELETE_BUDGET=7500000

# Maximum number of entries read from each frequency source.
#
# This is NOT the final dictionary size.
SOURCE_MAX_ENTRIES=500000

# ============================================================
# DELETE POLICY
# ============================================================

# Most frequent words receive edit-distance 2 deletes.
TOP_DISTANCE2_WORDS=15000

MAX_DELETE_DISTANCE=2

# Only the delete index is limited by word length.
# Long words can still remain in .dict.
MAX_DELETE_WORD_LENGTH=24

# Prevent a single delete key from exploding.
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

# We keep unmunch available for environments where it exists,
# but the runtime dictionary generation below intentionally does
# NOT expand the complete Hunspell dictionary with unmunch.
if command -v unmunch >/dev/null 2>&1; then
    echo "unmunch: available"
else
    echo "unmunch: not required for this dictionary build"
fi

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

    find \
        "${LEIPZIG_DIR}" \
        -maxdepth 4 \
        -type f \
        -print

    exit 1
fi

echo ""
echo "Leipzig frequency file:"
echo "  ${LEIPZIG_WORDS_FILE}"

# ============================================================
# NORMALIZE FREQUENCYWORDS
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
import unicodedata

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

        word = unicodedata.normalize(
            "NFC",
            word
        )

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)

        out.write(word + "\n")

        count += 1

        if count >= maximum_entries:
            break

print(
    f"Normalized frequency entries: {count}"
)
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
import unicodedata

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

        if not parts:
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

        word = unicodedata.normalize(
            "NFC",
            word
        ).lower()

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
# EXTRACT HUNSPELL BASE WORDS
# ============================================================
#
# We intentionally do NOT use the full unmunch expansion here.
#
# The complete expansion can become enormous.
#
# Instead:
#
#   frequency corpus -> useful/common vocabulary
#   Hunspell .dic    -> additional vocabulary fallback
#
# This keeps generation and APK size under control.
# ============================================================

extract_hunspell_base_words() {
    local language="$1"
    local dic="$2"
    local output="$3"

    echo ""
    echo "============================================================"
    echo " Extracting Hunspell base words: ${language}"
    echo "============================================================"

    python3 - \
        "${dic}" \
        "${output}" <<'PY'
import sys
import unicodedata

source = sys.argv[1]
destination = sys.argv[2]

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

    first = True

    for raw in f:
        line = raw.strip()

        if not line:
            continue

        # First line is normally the entry count.
        if first:
            first = False

            if line.isdigit():
                continue

        # Hunspell entries can have flags:
        #
        #   palabra/ABC
        #
        # Keep only the base word.
        if "/" in line:
            word = line.split("/", 1)[0]
        else:
            word = line

        word = word.strip().lower()

        word = unicodedata.normalize(
            "NFC",
            word
        )

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)

        out.write(word + "\n")
        count += 1

print(
    f"Hunspell base words: {count}"
)
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: Hunspell base word extraction produced no words:"
        echo "  ${language}"
        exit 1
    fi

    echo "Base words: $(wc -l < "${output}")"
}

extract_hunspell_base_words \
    "es-AR" \
    "${ES_DIR}/index.dic" \
    "${ES_DIR}/hunspell.base"

extract_hunspell_base_words \
    "en-en" \
    "${EN_DIR}/index.dic" \
    "${EN_DIR}/hunspell.base"

extract_hunspell_base_words \
    "de-de" \
    "${DE_DIR}/index.dic" \
    "${DE_DIR}/hunspell.base"

# ============================================================
# BUILD CANDIDATE VOCABULARY
# ============================================================
#
# Priority:
#
#   1. Frequency words.
#   2. Remaining Hunspell base words.
#
# No manual whitelist.
#
# IMPORTANT:
# We do not require frequency words to literally exist in the
# Hunspell base dictionary.
#
# This is important for forms such as:
#
#   podés
#   tenés
#   hacés
#
# and similar inflected/variant forms found in real usage data.
# ============================================================

build_candidates() {
    local language="$1"
    local frequency="$2"
    local hunspell="$3"
    local output="$4"

    echo ""
    echo "============================================================"
    echo " Building vocabulary: ${language}"
    echo "============================================================"

    python3 - \
        "${frequency}" \
        "${hunspell}" \
        "${output}" <<'PY'
import sys
import unicodedata

frequency_file = sys.argv[1]
hunspell_file = sys.argv[2]
output_file = sys.argv[3]

def normalize(word):
    return unicodedata.normalize(
        "NFC",
        word.strip().lower()
    )

def valid_word(word):
    if not word:
        return False

    has_letter = False

    for char in word:

        if char.isalpha():
            has_letter = True
            continue

        # Apostrophes and hyphens are useful in natural language.
        if char in "'’'-":
            continue

        return False

    return has_letter

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

        word = normalize(raw)

        if not valid_word(word):
            continue

        if word in seen:
            continue

        seen.add(word)
        result.append(word)

# ------------------------------------------------------------
# Hunspell fallback
# ------------------------------------------------------------

with open(
    hunspell_file,
    encoding="utf-8"
) as f:

    for raw in f:

        word = normalize(raw)

        if not valid_word(word):
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
    f"Vocabulary candidates: {len(result)}"
)
PY

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: candidate vocabulary is empty:"
        echo "  ${language}"
        exit 1
    fi

    echo "Candidates: $(wc -l < "${output}")"
}

build_candidates \
    "es-AR" \
    "${ES_DIR}/frequency.normalized" \
    "${ES_DIR}/hunspell.base" \
    "${ES_DIR}/candidates.txt"

build_candidates \
    "en-en" \
    "${EN_DIR}/frequency.normalized" \
    "${EN_DIR}/hunspell.base" \
    "${EN_DIR}/candidates.txt"

build_candidates \
    "de-de" \
    "${DE_DIR}/frequency.normalized" \
    "${DE_DIR}/hunspell.base" \
    "${DE_DIR}/candidates.txt"

# ============================================================
# WRITE RUNTIME DICTIONARIES
# ============================================================

write_dictionary() {
    local language="$1"
    local candidates="$2"
    local output="$3"

    echo ""
    echo "============================================================"
    echo " Writing runtime dictionary: ${language}"
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
# GENERATE DELETE INDEX
# ============================================================

generate_delete_index() {
    local language="$1"
    local dictionary="$2"
    local output="$3"
    local budget="$4"

    local words_file
    local pairs_file
    local header_bytes
    local mapping_budget

    words_file="${WORK_DIR}/${language}.delete.words"
    pairs_file="${WORK_DIR}/${language}.delete.pairs"

    rm -f \
        "${words_file}" \
        "${pairs_file}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Generating delete index: ${language}"
    echo " Budget: ${budget} bytes"
    echo " Distance 2 words: ${TOP_DISTANCE2_WORDS}"
    echo " Max word length: ${MAX_DELETE_WORD_LENGTH}"
    echo " Max candidates/delete: ${MAX_CANDIDATES_PER_DELETE}"
    echo "============================================================"

    tail -n +2 "${dictionary}" > "${words_file}"

    # --------------------------------------------------------
    # IMPORTANT
    #
    # The budget belongs to the COMPLETE .deletes file.
    #
    # Therefore subtract the actual header size before asking
    # Python to generate mappings.
    # --------------------------------------------------------

    header_bytes=$(
        printf \
            "#POCKETBOARD-DELETES-1\n#MAX_DISTANCE=%s\n#MAX_WORD_LENGTH=%s\n#MAX_CANDIDATES=%s\n" \
            "${MAX_DELETE_DISTANCE}" \
            "${MAX_DELETE_WORD_LENGTH}" \
            "${MAX_CANDIDATES_PER_DELETE}" |
        wc -c
    )

    mapping_budget=$(
        (
            budget - header_bytes
        )
    )

    if (( mapping_budget <= 0 )); then
        echo "ERROR: delete budget is smaller than the header."
        exit 1
    fi

    echo "Header bytes:   ${header_bytes}"
    echo "Mapping budget: ${mapping_budget}"

    python3 - \
        "${words_file}" \
        "${pairs_file}" \
        "${mapping_budget}" \
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

def generate_deletes(word, distance):
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

buckets = {}

used_bytes = 0
accepted_mappings = 0

for rank, word in enumerate(words):

    # Long words remain in the dictionary but do not get
    # expensive delete-index expansion.
    if len(word) > max_word_length:
        continue

    distance = (
        2
        if rank < top_distance2_words
        else 1
    )

    generated = generate_deletes(
        word,
        distance
    )

    for delete in generated:

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

        # Never exceed the mapping budget.
        if used_bytes + len(line) > budget:
            continue

        bucket.append(word)

        used_bytes += len(line)
        accepted_mappings += 1

with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

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
    f"Mapping bytes: {used_bytes}"
)
PY

    if [[ ! -s "${pairs_file}" ]]; then
        echo "ERROR: delete index produced no mappings:"
        echo "  ${language}"
        exit 1
    fi

    # --------------------------------------------------------
    # Build the final file using the SAME header whose size
    # was subtracted from the budget above.
    # --------------------------------------------------------

    {
        printf \
            "#POCKETBOARD-DELETES-1\n"
        printf \
            "#MAX_DISTANCE=%s\n" \
            "${MAX_DELETE_DISTANCE}"
        printf \
            "#MAX_WORD_LENGTH=%s\n" \
            "${MAX_DELETE_WORD_LENGTH}"
        printf \
            "#MAX_CANDIDATES=%s\n" \
            "${MAX_CANDIDATES_PER_DELETE}"
        cat "${pairs_file}"
    } > "${output}"

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
        uniq |
        wc -l
    )"

    echo ""
    echo "${language}:"
    echo "  Delete keys:    ${keys}"
    echo "  Delete mappings:${mappings}"
    echo "  Delete bytes:   ${size}"

    # Final safety check.
    if (( size > budget )); then
        echo ""
        echo "ERROR: delete index exceeded budget."
        echo "  Language: ${language}"
        echo "  Size:     ${size}"
        echo "  Budget:   ${budget}"
        exit 1
    fi

    echo "  Budget status:  OK"
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
        echo "  Word:     ${word}"
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
        echo "Found:    ${header}"
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

echo ""
echo "============================================================"
echo " TOTAL"
echo "============================================================"

echo "Dictionary bytes: ${TOTAL_DICTIONARY_BYTES}"
echo "Delete bytes:     ${TOTAL_DELETE_BYTES}"
echo "Metadata bytes:   ${TOTAL_METADATA_BYTES}"
echo "Generated bytes:  ${TOTAL_GENERATED_BYTES}"

echo ""
echo "Approximate generated data:"
echo "  $((TOTAL_GENERATED_BYTES / 1024 / 1024)) MiB"

echo ""
echo "Delete budget:"
echo "  Spanish: ${ES_DELETE_BUDGET}"
echo "  English: ${EN_DELETE_BUDGET}"
echo "  German:  ${DE_DELETE_BUDGET}"
echo "  Global:  ${GLOBAL_DELETE_BUDGET}"

if (( TOTAL_DELETE_BYTES > GLOBAL_DELETE_BUDGET )); then
    echo ""
    echo "ERROR: global delete budget exceeded."
    echo "  Size:   ${TOTAL_DELETE_BYTES}"
    echo "  Budget: ${GLOBAL_DELETE_BUDGET}"
    exit 1
fi

echo ""
echo "============================================================"
echo " Dictionary generation completed successfully"
echo "============================================================"
