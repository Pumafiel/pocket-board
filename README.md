# PocketBoard-E

A fork of [PocketBoard](https://github.com/SinuXVR/pocket-board), the IME for physical-keyboard Android phones,
tuned for the **Unihertz Titan Slim** on stock Android 11 and for typing in **Spanish (Argentina), English and German**.

![Preview](img/screen.png)

The upstream project targets the Titan Pocket and relies on an external spellchecker. This fork ships its own
dictionaries and correction engine, replaces the configurable SymPad with a fixed symbol layer, and drops features
that the Titan Slim firmware already covers. Everything that differs from upstream is listed below.

# At a glance

| This fork has | This fork does not have |
|---|---|
| Built-in dictionaries and correction engine for Spanish (AR), English and German, generated at build time | The external spellchecker requirement (no OpenBoard SpellChecker needed) |
| Auto-correction and word suggestions, with real-usage frequency used to rank suggestions | Russian layouts (the layout files are still in `res/xml`, but no input type uses them) |
| Learns names and slang you type, and `Backspace` right after a correction undoes it | SymPad (D-Pad, Home/End, Esc, media-key emulation and the SymPad editor) |
| Fixed `SYM` symbol layer (digits and extra punctuation) | Virtual touchpad (swipe on the suggestions bar) |
| Adjustable **Multipress speed** setting (slider plus number box) | `Fn` as `Ctrl` (the Titan Slim SystemUI already handles it) |
| Emoji and special symbol palettes | Overlay (`SYSTEM_ALERT_WINDOW`) permission and installed-apps query |
| Inline autofill (Android 11+) | A randomly generated signing key on every build |
| Voice input (Google Voice Input) | Use of the Android user dictionary, the one behind the popup's "Add to dictionary" (see [Known issues](#known-issues)) |
| Incoming call accept/decline with `SHIFT` / `ALT` (off by default) | |
| Stable release signing key via GitHub Actions secrets | |

# Key features

* Auto-correction and word suggestions from built-in dictionaries (see [Dictionaries](#dictionaries-and-auto-correction))
* Learned words: names and slang you use stop being corrected
* Auto-capitalization
* Double-space inserts a period (can be turned off)
* Accented and alternate characters by repeated key presses, with an adjustable timing window
* Fixed `SYM` layer for digits and extra symbols
* Inline autofill (Android 11+)
* Voice input (requires Google Voice Input, `com.google.android.googlequicksearchbox`)
* Emoji and special symbol palettes
* Incoming call accept/decline using shift/alt physical keys

# Keyboard shortcuts

* `SHIFT` (short press) - toggle SHIFT state or accept incoming call (disabled by default)
* `SHIFT` + `KEY` - print KEY in upper case
* `ALT` (short press) - toggle ALT state or decline incoming call (disabled by default)
* `ALT` + `KEY` - print KEY alternate value (digits and common punctuation)
* `ALT` + `SHIFT` or `SPACE` (hold) - switch to the next keyboard layout (input method)
* `CTRL` + `ALT` - launch voice input
* `CTRL` + `KEY` - some default CTRL shortcuts (e.g. `CTRL` + `V`)
* `SYM` (short press, without pressing any other key) - toggle emoji panel visibility
* `SYM` + `KEY` - print the key's symbol (see [SYM layer](#sym-layer))
* `SYM` (hold) - lock the SYM layer on (can be disabled with `Allow SYM lock`)

_Tip:_ to enable incoming call accept/decline go to the PocketBoard settings, turn on `Phone control`
and grant the required permission

_Tip:_ to print an alternate key value, you can simply hold down that key

_Tip:_ some keys have more than one alternate (and main) value. You can iterate over these values by quickly pressing
the same key again. How quickly is set by `Multipress speed` (see [Typing behaviour](#typing-behaviour))

## SYM layer

`SYM` replaces the old configurable SymPad with one fixed layer, the same for every language. It covers digits and the
punctuation that `ALT` does not already provide, so the two modifiers complement each other. Only the letter keys are
mapped; `Backspace`, `Space`, `Enter`, the arrows and other keys behave normally while `SYM` is held.

| Row | Keys | Symbols |
|---|---|---|
| Top | `Q W E R T Y U I O P` | `1 2 3 4 5 6 7 8 9 0` |
| Middle | `A S D F G H J K L` | `; $ % & = [ ] { }` |
| Bottom | `Z X C V B N M` | `~ < > \| \ ^ °` |

`ALT` already types: `0-9 ( ) - _ / : @ * # + " ' ! . , ?`

_Tip:_ to lock the `SYM` layer, hold `SYM` down for about 2-3 seconds. When the lock is active, the `<S>` indicator
is displayed. To go back to normal mode just press `SYM` again.

## Typing behaviour

* **Undo a correction**: when the spacebar corrects a word, pressing `Backspace` right away puts back what you typed,
  still editable, and removes the space. The keyboard then remembers that word as yours (see below) and will not
  correct it again. It only acts if the text before the cursor is exactly the corrected word plus the space, so it
  never touches anything else, and any other key you press first cancels it.
* **Learned words** (Settings, `Learn new words`): a word the dictionary does not know becomes known when you undo a
  correction of it, or after you keep it as typed three times (a typo is rarely typed identically three times; a name
  or a slang word usually is). Learned words are no longer corrected or underlined, are offered as completions and as
  corrections for near misses, and count for every language. See [Learned words](#learned-words) for storage and limits.

* **Multipress speed** (Settings, Input Settings): the maximum time, in milliseconds, between presses of the same key
  for it to count as the next step of its alternate-character cycle (for example `u`, `ú`, `ü`). Range 150-1200 ms,
  default 750 ms. It can be set with the slider or by typing the number, and it takes effect immediately. Lower it if
  fast typing keeps landing on accents by accident.
* **Auto-capitalization** follows Android's sentence rule (after `. ! ?` and a space, and at the start of a field). On
  top of that, a run of two or more terminators followed by a space or newline (`...`, `?!`, `!!`) also capitalizes the
  next letter. A single `.` is still left to Android, so abbreviations such as `e.g. ` are not capitalized, and
  `...hola` (no space) is not capitalized either.
* **Double-space period** only fires on a genuine second tap of `SPACE` shortly after the first. Holding `SPACE`
  repeats spaces and does not insert a period.

# Supported languages and layouts

* English (US)
* Español (Argentina)
* Deutsch (Deutschland)

Each layout has a generic mapping and a Titan Slim mapping (`*_titan.xml`, used automatically on a Titan Slim).

# Dictionaries and auto-correction

Auto-correction and suggestions run entirely inside the app. No Android SpellCheckerSession and no external
spellchecker app are involved. The suggestions bar shows 3 words (one per slot). The app also exposes its own
spellchecker service: when you tap a word underlined in red, the popup lists up to 5 corrections, which is the most
Android's standard text popup displays.

The dictionaries are **not stored in the repository**. `scripts/generate-dictionaries.sh` builds them on every build:
Gradle runs it before the build starts, the GitHub Actions workflow runs it explicitly, and the build fails if the
result is missing or invalid.

1. **What counts as a correct word**: the real `hunspell` spellchecker, run against the Hunspell dictionaries from
   [wooorm/dictionaries](https://github.com/wooorm/dictionaries) (`es-AR`, `en`, `de`). Real `hunspell` is used instead
   of `unmunch` on purpose: `unmunch` cannot expand Hunspell's compound rules, which dropped a large share of everyday
   German words (for example `Krankenhaus`) and some common Spanish forms (for example `aplicación`, `disponible`).
   For German, a word that fails in lower case is retried capitalized, since German nouns are capitalized and the
   compound rules are case-sensitive. This is deliberately not done for Spanish and English, where it would only let in
   proper names.
2. **What counts as an everyday word**: frequency from
   [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles). Valid words are taken
   most-used first until they cover 99.5% of the usage of all valid words (at least 5,000 and at most 70,000 words per
   language), so the vocabulary size is a result of coverage, not a fixed number.
3. **Noise filters**: only letters, apostrophes and hyphens; fragments of English contractions are dropped
   (`don` + `t`); and subtitle stutter such as `no-no`, `que-que` or `ich-ich-ich` is rejected, because `hunspell`
   checks hyphenated words piece by piece and would otherwise accept them.
4. **Always included**: short curated lists, such as Argentine voseo (`tenés`, `podés`, `hacés`...), English
   contractions with apostrophes, and common German function words. On top of those, `scripts/extra-words/es-AR.txt`,
   `en.txt` and `de.txt` hold everyday words that neither Hunspell nor the subtitle corpus cover: regional slang
   (`celu`, `compu`, `finde`, `bondi`, `pibe`), app and brand names (`whatsapp`, `mercadopago`, `bluetooth`, `emoji`)
   and administrative terms (`cuit`, `afip`, `arca`, `monotributo`, `cbu`). They are plain files, one word per line
   with `#` comments, so adding a word is a one-line edit; whitelisted words are always shipped and always indexed for
   correction.
5. **Correction index**: a symmetric-delete index. The most frequent words get 2-typo coverage, the rest 1-typo.
   The whole set is limited to a total size budget (12.5 MB uncompressed by default, roughly 3 MB inside the APK).
   The budget only trims the rare tail, and the build fails if it would be exceeded.

Each dictionary line is `word<TAB>rank` (1 is the most used word). The correction engine ranks candidates by edit
cost and edit distance first. Among candidates tied on both, it prefers the more used word, comparing usage in coarse
tiers (ranks within about 2x of each other tie), and only then falls back to language, keyboard-adjacency and
prefix/suffix evidence, with the exact rank as the final tie-break. A closer match is therefore never overridden just
because another word is more common.

## Measured accuracy

Accuracy was measured with the app's real correction classes and the generated dictionaries, on synthetic typos
(not real typing): words sampled by usage from the top 30,000, then given one realistic error, 800 per error type.
"Top-1" is the intended word being the first suggestion, "top-3" being among the three shown.

| Language | Top-1 | Top-3 (bar) | Top-5 (red-underline popup) |
|---|---|---|---|
| Español (AR) | 72.3% | 84.4% | 87.2% |
| English | 68.4% | 79.7% | 82.4% |
| Deutsch | 73.8% | 83.1% | 85.1% |

Per error type (all three languages are close): a neighbouring key instead of the right one reaches about 84-90%
top-1 and 92-96% top-3; an extra letter 87-90% / about 95%; swapped letters 87-94% / 90-96%. The weak spots are a
**missing letter** (about 51-61% top-1, 73-84% top-3) and **two errors in one word** (about 33-38%, 49-54%), and for
Spanish words typed **without their accent** (about 63%), where many accent-less forms are valid words themselves
(`esta`/`está`) and are skipped by this test. Treat these as relative numbers for comparing changes: real typing
differs from synthetic typos.

Two things were tried against the same measurement. Raising the number of candidates kept per index entry from 3 to 6
did not help (slightly worse, since it uses up the size budget), so it stays at 3. Using the rank as a tier right after
edit cost (the current behaviour) gained about 1-2 points of top-1 over using it only as the last tie-breaker, with
top-3 unchanged.

Optional environment overrides for the script: `TOTAL_BUDGET_BYTES`, `COVERAGE_TARGET`, `MAX_WORDS`,
`DISTANCE2_BUDGET_SHARE`, `MAX_CANDIDATES_PER_DELETE`, `DE_FREQUENCY_SOURCE` (`frequencywords` or `leipzig`) and
`FORCE_DOWNLOAD=1`.

## Learned words

This is not Android's "Add to dictionary". That button saves to the system user dictionary, which this keyboard does
not read (see [Known issues](#known-issues)). The keyboard keeps its own list instead:

* **Where**: `learned-words.tsv` in the app's private storage (device-protected, the same place as the settings), one
  line per word: `word`, how many times it was kept, and when it was last used. The app declares no internet
  permission, so nothing is sent anywhere. It survives updates signed with the same key and is deleted when the app is
  uninstalled. `Settings` > `Clear learned words` empties it (and shows how many words it holds).
* **What is learned**: only words of letters (an apostrophe or hyphen inside is fine), 2 to 40 characters. Numbers,
  codes, words with digits and keyboard mashing such as `holaaaa` are never learned. Words are stored in lower case.
* **Where it does not learn**: password, e-mail and URL fields, numeric fields, and anywhere suggestions are off. With
  `Learn new words` off nothing new is learned, but words already learned keep working until you clear them.
* **Limits**: at most 3,000 entries. When full, words that were never learned go first (oldest first), then learned
  words nobody has used for the longest time. Words seen once or twice and then abandoned expire after 90 days.
* **Which words it can learn by repetition**: only those that are kept as typed. A name close to a dictionary word
  (`nahuel` becomes `aquel`, `facundo` becomes `cuando`) is corrected every time, so the way to teach it is to undo the
  correction with `Backspace` once. A word far from every dictionary word is not corrected at all and is learned after
  three uses.

# What changed compared to upstream

## Added
* Built-in dictionaries and correction engine for es-AR, en and de, generated at build time (see above)
* Learned words and `Backspace` undo of a correction (see [Learned words](#learned-words))
* Up to 5 corrections in the red-underline popup (the bar keeps 3)
* Frequency ranking in the suggestion order (a usage tier right after edit cost, and the final tie-break)
* Editable extra-word lists (`scripts/extra-words/`) for slang, app names and administrative terms
* Fixed `SYM` symbol layer and the `Allow SYM lock` setting
* `Multipress speed` setting (slider plus number box)
* A fixed release signing key (see [Building](#building))

## Fixed
* **Accented characters needed unrealistically fast taps.** Cycling a key's alternates was cut off by a second,
  shorter 400 ms check on top of the real 750 ms window. A third value such as `ü` was almost unreachable, and the
  German `ü`, `ö`, `ä`, `ß` were affected too.
* **Possible crash on first use** on Android 10/11: the call-state listener registered without `READ_PHONE_STATE`,
  and even with `Phone control` off. It now registers only when the feature is on and the permission is granted.
* **Possible crash at startup** when Android had not selected an input subtype yet (common right after enabling the
  IME on a custom ROM). It now falls back to the English layout.
* **Error messages not showing** on Android 13+: toasts were gated behind the notification permission, which a plain
  toast does not need.
* **Cursor jumping back over written text**: re-entering a word for editing used a cursor position the app tracked
  itself, which lags behind fast typing. It now works relative to the real cursor (not yet confirmed on a device, see
  [Known issues](#known-issues)).
* **Words glued together while deleting letters**: after a backspace the keyboard re-read the text to find the word to keep editing. Many editors (WebView, Chrome-based fields) answer that read with the text from before the delete, so it removed one character too many (the space) and brought the deleted letter back. It now reads the text once, before changing anything.
* **Auto-capitalization lagging behind fast typing**: it is now re-checked right after a punctuation mark or space,
  instead of waiting for the editor's asynchronous selection callback.
* **No capital after `...`** (see Typing behaviour).
* **Spurious periods from `SPACE`**: the double-space rule compared timing against any previous key, and it also fired
  on key-repeat events.
* `ALL CAPS` detection ignored words containing an apostrophe or digit (`DON'T`, `COVID19`).
* The layout cache held 2 entries for 3 languages, so the XML was re-parsed on some language switches.
* Suggestion quality: `hunspell`-based validation, the German compound rescue and the stutter filter described above.

## Removed
* **SymPad**: the configurable key grid, its editor screen, JSON import/export, and the app/text/key actions. This
  includes the D-Pad, Home/End, Esc, Backspace/Delete and media-key emulation.
* **Virtual touchpad** (swipe on the suggestions bar to move the cursor), its settings and its hint animation. The
  Titan Slim stock firmware already has its own touchpad under Intelligent Assistant, and the two conflicted.
* `SYSTEM_ALERT_WINDOW` permission and the installed-apps query, which only the SymPad app launcher used.
* Dependency on the external OpenBoard SpellChecker.

## Not implemented here
* `Fn` as `Ctrl`: handled by the Titan Slim SystemUI, so the IME does not do it.

# Building

Releases are built by the **PocketBoard Release** GitHub Actions workflow (`Actions` > `PocketBoard Release` >
`Run workflow`). It installs `hunspell`, generates and validates the dictionaries, signs the APK and uploads it as an
artifact named `Pocketboard-E-release`.

Signing needs two repository secrets (`Settings` > `Secrets and variables` > `Actions`):

* `POCKETBOARD_KEYSTORE_BASE64`: the release keystore (PKCS12), base64-encoded on a single line
* `POCKETBOARD_KEYSTORE_PASSWORD`: its password (the key alias is `pocketboard`; PKCS12 uses one password for both the
  store and the key)

Keep a copy of the keystore and its password somewhere safe. Android only installs an update over an existing install
when the signature matches, so if you lose them you will have to uninstall PocketBoard before installing a build signed
with a new key. The first build signed with this fixed key also requires uninstalling a previous build that was signed
with a different key.

To build the dictionaries locally you need `bash`, `curl`, `python3` and the `hunspell` command
(`sudo apt-get install hunspell`), then run `scripts/generate-dictionaries.sh`.

# Known issues

* A cursor jump related to the `SPACE` key was reported. Two likely causes were fixed (see Fixed), but it has not been
  confirmed as resolved on a device. If you still see it, note what you typed before and after the space.
* Suggestion accuracy is weakest for a missing letter and for two errors in the same word (see
  [Measured accuracy](#measured-accuracy)). Part of it is ordering: looking at the first 10 results instead of 3 finds
  the intended word much more often (a missing letter in English goes from 73% to 84%, in Spanish from 84% to 90%).
  The rest is not ordering: even in the first 10, 10-16% of missing-letter cases never include the intended word.
* The dictionaries only know lower-case words, so proper nouns (people, places) are not suggested unless they are
  added to `scripts/extra-words/` or learned. Learned words are stored in lower case, so a learned name is suggested
  with a capital only if you type its first letter as a capital.
* The Android user dictionary is not used: the `READ_USER_DICTIONARY` permission is still declared, but no code reads
  it. The "Add to dictionary" button in the red-underline popup saves there, so it has no effect on this keyboard; use
  the learned words instead. The `Dictionary shortcuts` setting only applies the current top suggestion.
* `Auto-correction` and `Dictionary shortcuts` do the same thing on the spacebar (both apply the best suggestion, and
  the shortcut one runs first), so turning off only `Auto-correction` while `Dictionary shortcuts` is on does not stop
  corrections. Both are on by default.
* Undoing a correction works for corrections made by the spacebar, not for words picked from the suggestions bar.
* Digits duplication in Telegram login fields on Android < 15 (inherited from upstream, not re-checked)

  _Workaround:_ use the additional symbols palette (press `SYM` and go to the `$` tab) to enter phone and confirmation number

* Inline suggestions password selection dialog doesn't appear on Android < 15 (inherited from upstream, not re-checked)

  _Workaround:_ before clicking on `Passwords` suggestion press `SYM` to open emoji panel. Then you can pick `Passwords`
  suggestion and everything should work as expected

# License

GNU General Public License v3.0, the same as upstream. See [LICENSE](LICENSE).

Dictionary data is downloaded at build time from [wooorm/dictionaries](https://github.com/wooorm/dictionaries) and
[hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) and is subject to those projects' own licenses.
