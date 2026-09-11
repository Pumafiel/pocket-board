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
#
# Languages:
#   es-AR
#   en-en
#   de-de
#
# The Hunspell .dic/.aff files are downloaded during the build.
#
# Android does NOT receive Hunspell itself.
#
# Generated runtime assets:
#
#   <language>.dict
#       Normalized valid words.
#
#   <language>.meta
#       Suggestion-related Hunspell metadata.
#
#   <language>.deletes
#       SymSpell-style symmetric-delete candidate index.
#
# The runtime engine will consume these files later.
# ============================================================

ROOT_DIR="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/.." &&
    pwd
)"

WORK_DIR="${ROOT_DIR}/build/pocketboard-dictionaries"
OUTPUT_DIR="${ROOT_DIR}/app/src/main/assets/dictionaries"

WOOORM_BASE="https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries"

ES_AR_DIR="${WORK_DIR}/es-AR"
EN_DIR="${WORK_DIR}/en-en"
DE_DIR="${WORK_DIR}/de-de"

DELETE_MAX_DISTANCE=2
DELETE_MAX_WORD_LENGTH=24

mkdir -p "${WORK_DIR}"
mkdir -p "${ES_AR_DIR}"
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
require_command unmunch
require_command sort
require_command grep
require_command sed
require_command tr
require_command head
require_command tail
require_command wc
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
    echo "  -> ${destination}"

    curl \
        --fail \
        --location \
        --silent \
        --show-error \
        --retry 4 \
        --retry-delay 2 \
        --connect-timeout 30 \
        --max-time 300 \
        -A "PocketBoard-Build/1.1.6" \
        -o "${destination}" \
        "${url}"

    if [[ ! -s "${destination}" ]]; then
        echo "ERROR: downloaded file is empty:"
        echo "  ${destination}"
        exit 1
    fi
}

# ============================================================
# Download Hunspell dictionaries
# ============================================================

echo ""
echo "============================================================"
echo " Downloading PocketBoard Hunspell dictionaries"
echo "============================================================"

echo ""
echo "Argentina"
echo "wooorm/dictionaries -> dictionary-es-ar -> RLA-ES"

download \
    "${WOOORM_BASE}/es-AR/index.dic" \
    "${ES_AR_DIR}/index.dic"

download \
    "${WOOORM_BASE}/es-AR/index.aff" \
    "${ES_AR_DIR}/index.aff"

echo ""
echo "English"

download \
    "${WOOORM_BASE}/en/index.dic" \
    "${EN_DIR}/index.dic"

download \
    "${WOOORM_BASE}/en/index.aff" \
    "${EN_DIR}/index.aff"

echo ""
echo "German"

download \
    "${WOOORM_BASE}/de/index.dic" \
    "${DE_DIR}/index.dic"

download \
    "${WOOORM_BASE}/de/index.aff" \
    "${DE_DIR}/index.aff"

# ============================================================
# Validation
# ============================================================

validate_pair() {
    local language="$1"
    local dic="$2"
    local aff="$3"

    echo ""
    echo "Validating ${language}"

    if [[ ! -s "${dic}" ]]; then
        echo "ERROR: missing .dic:"
        echo "  ${dic}"
        exit 1
    fi

    if [[ ! -s "${aff}" ]]; then
        echo "ERROR: missing .aff:"
        echo "  ${aff}"
        exit 1
    fi

    local first_line

    first_line="$(
        head -n 1 "${dic}" |
        tr -d '\r'
    )"

    if ! [[ "${first_line}" =~ ^[0-9]+$ ]]; then
        echo "ERROR: invalid Hunspell .dic header:"
        echo "  ${dic}"
        echo "First line: ${first_line}"
        exit 1
    fi

    if ! grep -qE \
        '^(SET|LANG|PFX|SFX|REP|MAP|TRY|KEY|PHONE)([[:space:]]|$)' \
        "${aff}"
    then
        echo "ERROR: invalid Hunspell .aff:"
        echo "  ${aff}"
        exit 1
    fi

    echo " .dic: $(wc -l < "${dic}") lines"
    echo " .aff: $(wc -l < "${aff}") lines"
    echo " OK"
}

validate_pair \
    "es-AR" \
    "${ES_AR_DIR}/index.dic" \
    "${ES_AR_DIR}/index.aff"

validate_pair \
    "en-en" \
    "${EN_DIR}/index.dic" \
    "${EN_DIR}/index.aff"

validate_pair \
    "de-de" \
    "${DE_DIR}/index.dic" \
    "${DE_DIR}/index.aff"

# ============================================================
# Extract Hunspell suggestion metadata
# ============================================================

generate_metadata() {
    local language="$1"
    local aff="$2"
    local output="$3"

    local raw_output
    raw_output="${WORK_DIR}/${language}.meta.raw"

    rm -f \
        "${raw_output}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Extracting Hunspell suggestion metadata: ${language}"
    echo "============================================================"

    {
        echo "#POCKETBOARD-META-1"

        echo "# REP"
        grep -E '^(REP([[:space:]]|$)|REP[[:space:]]+[0-9]+)' \
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

    } > "${raw_output}"

    {
        echo "#POCKETBOARD-META-1"
        grep -vE '^[[:space:]]*$' "${raw_output}"
    } > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: metadata generation failed:"
        echo "  ${language}"
        exit 1
    fi

    echo " Metadata: ${output}"
    echo " Lines: $(wc -l < "${output}")"
}

# ============================================================
# Generate normalized dictionary
# ============================================================

generate_dictionary() {
    local language="$1"
    local dic="$2"
    local aff="$3"
    local output="$4"

    local raw_output
    local normalized_output

    raw_output="${WORK_DIR}/${language}.unmunch.txt"
    normalized_output="${WORK_DIR}/${language}.normalized.txt"

    rm -f \
        "${raw_output}" \
        "${normalized_output}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Expanding ${language} with Hunspell .aff rules"
    echo "============================================================"

    #
    # unmunch applies the .aff rules to the flagged entries
    # in the .dic file.
    #

    unmunch \
        "${dic}" \
        "${aff}" \
        > "${raw_output}"

    if [[ ! -s "${raw_output}" ]]; then
        echo "ERROR: unmunch generated no words for ${language}."
        exit 1
    fi

    #
    # Normalize:
    #
    # - remove CR
    # - remove empty lines
    # - lowercase
    # - retain Unicode letters
    # - retain apostrophes
    # - retain hyphens
    # - sort unique
    #

    sed \
        -e 's/\r$//' \
        -e '/^[[:space:]]*$/d' \
        "${raw_output}" |
        tr '[:upper:]' '[:lower:]' |
        grep -E \
        "^[[:alpha:]][[:alpha:]'’--]*$" |
        LC_ALL=C sort -u \
        > "${normalized_output}"

    if [[ ! -s "${normalized_output}" ]]; then
        echo "ERROR: normalized dictionary is empty:"
        echo "  ${language}"
        exit 1
    fi

    {
        echo "#POCKETBOARD-DICT-1"
        cat "${normalized_output}"
    } > "${output}"

    local count

    count="$(
        tail -n +2 "${output}" |
        wc -l
    )"

    echo ""
    echo "${language}: ${count} words"

    if [[ "${count}" -lt 1000 ]]; then
        echo "ERROR: dictionary ${language} is suspiciously small."
        exit 1
    fi
}

# ============================================================
# Generate SymSpell-style symmetric-delete index
# ============================================================

generate_delete_index() {
    local language="$1"
    local dictionary="$2"
    local output="$3"

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
    echo " Generating symmetric-delete index: ${language}"
    echo "============================================================"

    tail -n +2 "${dictionary}" > "${words_file}"

    #
    # The Python part is BUILD-TIME ONLY.
    #
    # It generates all unique deletes up to distance 2.
    #
    # Runtime will NOT generate deletes for the dictionary.
    #
    # Format before grouping:
    #
    # delete<TAB>word
    #
    # Unicode is handled by Python code points rather than bytes.
    #

    python3 - \
        "${words_file}" \
        "${pairs_file}" \
        "${DELETE_MAX_DISTANCE}" \
        "${DELETE_MAX_WORD_LENGTH}" <<'PY'
import sys

words_file = sys.argv[1]
pairs_file = sys.argv[2]
max_distance = int(sys.argv[3])
max_word_length = int(sys.argv[4])


def deletes(word, distance):
    if distance <= 0:
        return set()

    result = set()

    def walk(current, remaining):
        if remaining <= 0:
            return

        seen = set()

        for i in range(len(current)):
            candidate = current[:i] + current[i + 1:]

            if candidate in seen:
                continue

            seen.add(candidate)
            result.add(candidate)

            if remaining > 1:
                walk(candidate, remaining - 1)

    walk(word, distance)

    return result


with open(words_file, "r", encoding="utf-8") as source, \
     open(pairs_file, "w", encoding="utf-8") as output:

    for raw in source:
        word = raw.strip().lower()

        if not word:
            continue

        if len(word) > max_word_length:
            continue

        generated = deletes(word, max_distance)

        for delete in generated:
            output.write(delete)
            output.write("\t")
            output.write(word)
            output.write("\n")
PY

    if [[ ! -s "${pairs_file}" ]]; then
        echo "ERROR: delete index generation produced no entries:"
        echo "  ${language}"
        exit 1
    fi

    #
    # Sort by delete and then by word.
    #
    # This gives DictionaryManager a deterministic lookup structure.
    #

    LC_ALL=C sort -t $'\t' -k1,1 -k2,2 -u \
        "${pairs_file}" \
        > "${grouped_file}"

    {
        echo "#POCKETBOARD-DELETES-1"
        echo "#MAX_DISTANCE=${DELETE_MAX_DISTANCE}"
        echo "#MAX_WORD_LENGTH=${DELETE_MAX_WORD_LENGTH}"
        cat "${grouped_file}"
    } > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: delete index is empty:"
        echo "  ${language}"
        exit 1
    fi

    local pair_count
    local delete_count

    pair_count="$(
        tail -n +4 "${output}" |
        wc -l
    )"

    delete_count="$(
        tail -n +4 "${output}" |
        cut -f1 |
        LC_ALL=C uniq |
        wc -l
    )"

    echo ""
    echo "${language}: delete index generated"
    echo " Delete keys: ${delete_count}"
    echo " Word mappings: ${pair_count}"
}

# ============================================================
# Generate all assets
# ============================================================

generate_dictionary \
    "es-AR" \
    "${ES_AR_DIR}/index.dic" \
    "${ES_AR_DIR}/index.aff" \
    "${OUTPUT_DIR}/es-AR.dict"

generate_dictionary \
    "en-en" \
    "${EN_DIR}/index.dic" \
    "${EN_DIR}/index.aff" \
    "${OUTPUT_DIR}/en-en.dict"

generate_dictionary \
    "de-de" \
    "${DE_DIR}/index.dic" \
    "${DE_DIR}/index.aff" \
    "${OUTPUT_DIR}/de-de.dict"

generate_metadata \
    "es-AR" \
    "${ES_AR_DIR}/index.aff" \
    "${OUTPUT_DIR}/es-AR.meta"

generate_metadata \
    "en-en" \
    "${EN_DIR}/index.aff" \
    "${OUTPUT_DIR}/en-en.meta"

generate_metadata \
    "de-de" \
    "${DE_DIR}/index.aff" \
    "${OUTPUT_DIR}/de-de.meta"

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
# Required word checks
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
        echo ""
        echo "ERROR: required word missing"
        echo "Language: ${language}"
        echo "Word: ${word}"
        echo "File: ${dictionary}"
        echo ""
        exit 1
    fi

    echo " OK ${language}: ${word}"
}

echo ""
echo "============================================================"
echo " Required word checks"
echo "============================================================"

check_word "es-AR" "hago"
check_word "es-AR" "hacer"
check_word "es-AR" "veré"
check_word "es-AR" "mañana"
check_word "es-AR" "vos"
check_word "es-AR" "tenés"
check_word "es-AR" "podés"
check_word "es-AR" "hacés"

check_word "en-en" "the"
check_word "en-en" "have"
check_word "en-en" "hello"

check_word "de-de" "ich"
check_word "de-de" "habe"
check_word "de-de" "morgen"

# ============================================================
# Final validation
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

    echo " OK ${language}"
done

echo ""
echo "============================================================"
echo " PocketBoard dictionaries generated successfully"
echo "============================================================"
echo ""

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do
    dictionary="${OUTPUT_DIR}/${language}.dict"
    metadata="${OUTPUT_DIR}/${language}.meta"
    deletes="${OUTPUT_DIR}/${language}.deletes"

    echo "${language}"
    echo " Dictionary: $(du -h "${dictionary}" | cut -f1)"
    echo " Words: $(tail -n +2 "${dictionary}" | wc -l)"
    echo " Metadata: $(du -h "${metadata}" | cut -f1)"
    echo " Delete index: $(du -h "${deletes}" | cut -f1)"
    echo " Delete mappings: $(tail -n +4 "${deletes}" | wc -l)"
    echo ""
done
