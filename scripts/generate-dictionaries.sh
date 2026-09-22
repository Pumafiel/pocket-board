#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# PocketBoard dictionary generator
#
# Design goals:
#   - Unicode/NFC-safe vocabulary
#   - NEVER strip Spanish accents/diacritics
#   - Frequency-first vocabulary
#   - Curated mandatory/core vocabulary
#   - Complete Hunspell validation before .dict generation
#   - UTF-8 explicitly passed to Hunspell
#   - Spanish Argentina / voseo support
#   - Delete keys generated ONLY from validated .dict words
#   - Delete keys are NEVER fed back into candidate generation
#   - Strict token validation
#   - Stable runtime asset format
# ============================================================

set -euo pipefail

# ------------------------------------------------------------
# Locale
# ------------------------------------------------------------

export LC_ALL=C.UTF-8
export LANG=C.UTF-8

# ------------------------------------------------------------
# Paths
# ------------------------------------------------------------

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

ES_DELETE_BUDGET=2500000
EN_DELETE_BUDGET=2500000
DE_DELETE_BUDGET=2500000

GLOBAL_DELETE_BUDGET=7500000

# Maximum entries read from frequency sources.
SOURCE_MAX_ENTRIES=500000

# ============================================================
# DELETE POLICY
# ============================================================

TOP_DISTANCE2_WORDS=15000

MAX_DELETE_DISTANCE=2

MAX_DELETE_WORD_LENGTH=24

MAX_CANDIDATES_PER_DELETE=3

# ============================================================
# CORE VOCABULARY
#
# These words are mandatory.
#
# Spanish deliberately contains accented forms and Argentine
# voseo forms. Do NOT add unaccented variants such as "manana"
# as replacements for "mañana".
# ============================================================

CORE_ES_AR_WORDS=(
    "a"
    "al"
    "algo"
    "alguien"
    "ahora"
    "allá"
    "acá"
    "también"
    "bien"
    "cada"
    "casa"
    "cómo"
    "cuando"
    "cuándo"
    "dónde"
    "donde"
    "debería"
    "decís"
    "día"
    "días"
    "estás"
    "este"
    "esto"
    "hacer"
    "hacés"
    "hay"
    "hola"
    "mañana"
    "más"
    "menos"
    "mismo"
    "muy"
    "necesito"
    "necesitás"
    "nunca"
    "para"
    "pasaría"
    "podés"
    "porque"
    "por"
    "qué"
    "querés"
    "sabés"
    "sé"
    "ser"
    "si"
    "sí"
    "sobre"
    "sos"
    "tenés"
    "tengo"
    "tiempo"
    "todo"
    "todos"
    "tu"
    "tú"
    "una"
    "uno"
    "vos"
    "voy"
    "ya"
    "yo"
    "venís"
    "sentís"
    "decir"
    "venir"
    "estar"
    "tener"
    "poder"
    "querer"
    "saber"
)

CORE_EN_WORDS=(
    "a"
    "about"
    "after"
    "again"
    "all"
    "also"
    "always"
    "and"
    "any"
    "are"
    "around"
    "because"
    "before"
    "being"
    "between"
    "but"
    "can"
    "could"
    "day"
    "do"
    "does"
    "done"
    "each"
    "even"
    "every"
    "for"
    "from"
    "get"
    "give"
    "go"
    "good"
    "have"
    "hello"
    "here"
    "how"
    "i"
    "if"
    "in"
    "into"
    "is"
    "it"
    "just"
    "know"
    "like"
    "make"
    "more"
    "most"
    "my"
    "need"
    "never"
    "new"
    "no"
    "not"
    "now"
    "of"
    "on"
    "one"
    "only"
    "or"
    "other"
    "our"
    "out"
    "please"
    "really"
    "right"
    "say"
    "see"
    "she"
    "should"
    "so"
    "some"
    "than"
    "that"
    "the"
    "their"
    "there"
    "they"
    "this"
    "time"
    "to"
    "today"
    "tomorrow"
    "too"
    "under"
    "up"
    "us"
    "very"
    "want"
    "was"
    "we"
    "well"
    "were"
    "what"
    "when"
    "where"
    "which"
    "who"
    "why"
    "will"
    "with"
    "would"
    "you"
    "your"
)

CORE_DE_WORDS=(
    "aber"
    "alle"
    "als"
    "also"
    "auch"
    "auf"
    "aus"
    "bei"
    "bin"
    "bis"
    "bitte"
    "da"
    "danke"
    "das"
    "dass"
    "dein"
    "der"
    "die"
    "dies"
    "diese"
    "du"
    "ein"
    "eine"
    "er"
    "es"
    "für"
    "ganz"
    "gehen"
    "gut"
    "haben"
    "hallo"
    "hier"
    "ich"
    "immer"
    "in"
    "ist"
    "ja"
    "jetzt"
    "kann"
    "kein"
    "kommen"
    "können"
    "machen"
    "man"
    "mehr"
    "mein"
    "mit"
    "morgen"
    "möglich"
    "möglicherweise"
    "müssen"
    "nach"
    "nicht"
    "noch"
    "nur"
    "oder"
    "sehr"
    "sein"
    "sie"
    "sind"
    "so"
    "schon"
    "über"
    "und"
    "uns"
    "vom"
    "von"
    "war"
    "was"
    "wenn"
    "wer"
    "wie"
    "wieder"
    "will"
    "wir"
    "wo"
    "zu"
    "zum"
    "zur"
    "entschuldigung"
    "wahrscheinlich"
)

# ============================================================
# REQUIRED COMMANDS
# ============================================================

require_command() {
    local command_name="$1"

    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo ""
        echo "============================================================"
        echo " ERROR: required command is missing"
        echo "============================================================"
        echo ""
        echo "Command:"
        echo "  ${command_name}"
        echo ""
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
require_command tar
require_command find
require_command comm
require_command python3

# ============================================================
# HUNSPELL
# ============================================================

if ! command -v hunspell >/dev/null 2>&1; then

    echo ""
    echo "============================================================"
    echo " ERROR: Hunspell is not installed"
    echo "============================================================"
    echo ""
    echo "The dictionary generator requires the Hunspell executable."
    echo ""
    echo "Install Hunspell in GitHub Actions before running Gradle."
    echo ""
    echo "Example:"
    echo ""
    echo "  sudo apt-get update"
    echo "  sudo apt-get install -y hunspell"
    echo ""

    exit 1
fi

HUNSPELL_BIN="$(command -v hunspell)"

echo ""
echo "Hunspell executable:"
echo "  ${HUNSPELL_BIN}"

if ! "${HUNSPELL_BIN}" -v >/dev/null 2>&1; then

    echo ""
    echo "ERROR: Hunspell executable was found but could not be executed."
    echo "Path:"
    echo "  ${HUNSPELL_BIN}"

    exit 1
fi

echo ""
echo "Hunspell:"
"${HUNSPELL_BIN}" -v || true

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

        echo ""
        echo "ERROR: empty download:"
        echo "  ${destination}"

        exit 1
    fi
}

# ============================================================
# DOWNLOAD HUNSPELL DICTIONARIES
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
# DOWNLOAD FREQUENCY SOURCES
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

# IMPORTANT:
# Do not use "find | head" here.
# With set -o pipefail, find can receive SIGPIPE from head.
LEIPZIG_WORDS_FILE="$(
    find \
        "${LEIPZIG_DIR}" \
        -type f \
        -name '*-words.txt' \
        -print \
        -quit
)"

if [[ -z "${LEIPZIG_WORDS_FILE}" ]]; then

    echo ""
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

    python3 \
        - "${input}" \
        "${output}" \
        "${SOURCE_MAX_ENTRIES}" \
        <<'PY'
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

        echo ""
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

    python3 \
        - "${input}" \
        "${output}" \
        "${SOURCE_MAX_ENTRIES}" \
        <<'PY'
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

        echo ""
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

extract_hunspell_base_words() {

    local language="$1"
    local dic="$2"
    local output="$3"

    echo ""
    echo "============================================================"
    echo " Extracting Hunspell base words: ${language}"
    echo "============================================================"

    python3 \
        - "${dic}" \
        "${output}" \
        <<'PY'
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

        if first:

            first = False

            if line.isdigit():
                continue

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

        echo ""
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
# WRITE CORE WORD LISTS
# ============================================================

printf '%s\n' \
    "${CORE_ES_AR_WORDS[@]}" \
    > "${ES_DIR}/core.txt"

printf '%s\n' \
    "${CORE_EN_WORDS[@]}" \
    > "${EN_DIR}/core.txt"

printf '%s\n' \
    "${CORE_DE_WORDS[@]}" \
    > "${DE_DIR}/core.txt"

# ============================================================
# BUILD CANDIDATES
#
# Order:
#
#   1. Frequency vocabulary
#   2. Core vocabulary
#   3. Hunspell base vocabulary
#
# Candidate order remains the priority order.
# ============================================================

build_candidates() {

    local language="$1"
    local frequency="$2"
    local core="$3"
    local hunspell="$4"
    local output="$5"

    echo ""
    echo "============================================================"
    echo " Building candidate vocabulary: ${language}"
    echo "============================================================"

    python3 \
        - "${frequency}" \
        "${core}" \
        "${hunspell}" \
        "${output}" \
        <<'PY'
import sys
import unicodedata

frequency_file = sys.argv[1]
core_file = sys.argv[2]
hunspell_file = sys.argv[3]
output_file = sys.argv[4]


def normalize(word):
    return unicodedata.normalize(
        "NFC",
        word.strip().lower()
    )


def valid_token(word):

    if not word:
        return False

    has_letter = False

    for char in word:

        if char.isalpha():
            has_letter = True
            continue

        if char in "'’-":
            continue

        return False

    return has_letter


seen = set()
count = 0

with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

    for source_file in (
        frequency_file,
        core_file,
        hunspell_file,
    ):

        with open(
            source_file,
            encoding="utf-8",
            errors="replace"
        ) as f:

            for raw in f:

                word = normalize(raw)

                if not valid_token(word):
                    continue

                if word in seen:
                    continue

                seen.add(word)

                out.write(word + "\n")

                count += 1

print(
    f"Candidate vocabulary: {count}"
)
PY

    if [[ ! -s "${output}" ]]; then

        echo ""
        echo "ERROR: candidate vocabulary is empty:"
        echo "  ${language}"

        exit 1
    fi

    echo ""
    echo "Candidate vocabulary:"
    echo "  $(wc -l < "${output}")"
}

build_candidates \
    "es-AR" \
    "${ES_DIR}/frequency.normalized" \
    "${ES_DIR}/core.txt" \
    "${ES_DIR}/hunspell.base" \
    "${ES_DIR}/candidates.txt"

build_candidates \
    "en-en" \
    "${EN_DIR}/frequency.normalized" \
    "${EN_DIR}/core.txt" \
    "${EN_DIR}/hunspell.base" \
    "${EN_DIR}/candidates.txt"

build_candidates \
    "de-de" \
    "${DE_DIR}/frequency.normalized" \
    "${DE_DIR}/core.txt" \
    "${DE_DIR}/hunspell.base" \
    "${DE_DIR}/candidates.txt"

# ============================================================
# CREATE HUNSPELL CORE OVERLAY
#
# WHY:
#
# The upstream locale dictionary can legitimately lack a
# specific mandatory word/form.
#
# Spanish Argentine voseo is especially important here:
#
#   podés
#   tenés
#   querés
#   sabés
#   hacés
#   decís
#   venís
#   sentís
#
# Instead of bypassing Hunspell, we extend the dictionary with
# exact mandatory words and then validate the complete candidate
# list against the resulting Hunspell dictionary.
#
# This means mandatory words still pass through Hunspell.
#
# We DO NOT add typo variants such as:
#
#   manana
#
# Therefore "mañana" remains valid while "manana" remains
# rejected.
# ============================================================

make_hunspell_overlay() {

    local language="$1"
    local source_dic="$2"
    local core_file="$3"
    local output_base="$4"

    echo ""
    echo "============================================================"
    echo " Creating Hunspell core overlay: ${language}"
    echo "============================================================"

    python3 \
        - "${source_dic}" \
        "${core_file}" \
        "${output_base}.dic" \
        <<'PY'
import sys
import unicodedata

source_dic = sys.argv[1]
core_file = sys.argv[2]
destination = sys.argv[3]

with open(
    source_dic,
    encoding="utf-8",
    errors="replace"
) as f:
    lines = f.read().splitlines()

if not lines:
    raise SystemExit(
        "Hunspell dictionary is empty"
    )

first = lines[0].strip()

if first.isdigit():
    original_count = int(first)
    body = lines[1:]
else:
    original_count = len(lines)
    body = lines

existing = set()

for line in body:

    line = line.strip()

    if not line:
        continue

    word = line.split("/", 1)[0]

    word = unicodedata.normalize(
        "NFC",
        word.lower()
    )

    existing.add(word)

extra = []

with open(
    core_file,
    encoding="utf-8",
    errors="replace"
) as f:

    for raw in f:

        word = unicodedata.normalize(
            "NFC",
            raw.strip().lower()
        )

        if not word:
            continue

        if word in existing:
            continue

        existing.add(word)
        extra.append(word)

with open(
    destination,
    "w",
    encoding="utf-8"
) as out:

    out.write(
        str(original_count + len(extra))
    )
    out.write("\n")

    for line in body:

        out.write(line)
        out.write("\n")

    for word in extra:

        out.write(word)
        out.write("\n")

print(
    f"Core overlay additions: {len(extra)}"
)
PY

    cp \
        "${source_dic%/*}/index.aff" \
        "${output_base}.aff"

    if [[ ! -s "${output_base}.dic" ]]; then
        echo "ERROR: Hunspell overlay .dic is empty: ${language}"
        exit 1
    fi

    if [[ ! -s "${output_base}.aff" ]]; then
        echo "ERROR: Hunspell overlay .aff is empty: ${language}"
        exit 1
    fi
}

make_hunspell_overlay \
    "es-AR" \
    "${ES_DIR}/index.dic" \
    "${ES_DIR}/core.txt" \
    "${ES_DIR}/combined"

make_hunspell_overlay \
    "en-en" \
    "${EN_DIR}/index.dic" \
    "${EN_DIR}/core.txt" \
    "${EN_DIR}/combined"

make_hunspell_overlay \
    "de-de" \
    "${DE_DIR}/index.dic" \
    "${DE_DIR}/core.txt" \
    "${DE_DIR}/combined"

# ============================================================
# HUNSPELL BATCH VALIDATION
#
# IMPORTANT:
#
# We use:
#
#   -a
#   -i UTF-8
#
# instead of -G.
#
# -a gives one diagnostic result per input word:
#
#   *  accepted dictionary word
#   +  accepted through affix/root analysis
#   -  accepted compound
#   &  rejected
#   #  rejected
#
# This lets us preserve the ORIGINAL candidate line and its
# original NFC representation rather than trying to reconstruct
# vocabulary from Hunspell's filtered output.
# ============================================================

validate_candidates_with_hunspell() {

    local language="$1"
    local dictionary_base="$2"
    local candidates="$3"
    local output="$4"

    local status_file
    local candidate_count

    status_file="${WORK_DIR}/${language}.hunspell.status"

    rm -f \
        "${status_file}" \
        "${output}"

    echo ""
    echo "============================================================"
    echo " Validating vocabulary with Hunspell: ${language}"
    echo "============================================================"

    echo ""
    echo "Dictionary:"
    echo "  ${dictionary_base}.dic"
    echo "  ${dictionary_base}.aff"

    candidate_count="$(wc -l < "${candidates}")"

    echo ""
    echo "Candidates:"
    echo "  ${candidate_count}"

    # --------------------------------------------------------
    # One Hunspell process for the complete candidate list.
    #
    # Explicit UTF-8 is important because LC_ALL=C can otherwise
    # interact badly with Unicode input on CI systems.
    # --------------------------------------------------------

    "${HUNSPELL_BIN}" \
        -a \
        -i UTF-8 \
        -d "${dictionary_base}" \
        < "${candidates}" \
        > "${status_file}"

    if [[ ! -s "${status_file}" ]]; then

        echo ""
        echo "ERROR: Hunspell produced no diagnostic output:"
        echo "  ${language}"

        exit 1
    fi

    # --------------------------------------------------------
    # Convert Hunspell's per-word statuses back into the
    # original candidate vocabulary.
    #
    # We intentionally DO NOT use a set of Hunspell's output
    # words. That was the source of the previous mismatch:
    #
    #   Candidates:       ~500k
    #   Hunspell accepted: ~205k
    #   Validated:        ~186k
    #
    # Status-based mapping preserves the original candidate.
    # --------------------------------------------------------

    python3 \
        - "${candidates}" \
        "${status_file}" \
        "${output}" \
        <<'PY'
import sys
import unicodedata

candidates_file = sys.argv[1]
status_file = sys.argv[2]
output_file = sys.argv[3]


def normalize(word):
    return unicodedata.normalize(
        "NFC",
        word.strip().lower()
    )


with open(
    candidates_file,
    encoding="utf-8",
    errors="replace"
) as f:

    candidates = [
        normalize(line)
        for line in f
        if line.strip()
    ]


accepted_indexes = []

status_count = 0

started = False

with open(
    status_file,
    encoding="utf-8",
    errors="replace"
) as f:

    for raw in f:

        line = raw.rstrip("\r\n")

        # Hunspell prints a version/header line before the
        # per-word diagnostics.
        if not started:

            if line.startswith(
                ("*", "+", "-", "&", "#", "?")
            ):
                started = True
            else:
                continue

        if not line:
            continue

        marker = line[0]

        if marker not in (
            "*",
            "+",
            "-",
            "&",
            "#",
            "?",
        ):
            continue

        current_index = status_count

        status_count += 1

        if marker in (
            "*",
            "+",
            "-",
        ):
            accepted_indexes.append(
                current_index
            )


if status_count != len(candidates):

    raise SystemExit(
        "Hunspell status count mismatch: "
        f"candidates={len(candidates)} "
        f"statuses={status_count}"
    )


seen = set()

validated_count = 0

with open(
    output_file,
    "w",
    encoding="utf-8"
) as out:

    for index in accepted_indexes:

        word = candidates[index]

        if not word:
            continue

        if word in seen:
            continue

        seen.add(word)

        out.write(word)
        out.write("\n")

        validated_count += 1


print(
    f"Hunspell accepted: {validated_count}"
)

print(
    f"Validated vocabulary: {validated_count}"
)
PY

    # --------------------------------------------------------
    # HARD GUARD:
    #
    # Never allow the next stage to run without validated.txt.
    # This directly prevents the failure:
    #
    #   cat: .../validated.txt: No such file or directory
    # --------------------------------------------------------

    if [[ ! -f "${output}" ]]; then

        echo ""
        echo "ERROR: Hunspell validation did not create validated.txt:"
        echo "  ${output}"

        exit 1
    fi

    if [[ ! -s "${output}" ]]; then

        echo ""
        echo "ERROR: validated vocabulary is empty:"
        echo "  ${output}"

        exit 1
    fi

    echo ""
    echo "Validated words:"
    echo "  $(wc -l < "${output}")"
}

# ============================================================
# VALIDATE ALL THREE LANGUAGES
# ============================================================

validate_candidates_with_hunspell \
    "es-AR" \
    "${ES_DIR}/combined" \
    "${ES_DIR}/candidates.txt" \
    "${ES_DIR}/validated.txt"

validate_candidates_with_hunspell \
    "en-en" \
    "${EN_DIR}/combined" \
    "${EN_DIR}/candidates.txt" \
    "${EN_DIR}/validated.txt"

validate_candidates_with_hunspell \
    "de-de" \
    "${DE_DIR}/combined" \
    "${DE_DIR}/candidates.txt" \
    "${DE_DIR}/validated.txt"

# ============================================================
# VERIFY VALIDATED FILES BEFORE GENERATING DICTIONARIES
# ============================================================

echo ""
echo "============================================================"
echo " Checking validated vocabulary files"
echo "============================================================"

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    validated="${WORK_DIR}/${language}/validated.txt"

    if [[ ! -f "${validated}" ]]; then

        echo ""
        echo "ERROR: validated vocabulary file does not exist:"
        echo "  ${validated}"

        exit 1
    fi

    if [[ ! -s "${validated}" ]]; then

        echo ""
        echo "ERROR: validated vocabulary file is empty:"
        echo "  ${validated}"

        exit 1
    fi

    echo "OK: ${language}: $(wc -l < "${validated}") validated words"

done

# ============================================================
# WRITE RUNTIME DICTIONARIES
# ============================================================

write_dictionary() {

    local language="$1"
    local words="$2"
    local output="$3"

    echo ""
    echo "============================================================"
    echo " Generating dictionary: ${language}"
    echo "============================================================"

    # --------------------------------------------------------
    # HARD GUARDS.
    #
    # Never call cat on a missing validated file.
    # --------------------------------------------------------

    if [[ ! -f "${words}" ]]; then

        echo ""
        echo "ERROR: validated vocabulary is missing:"
        echo "  ${words}"

        exit 1
    fi

    if [[ ! -s "${words}" ]]; then

        echo ""
        echo "ERROR: validated vocabulary is empty:"
        echo "  ${words}"

        exit 1
    fi

    {
        printf '%s\n' \
            "#POCKETBOARD-DICT-1"

        cat \
            "${words}"

    } > "${output}"

    if [[ ! -s "${output}" ]]; then

        echo ""
        echo "ERROR: generated dictionary is empty:"
        echo "  ${output}"

        exit 1
    fi

    local word_count
    local byte_count

    word_count="$(
        tail \
            -n +2 \
            "${output}" |
        wc -l
    )"

    byte_count="$(
        wc -c < "${output}"
    )"

    echo ""
    echo "Words: ${word_count}"
    echo "Bytes: ${byte_count}"
}

write_dictionary \
    "es-AR" \
    "${ES_DIR}/validated.txt" \
    "${OUTPUT_DIR}/es-AR.dict"

write_dictionary \
    "en-en" \
    "${EN_DIR}/validated.txt" \
    "${OUTPUT_DIR}/en-en.dict"

write_dictionary \
    "de-de" \
    "${DE_DIR}/validated.txt" \
    "${OUTPUT_DIR}/de-de.dict"

# ============================================================
# DELETE INDEX
#
# IMPORTANT:
#
# Deletes are generated ONLY from validated .dict words.
#
# A delete key is NOT a dictionary word.
#
# Example:
#
#   dictionary:
#       mañana
#
#   delete:
#       manana
#
# "manana" may exist in .deletes as a search/correction key.
# It will NOT be inserted into .dict unless it passed Hunspell.
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
    echo "============================================================"

    echo ""
    echo "Budget:"
    echo "  ${budget} bytes"

    echo ""
    echo "Distance 2 words:"
    echo "  ${TOP_DISTANCE2_WORDS}"

    echo ""
    echo "Maximum word length:"
    echo "  ${MAX_DELETE_WORD_LENGTH}"

    echo ""
    echo "Maximum candidates/delete:"
    echo "  ${MAX_CANDIDATES_PER_DELETE}"

    # --------------------------------------------------------
    # ONLY VALIDATED WORDS.
    # --------------------------------------------------------

    if [[ ! -f "${dictionary}" ]]; then

        echo ""
        echo "ERROR: dictionary missing before delete generation:"
        echo "  ${dictionary}"

        exit 1
    fi

    tail \
        -n +2 \
        "${dictionary}" \
        > "${words_file}"

    if [[ ! -s "${words_file}" ]]; then

        echo ""
        echo "ERROR: no validated words available for delete index:"
        echo "  ${language}"

        exit 1
    fi

    # --------------------------------------------------------
    # Header size
    # --------------------------------------------------------

    header_bytes="$(
        printf \
            "#POCKETBOARD-DELETES-1\n#MAX_DISTANCE=%s\n#MAX_WORD_LENGTH=%s\n#MAX_CANDIDATES=%s\n" \
            "${MAX_DELETE_DISTANCE}" \
            "${MAX_DELETE_WORD_LENGTH}" \
            "${MAX_CANDIDATES_PER_DELETE}" |
        wc -c
    )"

    mapping_budget=$(
        (
            printf '%s\n' \
                "$((budget - header_bytes))"
        )
    )

    if (( mapping_budget <= 0 )); then

        echo ""
        echo "ERROR: delete budget is smaller than the header."
        echo "Budget:"
        echo "  ${budget}"
        echo "Header:"
        echo "  ${header_bytes}"

        exit 1
    fi

    echo ""
    echo "Header bytes:"
    echo "  ${header_bytes}"

    echo ""
    echo "Mapping budget:"
    echo "  ${mapping_budget}"

    # --------------------------------------------------------
    # Generate delete mappings.
    # --------------------------------------------------------

    python3 \
        - "${words_file}" \
        "${pairs_file}" \
        "${mapping_budget}" \
        "${TOP_DISTANCE2_WORDS}" \
        "${MAX_DELETE_DISTANCE}" \
        "${MAX_DELETE_WORD_LENGTH}" \
        "${MAX_CANDIDATES_PER_DELETE}" \
        <<'PY'
import sys

words_file = sys.argv[1]
output_file = sys.argv[2]
budget = int(sys.argv[3])
top_distance2_words = int(sys.argv[4])
max_distance = int(sys.argv[5])
max_word_length = int(sys.argv[6])
max_candidates = int(sys.argv[7])


def generate_deletes(
    word,
    distance
):

    result = set()

    if distance <= 0:
        return result

    current_level = {word}

    for _ in range(distance):

        next_level = set()

        for current in current_level:

            if not current:
                continue

            for index in range(len(current)):

                candidate = (
                    current[:index] +
                    current[index + 1:]
                )

                if not candidate:
                    continue

                result.add(candidate)
                next_level.add(candidate)

        current_level = next_level

    return result


words = []

with open(
    words_file,
    encoding="utf-8",
    errors="replace"
) as f:

    for raw in f:

        word = raw.strip()

        if word:
            words.append(word)


buckets = {}

used_bytes = 0
accepted_mappings = 0

for rank, word in enumerate(words):

    if len(word) > max_word_length:
        continue

    if rank < top_distance2_words:

        distance = min(
            2,
            max_distance
        )

    else:

        distance = 1

    deletes = generate_deletes(
        word,
        distance
    )

    for delete in deletes:

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

        echo ""
        echo "ERROR: delete index produced no mappings:"
        echo "  ${language}"

        exit 1
    fi

    # --------------------------------------------------------
    # Write final delete file.
    # --------------------------------------------------------

    {
        printf '%s\n' \
            "#POCKETBOARD-DELETES-1"

        printf '%s\n' \
            "#MAX_DISTANCE=${MAX_DELETE_DISTANCE}"

        printf '%s\n' \
            "#MAX_WORD_LENGTH=${MAX_DELETE_WORD_LENGTH}"

        printf '%s\n' \
            "#MAX_CANDIDATES=${MAX_CANDIDATES_PER_DELETE}"

        cat \
            "${pairs_file}"

    } > "${output}"

    local size
    local mappings
    local keys

    size="$(
        wc -c < "${output}"
    )"

    mappings="$(
        tail \
            -n +5 \
            "${output}" |
        wc -l
    )"

    keys="$(
        tail \
            -n +5 \
            "${output}" |
        cut -f1 |
        sort -u |
        wc -l
    )"

    echo ""
    echo "${language}:"
    echo "  Delete keys:     ${keys}"
    echo "  Delete mappings: ${mappings}"
    echo "  Delete bytes:    ${size}"

    if (( size > budget )); then

        echo ""
        echo "ERROR: delete index exceeded budget."
        echo "  Language: ${language}"
        echo "  Size:     ${size}"
        echo "  Budget:   ${budget}"

        exit 1
    fi

    echo "  Budget status:   OK"
}

# ============================================================
# GENERATE DELETE FILES
# ============================================================

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

    echo ""
    echo "Generating metadata:"
    echo "  ${language}"

    {
        printf '%s\n' \
            "#POCKETBOARD-META-1"

        printf '%s\n' \
            "# REP"

        grep \
            -E '^REP([[:space:]]|$)' \
            "${aff}" ||
            true

        printf '%s\n' \
            "# KEY"

        grep \
            -E '^KEY([[:space:]]|$)' \
            "${aff}" ||
            true

        printf '%s\n' \
            "# TRY"

        grep \
            -E '^TRY([[:space:]]|$)' \
            "${aff}" ||
            true

        printf '%s\n' \
            "# PHONE"

        grep \
            -E '^PHONE([[:space:]]|$)' \
            "${aff}" ||
            true

        printf '%s\n' \
            "# ph"

        grep \
            -E '^ph:' \
            "${aff}" ||
            true

        printf '%s\n' \
            "# NOSUGGEST"

        grep \
            -E '^NOSUGGEST([[:space:]]|$)' \
            "${aff}" ||
            true

        printf '%s\n' \
            "# SUBSTANDARD"

        grep \
            -E '^SUBSTANDARD([[:space:]]|$)' \
            "${aff}" ||
            true

    } > "${output}"

    if [[ ! -s "${output}" ]]; then

        echo ""
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
        <(
            tail \
                -n +2 \
                "${dictionary}"
        )
    then

        echo "OK: ${language}: ${word}"

    else

        echo ""
        echo "ERROR: required word not selected"
        echo "  Language: ${language}"
        echo "  Word:     ${word}"

        exit 1
    fi
}

check_word_absent() {

    local language="$1"
    local word="$2"

    local dictionary="${OUTPUT_DIR}/${language}.dict"

    if grep \
        -Fqx \
        "${word}" \
        <(
            tail \
                -n +2 \
                "${dictionary}"
        )
    then

        echo ""
        echo "ERROR: invalid word selected"
        echo "  Language: ${language}"
        echo "  Word:     ${word}"

        exit 1

    else

        echo "OK: rejected: ${language}: ${word}"

    fi
}

echo ""
echo "============================================================"
echo " Word diagnostics"
echo "============================================================"

# ------------------------------------------------------------
# Spanish
# ------------------------------------------------------------

check_word "es-AR" "mañana"
check_word "es-AR" "pasaría"
check_word "es-AR" "debería"
check_word "es-AR" "vos"
check_word "es-AR" "tenés"
check_word "es-AR" "podés"
check_word "es-AR" "hacés"
check_word "es-AR" "decís"
check_word "es-AR" "venís"
check_word "es-AR" "sentís"
check_word "es-AR" "acá"
check_word "es-AR" "cuándo"
check_word "es-AR" "dónde"

# This MUST NOT become a dictionary word merely because it can
# be a useful delete/correction key.
check_word_absent "es-AR" "manana"

# ------------------------------------------------------------
# English
# ------------------------------------------------------------

check_word "en-en" "the"
check_word "en-en" "have"
check_word "en-en" "hello"

check_word_absent "en-en" "helo"

# ------------------------------------------------------------
# German
# ------------------------------------------------------------

check_word "de-de" "ich"
check_word "de-de" "nicht"
check_word "de-de" "morgen"
check_word "de-de" "entschuldigung"
check_word "de-de" "wahrscheinlich"
check_word "de-de" "möglicherweise"

# ============================================================
# VALIDATE GENERATED ASSETS
# ============================================================

validate_generated_asset() {

    local file="$1"
    local expected_header="$2"

    if [[ ! -s "${file}" ]]; then

        echo ""
        echo "ERROR: generated asset is empty:"
        echo "  ${file}"

        exit 1
    fi

    local header

    header="$(
        head \
            -n 1 \
            "${file}" |
        tr -d '\r'
    )"

    if [[ "${header}" != "${expected_header}" ]]; then

        echo ""
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
# VALIDATE DICTIONARY TOKENS
#
# IMPORTANT:
#
# This uses Python directly with the file as an argument.
#
# Do NOT use:
#
#   tail ... | python3 - <<'PY'
#
# because the heredoc occupies stdin and the piped data never
# reaches the Python program.
# ============================================================

validate_dictionary_tokens() {

    local language="$1"
    local dictionary="$2"

    local invalid_file

    invalid_file="${WORK_DIR}/${language}.invalid.tokens"

    rm -f \
        "${invalid_file}"

    echo ""
    echo "Checking dictionary format: ${language}"

    python3 \
        - "${dictionary}" \
        > "${invalid_file}" \
        <<'PY'
import sys

dictionary = sys.argv[1]

with open(
    dictionary,
    encoding="utf-8",
    errors="replace"
) as f:

    for raw in f:

        word = raw.rstrip(
            "\n\r"
        )

        if not word:
            continue

        if word.startswith("#"):
            continue

        valid = False

        for char in word:

            if char.isalpha():

                valid = True

                continue

            if char in "'’-":
                continue

            valid = False
            break

        if not valid:
            print(word)
PY

    local invalid_count

    invalid_count="$(
        wc -l < "${invalid_file}"
    )"

    echo "Invalid dictionary entries: ${invalid_count}"

    if (( invalid_count > 0 )); then

        echo ""
        echo "ERROR: invalid dictionary entries found:"

        head \
            -n 50 \
            "${invalid_file}"

        exit 1
    fi

    echo "OK: ${language}.dict contains only valid token format"
}

validate_dictionary_tokens \
    "es-AR" \
    "${OUTPUT_DIR}/es-AR.dict"

validate_dictionary_tokens \
    "en-en" \
    "${OUTPUT_DIR}/en-en.dict"

validate_dictionary_tokens \
    "de-de" \
    "${OUTPUT_DIR}/de-de.dict"

# ============================================================
# VERIFY DELETE TARGETS
#
# Every target word referenced by .deletes MUST exist in .dict.
#
# Delete keys themselves do NOT have to exist in .dict.
# ============================================================

validate_delete_targets() {

    local language="$1"
    local dictionary="$2"
    local deletes="$3"

    local dictionary_words
    local targets
    local invalid_targets

    dictionary_words="${WORK_DIR}/${language}.delete.dictionary.words"
    targets="${WORK_DIR}/${language}.delete.targets"
    invalid_targets="${WORK_DIR}/${language}.invalid.delete.targets"

    tail \
        -n +2 \
        "${dictionary}" |
        sort -u \
        > "${dictionary_words}"

    tail \
        -n +5 \
        "${deletes}" |
        cut \
            -f2 |
        sort -u \
        > "${targets}"

    comm \
        -23 \
        "${targets}" \
        "${dictionary_words}" \
        > "${invalid_targets}"

    local invalid_count

    invalid_count="$(
        wc -l < "${invalid_targets}"
    )"

    echo ""
    echo "Delete target validation: ${language}"
    echo "  Unique targets: ${targets}"
    echo "  Invalid targets: ${invalid_count}"

    if (( invalid_count > 0 )); then

        echo ""
        echo "ERROR: delete index references words that are not in .dict:"

        head \
            -n 50 \
            "${invalid_targets}"

        exit 1
    fi

    echo "OK: all delete targets exist in ${language}.dict"
}

validate_delete_targets \
    "es-AR" \
    "${OUTPUT_DIR}/es-AR.dict" \
    "${OUTPUT_DIR}/es-AR.deletes"

validate_delete_targets \
    "en-en" \
    "${OUTPUT_DIR}/en-en.dict" \
    "${OUTPUT_DIR}/en-en.deletes"

validate_delete_targets \
    "de-de" \
    "${OUTPUT_DIR}/de-de.dict" \
    "${OUTPUT_DIR}/de-de.deletes"

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
        tail \
            -n +2 \
            "${dictionary}" |
        wc -l
    )"

    delete_mappings="$(
        tail \
            -n +5 \
            "${deletes}" |
        wc -l
    )"

    # IMPORTANT:
    #
    # Correct Bash arithmetic.
    #
    # The previous broken implementation attempted to execute
    # arithmetic expressions as commands.
    #

    language_total=$(
        (
            echo $(
                (
                    dictionary_size +
                    delete_size +
                    metadata_size
                )
            )
        )
    )

    # Normalize through explicit arithmetic below.
    language_total=$(
        (
            printf '%s' \
                "$((dictionary_size + delete_size + metadata_size))"
        )
    )

    TOTAL_DICTIONARY_BYTES=$(
        (
            printf '%s' \
                "$((TOTAL_DICTIONARY_BYTES + dictionary_size))"
        )
    )

    TOTAL_DELETE_BYTES=$(
        (
            printf '%s' \
                "$((TOTAL_DELETE_BYTES + delete_size))"
        )
    )

    TOTAL_METADATA_BYTES=$(
        (
            printf '%s' \
                "$((TOTAL_METADATA_BYTES + metadata_size))"
        )
    )

    echo ""
    echo "${language}"
    echo "  Words:           ${dictionary_words}"
    echo "  Dictionary:      ${dictionary_size} bytes"
    echo "  Delete index:    ${delete_size} bytes"
    echo "  Delete mappings: ${delete_mappings}"
    echo "  Metadata:        ${metadata_size} bytes"
    echo "  Total:           ${language_total} bytes"

done

# ============================================================
# FINAL TOTALS
# ============================================================

TOTAL_GENERATED_BYTES=$(
    (
        printf '%s' \
            "$((TOTAL_DICTIONARY_BYTES + TOTAL_DELETE_BYTES + TOTAL_METADATA_BYTES))"
    )
)

TOTAL_MIB=$(
    (
        printf '%s' \
            "$((TOTAL_GENERATED_BYTES / 1024 / 1024))"
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
echo "Approximate data: ${TOTAL_MIB} MiB"

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

# ============================================================
# FINAL SANITY CHECK
# ============================================================

echo ""
echo "============================================================"
echo " Final sanity checks"
echo "============================================================"

for language in \
    "es-AR" \
    "en-en" \
    "de-de"
do

    dictionary="${OUTPUT_DIR}/${language}.dict"
    deletes="${OUTPUT_DIR}/${language}.deletes"
    metadata="${OUTPUT_DIR}/${language}.meta"

    if [[ ! -s "${dictionary}" ]]; then

        echo "ERROR: empty dictionary: ${language}"

        exit 1
    fi

    if [[ ! -s "${deletes}" ]]; then

        echo "ERROR: empty delete index: ${language}"

        exit 1
    fi

    if [[ ! -s "${metadata}" ]]; then

        echo "ERROR: empty metadata: ${language}"

        exit 1
    fi

    echo "OK: ${language}"

done

echo ""
echo "============================================================"
echo " Dictionary generation completed successfully"
echo "============================================================"
