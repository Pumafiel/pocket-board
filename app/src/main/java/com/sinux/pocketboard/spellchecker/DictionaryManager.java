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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DictionaryManager {

    private static final String TAG =
            "PocketBoardDictionary";

    private static final String FORMAT_HEADER_PREFIX =
            "# PocketBoard dictionary: ";

    private static final int MAX_DICTIONARY_WORDS =
            250000;

    private static final int MAX_PREFIX_RESULTS =
            3;

    private static final int MAX_CORRECTION_CANDIDATES =
            600;

    private static final int MAX_CORRECTION_RESULTS =
            3;

    private static final int MAX_WORD_LENGTH =
            64;

    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyz";

    private static final String SPANISH_EXTRA =
            "áéíóúñ";

    private static final String GERMAN_EXTRA =
            "äöüß";

    private final Context context;

    private final Map<String, Dictionary>
            dictionaries =
            new HashMap<>();

    private final CorrectionEngine
            correctionEngine =
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
                        MAX_CORRECTION_RESULTS
                );

        /*
         * ========================================================
         * 1. COMPLETION
         * ========================================================
         *
         * Prefix suggestions are completely independent from
         * spelling correction.
         */

        List<String> prefixResults =
                getPrefixSuggestions(
                        dictionary,
                        normalizedWord,
                        resultLimit
                );

        /*
         * If the user is typing a prefix, completion wins.
         *
         * We do not send completion candidates through the
         * correction engine.
         */

        if (!dictionary.contains(
                normalizedWord
        )) {

            if (!prefixResults.isEmpty()) {

                return applyCapitalization(
                        prefixResults,
                        word
                );
            }
        }

        /*
         * An exact dictionary word is already correct.
         *
         * We return other completions only when there are any,
         * otherwise no correction is necessary.
         */

        if (dictionary.contains(
                normalizedWord
        )) {

            List<String> alternatives =
                    new ArrayList<>();

            for (String candidate :
                    prefixResults) {

                if (!candidate.equals(
                        normalizedWord
                )) {

                    alternatives.add(
                            candidate
                    );
                }
            }

            return applyCapitalization(
                    alternatives,
                    word
            );
        }

        /*
         * ========================================================
         * 2. CORRECTION
         * ========================================================
         */

        List<String> candidates =
                collectCorrectionCandidates(
                        normalizedWord,
                        language,
                        dictionary
                );

        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> ranked =
                correctionEngine.rankCandidates(
                        normalizedWord,
                        candidates,
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

        List<String> flags =
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

            /*
             * The dictionary generator writes:
             *
             *     # PocketBoard dictionary: es-AR
             *     # PocketBoard dictionary: en-en
             *     # PocketBoard dictionary: de-de
             *
             * The runtime language is normalized to:
             *
             *     es-AR
             *     en
             *     de
             *
             * while the generated asset keeps the source
             * dictionary identifier:
             *
             *     es-AR
             *     en-en
             *     de-de
             *
             * Therefore the expected header is derived from
             * the asset name rather than from the normalized
             * runtime language.
             */

            String expectedDictionaryId =
                    getDictionaryId(
                            language
                    );

            String expectedHeader =
                    FORMAT_HEADER_PREFIX +
                            expectedDictionaryId;

            if (!expectedHeader.equals(
                    header
            )) {

                Log.e(
                        TAG,
                        "Invalid dictionary header. " +
                                "language=" +
                                language +
                                ", asset=" +
                                assetName +
                                ", expected='" +
                                expectedHeader +
                                "', actual='" +
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
                String wordFlags;

                if (separator >= 0) {

                    word =
                            line.substring(
                                    0,
                                    separator
                            );

                    wordFlags =
                            line.substring(
                                    separator + 1
                            );

                } else {

                    word = line;
                    wordFlags = "";
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

                flags.add(
                        wordFlags.trim()
                );

                if (words.size() >=
                        MAX_DICTIONARY_WORDS) {

                    break;
                }
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

        String[] wordArray =
                words.toArray(
                        new String[0]
                );

        String[] flagArray =
                flags.toArray(
                        new String[0]
                );

        sortEntries(
                wordArray,
                flagArray
        );

        /*
         * Remove duplicate words after sorting.
         */

        Dictionary dictionary =
                compactDictionary(
                        wordArray,
                        flagArray
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

    private void sortEntries(
            String[] words,
            String[] flags) {

        Integer[] indexes =
                new Integer[
                        words.length
                ];

        for (int i = 0;
             i < indexes.length;
             i++) {

            indexes[i] = i;
        }

        Arrays.sort(
                indexes,
                Comparator.comparing(
                        i -> words[i]
                )
        );

        String[] sortedWords =
                words.clone();

        String[] sortedFlags =
                flags.clone();

        for (int i = 0;
             i < indexes.length;
             i++) {

            int source =
                    indexes[i];

            words[i] =
                    sortedWords[source];

            flags[i] =
                    sortedFlags[source];
        }
    }

    private Dictionary compactDictionary(
            String[] words,
            String[] flags) {

        if (words.length == 0) {
            return emptyDictionary();
        }

        List<String> compactWords =
                new ArrayList<>();

        List<String> compactFlags =
                new ArrayList<>();

        String previous = null;

        for (int i = 0;
             i < words.length;
             i++) {

            String current =
                    words[i];

            if (current.equals(
                    previous
            )) {

                continue;
            }

            compactWords.add(
                    current
            );

            compactFlags.add(
                    flags[i]
            );

            previous = current;
        }

        return new Dictionary(
                compactWords.toArray(
                        new String[0]
                ),
                compactFlags.toArray(
                        new String[0]
                )
        );
    }

    private Dictionary emptyDictionary() {

        return new Dictionary(
                new String[0],
                new String[0]
        );
    }

    /*
     * ============================================================
     * COMPLETION
     * ============================================================
     */

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
                index < dictionary.size() &&
                        results.size() < maxResults;
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

        int high =
                dictionary.size();

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
     * CORRECTION CANDIDATES
     * ============================================================
     *
     * Every candidate must exist EXACTLY in the dictionary.
     *
     * We never use prefix matching here.
     */

    private List<String> collectCorrectionCandidates(
            String input,
            String language,
            Dictionary dictionary) {

        LinkedHashSet<String> candidates =
                new LinkedHashSet<>();

        addLanguageCandidates(
                input,
                language,
                dictionary,
                candidates
        );

        addDeletionCandidates(
                input,
                dictionary,
                candidates
        );

        addInsertionCandidates(
                input,
                language,
                dictionary,
                candidates
        );

        addTranspositionCandidates(
                input,
                dictionary,
                candidates
        );

        addSubstitutionCandidates(
                input,
                language,
                dictionary,
                candidates
        );

        addRepetitionCandidates(
                input,
                dictionary,
                candidates
        );

        if (candidates.size() >
                MAX_CORRECTION_CANDIDATES) {

            List<String> limited =
                    new ArrayList<>(
                            candidates
                    );

            return new ArrayList<>(
                    limited.subList(
                            0,
                            MAX_CORRECTION_CANDIDATES
                    )
            );
        }

        return new ArrayList<>(
                candidates
        );
    }

    private void addLanguageCandidates(
            String input,
            String language,
            Dictionary dictionary,
            Set<String> candidates) {

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
            }
        }
    }

    private void addDeletionCandidates(
            String input,
            Dictionary dictionary,
            Set<String> candidates) {

        for (int i = 0;
             i < input.length();
             i++) {

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

    private void addInsertionCandidates(
            String input,
            String language,
            Dictionary dictionary,
            Set<String> candidates) {

        String alphabet =
                getAlphabet(
                        language
                );

        for (int position = 0;
             position <= input.length();
             position++) {

            for (int i = 0;
                 i < alphabet.length();
                 i++) {

                char inserted =
                        alphabet.charAt(i);

                String variant =
                        input.substring(
                                0,
                                position
                        ) +
                        inserted +
                        input.substring(
                                position
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
    }

    private void addTranspositionCandidates(
            String input,
            Dictionary dictionary,
            Set<String> candidates) {

        for (int i = 0;
             i + 1 < input.length();
             i++) {

            if (input.charAt(i) ==
                    input.charAt(i + 1)) {

                continue;
            }

            char[] chars =
                    input.toCharArray();

            char temporary =
                    chars[i];

            chars[i] =
                    chars[i + 1];

            chars[i + 1] =
                    temporary;

            addExactCandidate(
                    new String(chars),
                    dictionary,
                    candidates
            );

            if (candidates.size() >=
                    MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }

    private void addSubstitutionCandidates(
            String input,
            String language,
            Dictionary dictionary,
            Set<String> candidates) {

        String alphabet =
                getAlphabet(
                        language
                );

        for (int position = 0;
             position < input.length();
             position++) {

            char original =
                    input.charAt(position);

            /*
             * First try physical keyboard neighbors.
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
            }

            /*
             * Then language-specific substitutions.
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
            }

            if (candidates.size() >=
                    MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }

    private void addRepetitionCandidates(
            String input,
            Dictionary dictionary,
            Set<String> candidates) {

        /*
         * helllo -> hello
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
        }
    }

    private void addExactCandidate(
            String candidate,
            Dictionary dictionary,
            Set<String> candidates) {

        if (candidate == null ||
                candidate.isEmpty()) {

            return;
        }

        if (candidate.equals(
                normalizeWord(candidate)
        ) &&
                dictionary.contains(
                        candidate
                )) {

            candidates.add(
                    candidate
            );
        }
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

        boolean allUpper = true;

        for (int i = 0;
             i < original.length();
             i++) {

            char c =
                    original.charAt(i);

            if (Character.isLetter(c) &&
                    !Character.isUpperCase(c)) {

                allUpper = false;
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
                word.length() >
                        MAX_WORD_LENGTH) {

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

    /*
     * ============================================================
     * INTERNAL DICTIONARY
     * ============================================================
     */

    private static final class Dictionary {

        private final String[] words;
        private final String[] flags;

        Dictionary(
                String[] words,
                String[] flags) {

            this.words = words;
            this.flags = flags;
        }

        int size() {
            return words.length;
        }

        boolean isEmpty() {
            return words.length == 0;
        }

        String get(int index) {
            return words[index];
        }

        boolean contains(
                String word) {

            if (word == null ||
                    words.length == 0) {

                return false;
            }

            int low = 0;
            int high = words.length - 1;

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
}
