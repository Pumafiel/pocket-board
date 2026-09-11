#!/usr/bin/env bash

set -euo pipefail

###############################################################################
# PocketBoard dictionary generator
#
# Generates flat dictionaries from Hunspell .dic/.aff sources.
#
# Sources:
#   es-AR -> RLA-ES v2.9
#   en-en -> wooorm/dictionaries
#   de-de -> wooorm/dictionaries
#
# Expected tools:
#   curl
#   unmunch
#   python3
#   sort
#   grep
#   sed
#   awk
#
# Missing expected words are WARNINGS.
# Broken/missing dictionary infrastructure remains fatal.
###############################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

OUTPUT_DIR="${PROJECT_ROOT}/app/src/main/assets/dictionaries"
TMP_DIR="${PROJECT_ROOT}/build/dictionary-tmp"

mkdir -p "${OUTPUT_DIR}"
mkdir -p "${TMP_DIR}"

###############################################################################
# Helpers
###############################################################################

log() {
    echo
    echo "============================================================"
    echo "$1"
    echo "============================================================"
}

info() {
    echo "[INFO] $1"
}

warning() {
    echo "[WARNING] $1"
}

error() {
    echo "[ERROR] $1" >&2
}

require_command() {
    local command_name="$1"

    if ! command -v "${command_name}" >/dev/null 2>&1; then
        error "Required command not found: ${command_name}"
        exit 1
    fi
}

cleanup() {
    rm -rf "${TMP_DIR}"
}

trap cleanup EXIT

###############################################################################
# Required commands
###############################################################################

log "PocketBoard dictionary generation"

require_command curl
require_command unmunch
require_command python3
require_command sort
require_command grep
require_command sed
require_command awk

###############################################################################
# Sources
###############################################################################

# RLA-ES v2.9
#
# This release contains an explicit es_AR.oxt dictionary for Argentina.
# We pin the version so builds remain reproducible.
RLA_ES_VERSION="v2.9"
RLA_ES_BASE_URL="https://github.com/sbosio/rla-es/releases/download/${RLA_ES_VERSION}"

# wooorm/dictionaries
EN_BASE_URL="https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/en"
DE_BASE_URL="https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/de"

###############################################################################
# Download helper
###############################################################################

download_file() {
    local url="$1"
    local destination="$2"

    info "Downloading:"
    info "  ${url}"

    if ! curl \
        --fail \
        --location \
        --silent \
        --show-error \
        --retry 3 \
        --retry-delay 2 \
        "${url}" \
        --output "${destination}"
    then
        error "Download failed:"
        error "  ${url}"
        exit 1
    fi

    if [ ! -s "${destination}" ]; then
        error "Downloaded file is empty:"
        error "  ${destination}"
        exit 1
    fi
}

###############################################################################
# Extract RLA-ES OXT
###############################################################################

extract_rla_es_argentina() {
    local oxt_file="$1"
    local destination_dir="$2"

    mkdir -p "${destination_dir}"

    info "Extracting RLA-ES ${RLA_ES_VERSION} es_AR.oxt..."

    if ! python3 - "${oxt_file}" "${destination_dir}" <<'PY'
import sys
import zipfile
from pathlib import Path

archive = Path(sys.argv[1])
destination = Path(sys.argv[2])

required = {
    "es_AR.dic",
    "es_AR.aff",
}

try:
    with zipfile.ZipFile(archive, "r") as zf:
        names = set(zf.namelist())

        missing = required - names

        if missing:
            print(
                "ERROR: RLA-ES archive is missing: "
                + ", ".join(sorted(missing)),
                file=sys.stderr,
            )
            sys.exit(1)

        for filename in required:
            data = zf.read(filename)
            output = destination / filename
            output.write_bytes(data)

except zipfile.BadZipFile:
    print(
        "ERROR: downloaded RLA-ES file is not a valid ZIP/OXT archive.",
        file=sys.stderr,
    )
    sys.exit(1)
PY
    then
        error "Could not extract RLA-ES es_AR.oxt."
        exit 1
    fi
}

###############################################################################
# Validate Hunspell source files
###############################################################################

validate_hunspell_files() {
    local dic_file="$1"
    local aff_file="$2"
    local language="$3"

    if [ ! -f "${dic_file}" ]; then
        error "${language}: missing .dic file:"
        error "  ${dic_file}"
        exit 1
    fi

    if [ ! -f "${aff_file}" ]; then
        error "${language}: missing .aff file:"
        error "  ${aff_file}"
        exit 1
    fi

    if [ ! -s "${dic_file}" ]; then
        error "${language}: .dic file is empty:"
        error "  ${dic_file}"
        exit 1
    fi

    if [ ! -s "${aff_file}" ]; then
        error "${language}: .aff file is empty:"
        error "  ${aff_file}"
        exit 1
    fi

    local dic_entries
    dic_entries="$(head -n 1 "${dic_file}" | tr -d '\r')"

    if ! [[ "${dic_entries}" =~ ^[0-9]+$ ]]; then
        error "${language}: invalid Hunspell .dic header:"
        error "  ${dic_entries}"
        exit 1
    fi

    info "${language}: Hunspell source files validated."
    info "  .dic entries: ${dic_entries}"
}

###############################################################################
# Generate flat dictionary
###############################################################################

generate_dictionary() {
    local language="$1"
    local dic_file="$2"
    local aff_file="$3"
    local output_file="$4"

    local expanded_file="${TMP_DIR}/${language}.expanded"

    log "Generating ${language}"

    validate_hunspell_files \
        "${dic_file}" \
        "${aff_file}" \
        "${language}"

    info "Running unmunch..."

    if ! unmunch \
        "${dic_file}" \
        "${aff_file}" \
        > "${expanded_file}"
    then
        error "${language}: unmunch failed."
        exit 1
    fi

    if [ ! -s "${expanded_file}" ]; then
        error "${language}: unmunch produced an empty result."
        exit 1
    fi

    info "Normalizing generated words..."

    {
        echo "# PocketBoard dictionary: ${language}"

        sed \
            -e 's/\r$//' \
            -e 's/[[:space:]]*$//' \
            "${expanded_file}" \
        | grep -v '^$' \
        | sort -fu
    } > "${output_file}"

    if [ ! -s "${output_file}" ]; then
        error "${language}: generated dictionary is empty."
        exit 1
    fi

    local word_count
    word_count="$(
        tail -n +2 "${output_file}" |
        wc -l |
        tr -d ' '
    )"

    if [ "${word_count}" -lt 1000 ]; then
        error "${language}: generated dictionary is suspiciously small."
        error "  Words generated: ${word_count}"
        exit 1
    fi

    info "${language}: dictionary generated successfully."
    info "  Output: ${output_file}"
    info "  Words: ${word_count}"
}

###############################################################################
# Word validation
#
# IMPORTANT:
# Missing words are warnings, NOT build failures.
###############################################################################

check_word() {
    local language="$1"
    local word="$2"
    local dictionary="${OUTPUT_DIR}/${language}.dict"

    if [ ! -f "${dictionary}" ]; then
        error "${language}: dictionary does not exist:"
        error "  ${dictionary}"
        exit 1
    fi

    if grep \
        -Fqx \
        "${word}" \
        <(tail -n +2 "${dictionary}")
    then
        info "OK ${language}: ${word}"
    else
        warning "${language}: expected word is missing: ${word}"
    fi
}

###############################################################################
# Download source dictionaries
###############################################################################

log "Downloading source dictionaries"

###############################################################################
# Argentine Spanish - RLA-ES v2.9
###############################################################################

ES_AR_OXT="${TMP_DIR}/es_AR.oxt"
ES_AR_DIR="${TMP_DIR}/es_AR"

download_file \
    "${RLA_ES_BASE_URL}/es_AR.oxt" \
    "${ES_AR_OXT}"

extract_rla_es_argentina \
    "${ES_AR_OXT}" \
    "${ES_AR_DIR}"

ES_AR_DIC="${ES_AR_DIR}/es_AR.dic"
ES_AR_AFF="${ES_AR_DIR}/es_AR.aff"

###############################################################################
# English
###############################################################################

EN_DIC="${TMP_DIR}/en.dic"
EN_AFF="${TMP_DIR}/en.aff"

download_file \
    "${EN_BASE_URL}/index.dic" \
    "${EN_DIC}"

download_file \
    "${EN_BASE_URL}/index.aff" \
    "${EN_AFF}"

###############################################################################
# German
###############################################################################

DE_DIC="${TMP_DIR}/de.dic"
DE_AFF="${TMP_DIR}/de.aff"

download_file \
    "${DE_BASE_URL}/index.dic" \
    "${DE_DIC}"

download_file \
    "${DE_BASE_URL}/index.aff" \
    "${DE_AFF}"

###############################################################################
# Generate dictionaries
###############################################################################

generate_dictionary \
    "es-AR" \
    "${ES_AR_DIC}" \
    "${ES_AR_AFF}" \
    "${OUTPUT_DIR}/es-AR.dict"

generate_dictionary \
    "en-en" \
    "${EN_DIC}" \
    "${EN_AFF}" \
    "${OUTPUT_DIR}/en-en.dict"

generate_dictionary \
    "de-de" \
    "${DE_DIC}" \
    "${DE_AFF}" \
    "${OUTPUT_DIR}/de-de.dict"

###############################################################################
# Expected word checks
#
# These checks intentionally do NOT fail the build.
###############################################################################

log "Checking expected words"

# Spanish / Argentine Spanish
check_word "es-AR" "hago"
check_word "es-AR" "hacer"
check_word "es-AR" "veré"
check_word "es-AR" "mañana"
check_word "es-AR" "vos"
check_word "es-AR" "tenés"
check_word "es-AR" "podés"
check_word "es-AR" "hacés"

# English
check_word "en-en" "hello"
check_word "en-en" "world"

# German
check_word "de-de" "hallo"
check_word "de-de" "welt"

###############################################################################
# Final report
###############################################################################

log "Dictionary generation complete"

for dictionary in \
    "${OUTPUT_DIR}/es-AR.dict" \
    "${OUTPUT_DIR}/en-en.dict" \
    "${OUTPUT_DIR}/de-de.dict"
do
    if [ ! -f "${dictionary}" ]; then
        error "Expected output dictionary is missing:"
        error "  ${dictionary}"
        exit 1
    fi

    word_count="$(
        tail -n +2 "${dictionary}" |
        wc -l |
        tr -d ' '
    )"

    info "$(basename "${dictionary}"): ${word_count} words"
done

info "Dictionary generation finished successfully."
