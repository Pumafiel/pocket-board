#!/usr/bin/env bash

set -euo pipefail

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# ============================================================
# PocketBoard dictionary generator
# ============================================================
#
# Sources are Hunspell dictionaries distributed by
# wooorm/dictionaries.
#
# es-AR is the Argentine Spanish dictionary generated from
# RLA-ES.
#
# The .aff files are REQUIRED.
# They are processed by Hunspell's unmunch tool during the
# GitHub Actions build.
#
# The Android application receives only plain word lists.
#
# Missing individual words are treated as warnings rather
# than build-breaking errors. This allows the upstream
# dictionary to remain the primary source of truth while
# still reporting regional forms that may be worth reviewing.
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

mkdir -p "${WORK_DIR}"
mkdir -p "${ES_AR_DIR}"
mkdir -p "${EN_DIR}"
mkdir -p "${DE_DIR}"
mkdir -p "${OUTPUT_DIR}"


# ------------------------------------------------------------
# Required commands
# ------------------------------------------------------------

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
require_command du
require_command cut


# ------------------------------------------------------------
# Download helper
# ------------------------------------------------------------

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


# ------------------------------------------------------------
# Download Hunspell dictionaries
# ------------------------------------------------------------

echo ""
echo "============================================================"
echo " Downloading PocketBoard Hunspell dictionaries"
echo "============================================================"
echo ""

# Argentine Spanish
#
# wooorm/dictionaries publishes dictionary-es-ar as the
# Argentine Spanish dictionary generated from RLA-ES.

download \
    "${WOOORM_BASE}/es-AR/index.dic" \
    "${ES_AR_DIR}/index.dic"

download \
    "${WOOORM_BASE}/es-AR/index.aff" \
    "${ES_AR_DIR}/index.aff"


# English

download \
    "${WOOORM_BASE}/en/index.dic" \
    "${EN_DIR}/index.dic"

download \
    "${WOOORM_BASE}/en/index.aff" \
    "${EN_DIR}/index.aff"


# German

download \
    "${WOOORM_BASE}/de/index.dic" \
    "${DE_DIR}/index.dic"

download \
    "${WOOORM_BASE}/de/index.aff" \
    "${DE_DIR}/index.aff"


# ------------------------------------------------------------
# Validation
# ------------------------------------------------------------

validate_pair() {
    local language="$1"
    local dic="$2"
    local aff="$3"

    echo ""
    echo "Validating ${language}"

    if [[ ! -s "${dic}" ]]; then
        echo "ERROR: missing .dic:"
        echo "${dic}"
        exit 1
    fi

    if [[ ! -s "${aff}" ]]; then
        echo "ERROR: missing .aff:"
        echo "${aff}"
        exit 1
    fi

    local first_line

    first_line="$(
        head -n 1 "${dic}" |
        tr -d '\r'
    )"

    if ! [[ "${first_line}" =~ ^[0-9]+$ ]]; then
        echo "ERROR: invalid Hunspell .dic header:"
        echo "${dic}"
        echo "First line: ${first_line}"
        exit 1
    fi

    if ! grep -qE \
        '^(SET|LANG|PFX|SFX|REP|MAP|TRY)([[:space:]]|$)' \
        "${aff}"
    then
        echo "ERROR: invalid Hunspell .aff:"
        echo "${aff}"
        exit 1
    fi

    echo "  .dic: $(wc -l < "${dic}") lines"
    echo "  .aff: $(wc -l < "${aff}") lines"
    echo "  OK"
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


# ------------------------------------------------------------
# Generate plain word list
# ------------------------------------------------------------

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
    echo ""

    # unmunch expands the entries in the .dic file using the
    # affix rules from the corresponding .aff file.

    unmunch \
        "${dic}" \
        "${aff}" \
        > "${raw_output}"

    if [[ ! -s "${raw_output}" ]]; then
        echo "ERROR: unmunch generated no words for ${language}."
        exit 1
    fi

    # --------------------------------------------------------
    # Normalize:
    #
    # - remove CR
    # - remove empty lines
    # - lowercase
    # - retain letters
    # - retain apostrophes
    # - retain hyphens
    # - sort unique
    #
    # UTF-8 locale is explicitly configured at the top of this
    # script so accented characters are handled consistently.
    # --------------------------------------------------------

    sed \
        -e 's/\r$//' \
        -e '/^[[:space:]]*$/d' \
        "${raw_output}" |
        tr '[:upper:]' '[:lower:]' |
        grep -E \
            "^[[:alpha:]][[:alpha:]'’\-]*$" |
        sort -u \
        > "${normalized_output}"

    if [[ ! -s "${normalized_output}" ]]; then
        echo "ERROR: normalized dictionary is empty:"
        echo "${language}"
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
    echo "Output: ${output}"

    if [[ "${count}" -lt 1000 ]]; then
        echo "ERROR: dictionary ${language} is suspiciously small."
        exit 1
    fi
}


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


# ------------------------------------------------------------
# Optional word checks
# ------------------------------------------------------------
#
# These checks are quality checks, not hard requirements.
#
# The upstream Hunspell dictionary remains the source of truth.
# A missing regional form must not break the build.
#
# Missing words are reported as WARNINGs so they can be reviewed
# and optionally added later if they are confirmed as appropriate
# for the target language.
# ------------------------------------------------------------

MISSING_WORDS=0

check_word() {
    local language="$1"
    local word="$2"
    local dictionary="${OUTPUT_DIR}/${language}.dict"

    if grep \
        -Fqx \
        "${word}" \
        <(tail -n +2 "${dictionary}")
    then
        echo "  OK      ${language}: ${word}"
    else
        echo "  WARNING ${language}: missing word: ${word}"
        MISSING_WORDS=$((MISSING_WORDS + 1))
    fi
}


echo ""
echo "============================================================"
echo " Optional word checks"
echo "============================================================"
echo ""

# Argentine Spanish

check_word "es-AR" "hago"
check_word "es-AR" "hacer"
check_word "es-AR" "veré"
check_word "es-AR" "mañana"
check_word "es-AR" "vos"
check_word "es-AR" "tenés"
check_word "es-AR" "podés"
check_word "es-AR" "hacés"


# English

check_word "en-en" "the"
check_word "en-en" "have"
check_word "en-en" "hello"


# German

check_word "de-de" "ich"
check_word "de-de" "habe"
check_word "de-de" "morgen"


# ------------------------------------------------------------
# Word check summary
# ------------------------------------------------------------

echo ""
echo "============================================================"
echo " Word check summary"
echo "============================================================"
echo ""

if [[ "${MISSING_WORDS}" -gt 0 ]]; then
    echo "WARNING: ${MISSING_WORDS} optional word(s) were not found."
    echo "The build will continue."
else
    echo "All optional word checks passed."
fi

echo ""


# ------------------------------------------------------------
# Final report
# ------------------------------------------------------------

echo ""
echo "============================================================"
echo " PocketBoard dictionaries generated successfully"
echo "============================================================"
echo ""

for dictionary in \
    "${OUTPUT_DIR}/es-AR.dict" \
    "${OUTPUT_DIR}/en-en.dict" \
    "${OUTPUT_DIR}/de-de.dict"
do
    echo "$(basename "${dictionary}")"
    echo " Size: $(du -h "${dictionary}" | cut -f1)"
    echo " Words: $(tail -n +2 "${dictionary}" | wc -l)"
    echo ""
done

echo "Done."
