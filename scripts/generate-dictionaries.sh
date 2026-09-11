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
#
# Strategy:
#
#   Hunspell
#       ↓
#   frequency-ranked vocabulary
#       ↓
#   PocketBoard whitelist
#       ↓
#   compact runtime dictionary
#       ↓
#   SymSpell symmetric deletes
#
# We intentionally DO NOT unmunch the complete Hunspell
# dictionary into the APK. That creates an enormous number of
# derived forms and makes the runtime assets unnecessarily big.
#
# Target:
#   ~50k useful words per language
#   compact correction index
#   APK target around 10 MB
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

MAX_WORDS=50000
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
# Download Hunspell sources
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
# Download frequency lists
# ============================================================

echo ""
echo "============================================================"
echo " Downloading frequency lists"
echo "============================================================"

download \
    "${FREQUENCY_BASE}/es/es_50k.txt" \
    "${ES_DIR}/frequency.txt"

download \
    "${FREQUENCY_BASE}/en/en_50k.txt" \
    "${EN_DIR}/frequency.txt"

download \
    "${FREQUENCY_BASE}/de/de_50k.txt" \
    "${DE_DIR}/frequency.txt"

# ============================================================
# Normalize frequency list
# ============================================================

normalize_frequency() {
    local input="$1"
    local output="$2"

    sed \
        -e 's/\r$//' \
        -e '/^[[:space:]]*$/d' \
        "${input}" |
        python3 -c '
import sys

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

    print(word)
' |
        awk '!seen[$0]++' \
        > "${output}"

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
# Extract base Hunspell words
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
# Build compact vocabulary
# ============================================================

build_vocabulary() {
    local language="$1"
    local frequency="$2"
    local hunspell="$3"
    local whitelist="$4"
    local output="$5"

    local ranked
    local common
    local merged

    ranked="${WORK_DIR}/${language}.ranked"
    common="${WORK_DIR}/${language}.common"
    merged="${WORK_DIR}/${language}.merged"

    rm -f \
        "${ranked}" \
        "${common}" \
        "${merged}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Building compact vocabulary: ${language}"
    echo "============================================================"

    #
    # Frequency list is already ranked from most frequent to
    # least frequent. Keep only words recognized by Hunspell.
    #

    python3 - \
        "${frequency}" \
        "${hunspell}" \
        "${ranked}" \
        "${MAX_WORDS}" <<'PY'
import sys

frequency_file = sys.argv[1]
hunspell_file = sys.argv[2]
output_file = sys.argv[3]
limit = int(sys.argv[4])

with open(hunspell_file, encoding="utf-8") as f:
    valid = {
        line.strip().lower()
        for line in f
        if line.strip()
    }

count = 0

with open(output_file, "w", encoding="utf-8") as out:
    with open(frequency_file, encoding="utf-8") as f:
        for raw in f:
            word = raw.strip().lower()

            if not word:
                continue

            if word not in valid:
                continue

            out.write(word + "\n")
            count += 1

            if count >= limit:
                break

print(f"Selected frequent words: {count}")
PY

    #
    # Whitelist contains words that must survive frequency
    # filtering, especially regional Spanish forms.
    #

    if [[ -s "${whitelist}" ]]; then
        cat "${ranked}" "${whitelist}" |
            sed '/^[[:space:]]*$/d' |
            tr '[:upper:]' '[:lower:]' |
            LC_ALL=C sort -u \
            > "${merged}"
    else
        cp "${ranked}" "${merged}"
    fi

    #
    # Keep final vocabulary bounded. Whitelist words are added
    # after frequency selection and therefore may exceed the
    # nominal 50k target by a small amount.
    #

    cp "${merged}" "${output}"

    local count
    count="$(wc -l < "${output}")"

    echo " Final vocabulary: ${count} words"

    if [[ "${count}" -eq 0 ]]; then
        echo "ERROR: empty vocabulary: ${language}"
        exit 1
    fi
}

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
tenés
podés
querés
hacés
sabés
venís
decís
dás
vas
ves
oís
reís
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
EOF

# ============================================================
# Build vocabularies
# ============================================================

build_vocabulary \
    "es-AR" \
    "${ES_DIR}/frequency.normalized" \
    "${ES_DIR}/hunspell.words" \
    "${ES_DIR}/whitelist.txt" \
    "${ES_DIR}/vocabulary.txt"

build_vocabulary \
    "en-en" \
    "${EN_DIR}/frequency.normalized" \
    "${EN_DIR}/hunspell.words" \
    "${EN_DIR}/whitelist.txt" \
    "${EN_DIR}/vocabulary.txt"

build_vocabulary \
    "de-de" \
    "${DE_DIR}/frequency.normalized" \
    "${DE_DIR}/hunspell.words" \
    "${DE_DIR}/whitelist.txt" \
    "${DE_DIR}/vocabulary.txt"

# ============================================================
# Generate dictionary assets
# ============================================================

generate_dictionary() {
    local language="$1"
    local vocabulary="$2"
    local output="$3"

    {
        # Runtime compatibility:
        # DictionaryManager expects DICT-1.
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

        local = set()

        for i in range(len(current)):
            candidate = current[:i] + current[i + 1:]

            if candidate in local:
                continue

            local.add(candidate)
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


#
# We process words in frequency order.
# Earlier words therefore win when a delete has many candidates.
#

candidates = {}

for word in words:
    if len(word) > max_length:
        continue

    #
    # Short words get distance 1 only.
    # Longer words can use distance 2.
    #

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

    LC_ALL=C sort -t $'\t' -k1,1 -k2,2 \
        "${pairs}" |
        LC_ALL=C uniq \
        > "${grouped}"

    {
        # Runtime compatibility:
        # DictionaryManager expects DELETES-1.
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
# Non-fatal sanity checks
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

check_word "en-en" "the"
check_word "en-en" "have"
check_word "en-en" "hello"

check_word "de-de" "ich"
check_word "de-de" "nicht"
check_word "de-de" "morgen"

# ============================================================
# Final size report
# ============================================================

echo ""
echo "============================================================"
echo " PocketBoard dictionary build summary"
echo "============================================================"

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do
    dictionary="${OUTPUT_DIR}/${language}.dict"
    deletes="${OUTPUT_DIR}/${language}.deletes"
    metadata="${OUTPUT_DIR}/${language}.meta"

    echo ""
    echo "${language}"
    echo "  Words:          $(tail -n +2 "${dictionary}" | wc -l)"
    echo "  Dictionary:     $(du -h "${dictionary}" | cut -f1)"
    echo "  Delete index:   $(du -h "${deletes}" | cut -f1)"
    echo "  Metadata:       $(du -h "${metadata}" | cut -f1)"
done

echo ""
echo "------------------------------------------------------------"
echo " TOTAL GENERATED ASSETS"
echo "------------------------------------------------------------"

du -ch \
    "${OUTPUT_DIR}"/*.dict \
    "${OUTPUT_DIR}"/*.deletes \
    "${OUTPUT_DIR}"/*.meta |
    tail -n 1

echo ""
echo "============================================================"
echo " PocketBoard dictionaries generated successfully"
echo "============================================================"
