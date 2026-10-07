#!/usr/bin/env bash
#
# PocketBoard dictionary generator
# ================================
#
# Produces, for es-AR, en-en and de-de:
#
#     <lang>.dict      vocabulary (one word per line)
#     <lang>.deletes   symmetric-delete correction index
#     <lang>.meta      Hunspell REP/KEY/TRY metadata
#
# How a word is accepted
# -----------------------
#   A candidate word (from a real-world frequency list, i.e. words people
#   actually type/say) is accepted when the real `hunspell` spellchecker
#   says it is correctly spelled for that language's Hunspell dictionary
#   (REAL hunspell, not `unmunch`: unmunch only expands simple PFX/SFX
#   affixes and silently drops anything built with Hunspell's compound
#   mechanism (COMPOUNDBEGIN/MIDDLE/END) and some cross-affix rules.
#   German leans on compounding for a large share of its everyday
#   vocabulary, so unmunch alone used to miss a very large, very common
#   slice of German words -- see DE_TITLECASE_RESCUE below).
#
#   German nouns are capitalized, and Hunspell's compound rules are
#   case-sensitive, but the frequency corpus is all-lowercase. So for
#   German a word that fails lowercase is re-tried capitalized
#   (Title-case); if THAT passes, the (lowercase) word is accepted. This
#   alone rescues a large fraction of common German compounds
#   (Hausarzt, Krankenhaus, Geburtstag, ...) that would otherwise be
#   invisible to the correction engine. This rescue is intentionally
#   NOT applied to Spanish/English: there it mostly just lets in
#   proper names (Mike, Sam, John, ...) that the subtitle-based
#   frequency corpus is full of, which is noise for a general dictionary.
#
# How "most used daily" is decided
# ---------------------------------
#   Accepted words are ranked by real-world frequency and taken from the
#   top until they cover COVERAGE_TARGET of the USAGE of all accepted
#   words (not raw word count), or MAX_WORDS is hit. So vocabulary size
#   is a RESULT of how much everyday usage is covered, never a fixed
#   number chosen up front.
#   A small curated whitelist (voseo, English contractions, basic
#   German function words/greetings) is always included on top of that.
#
# How the 10 MB limit is enforced
# --------------------------------
#   The delete index is what grows into millions of lines, so it gets
#   whatever is left of TOTAL_BUDGET_BYTES after the vocabularies and
#   metadata, split between languages by vocabulary size. Inside its
#   budget an index is filled strictly by frequency (most used words
#   first, with the most frequent words also getting 2-typo correction,
#   not just 1-typo), so the budget only ever cuts the rare tail. The
#   build fails loudly if the total would exceed TOTAL_BUDGET_BYTES.
#
# Requirements: curl, python3, hunspell (the spellchecker CLI, not just
# hunspell-tools -- Debian/Ubuntu: `apt-get install hunspell`).
#
# Optional environment overrides:
#   TOTAL_BUDGET_BYTES   hard cap for all generated assets   (10000000)
#   COVERAGE_TARGET      share of everyday usage to cover     (0.99)
#   MAX_WORDS            hard cap of words per language       (60000)
#   DE_FREQUENCY_SOURCE  frequencywords | leipzig             (frequencywords)
#   FORCE_DOWNLOAD       1 = ignore the download cache        (0)

set -euo pipefail

export LC_ALL=C
export LANG=C
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8

ROOT_DIR="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/.." &&
    pwd
)"

WORK_DIR="${ROOT_DIR}/build/pocketboard-dictionaries"
OUTPUT_DIR="${ROOT_DIR}/app/src/main/assets/dictionaries"

WOOORM_BASE="https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries"
FREQUENCY_BASE="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"
LEIPZIG_BASE="${LEIPZIG_BASE:-https://downloads.wortschatz-leipzig.de/corpora}"

ES_DIR="${WORK_DIR}/es-AR"
EN_DIR="${WORK_DIR}/en-en"
DE_DIR="${WORK_DIR}/de-de"

# ============================================================
# SIZE BUDGET
# ============================================================

TOTAL_BUDGET_BYTES="${TOTAL_BUDGET_BYTES:-10000000}"

# Head-room kept free inside the total budget (percent of the index share).
INDEX_MARGIN_PERCENT=2

# Below this the correction index would be useless: fail loudly instead.
MIN_TOTAL_INDEX_BYTES=1000000

# ============================================================
# VOCABULARY SELECTION
# ============================================================

COVERAGE_TARGET="${COVERAGE_TARGET:-0.99}"
MAX_WORDS="${MAX_WORDS:-60000}"
MIN_WORDS=5000

# Whitelisted words that have no corpus frequency are treated as if they
# ranked at this position (keeps them inside the correction index).
WHITELIST_RANK_FLOOR=2000

DE_FREQUENCY_SOURCE="${DE_FREQUENCY_SOURCE:-frequencywords}"
FORCE_DOWNLOAD="${FORCE_DOWNLOAD:-0}"

# ============================================================
# DELETE INDEX POLICY
# ============================================================

# Share of each language's index budget reserved for distance-2 (2-typo)
# deletes of its most frequent words; the rest only gets distance-1.
# How many words that buys is NOT fixed -- it keeps going, most frequent
# word first, until this share of the budget is used up.
DISTANCE2_BUDGET_SHARE=0.20

# These three must match DictionaryManager.java.
MAX_DELETE_DISTANCE=2
MAX_DELETE_WORD_LENGTH=32
MAX_CANDIDATES_PER_DELETE=3

# ============================================================
# REQUIRED COMMANDS
# ============================================================

require_command() {
    local command_name="$1"
    local hint="${2:-}"

    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo "ERROR: command not found: ${command_name}"
        if [[ -n "${hint}" ]]; then
            echo "       ${hint}"
        fi
        exit 1
    fi
}

require_command curl
require_command python3
require_command grep
require_command wc
require_command tail
require_command head
require_command cut

require_command hunspell \
    "Debian/Ubuntu: sudo apt-get install hunspell"

if [[ "${DE_FREQUENCY_SOURCE}" == "leipzig" ]]; then
    require_command tar
    require_command find
fi

case "${DE_FREQUENCY_SOURCE}" in
    frequencywords|leipzig) ;;
    *)
        echo "ERROR: DE_FREQUENCY_SOURCE must be 'frequencywords' or 'leipzig'"
        exit 1
        ;;
esac

mkdir -p "${ES_DIR}" "${EN_DIR}" "${DE_DIR}" "${OUTPUT_DIR}"

# ============================================================
# DOWNLOAD (cached in build/, so local rebuilds stay offline)
# ============================================================

download() {
    local url="$1"
    local destination="$2"

    if [[ -s "${destination}" && "${FORCE_DOWNLOAD}" != "1" ]]; then
        echo "Cached: ${destination#"${ROOT_DIR}"/}"
        return 0
    fi

    echo "Downloading: ${url}"

    # Download to a temporary name so an interrupted transfer is never
    # mistaken for a complete cached file.
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
        -o "${destination}.part" \
        "${url}"

    if [[ ! -s "${destination}.part" ]]; then
        echo "ERROR: empty download: ${url}"
        rm -f "${destination}.part"
        exit 1
    fi

    mv -f "${destination}.part" "${destination}"
}

banner() {
    echo ""
    echo "============================================================"
    echo " $1"
    echo "============================================================"
}

# ============================================================
# SOURCES
# ============================================================

banner "Downloading Hunspell dictionaries and frequency lists"

download "${WOOORM_BASE}/es-AR/index.dic" "${ES_DIR}/index.dic"
download "${WOOORM_BASE}/es-AR/index.aff" "${ES_DIR}/index.aff"
download "${WOOORM_BASE}/en/index.dic"    "${EN_DIR}/index.dic"
download "${WOOORM_BASE}/en/index.aff"    "${EN_DIR}/index.aff"
download "${WOOORM_BASE}/de/index.dic"    "${DE_DIR}/index.dic"
download "${WOOORM_BASE}/de/index.aff"    "${DE_DIR}/index.aff"

# Spoken/everyday frequency (OpenSubtitles based).
download "${FREQUENCY_BASE}/es/es_full.txt" "${ES_DIR}/frequency.txt"
download "${FREQUENCY_BASE}/en/en_full.txt" "${EN_DIR}/frequency.txt"

if [[ "${DE_FREQUENCY_SOURCE}" == "leipzig" ]]; then
    # Written/news German (Leipzig Corpora Collection).
    # Lines look like:  <rank><TAB><word><TAB><count>
    LEIPZIG_ARCHIVE="${DE_DIR}/deu_news_2025_1M.tar.gz"
    LEIPZIG_DIR="${DE_DIR}/leipzig"

    download "${LEIPZIG_BASE}/deu_news_2025_1M.tar.gz" "${LEIPZIG_ARCHIVE}"

    rm -rf "${LEIPZIG_DIR}"
    mkdir -p "${LEIPZIG_DIR}"
    tar -xzf "${LEIPZIG_ARCHIVE}" -C "${LEIPZIG_DIR}"

    DE_FREQUENCY_FILE="$(
        find "${LEIPZIG_DIR}" -type f -name '*-words.txt' | head -n 1
    )"

    if [[ -z "${DE_FREQUENCY_FILE}" ]]; then
        echo "ERROR: Leipzig word-frequency file not found."
        find "${LEIPZIG_DIR}" -maxdepth 4 -type f -print
        exit 1
    fi
else
    download "${FREQUENCY_BASE}/de/de_full.txt" "${DE_DIR}/frequency.txt"
    DE_FREQUENCY_FILE="${DE_DIR}/frequency.txt"
fi

# ============================================================
# WHITELISTS
# ============================================================
#
# Always shipped, even when Hunspell or the corpus does not know them.
# Accents and umlauts are deliberately preserved.
#
# English lists ONLY forms with the apostrophe. Apostrophe-less forms
# ("dont", "im", "isnt") are intentionally absent: if they were valid
# words the checker would stop correcting them to "don't", "i'm", ...
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
i'm
i've
i'll
i'd
you're
you've
you'll
you'd
he's
he'll
he'd
she's
she'll
she'd
it's
it'll
it'd
we're
we've
we'll
we'd
they're
they've
they'll
they'd
that's
that'll
there's
here's
what's
who's
where's
how's
let's
o'clock
aren't
can't
couldn't
didn't
doesn't
don't
hadn't
hasn't
haven't
isn't
mustn't
shouldn't
wasn't
weren't
won't
wouldn't
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
# STOPLISTS
# ============================================================
#
# The English frequency list splits contractions at the apostrophe, so
# "don't" is counted as "don" + "t". Those fragments would rank among
# the most frequent "words" without being words. The real contractions
# come from the whitelist above.
#
# ============================================================

cat > "${EN_DIR}/stoplist.txt" <<'EOF'
s
t
ll
re
ve
d
m
don
didn
doesn
isn
wasn
aren
weren
couldn
wouldn
shouldn
hasn
haven
hadn
mustn
needn
ain
EOF

: > "${ES_DIR}/stoplist.txt"
: > "${DE_DIR}/stoplist.txt"

# ============================================================
# PYTHON HELPER
# ============================================================

BUILDER="${WORK_DIR}/pocketboard_dictionary_builder.py"

cat > "${BUILDER}" <<'PY'
import json
import subprocess
import sys

MAX_WORD_LENGTH = 32


def word_shape_ok(word):
    """Same character rules as DictionaryManager.isValidWord()."""
    if not 1 <= len(word) <= MAX_WORD_LENGTH:
        return False
    if word[0] in "'-" or word[-1] in "'-":
        return False
    previous = ""
    for ch in word:
        if ch in "'-":
            if previous in ("'", "-"):
                return False
        elif not ch.isalpha():
            return False
        previous = ch
    return True


def load_frequency(path):
    """word -> total count, case-insensitive.

    Accepts both "word count" (FrequencyWords) and
    "rank word count" (Leipzig) lines.
    """
    counts = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            parts = raw.split()
            if len(parts) < 2 or not parts[-1].isdigit():
                continue
            word = next((p for p in parts[:-1] if not p.isdigit()), None)
            if word is None:
                continue
            word = word.lower()
            counts[word] = counts.get(word, 0) + int(parts[-1])
    return counts


def load_word_list(path):
    words, seen = [], set()
    with open(path, encoding="utf-8") as f:
        for raw in f:
            w = raw.strip().lower()
            if w and not w.startswith("#") and w not in seen:
                seen.add(w)
                words.append(w)
    return words


def hunspell_valid(words, dic_base):
    """Which of `words` does the REAL hunspell spellchecker accept.

    Unlike `unmunch`, this runs the actual checker, so compound words
    (COMPOUNDBEGIN/MIDDLE/END, important for German) and cross-affix
    rules are honoured correctly instead of silently dropped.
    """
    if not words:
        return set()
    result = subprocess.run(
        ["hunspell", "-d", dic_base, "-i", "utf-8", "-l"],
        input="\n".join(words),
        capture_output=True,
        text=True,
        check=True,
    )
    misspelled = set(result.stdout.split())
    return set(words) - misspelled


def cmd_vocab(args):
    (language, frequency_path, dic_base, whitelist_path, stoplist_path,
     single_letters, titlecase_rescue, coverage, max_words, min_words,
     floor_rank, vocab_path, report_path) = args

    titlecase_rescue = titlecase_rescue == "1"
    coverage = float(coverage)
    max_words = int(max_words)
    min_words = int(min_words)
    floor_rank = int(floor_rank)
    singles = set(single_letters) - {","}

    stop = set(load_word_list(stoplist_path))
    whitelist = [w for w in load_word_list(whitelist_path) if word_shape_ok(w)]
    frequency = load_frequency(frequency_path)

    def usable(w):
        if w in stop:
            return False
        if len(w) == 1 and w not in singles:
            return False
        return True

    candidates = sorted(w for w in frequency if word_shape_ok(w) and usable(w))

    valid = hunspell_valid(candidates, dic_base)
    rescued = set()
    if titlecase_rescue:
        # German nouns are capitalized and Hunspell's compound rules are
        # case-sensitive; the lowercase frequency corpus hides a large
        # share of valid compounds (Hausarzt, Krankenhaus...) unless we
        # also try them capitalized.
        failed = [w for w in candidates if w not in valid]
        titled = [w[:1].upper() + w[1:] for w in failed]
        titled_ok = hunspell_valid(titled, dic_base)
        rescued = {w for w, t in zip(failed, titled) if t in titled_ok}
    valid |= rescued

    ranked = sorted(
        ((w, frequency[w]) for w in valid),
        key=lambda item: (-item[1], item[0]),
    )
    total_mass = sum(c for _, c in ranked)

    selected, accumulated = [], 0
    for word, count in ranked:
        if len(selected) >= max_words:
            break
        if len(selected) >= min_words and accumulated >= coverage * total_mass:
            break
        selected.append((word, count))
        accumulated += count

    cutoff_count = selected[-1][1] if selected else 0
    floor_count = (
        selected[min(floor_rank, len(selected) - 1)][1] if selected else 1
    )

    present = {w for w, _ in selected}
    forced = []
    for w in whitelist:
        if w in present:
            continue
        selected.append((w, frequency.get(w) or floor_count))
        present.add(w)
        forced.append(w)

    selected.sort(key=lambda item: (-item[1], item[0]))

    with open(vocab_path, "w", encoding="utf-8") as out:
        for word, count in selected:
            out.write("%s\t%d\n" % (word, count))

    report = {
        "language": language,
        "candidates": len(candidates),
        "valid_by_hunspell": len(valid) - len(rescued),
        "rescued_titlecase": len(rescued),
        "words": len(selected),
        "usage_coverage": round(accumulated / total_mass, 4) if total_mass else 0,
        "min_corpus_count": cutoff_count,
        "added_from_whitelist": len(forced),
    }
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False)
    print(json.dumps(report, ensure_ascii=False))


def deletes_of(word, distance):
    current, result = {word}, set()
    for _ in range(distance):
        following = set()
        for item in current:
            for i in range(len(item)):
                candidate = item[:i] + item[i + 1:]
                if candidate:
                    result.add(candidate)
                    following.add(candidate)
        current = following
    return result


def cmd_index(args):
    (language, vocab_path, index_path, budget, distance2_share,
     max_candidates, max_distance, max_word_length, report_path) = args

    budget = int(budget)
    distance2_share = float(distance2_share)
    max_candidates = int(max_candidates)
    max_distance = int(max_distance)
    max_word_length = int(max_word_length)

    words = []
    with open(vocab_path, encoding="utf-8") as f:
        for raw in f:
            word, count = raw.rstrip("\n").split("\t")
            words.append((word, int(count)))       # frequency order

    header = (
        "#POCKETBOARD-DELETES-1\n"
        "#MAX_DISTANCE=%d\n"
        "#MAX_WORD_LENGTH=%d\n"
        "#MAX_CANDIDATES=%d\n"
        % (max_distance, max_word_length, max_candidates)
    )
    state = {"used": len(header.encode("utf-8"))}
    buckets = {}

    def try_add(word, distance, limit):
        """All-or-nothing: a word is either fully indexed or not at all."""
        word_bytes = len(word.encode("utf-8")) + 2
        added, cost = [], 0
        for d in deletes_of(word, distance):
            bucket = buckets.get(d)
            if bucket is not None and (
                    len(bucket) >= max_candidates or word in bucket):
                continue
            added.append(d)
            cost += len(d.encode("utf-8")) + word_bytes
        if state["used"] + cost > limit:
            return False
        for d in added:
            buckets.setdefault(d, []).append(word)
        state["used"] += cost
        return True

    eligible = [(w, c) for w, c in words if len(w) <= max_word_length]

    # Tier 1: distance-2 deletes for the most frequent words, bounded only
    # by a share of this language's budget -- NOT by a fixed word count,
    # so it automatically uses whatever room the budget actually gives it.
    tier1, tier1_limit = set(), int(budget * distance2_share)
    if max_distance >= 2:
        for word, _ in eligible:
            if not try_add(word, 2, tier1_limit):
                break
            tier1.add(word)

    # Tier 2: distance-1 deletes in strict frequency order until the
    # language budget is used up. Only the rare tail is left out.
    tier2 = 0
    for word, _ in eligible:
        if word in tier1:
            continue
        if not try_add(word, 1, budget):
            break
        tier2 += 1

    with open(index_path, "w", encoding="utf-8") as out:
        out.write(header)
        for d in sorted(buckets):
            # Keep each bucket's candidates in FREQUENCY order (most
            # frequent word for that delete-key first) -- do not
            # re-sort alphabetically, that would throw the ranking away.
            for w in buckets[d]:
                out.write("%s\t%s\n" % (d, w))

    indexed = len(tier1) + tier2
    mass = sum(c for _, c in words) or 1
    covered = sum(c for _, c in eligible[:indexed])

    report = {
        "language": language,
        "vocabulary": len(words),
        "indexed_words": indexed,
        "distance2_words": len(tier1),
        "usage_covered_by_index": round(covered / mass, 4),
        "mappings": sum(len(v) for v in buckets.values()),
        "bytes": state["used"],
        "budget": budget,
    }
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f)
    print(json.dumps(report))


COMMANDS = {"vocab": cmd_vocab, "index": cmd_index}

if __name__ == "__main__":
    COMMANDS[sys.argv[1]](sys.argv[2:])
PY

# ============================================================
# BUILD VOCABULARIES
# ============================================================

build_vocabulary() {
    local language="$1"
    local directory="$2"
    local frequency="$3"
    local single_letters="$4"
    local titlecase_rescue="$5"
    local output="$6"

    banner "Selecting everyday vocabulary: ${language}"

    python3 "${BUILDER}" vocab \
        "${language}" \
        "${frequency}" \
        "${directory}/index" \
        "${directory}/whitelist.txt" \
        "${directory}/stoplist.txt" \
        "${single_letters}" \
        "${titlecase_rescue}" \
        "${COVERAGE_TARGET}" \
        "${MAX_WORDS}" \
        "${MIN_WORDS}" \
        "${WHITELIST_RANK_FLOOR}" \
        "${directory}/vocabulary.tsv" \
        "${directory}/vocabulary.json"

    if [[ ! -s "${directory}/vocabulary.tsv" ]]; then
        echo "ERROR: empty vocabulary for ${language}"
        exit 1
    fi

    # The runtime expects a sorted, unique word list.
    {
        echo "#POCKETBOARD-DICT-1"
        cut -f1 "${directory}/vocabulary.tsv" | LC_ALL=C sort -u
    } > "${output}"

    echo "Words: $(tail -n +2 "${output}" | wc -l)"
    echo "Bytes: $(wc -c < "${output}")"
}

build_vocabulary "es-AR" "${ES_DIR}" "${ES_DIR}/frequency.txt" \
    "a,e,o,u,y" "0" "${OUTPUT_DIR}/es-AR.dict"

build_vocabulary "en-en" "${EN_DIR}" "${EN_DIR}/frequency.txt" \
    "a,i" "0" "${OUTPUT_DIR}/en-en.dict"

build_vocabulary "de-de" "${DE_DIR}" "${DE_FREQUENCY_FILE}" \
    "" "1" "${OUTPUT_DIR}/de-de.dict"

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
        grep -E '^REP([[:space:]]|$)' "${aff}" || true
        echo "# KEY"
        grep -E '^KEY([[:space:]]|$)' "${aff}" || true
        echo "# TRY"
        grep -E '^TRY([[:space:]]|$)' "${aff}" || true
        echo "# PHONE"
        grep -E '^PHONE([[:space:]]|$)' "${aff}" || true
        echo "# ph"
        grep -E '^ph:' "${aff}" || true
        echo "# NOSUGGEST"
        grep -E '^NOSUGGEST([[:space:]]|$)' "${aff}" || true
        echo "# SUBSTANDARD"
        grep -E '^SUBSTANDARD([[:space:]]|$)' "${aff}" || true
    } > "${output}"

    if [[ ! -s "${output}" ]]; then
        echo "ERROR: metadata generation failed: ${language}"
        exit 1
    fi
}

generate_metadata "es-AR" "${ES_DIR}/index.aff" "${OUTPUT_DIR}/es-AR.meta"
generate_metadata "en-en" "${EN_DIR}/index.aff" "${OUTPUT_DIR}/en-en.meta"
generate_metadata "de-de" "${DE_DIR}/index.aff" "${OUTPUT_DIR}/de-de.meta"

# ============================================================
# SPLIT THE SIZE BUDGET
# ============================================================
#
# index budget = total budget - vocabularies - metadata - margin,
# divided between languages in proportion to their vocabulary size.
#
# ============================================================

file_size() {
    wc -c < "$1" | tr -d '[:space:]'
}

word_count() {
    tail -n +2 "$1" | wc -l | tr -d '[:space:]'
}

LANGUAGES=("es-AR" "en-en" "de-de")

FIXED_BYTES=0
TOTAL_WORDS=0

for language in "${LANGUAGES[@]}"; do
    FIXED_BYTES=$((
        FIXED_BYTES +
        $(file_size "${OUTPUT_DIR}/${language}.dict") +
        $(file_size "${OUTPUT_DIR}/${language}.meta")
    ))
    TOTAL_WORDS=$((
        TOTAL_WORDS + $(word_count "${OUTPUT_DIR}/${language}.dict")
    ))
done

TOTAL_INDEX_BYTES=$((
    (TOTAL_BUDGET_BYTES - FIXED_BYTES) *
    (100 - INDEX_MARGIN_PERCENT) / 100
))

banner "Size budget"
echo "Total budget:      ${TOTAL_BUDGET_BYTES} bytes"
echo "Vocab + metadata:  ${FIXED_BYTES} bytes"
echo "Correction index:  ${TOTAL_INDEX_BYTES} bytes available"

if (( TOTAL_INDEX_BYTES < MIN_TOTAL_INDEX_BYTES )); then
    echo ""
    echo "ERROR: not enough budget left for the correction indexes."
    echo "       Lower COVERAGE_TARGET / MAX_WORDS or raise TOTAL_BUDGET_BYTES."
    exit 1
fi

# ============================================================
# BUILD CORRECTION INDEXES
# ============================================================

build_index() {
    local language="$1"
    local directory="$2"

    local words
    words="$(word_count "${OUTPUT_DIR}/${language}.dict")"

    local budget=$(( TOTAL_INDEX_BYTES * words / TOTAL_WORDS ))

    banner "Correction index: ${language} (budget ${budget} bytes)"

    python3 "${BUILDER}" index \
        "${language}" \
        "${directory}/vocabulary.tsv" \
        "${OUTPUT_DIR}/${language}.deletes" \
        "${budget}" \
        "${DISTANCE2_BUDGET_SHARE}" \
        "${MAX_CANDIDATES_PER_DELETE}" \
        "${MAX_DELETE_DISTANCE}" \
        "${MAX_DELETE_WORD_LENGTH}" \
        "${directory}/index.json"
}

build_index "es-AR" "${ES_DIR}"
build_index "en-en" "${EN_DIR}"
build_index "de-de" "${DE_DIR}"

# ============================================================
# WORD DIAGNOSTICS (warnings only)
# ============================================================

check_word() {
    local language="$1"
    local word="$2"

    if grep -Fqx -- "${word}" <(tail -n +2 "${OUTPUT_DIR}/${language}.dict"); then
        echo "OK: ${language}: ${word}"
    else
        echo "WARNING: word not selected: ${language}: ${word}"
    fi
}

banner "Word diagnostics"

for word in mañana vos tenés podés hacés acá hago hacer veré \
    aplicación disponible bolsillo; do
    check_word "es-AR" "${word}"
done

for word in the have hello world "don't" "i'm" "it's" "can't"; do
    check_word "en-en" "${word}"
done

for word in ich nicht morgen hallo welt entschuldigung wahrscheinlich \
    möglicherweise krankenhaus hausarzt geburtstag; do
    check_word "de-de" "${word}"
done

# ============================================================
# VALIDATION
# ============================================================

validate_generated_asset() {
    local file="$1"
    local expected_header="$2"

    if [[ ! -s "${file}" ]]; then
        echo "ERROR: generated asset is empty: ${file}"
        exit 1
    fi

    local header
    header="$(head -n 1 "${file}" | tr -d '\r')"

    if [[ "${header}" != "${expected_header}" ]]; then
        echo "ERROR: invalid generated asset header: ${file}"
        echo "Expected: ${expected_header}"
        echo "Found:    ${header}"
        exit 1
    fi
}

banner "Validating generated assets"

for language in "${LANGUAGES[@]}"; do
    validate_generated_asset "${OUTPUT_DIR}/${language}.dict"    "#POCKETBOARD-DICT-1"
    validate_generated_asset "${OUTPUT_DIR}/${language}.meta"    "#POCKETBOARD-META-1"
    validate_generated_asset "${OUTPUT_DIR}/${language}.deletes" "#POCKETBOARD-DELETES-1"
    echo "OK: ${language}"
done

# ============================================================
# FINAL SIZE REPORT
# ============================================================

banner "PocketBoard dictionary build summary"

TOTAL_GENERATED_BYTES=0

for language in "${LANGUAGES[@]}"; do
    dictionary="${OUTPUT_DIR}/${language}.dict"
    deletes="${OUTPUT_DIR}/${language}.deletes"
    metadata="${OUTPUT_DIR}/${language}.meta"

    dictionary_size="$(file_size "${dictionary}")"
    delete_size="$(file_size "${deletes}")"
    metadata_size="$(file_size "${metadata}")"
    language_total=$(( dictionary_size + delete_size + metadata_size ))

    TOTAL_GENERATED_BYTES=$(( TOTAL_GENERATED_BYTES + language_total ))

    echo ""
    echo "${language}"
    echo "  Words:           $(word_count "${dictionary}")"
    echo "  Dictionary:      ${dictionary_size} bytes"
    echo "  Delete index:    ${delete_size} bytes ($(tail -n +5 "${deletes}" | wc -l | tr -d '[:space:]') mappings)"
    echo "  Metadata:        ${metadata_size} bytes"
    echo "  Total:           ${language_total} bytes"
done

echo ""
echo "All languages:     ${TOTAL_GENERATED_BYTES} bytes"
echo "Budget:            ${TOTAL_BUDGET_BYTES} bytes"

if command -v gzip >/dev/null 2>&1; then
    COMPRESSED_BYTES="$(
        cat "${OUTPUT_DIR}"/*.dict "${OUTPUT_DIR}"/*.deletes "${OUTPUT_DIR}"/*.meta |
        gzip -9 -c |
        wc -c |
        tr -d '[:space:]'
    )"
    echo "Compressed (~APK): ${COMPRESSED_BYTES} bytes"
fi

if (( TOTAL_GENERATED_BYTES > TOTAL_BUDGET_BYTES )); then
    echo ""
    echo "ERROR: generated assets exceed the total budget."
    exit 1
fi

echo "Status: OK"
echo ""
echo "PocketBoard dictionaries generated successfully"
