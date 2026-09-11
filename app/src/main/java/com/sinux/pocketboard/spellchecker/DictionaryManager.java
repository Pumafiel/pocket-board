package com.sinux.pocketboard.spellchecker;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DictionaryManager {

    private static final String TAG =
            "PocketBoardDictionary";

    private static final String LEGACY_HEADER_PREFIX =
            "# PocketBoard dictionary: ";

    private static final String GENERATED_DICTIONARY_HEADER =
            "#POCKETBOARD-DICT-1";

    private static final String DELETE_HEADER =
            "#POCKETBOARD-DELETES-1";

    private static final int MAX_PREFIX_CANDIDATES =
            32;

    private static final int MAX_CORRECTION_CANDIDATES =
            600;

    private static final int MAX_RESULTS =
            3;

    private static final int MAX_WORD_LENGTH =
            64;

    private static final int MAX_DELETE_DISTANCE =
            2;

    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyz";

    private static final String SPANISH_EXTRA =
            "áéíóúñ";

    private static final String GERMAN_EXTRA =
            "äöüß";

    private final Context context;

    private final Map<String, Dictionary> dictionaries =
            new HashMap<>();

    private final Map<String, DeleteIndex> deleteIndexes =
            new HashMap<>();

    private final CorrectionEngine correctionEngine =
            new CorrectionEngine();

    public DictionaryManager(
            Context context) {

        this.context =
                context.getApplicationContext();
    }

    /*
     * ============================================================
     * PUBLIC API
     * ============================================================
     */

    public synchronized List<String> getSuggestions(
            String word,
            String languageTag,
            int maxResults) {

        if (TextUtils.isEmpty(word) ||
                maxResults <= 0) {

            return new ArrayList<>();
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        Dictionary dictionary =
                getDictionary(
                        language
                );

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return new ArrayList<>();
        }

        String normalizedWord =
                normalizeWord(
                        word
                );

        if (normalizedWord.isEmpty()) {
            return new ArrayList<>();
        }

        int resultLimit =
                Math.min(
                        maxResults,
                        MAX_RESULTS
                );

        boolean exactMatch =
                dictionary.contains(
                        normalizedWord
                );

        /*
         * A valid exact word must remain the strongest
         * interpretation of what the user actually typed.
         *
         * We still expose completions as alternatives.
         */
        if (exactMatch) {

            LinkedHashSet<String> alternatives =
                    new LinkedHashSet<>();

            addPrefixCandidates(
                    dictionary,
                    normalizedWord,
                    alternatives
            );

            addOwnLanguageCandidates(
                    normalizedWord,
                    language,
                    dictionary,
                    alternatives
            );

            alternatives.remove(
                    normalizedWord
            );

            List<String> ranked =
                    rankCandidates(
                            normalizedWord,
                            new ArrayList<>(
                                    alternatives
                            ),
                            language,
                            Math.max(
                                    resultLimit - 1,
                                    0
                            )
                    );

            List<String> results =
                    new ArrayList<>(
                            resultLimit
                    );

            results.add(
                    normalizedWord
            );

            for (String candidate :
                    ranked) {

                if (results.size() >=
                        resultLimit) {

                    break;
                }

                if (candidate == null ||
                        candidate.equals(
                                normalizedWord
                        )) {

                    continue;
                }

                results.add(
                        candidate
                );
            }

            return applyCapitalization(
                    results,
                    word
            );
        }

        /*
         * Non-exact input:
         *
         * 1. prefix candidates
         * 2. SymSpell candidates
         * 3. our language-specific candidates
         * 4. keyboard/language substitutions
         * 5. repetition handling
         *
         * CorrectionEngine decides the final order.
         */
        LinkedHashSet<String> candidates =
                new LinkedHashSet<>();

        addPrefixCandidates(
                dictionary,
                normalizedWord,
                candidates
        );

        addDeleteIndexCandidates(
                normalizedWord,
                language,
                dictionary,
                candidates
        );

        addOwnLanguageCandidates(
                normalizedWord,
                language,
                dictionary,
                candidates
        );

        /*
         * Symmetric-delete handles the vast majority of
         * edit-distance candidates. Our own models remain as
         * complementary sources, especially for keyboard and
         * language-specific errors.
         */
        addKeyboardAndLanguageCandidates(
                normalizedWord,
                language,
                dictionary,
                candidates
        );

        addRepetitionCandidates(
                normalizedWord,
                dictionary,
                candidates
        );

        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> ranked =
                rankCandidates(
                        normalizedWord,
                        new ArrayList<>(
                                candidates
                        ),
                        language,
                        resultLimit
                );

        return applyCapitalization(
                ranked,
                word
        );
    }

    public synchronized boolean contains(
            String word,
            String languageTag) {

        if (TextUtils.isEmpty(word)) {
            return false;
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        Dictionary dictionary =
                getDictionary(
                        language
                );

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return false;
        }

        return dictionary.contains(
                normalizeWord(
                        word
                )
        );
    }

    /*
     * ============================================================
     * RANKING
     * ============================================================
     */

    private List<String> rankCandidates(
            String input,
            List<String> candidates,
            String language,
            int maxResults) {

        if (candidates == null ||
                candidates.isEmpty() ||
                maxResults <= 0) {

            return new ArrayList<>();
        }

        return correctionEngine.rankCandidates(
                input,
                candidates,
                language,
                maxResults
        );
    }

    /*
     * ============================================================
     * PREFIX COMPLETION
     * ============================================================
     */

    private void addPrefixCandidates(
            Dictionary dictionary,
            String prefix,
            Set<String> candidates) {

        if (dictionary == null ||
                dictionary.isEmpty() ||
                prefix == null ||
                prefix.isEmpty()) {

            return;
        }

        List<String> prefixResults =
                getPrefixSuggestions(
                        dictionary,
                        prefix,
                        MAX_PREFIX_CANDIDATES
                );

        for (String candidate :
                prefixResults) {

            if (candidate == null ||
                    candidate.isEmpty()) {

                continue;
            }

            candidates.add(
                    candidate
            );

            if (candidates.size() >=
                    MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }

    private List<String> getPrefixSuggestions(
            Dictionary dictionary,
            String prefix,
            int maxResults) {

        List<String> results =
                new ArrayList<>();

        if (dictionary == null ||
                dictionary.isEmpty() ||
                prefix == null ||
                prefix.isEmpty() ||
                maxResults <= 0) {

            return results;
        }

        int start =
                findPrefixStart(
                        dictionary,
                        prefix
                );

        for (
                int index = start;
                index < dictionary.size()
                        && results.size() < maxResults;
                index++
        ) {

            String candidate =
                    dictionary.get(
                            index
                    );

            if (!candidate.startsWith(
                    prefix
            )) {

                break;
            }

            results.add(
                    candidate
            );
        }

        return results;
    }

    private int findPrefixStart(
            Dictionary dictionary,
            String prefix) {

        int low = 0;
        int high = dictionary.size();

        while (low < high) {

            int middle =
                    (low + high) >>> 1;

            String candidate =
                    dictionary.get(
                            middle
                    );

            if (candidate.compareTo(
                    prefix
            ) < 0) {

                low =
                        middle + 1;

            } else {

                high =
                        middle;
            }
        }

        return low;
    }

    /*
     * ============================================================
     * SYMMETRIC DELETE INDEX
     * ============================================================
     */

    private void addDeleteIndexCandidates(
            String input,
            String language,
            Dictionary dictionary,
            Set<String> candidates) {

        if (input == null ||
                input.isEmpty() ||
                dictionary == null ||
                dictionary.isEmpty()) {

            return;
        }

        DeleteIndex index =
                getDeleteIndex(
                        language
                );

        if (index == null ||
                index.isEmpty()) {

            /*
             * The generated index is an optimization, not the
             * only source of candidates.
             */
            return;
        }

        LinkedHashSet<String> deletes =
                new LinkedHashSet<>();

        generateDeletes(
                input,
                MAX_DELETE_DISTANCE,
                deletes
        );

        /*
         * The original word itself can also be a delete key when
         * the candidate differs by insertion.
         */
        deletes.add(
                input
        );

        for (String delete :
                deletes) {

            List<String> matches =
                    index.find(
                            delete
                    );

            for (String candidate :
                    matches) {

                if (candidate == null ||
                        candidate.equals(
                                input
                        )) {

                    continue;
                }

                if (!dictionary.contains(
                        candidate
                )) {

                    continue;
                }

                candidates.add(
                        candidate
                );

                if (candidates.size() >=
                        MAX_CORRECTION_CANDIDATES) {

                    return;
                }
            }
        }
    }

    /*
     * SymSpell's symmetric-delete operation.
     *
     * This is intentionally performed only for the user's
     * current word at runtime. The expensive dictionary-wide
     * delete generation happens in the build script.
     */
    private void generateDeletes(
            String word,
            int maxDistance,
            Set<String> output) {

        if (word == null ||
                word.isEmpty() ||
                maxDistance <= 0) {

            return;
        }

        generateDeletesRecursive(
                word,
                maxDistance,
                output
        );
    }

    private void generateDeletesRecursive(
            String current,
            int remainingDistance,
            Set<String> output) {

        if (remainingDistance <= 0 ||
                current.isEmpty()) {

            return;
        }

        for (int i = 0;
             i < current.length();
             i++) {

            String deleted =
                    current.substring(
                            0,
                            i
                    ) +
                    current.substring(
                            i + 1
                    );

            if (output.add(
                    deleted
            )) {

                generateDeletesRecursive(
                        deleted,
                        remainingDistance - 1,
                        output
                );
            }
        }
    }

    private DeleteIndex getDeleteIndex(
            String language) {

        DeleteIndex index =
                deleteIndexes.get(
                        language
                );

        if (index != null) {
            return index;
        }

        index =
                loadDeleteIndex(
                        language
                );

        deleteIndexes.put(
                language,
                index
        );

        return index;
    }

    private DeleteIndex loadDeleteIndex(
            String language) {

        String assetName =
                "dictionaries/" +
                        getDeleteAssetName(
                                language
                        );

        List<String> keys =
                new ArrayList<>();

        List<String> words =
                new ArrayList<>();

        try (
                InputStream inputStream =
                        context.getAssets().open(
                                assetName
                        );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String header =
                    reader.readLine();

            if (header == null ||
                    !header.startsWith(
                            DELETE_HEADER
                    )) {

                Log.e(
                        TAG,
                        "Invalid delete index header. " +
                                "language=" +
                                language +
                                ", asset=" +
                                assetName +
                                ", actual='" +
                                header +
                                "'"
                );

                return emptyDeleteIndex();
            }

            String line;

            while (
                    (line = reader.readLine()) != null
            ) {

                if (line.isEmpty() ||
                        line.startsWith("#")) {

                    continue;
                }

                int separator =
                        line.indexOf('\t');

                if (separator <= 0 ||
                        separator >=
                                line.length() - 1) {

                    continue;
                }

                String deleteKey =
                        normalizeWord(
                                line.substring(
                                        0,
                                        separator
                                )
                        );

                String candidate =
                        normalizeWord(
                                line.substring(
                                        separator + 1
                                )
                        );

                if (deleteKey.isEmpty() ||
                        candidate.isEmpty()) {

                    continue;
                }

                if (!isValidWord(
                        candidate
                )) {

                    continue;
                }

                keys.add(
                        deleteKey
                );

                words.add(
                        candidate
                );
            }

        } catch (IOException exception) {

            Log.e(
                    TAG,
                    "Could not load delete index. " +
                            "language=" +
                            language +
                            ", asset=" +
                            assetName,
                    exception
            );

            return emptyDeleteIndex();
        }

        if (keys.isEmpty()) {

            Log.w(
                    TAG,
                    "Delete index is empty. " +
                            "language=" +
                            language +
                            ", asset=" +
                            assetName
            );

            return emptyDeleteIndex();
        }

        String[] keyArray =
                keys.toArray(
                        new String[0]
                );

        String[] wordArray =
                words.toArray(
                        new String[0]
                );

        DeleteIndex index =
                new DeleteIndex(
                        keyArray,
                        wordArray
                );

        Log.i(
                TAG,
                "Delete index loaded. " +
                        "language=" +
                        language +
                        ", entries=" +
                        index.size()
        );

        return index;
    }

    private DeleteIndex emptyDeleteIndex() {

        return new DeleteIndex(
                new String[0],
                new String[0]
        );
    }

    /*
     * ============================================================
     * OUR LANGUAGE-SPECIFIC CANDIDATES
     * ============================================================
     */

    private void addOwnLanguageCandidates(
            String input,
            String language,
            Dictionary dictionary,
            Set<String> candidates) {

        if (input == null ||
                input.isEmpty()) {

            return;
        }

        List<String> variants =
                LanguageRules.getDiacriticVariants(
                        input,
                        language
                );

        for (String variant :
                variants) {

            addExactCandidate(
                    variant,
                    dictionary,
                    candidates
            );

            if (candidates.size() >=
                    MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }

        /*
         * Spanish n <-> ñ.
         */
        if ("es-AR".equals(
                language
        )) {

            for (int i = 0;
                 i < input.length();
                 i++) {

                char current =
                        input.charAt(i);

                if (current != 'n' &&
                        current != 'ñ') {

                    continue;
                }

                char replacement =
                        current == 'n'
                                ? 'ñ'
                                : 'n';

                addExactCandidate(
                        replaceCharacter(
                                input,
                                i,
                                replacement
                        ),
                        dictionary,
                        candidates
                );

                if (candidates.size() >=
                        MAX_CORRECTION_CANDIDATES) {

                    return;
                }
            }
        }
    }

    /*
     * ============================================================
     * KEYBOARD + LANGUAGE CANDIDATES
     * ============================================================
     */

    private void addKeyboardAndLanguageCandidates(
            String input,
            String language,
            Dictionary dictionary,
            Set<String> candidates) {

        if (input == null ||
                input.isEmpty()) {

            return;
        }

        String alphabet =
                getAlphabet(
                        language
                );

        for (int position = 0;
             position < input.length();
             position++) {

            char original =
                    input.charAt(
                            position
                    );

            /*
             * Keyboard-aware substitutions.
             *
             * We deliberately do not generate every alphabet
             * substitution. The keyboard model is used as a
             * candidate gate.
             */
            for (int i = 0;
                 i < alphabet.length();
                 i++) {

                char replacement =
                        alphabet.charAt(i);

                if (replacement ==
                        original) {

                    continue;
                }

                int keyboardCost =
                        KeyboardErrorModel
                                .getSubstitutionCost(
                                        original,
                                        replacement,
                                        language
                                );

                if (keyboardCost > 2) {
                    continue;
                }

                addExactCandidate(
                        replaceCharacter(
                                input,
                                position,
                                replacement
                        ),
                        dictionary,
                        candidates
                );

                if (candidates.size() >=
                        MAX_CORRECTION_CANDIDATES) {

                    return;
                }
            }

            /*
             * Language-specific substitutions.
             */
            for (int i = 0;
                 i < alphabet.length();
                 i++) {

                char replacement =
                        alphabet.charAt(i);

                if (replacement ==
                        original) {

                    continue;
                }

                int languageCost =
                        LanguageRules
                                .getCharacterSubstitutionCost(
                                        original,
                                        replacement,
                                        language
                                );

                if (languageCost >= 5) {
                    continue;
                }

                addExactCandidate(
                        replaceCharacter(
                                input,
                                position,
                                replacement
                        ),
                        dictionary,
                        candidates
                );

                if (candidates.size() >=
                        MAX_CORRECTION_CANDIDATES) {

                    return;
                }
            }
        }
    }

    /*
     * ============================================================
     * REPETITION
     * ============================================================
     */

    private void addRepetitionCandidates(
            String input,
            Dictionary dictionary,
            Set<String> candidates) {

        if (input == null ||
                input.length() < 2) {

            return;
        }

        /*
         * Example:
         *
         *     helllo -> hello
         */
        for (int i = 0;
             i < input.length() - 1;
             i++) {

            if (input.charAt(i) !=
                    input.charAt(i + 1)) {

                continue;
            }

            String variant =
                    input.substring(
                            0,
                            i
                    ) +
                    input.substring(
                            i + 1
                    );

            addExactCandidate(
                    variant,
                    dictionary,
                    candidates
            );

            if (candidates.size() >=
                    MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }

    /*
     * ============================================================
     * EXACT CANDIDATE
     * ============================================================
     */

    private void addExactCandidate(
            String candidate,
            Dictionary dictionary,
            Set<String> candidates) {

        if (candidate == null ||
                candidate.isEmpty() ||
                dictionary == null) {

            return;
        }

        String normalized =
                normalizeWord(
                        candidate
                );

        if (!candidate.equals(
                normalized
        )) {

            return;
        }

        if (!isValidWord(
                normalized
        )) {

            return;
        }

        if (dictionary.contains(
                normalized
        )) {

            candidates.add(
                    normalized
            );
        }
    }

    /*
     * ============================================================
     * DICTIONARY LOADING
     * ============================================================
     */

    private Dictionary getDictionary(
            String language) {

        Dictionary dictionary =
                dictionaries.get(
                        language
                );

        if (dictionary != null) {
            return dictionary;
        }

        dictionary =
                loadDictionary(
                        language
                );

        dictionaries.put(
                language,
                dictionary
        );

        return dictionary;
    }

    private Dictionary loadDictionary(
            String language) {

        String assetName =
                "dictionaries/" +
                        getAssetName(
                                language
                        );

        List<String> words =
                new ArrayList<>();

        try (
                InputStream inputStream =
                        context.getAssets().open(
                                assetName
                        );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String header =
                    reader.readLine();

            String expectedDictionaryId =
                    getDictionaryId(
                            language
                    );

            boolean generatedFormat =
                    GENERATED_DICTIONARY_HEADER.equals(
                            header
                    );

            boolean legacyFormat =
                    header != null &&
                            header.equals(
                                    LEGACY_HEADER_PREFIX +
                                            expectedDictionaryId
                            );

            if (!generatedFormat &&
                    !legacyFormat) {

                Log.e(
                        TAG,
                        "Invalid dictionary header. " +
                                "language=" +
                                language +
                                ", asset=" +
                                assetName +
                                ", actual='" +
                                header +
                                "'"
                );

                return emptyDictionary();
            }

            String line;

            while (
                    (line = reader.readLine()) != null
            ) {

                if (line.isEmpty() ||
                        line.startsWith("#")) {

                    continue;
                }

                int separator =
                        line.indexOf('\t');

                String word;

                if (separator >= 0) {

                    word =
                            line.substring(
                                    0,
                                    separator
                            );

                } else {

                    word =
                            line;
                }

                word =
                        normalizeWord(
                                word
                        );

                if (!isValidWord(
                        word
                )) {

                    continue;
                }

                words.add(
                        word
                );
            }

        } catch (IOException exception) {

            Log.e(
                    TAG,
                    "Could not load dictionary. " +
                            "language=" +
                            language +
                            ", asset=" +
                            assetName,
                    exception
            );

            return emptyDictionary();
        }

        if (words.isEmpty()) {

            Log.e(
                    TAG,
                    "Dictionary loaded but contains " +
                            "no valid words. " +
                            "language=" +
                            language +
                            ", asset=" +
                            assetName
            );

            return emptyDictionary();
        }

        /*
         * The generated dictionary is already sorted and unique.
         * We still defensively sort here because the runtime
         * prefix lookup depends on ordering.
         */
        String[] wordArray =
                words.toArray(
                        new String[0]
                );

        java.util.Arrays.sort(
                wordArray
        );

        List<String> unique =
                new ArrayList<>(
                        wordArray.length
                );

        String previous =
                null;

        for (String word :
                wordArray) {

            if (word.equals(
                    previous
            )) {

                continue;
            }

            unique.add(
                    word
            );

            previous =
                    word;
        }

        Dictionary dictionary =
                new Dictionary(
                        unique.toArray(
                                new String[0]
                        )
                );

        Log.i(
                TAG,
                "Dictionary loaded successfully. " +
                        "language=" +
                        language +
                        ", asset=" +
                        assetName +
                        ", words=" +
                        dictionary.size()
        );

        return dictionary;
    }

    private Dictionary emptyDictionary() {

        return new Dictionary(
                new String[0]
        );
    }

    /*
     * ============================================================
     * HELPERS
     * ============================================================
     */

    private String getAlphabet(
            String language) {

        if ("es-AR".equals(
                language
        )) {

            return ALPHABET +
                    SPANISH_EXTRA;
        }

        if ("de".equals(
                language
        )) {

            return ALPHABET +
                    GERMAN_EXTRA;
        }

        return ALPHABET;
    }

    private String replaceCharacter(
            String input,
            int position,
            char replacement) {

        StringBuilder builder =
                new StringBuilder(
                        input
                );

        builder.setCharAt(
                position,
                replacement
        );

        return builder.toString();
    }

    /*
     * ============================================================
     * CAPITALIZATION
     * ============================================================
     */

    private List<String> applyCapitalization(
            List<String> suggestions,
            String original) {

        List<String> results =
                new ArrayList<>();

        if (suggestions == null ||
                suggestions.isEmpty()) {

            return results;
        }

        for (String suggestion :
                suggestions) {

            results.add(
                    applyCapitalization(
                            suggestion,
                            original
                    )
            );
        }

        return results;
    }

    private String applyCapitalization(
            String suggestion,
            String original) {

        if (suggestion == null ||
                TextUtils.isEmpty(
                        original
                )) {

            return suggestion;
        }

        boolean allUpper =
                true;

        for (int i = 0;
             i < original.length();
             i++) {

            char c =
                    original.charAt(i);

            if (Character.isLetter(c) &&
                    !Character.isUpperCase(c)) {

                allUpper =
                        false;

                break;
            }
        }

        if (allUpper) {

            return suggestion.toUpperCase(
                    Locale.ROOT
            );
        }

        if (Character.isUpperCase(
                original.charAt(0)
        )) {

            return Character.toUpperCase(
                    suggestion.charAt(0)
            ) +
                    suggestion.substring(1);
        }

        return suggestion;
    }

    /*
     * ============================================================
     * NORMALIZATION
     * ============================================================
     */

    private String normalizeLanguage(
            String languageTag) {

        return LanguageRules.normalizeLanguage(
                languageTag
        );
    }

    private String normalizeWord(
            String word) {

        if (word == null) {
            return "";
        }

        return word
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private boolean isValidWord(
            String word) {

        if (word == null ||
                word.length() < 1 ||
                word.length() > MAX_WORD_LENGTH) {

            return false;
        }

        for (int i = 0;
             i < word.length();
             i++) {

            char c =
                    word.charAt(i);

            if (Character.isLetter(c) ||
                    c == '\'' ||
                    c == '-') {

                continue;
            }

            return false;
        }

        return true;
    }

    private String getDictionaryId(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR";

            case "de":
                return "de-de";

            case "en":
            default:
                return "en-en";
        }
    }

    private String getAssetName(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.dict";

            case "de":
                return "de-de.dict";

            case "en":
            default:
                return "en-en.dict";
        }
    }

    private String getDeleteAssetName(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.deletes";

            case "de":
                return "de-de.deletes";

            case "en":
            default:
                return "en-en.deletes";
        }
    }

    /*
     * ============================================================
     * INTERNAL DICTIONARY
     * ============================================================
     */

    private static final class Dictionary {

        private final String[] words;

        Dictionary(
                String[] words) {

            this.words =
                    words;
        }

        int size() {

            return words.length;
        }

        boolean isEmpty() {

            return words.length == 0;
        }

        String get(
                int index) {

            return words[index];
        }

        boolean contains(
                String word) {

            if (word == null ||
                    words.length == 0) {

                return false;
            }

            int low = 0;
            int high =
                    words.length - 1;

            while (low <= high) {

                int middle =
                        (low + high) >>> 1;

                int comparison =
                        words[middle]
                                .compareTo(
                                        word
                                );

                if (comparison < 0) {

                    low =
                            middle + 1;

                } else if (
                        comparison > 0
                ) {

                    high =
                            middle - 1;

                } else {

                    return true;
                }
            }

            return false;
        }
    }

    /*
     * ============================================================
     * INTERNAL DELETE INDEX
     * ============================================================
     *
     * The build script writes:
     *
     *     delete<TAB>word
     *
     * sorted by delete and then word.
     *
     * We keep parallel arrays instead of HashMap<String, List<>>
     * because a large HashMap of Java objects would consume much
     * more RAM on Android.
     * ============================================================
     */

    private static final class DeleteIndex {

        private final String[] keys;
        private final String[] words;

        DeleteIndex(
                String[] keys,
                String[] words) {

            this.keys =
                    keys;

            this.words =
                    words;
        }

        int size() {

            return keys.length;
        }

        boolean isEmpty() {

            return keys.length == 0;
        }

        List<String> find(
                String key) {

            List<String> results =
                    new ArrayList<>();

            if (key == null ||
                    key.isEmpty() ||
                    keys.length == 0) {

                return results;
            }

            int start =
                    lowerBound(
                            key
                    );

            for (int i = start;
                 i < keys.length;
                 i++) {

                int comparison =
                        keys[i].compareTo(
                                key
                        );

                if (comparison != 0) {
                    break;
                }

                results.add(
                        words[i]
                );
            }

            return results;
        }

        private int lowerBound(
                String key) {

            int low = 0;
            int high =
                    keys.length;

            while (low < high) {

                int middle =
                        (low + high) >>> 1;

                if (keys[middle].compareTo(
                        key
                ) < 0) {

                    low =
                            middle + 1;

                } else {

                    high =
                            middle;
                }
            }

            return low;
        }
    }
}
