#!/usr/bin/env bash

set -euo pipefail

###############################################################################
# PocketBoard dictionary generator
#
# Runtime dictionary builder for GitHub Actions / Gradle
#
# Design goals:
#   - No dictionary-data/<language>/dictionary.dic required.
#   - Source dictionaries downloaded at build time.
#   - Hunspell .dic files used as lexical sources.
#   - NO "hunspell -a" invocation for every candidate word.
#   - Unicode/NFC preserved.
#   - Accents, ñ and ü preserved.
#   - Explicit es-AR voseo/core vocabulary.
#   - Frequency-first vocabulary selection.
#   - Strict token validation.
#   - Distance-1 deletes for the complete dictionary.
#   - Distance-2 deletes only for high-value words.
#   - Delete targets must always exist in .dict.
#   - Deterministic output.
###############################################################################

SCRIPT_NAME="PocketBoard dictionary generator"

###############################################################################
# Paths
###############################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_ROOT="${BUILD_ROOT:-${PROJECT_ROOT}/build/pocketboard-dictionaries}"

SOURCE_ROOT="${BUILD_ROOT}/sources"
HUNSPELL_ROOT="${BUILD_ROOT}/hunspell"
FREQUENCY_ROOT="${BUILD_ROOT}/frequency"
LEIPZIG_ROOT="${BUILD_ROOT}/leipzig}"

WORK_ROOT="${BUILD_ROOT}/work"
OUTPUT_ROOT="${BUILD_ROOT}/generated"

ASSETS_ROOT="${PROJECT_ROOT}/app/src/main/assets/dictionaries"

###############################################################################
# Configuration
###############################################################################

MAX_WORDS="${MAX_WORDS:-180000}"

DIST2_WORDS="${DIST2_WORDS:-12000}"

MAX_DELETES_PER_WORD="${MAX_DELETES_PER_WORD:-96}"

MAX_DELETE_ENTRIES="${MAX_DELETE_ENTRIES:-2500000}"

MIN_WORD_LEN="${MIN_WORD_LEN:-2}"

MAX_WORD_LEN="${MAX_WORD_LEN:-40}"

PYTHON_BIN="${PYTHON_BIN:-python3}"

CURL_BIN="${CURL_BIN:-curl}"

###############################################################################
# Languages
###############################################################################

LANGUAGES=(
    "es-AR"
    "en"
    "de"
)

###############################################################################
# Runtime Hunspell sources
###############################################################################

HUNSPELL_ES_URL="${HUNSPELL_ES_URL:-https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/es/index.dic}"
HUNSPELL_EN_URL="${HUNSPELL_EN_URL:-https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/en/index.dic}"
HUNSPELL_DE_URL="${HUNSPELL_DE_URL:-https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/de/index.dic}"

###############################################################################
# Runtime frequency sources
###############################################################################

FREQUENCY_ES_URL="${FREQUENCY_ES_URL:-https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_50k.txt}"
FREQUENCY_EN_URL="${FREQUENCY_EN_URL:-https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt}"
FREQUENCY_DE_URL="${FREQUENCY_DE_URL:-https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_50k.txt}"

###############################################################################
# Utilities
###############################################################################

die() {
    echo
    echo "ERROR: $*" >&2
    exit 1
}

log() {
    printf '%s\n' "$*"
}

separator() {
    printf '\n============================================================\n'
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        die "Required command not found: $1"
}

###############################################################################
# Required commands
###############################################################################

require_command "$PYTHON_BIN"
require_command "$CURL_BIN"
require_command "sort"
require_command "cmp"
require_command "grep"
require_command "mktemp"

###############################################################################
# Create directories
###############################################################################

mkdir -p \
    "$SOURCE_ROOT" \
    "$HUNSPELL_ROOT" \
    "$FREQUENCY_ROOT" \
    "$LEIPZIG_ROOT" \
    "$WORK_ROOT" \
    "$OUTPUT_ROOT" \
    "$ASSETS_ROOT"

###############################################################################
# Temporary workspace
###############################################################################

TMP_DIR="$(mktemp -d)"

cleanup() {
    rm -rf "$TMP_DIR"
}

trap cleanup EXIT INT TERM

###############################################################################
# Download helper
###############################################################################

download_file() {
    local url="$1"
    local destination="$2"

    mkdir -p "$(dirname "$destination")"

    log "Downloading:"
    log "  ${url}"

    "$CURL_BIN" \
        --fail \
        --location \
        --retry 5 \
        --retry-delay 2 \
        --connect-timeout 30 \
        --max-time 300 \
        --silent \
        --show-error \
        --output "$destination" \
        "$url"

    [[ -s "$destination" ]] ||
        die "Downloaded file is empty: ${destination}"
}

###############################################################################
# Prepare Hunspell sources
###############################################################################

prepare_hunspell() {

    separator
    log "Preparing Hunspell dictionaries"
    separator

    download_file \
        "$HUNSPELL_ES_URL" \
        "${HUNSPELL_ROOT}/es-AR.dic"

    download_file \
        "$HUNSPELL_EN_URL" \
        "${HUNSPELL_ROOT}/en.dic"

    download_file \
        "$HUNSPELL_DE_URL" \
        "${HUNSPELL_ROOT}/de.dic"
}

###############################################################################
# Prepare frequency sources
###############################################################################

prepare_frequency() {

    separator
    log "Preparing frequency dictionaries"
    separator

    download_file \
        "$FREQUENCY_ES_URL" \
        "${FREQUENCY_ROOT}/es-AR.txt"

    download_file \
        "$FREQUENCY_EN_URL" \
        "${FREQUENCY_ROOT}/en.txt"

    download_file \
        "$FREQUENCY_DE_URL" \
        "${FREQUENCY_ROOT}/de.txt"
}

###############################################################################
# Curated core vocabulary
###############################################################################

prepare_core_files() {

    separator
    log "Preparing curated core vocabulary"
    separator

    mkdir -p "${SOURCE_ROOT}/core"

    cat > "${SOURCE_ROOT}/core/es-AR.txt" <<'EOF'
vos
tenés
tenes
podés
podes
querés
queres
sabés
sabes
venís
venis
decís
decis
hacés
haces
mirás
miras
hablás
hablas
comés
comes
vivís
vivis
salís
salis
vení
veni
decime
haceme
mañana
también
qué
cómo
cuándo
dónde
quién
porque
porqué
día
días
más
sí
está
estás
están
acá
allá
después
así
sólo
EOF

    cat > "${SOURCE_ROOT}/core/en.txt" <<'EOF'
the
and
that
this
with
from
have
for
you
your
are
was
were
what
when
where
who
how
why
not
can
will
would
could
should
EOF

    cat > "${SOURCE_ROOT}/core/de.txt" <<'EOF'
der
die
das
und
ist
sind
nicht
ein
eine
einen
einem
einer
mit
von
für
auf
zu
den
dem
des
ich
du
er
sie
wir
ihr
was
wie
wann
wo
wer
EOF
}

###############################################################################
# Normalize frequency source
###############################################################################

normalize_frequency_file() {

    local lang="$1"
    local source="$2"
    local destination="$3"

    "$PYTHON_BIN" - "$source" "$destination" "$lang" <<'PY'
import sys
import unicodedata

source = sys.argv[1]
destination = sys.argv[2]
language = sys.argv[3]


def nfc(value):
    return unicodedata.normalize("NFC", value)


def valid(word):
    if not word:
        return False

    word = nfc(word.strip())

    if not word:
        return False

    if any(ch.isspace() for ch in word):
        return False

    if not any(ch.isalpha() for ch in word):
        return False

    for ch in word:
        category = unicodedata.category(ch)

        if ch.isalpha():
            continue

        if category.startswith("M"):
            continue

        if ch in ("'", "’", "-"):
            continue

        return False

    return True


rows = []

with open(
    source,
    "r",
    encoding="utf-8",
    errors="replace",
) as fh:

    for raw in fh:

        raw = raw.strip()

        if not raw or raw.startswith("#"):
            continue

        parts = raw.split()

        if not parts:
            continue

        word = nfc(parts[0])

        if not valid(word):
            continue

        score = 0.0

        if len(parts) >= 2:
            try:
                score = float(parts[1])
            except ValueError:
                score = 0.0

        rows.append((score, word))


rows.sort(key=lambda item: (-item[0], item[1]))

seen = set()

with open(
    destination,
    "w",
    encoding="utf-8",
    newline="\n",
) as fh:

    for score, word in rows:

        if word in seen:
            continue

        seen.add(word)

        fh.write(word)
        fh.write("\t")
        fh.write(str(score))
        fh.write("\n")
PY
}

###############################################################################
# Build one language
###############################################################################

build_language() {

    local lang="$1"
    local hunspell="$2"
    local frequency="$3"
    local core="$4"
    local output="$5"

    separator
    log "Building dictionary: ${lang}"
    separator

    mkdir -p "$output"

    "$PYTHON_BIN" - \
        "$frequency" \
        "$core" \
        "$hunspell" \
        "$output" \
        "$MAX_WORDS" \
        "$DIST2_WORDS" \
        "$MAX_DELETES_PER_WORD" \
        "$MAX_DELETE_ENTRIES" \
        "$MIN_WORD_LEN" \
        "$MAX_WORD_LEN" \
        "$lang" <<'PY'
import sys
import os
import unicodedata
from collections import defaultdict

###############################################################################
# Arguments
###############################################################################

if len(sys.argv) != 12:
    raise SystemExit(
        "Internal error: expected 11 arguments"
    )

frequency_path = sys.argv[1]
core_path = sys.argv[2]
hunspell_path = sys.argv[3]
output_dir = sys.argv[4]

MAX_WORDS = int(sys.argv[5])
DIST2_WORDS = int(sys.argv[6])
MAX_DELETES_PER_WORD = int(sys.argv[7])
MAX_DELETE_ENTRIES = int(sys.argv[8])
MIN_WORD_LEN = int(sys.argv[9])
MAX_WORD_LEN = int(sys.argv[10])
LANGUAGE = sys.argv[11]

###############################################################################
# Unicode
###############################################################################

def nfc(value):
    return unicodedata.normalize("NFC", value)

###############################################################################
# Strict token validation
###############################################################################

def is_valid_token(word):

    if not word:
        return False

    word = nfc(word.strip())

    if not word:
        return False

    if len(word) < MIN_WORD_LEN:
        return False

    if len(word) > MAX_WORD_LEN:
        return False

    if any(ch.isspace() for ch in word):
        return False

    if any(
        unicodedata.category(ch).startswith("C")
        for ch in word
    ):
        return False

    if not any(ch.isalpha() for ch in word):
        return False

    for ch in word:

        category = unicodedata.category(ch)

        if ch.isalpha():
            continue

        if category.startswith("M"):
            continue

        if ch in ("'", "’", "-"):
            continue

        return False

    return True

###############################################################################
# Hunspell parser
###############################################################################

def parse_hunspell_word(line):

    line = line.rstrip("\r\n")

    if not line:
        return None

    line = line.lstrip("\ufeff")

    # Hunspell dictionary header.
    if line.isdigit():
        return None

    # Ignore leading/trailing whitespace.
    line = line.strip()

    if not line:
        return None

    # Remove morphology information.
    line = line.split(None, 1)[0]

    if not line:
        return None

    # Remove Hunspell flags.
    if "/" in line:
        line = line.split("/", 1)[0]

    line = nfc(line.strip())

    if not is_valid_token(line):
        return None

    return line

###############################################################################
# Frequency data
###############################################################################

frequency = {}

if os.path.exists(frequency_path):

    with open(
        frequency_path,
        "r",
        encoding="utf-8",
        errors="replace",
    ) as fh:

        for raw in fh:

            raw = raw.strip()

            if not raw or raw.startswith("#"):
                continue

            parts = raw.split()

            if not parts:
                continue

            word = nfc(parts[0])

            if not is_valid_token(word):
                continue

            score = 0.0

            if len(parts) >= 2:

                try:
                    score = float(parts[1])
                except ValueError:
                    score = 0.0

            frequency[word] = score

###############################################################################
# Core vocabulary
###############################################################################

core = []

if os.path.exists(core_path):

    with open(
        core_path,
        "r",
        encoding="utf-8",
        errors="replace",
    ) as fh:

        for raw in fh:

            raw = raw.strip()

            if not raw or raw.startswith("#"):
                continue

            word = nfc(raw)

            if is_valid_token(word):
                core.append(word)

###############################################################################
# Explicit es-AR vocabulary
###############################################################################

if LANGUAGE == "es-AR":

    es_ar_core = {
        "vos",

        "tenés",
        "tenes",

        "podés",
        "podes",

        "querés",
        "queres",

        "sabés",
        "sabes",

        "venís",
        "venis",

        "decís",
        "decis",

        "hacés",
        "haces",

        "mirás",
        "miras",

        "hablás",
        "hablas",

        "comés",
        "comes",

        "vivís",
        "vivis",

        "salís",
        "salis",

        "vení",
        "veni",

        "decime",
        "haceme",

        "mañana",
        "también",

        "qué",
        "cómo",
        "cuándo",
        "dónde",
        "quién",

        "porque",
        "porqué",

        "día",
        "días",

        "más",
        "sí",

        "está",
        "estás",
        "están",

        "acá",
        "allá",

        "después",
        "así",
        "sólo",
    }

    core.extend(es_ar_core)

###############################################################################
# Candidate selection
###############################################################################

accepted = set()

###############################################################################
# Mandatory/core vocabulary always comes first
###############################################################################

for word in core:

    word = nfc(word)

    if is_valid_token(word):
        accepted.add(word)

###############################################################################
# Frequency candidates
#
# A frequency word is accepted when:
#   1. it exists in Hunspell, OR
#   2. it is explicitly mandatory/core vocabulary.
###############################################################################

frequency_candidates = []

for word, score in frequency.items():

    word = nfc(word)

    if not is_valid_token(word):
        continue

    if word in hunspell_words if False else False:
        pass

###############################################################################
# Parse Hunspell source
###############################################################################

hunspell_words = set()

with open(
    hunspell_path,
    "r",
    encoding="utf-8",
    errors="replace",
) as fh:

    for raw in fh:

        word = parse_hunspell_word(raw)

        if word is not None:
            hunspell_words.add(word)

###############################################################################
# Build frequency candidate list
###############################################################################

frequency_candidates = []

for word, score in frequency.items():

    word = nfc(word)

    if not is_valid_token(word):
        continue

    if word in hunspell_words or word in accepted:

        frequency_candidates.append(
            (score, word)
        )

frequency_candidates.sort(
    key=lambda item: (-item[0], item[1])
)

###############################################################################
# Add frequency words
###############################################################################

for _, word in frequency_candidates:

    if len(accepted) >= MAX_WORDS:
        break

    accepted.add(word)

###############################################################################
# Fill remaining capacity from Hunspell
###############################################################################

if len(accepted) < MAX_WORDS:

    remaining = sorted(
        word
        for word in hunspell_words
        if word not in accepted
    )

    for word in remaining:

        if len(accepted) >= MAX_WORDS:
            break

        accepted.add(word)

###############################################################################
# Final validation
###############################################################################

words = sorted(
    word
    for word in accepted
    if is_valid_token(word)
)

###############################################################################
# Preserve mandatory/core vocabulary when enforcing MAX_WORDS
###############################################################################

if len(words) > MAX_WORDS:

    initial_set = set(words)

    mandatory = {
        nfc(word)
        for word in core
        if is_valid_token(nfc(word))
    }

    mandatory &= initial_set

    result = []
    seen = set()

    # Core first.
    for word in sorted(mandatory):

        if len(result) >= MAX_WORDS:
            break

        if word not in seen:

            result.append(word)
            seen.add(word)

    # Frequency second.
    for _, word in frequency_candidates:

        if len(result) >= MAX_WORDS:
            break

        if (
            word in initial_set
            and word not in seen
        ):

            result.append(word)
            seen.add(word)

    # Remaining lexical vocabulary.
    for word in words:

        if len(result) >= MAX_WORDS:
            break

        if word not in seen:

            result.append(word)
            seen.add(word)

    words = result

###############################################################################
# Final deterministic ordering
###############################################################################

words = list(dict.fromkeys(words))

###############################################################################
# Output paths
###############################################################################

dict_path = os.path.join(
    output_dir,
    f"{LANGUAGE}.dict"
)

delete_path = os.path.join(
    output_dir,
    f"{LANGUAGE}.deletes"
)

validated_path = os.path.join(
    output_dir,
    f"{LANGUAGE}.validated.txt"
)

stats_path = os.path.join(
    output_dir,
    f"{LANGUAGE}.stats"
)

###############################################################################
# Write dictionary
###############################################################################

with open(
    validated_path,
    "w",
    encoding="utf-8",
    newline="\n",
) as fh:

    for word in words:

        fh.write(word)
        fh.write("\n")

with open(
    dict_path,
    "w",
    encoding="utf-8",
    newline="\n",
) as fh:

    for word in words:

        fh.write(word)
        fh.write("\n")

###############################################################################
# Delete generation
###############################################################################

def generate_deletes(word, maximum):

    result = set()

    chars = list(word)

    for i in range(len(chars)):

        candidate = (
            "".join(chars[:i])
            +
            "".join(chars[i + 1:])
        )

        # A delete shorter than MIN_WORD_LEN-1
        # has no useful correction value.
        if len(candidate) < MIN_WORD_LEN - 1:
            continue

        result.add(candidate)

        if len(result) >= maximum:
            break

    return result

###############################################################################
# Dictionary set
###############################################################################

word_set = set(words)

###############################################################################
# Delete index
#
# IMPORTANT:
# MAX_DELETE_ENTRIES is enforced during construction.
#
# This prevents an unnecessarily huge in-memory delete index.
###############################################################################

delete_to_words = defaultdict(set)

###############################################################################
# Distance-1
###############################################################################

for word in words:

    deletes = generate_deletes(
        word,
        MAX_DELETES_PER_WORD,
    )

    for delete in deletes:

        if len(delete_to_words) >= MAX_DELETE_ENTRIES:
            break

        delete_to_words[delete].add(word)

    if len(delete_to_words) >= MAX_DELETE_ENTRIES:
        break

###############################################################################
# High-value words for distance-2
###############################################################################

high_value = []
seen_high = set()

###############################################################################
# Core first
###############################################################################

for word in core:

    word = nfc(word)

    if (
        word in word_set
        and word not in seen_high
    ):

        high_value.append(word)
        seen_high.add(word)

        if len(high_value) >= DIST2_WORDS:
            break

###############################################################################
# Frequency second
###############################################################################

if len(high_value) < DIST2_WORDS:

    for _, word in frequency_candidates:

        if (
            word in word_set
            and word not in seen_high
        ):

            high_value.append(word)
            seen_high.add(word)

            if len(high_value) >= DIST2_WORDS:
                break

###############################################################################
# Remaining dictionary words
###############################################################################

if len(high_value) < DIST2_WORDS:

    for word in words:

        if word not in seen_high:

            high_value.append(word)
            seen_high.add(word)

            if len(high_value) >= DIST2_WORDS:
                break

high_value = high_value[:DIST2_WORDS]

###############################################################################
# Distance-2 generation
###############################################################################

if len(delete_to_words) < MAX_DELETE_ENTRIES:

    for word in high_value:

        first_level = generate_deletes(
            word,
            MAX_DELETES_PER_WORD,
        )

        local_second_level = set()

        for d1 in first_level:

            if not d1:
                continue

            second_level = generate_deletes(
                d1,
                MAX_DELETES_PER_WORD,
            )

            local_second_level.update(
                second_level
            )

            if len(local_second_level) >= MAX_DELETES_PER_WORD:
                break

        for delete in local_second_level:

            if len(delete_to_words) >= MAX_DELETE_ENTRIES:
                break

            delete_to_words[delete].add(word)

        if len(delete_to_words) >= MAX_DELETE_ENTRIES:
            break

###############################################################################
# Clean delete index
###############################################################################

clean_delete_to_words = {}

for delete, targets in delete_to_words.items():

    if not delete:
        continue

    if len(delete) > MAX_WORD_LEN:
        continue

    valid_targets = {
        word
        for word in targets
        if word in word_set
    }

    if not valid_targets:
        continue

    clean_delete_to_words[delete] = valid_targets

###############################################################################
# Deterministic delete ordering
###############################################################################

delete_items = sorted(
    clean_delete_to_words.items(),
    key=lambda item: item[0],
)

###############################################################################
# Hard safety limit
###############################################################################

if len(delete_items) > MAX_DELETE_ENTRIES:

    delete_items = delete_items[:MAX_DELETE_ENTRIES]

###############################################################################
# Write deletes
###############################################################################

with open(
    delete_path,
    "w",
    encoding="utf-8",
    newline="\n",
) as fh:

    for delete, targets in delete_items:

        targets = sorted(
            target
            for target in targets
            if target in word_set
        )

        if not targets:
            continue

        fh.write(delete)
        fh.write("\t")
        fh.write(",".join(targets))
        fh.write("\n")

###############################################################################
# Regression checks
###############################################################################

def require_word(word):

    if word not in word_set:

        raise SystemExit(
            f"Regression failure for {LANGUAGE}: "
            f"required word missing from .dict: {word!r}"
        )


if LANGUAGE == "es-AR":

    require_word("mañana")
    require_word("tenés")
    require_word("podés")
    require_word("querés")

    if nfc("mañana") != "mañana":

        raise SystemExit(
            "Regression failure: NFC normalization broken"
        )

###############################################################################
# Dictionary integrity
###############################################################################

with open(
    dict_path,
    "r",
    encoding="utf-8",
) as fh:

    for line_number, raw in enumerate(
        fh,
        1,
    ):

        word = raw.rstrip("\r\n")

        if not word:

            raise SystemExit(
                f"Dictionary integrity failure at line "
                f"{line_number}: empty word"
            )

        if not is_valid_token(word):

            raise SystemExit(
                f"Dictionary integrity failure at line "
                f"{line_number}: invalid word {word!r}"
            )

###############################################################################
# Delete integrity
###############################################################################

with open(
    delete_path,
    "r",
    encoding="utf-8",
) as fh:

    for line_number, raw in enumerate(
        fh,
        1,
    ):

        raw = raw.rstrip("\r\n")

        if not raw:
            continue

        if "\t" not in raw:

            raise SystemExit(
                f"Delete-format failure at line "
                f"{line_number}: missing TAB"
            )

        delete, targets_raw = raw.split(
            "\t",
            1,
        )

        if not delete:

            raise SystemExit(
                f"Delete-format failure at line "
                f"{line_number}: empty delete"
            )

        if not targets_raw:

            raise SystemExit(
                f"Delete-format failure at line "
                f"{line_number}: empty target list"
            )

        for target in targets_raw.split(","):

            if target not in word_set:

                raise SystemExit(
                    f"Delete-integrity failure at line "
                    f"{line_number}: {target!r} "
                    f"is not present in .dict"
                )

###############################################################################
# Statistics
###############################################################################

with open(
    stats_path,
    "w",
    encoding="utf-8",
    newline="\n",
) as fh:

    fh.write(
        f"language={LANGUAGE}\n"
    )

    fh.write(
        f"hunspell_source_words="
        f"{len(hunspell_words)}\n"
    )

    fh.write(
        f"frequency_words="
        f"{len(frequency)}\n"
    )

    fh.write(
        f"core_words="
        f"{len(core)}\n"
    )

    fh.write(
        f"dictionary_words="
        f"{len(words)}\n"
    )

    fh.write(
        f"delete_entries="
        f"{len(delete_items)}\n"
    )

    fh.write(
        f"distance2_words="
        f"{len(high_value)}\n"
    )

    fh.write(
        f"max_words="
        f"{MAX_WORDS}\n"
    )

    fh.write(
        f"dist2_words_limit="
        f"{DIST2_WORDS}\n"
    )

###############################################################################
# Console summary
###############################################################################

print(
    f"Language                  : {LANGUAGE}"
)

print(
    f"Hunspell source words     : "
    f"{len(hunspell_words):,}"
)

print(
    f"Frequency words           : "
    f"{len(frequency):,}"
)

print(
    f"Core words                : "
    f"{len(core):,}"
)

print(
    f"Dictionary words          : "
    f"{len(words):,}"
)

print(
    f"Delete entries            : "
    f"{len(delete_items):,}"
)

print(
    f"Distance-2 words          : "
    f"{len(high_value):,}"
)

print(
    f"Output                    : "
    f"{dict_path}"
)

print(
    f"Deletes                   : "
    f"{delete_path}"
)

print(
    f"Validated                 : "
    f"{validated_path}"
)

print(
    f"Stats                     : "
    f"{stats_path}"
)
PY
}

###############################################################################
# Prepare runtime sources
###############################################################################

prepare_hunspell
prepare_frequency
prepare_core_files

###############################################################################
# Normalize frequency sources
###############################################################################

separator
log "Normalizing frequency sources"
separator

normalize_frequency_file \
    "es-AR" \
    "${FREQUENCY_ROOT}/es-AR.txt" \
    "${WORK_ROOT}/es-AR.frequency"

normalize_frequency_file \
    "en" \
    "${FREQUENCY_ROOT}/en.txt" \
    "${WORK_ROOT}/en.frequency"

normalize_frequency_file \
    "de" \
    "${FREQUENCY_ROOT}/de.txt" \
    "${WORK_ROOT}/de.frequency"

###############################################################################
# Build dictionaries
###############################################################################

build_language \
    "es-AR" \
    "${HUNSPELL_ROOT}/es-AR.dic" \
    "${WORK_ROOT}/es-AR.frequency" \
    "${SOURCE_ROOT}/core/es-AR.txt" \
    "${OUTPUT_ROOT}/es-AR"

build_language \
    "en" \
    "${HUNSPELL_ROOT}/en.dic" \
    "${WORK_ROOT}/en.frequency" \
    "${SOURCE_ROOT}/core/en.txt" \
    "${OUTPUT_ROOT}/en"

build_language \
    "de" \
    "${HUNSPELL_ROOT}/de.dic" \
    "${WORK_ROOT}/de.frequency" \
    "${SOURCE_ROOT}/core/de.txt" \
    "${OUTPUT_ROOT}/de"

###############################################################################
# Final validation
###############################################################################

separator
log "Final validation"
separator

for lang in "${LANGUAGES[@]}"; do

    LANGUAGE_OUT="${OUTPUT_ROOT}/${lang}"

    DICT="${LANGUAGE_OUT}/${lang}.dict"
    DELETES="${LANGUAGE_OUT}/${lang}.deletes"
    VALIDATED="${LANGUAGE_OUT}/${lang}.validated.txt"
    STATS="${LANGUAGE_OUT}/${lang}.stats"

    [[ -s "$DICT" ]] ||
        die "${lang}: .dict was not generated"

    [[ -s "$DELETES" ]] ||
        die "${lang}: .deletes was not generated"

    [[ -s "$VALIDATED" ]] ||
        die "${lang}: validated vocabulary was not generated"

    [[ -s "$STATS" ]] ||
        die "${lang}: stats file was not generated"

    cmp -s "$DICT" "$VALIDATED" ||
        die "${lang}: .dict and validated vocabulary differ"

    if grep -n '^$' "$DICT" >/dev/null 2>&1; then
        die "${lang}: blank line found in .dict"
    fi

    if grep -n '[[:space:]]' "$DICT" >/dev/null 2>&1; then
        die "${lang}: whitespace found inside .dict"
    fi

done

###############################################################################
# Install generated dictionaries into Android assets
#
# IMPORTANT:
# Gradle validatePocketBoardDictionaries expects the dictionary files
# directly inside:
#
#   app/src/main/assets/dictionaries/
#
# Therefore we DO NOT create per-language subdirectories here.
###############################################################################

separator
log "Installing dictionaries into Android assets"
separator

rm -rf "$ASSETS_ROOT"

mkdir -p "$ASSETS_ROOT"

for lang in "${LANGUAGES[@]}"; do

    SOURCE_DICT="${OUTPUT_ROOT}/${lang}/${lang}.dict"
    SOURCE_DELETES="${OUTPUT_ROOT}/${lang}/${lang}.deletes"

    TARGET_DICT="${ASSETS_ROOT}/${lang}.dict"
    TARGET_DELETES="${ASSETS_ROOT}/${lang}.deletes"

    [[ -s "$SOURCE_DICT" ]] ||
        die "Generated dictionary missing before installation: ${SOURCE_DICT}"

    [[ -s "$SOURCE_DELETES" ]] ||
        die "Generated deletes missing before installation: ${SOURCE_DELETES}"

    cp \
        "$SOURCE_DICT" \
        "$TARGET_DICT"

    cp \
        "$SOURCE_DELETES" \
        "$TARGET_DELETES"

    log "Installed ${lang}:"
    log "  ${TARGET_DICT}"
    log "  ${TARGET_DELETES}"

done

###############################################################################
# Final asset validation
###############################################################################

separator
log "Validating installed Android assets"
separator

for lang in "${LANGUAGES[@]}"; do

    DICT_ASSET="${ASSETS_ROOT}/${lang}.dict"
    DELETES_ASSET="${ASSETS_ROOT}/${lang}.deletes"

    [[ -s "$DICT_ASSET" ]] ||
        die "Asset missing: ${DICT_ASSET}"

    [[ -s "$DELETES_ASSET" ]] ||
        die "Asset missing: ${DELETES_ASSET}"

done

###############################################################################
# Verify there are no unexpected per-language directories
###############################################################################

for lang in "${LANGUAGES[@]}"; do

    if [[ -d "${ASSETS_ROOT}/${lang}" ]]; then
        die "Unexpected language asset directory remains: ${ASSETS_ROOT}/${lang}"
    fi

done

log "Android asset validation completed successfully."

###############################################################################
# Final asset validation
###############################################################################

for lang in "${LANGUAGES[@]}"; do

    [[ -s "${ASSETS_ROOT}/${lang}/${lang}.dict" ]] ||
        die "Asset missing: ${lang}.dict"

    [[ -s "${ASSETS_ROOT}/${lang}/${lang}.deletes" ]] ||
        die "Asset missing: ${lang}.deletes"

done

###############################################################################
# Final summary
###############################################################################

separator
log "Dictionary generation completed successfully."
separator

log "Runtime source directory:"
log "  ${BUILD_ROOT}"

log "Generated dictionaries:"
log "  ${OUTPUT_ROOT}"

log "Android assets:"
log "  ${ASSETS_ROOT}"

log
log "Generated languages:"

for lang in "${LANGUAGES[@]}"; do
    log "  - ${lang}"
done

log
log "Important:"
log "  Hunspell was used as a lexical source."
log "  No per-word 'hunspell -a' validation was performed."
log "  Frequency data was processed at runtime."
log "  es-AR voseo/core vocabulary was preserved."
log "  Unicode/NFC and accents were preserved."
log "  Distance-1 deletes cover the dictionary."
log "  Distance-2 deletes are limited to high-value words."
log "  Delete targets are validated against the final .dict."
log "  Generated files were copied into app/src/main/assets/dictionaries."
