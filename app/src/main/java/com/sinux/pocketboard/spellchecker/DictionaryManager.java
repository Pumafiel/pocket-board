package com.sinux.pocketboard.spellchecker;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DictionaryManager {

    private static final int MAX_DICTIONARY_WORDS = 100000;
    private static final int MAX_PREFIX_RESULTS = 20;
    private static final int MAX_SPELLING_RESULTS = 10;

    private final Context context;

    private final Map<String, List<String>> dictionaries = new HashMap<>();
    private final Map<String, Boolean> loadedLanguages = new HashMap<>();

    public DictionaryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns suggestions for the supplied word.
     *
     * The manager first searches for words beginning with the
     * supplied text. If there are not enough results, it also
     * searches for words with a small spelling distance.
     */
    public synchronized List<String> getSuggestions(
            String word,
            String languageTag,
            int maxResults) {

        if (TextUtils.isEmpty(word) || maxResults <= 0) {
            return Collections.emptyList();
        }

        String language = normalizeLanguage(languageTag);

        loadDictionary(language);

        List<String> dictionary = dictionaries.get(language);

        if (dictionary == null || dictionary.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedWord = word.toLowerCase(Locale.ROOT);

        Set<String> results = new LinkedHashSet<>();

        /*
         * First: prefix matches.
         *
         * Example:
         *
         * ca -> casa
         *      camino
         *      cambio
         */
        for (String candidate : dictionary) {

            if (candidate.startsWith(normalizedWord)) {
                results.add(candidate);

                if (results.size() >= Math.min(
                        maxResults,
                        MAX_PREFIX_RESULTS
                )) {
                    break;
                }
            }
        }

        /*
         * If we don't have enough prefix matches, look for
         * spelling corrections.
         *
         * Example:
         *
         * "csa" -> "casa"
         * "hallo" -> "Hallo"
         */
        if (results.size() < maxResults) {

            int maxDistance = getMaximumDistance(normalizedWord);

            List<ScoredWord> spellingResults = new ArrayList<>();

            for (String candidate : dictionary) {

                if (results.contains(candidate)) {
                    continue;
                }

                if (Math.abs(
                        candidate.length() - normalizedWord.length()
                ) > maxDistance) {
                    continue;
                }

                int distance = levenshteinDistance(
                        normalizedWord,
                        candidate
                );

                if (distance <= maxDistance) {
                    spellingResults.add(
                            new ScoredWord(candidate, distance)
                    );
                }
            }

            spellingResults.sort(
                    Comparator.comparingInt(
                            ScoredWord::getDistance
                    ).thenComparing(
                            ScoredWord::getWord
                    )
            );

            for (ScoredWord scoredWord : spellingResults) {

                results.add(scoredWord.getWord());

                if (results.size() >= maxResults) {
                    break;
                }

                if (spellingResults.size() >= MAX_SPELLING_RESULTS &&
                        results.size() >= maxResults) {
                    break;
                }
            }
        }

        List<String> finalResults =
                new ArrayList<>(results);

        /*
         * Preserve the capitalization style typed by the user.
         */
        for (int i = 0; i < finalResults.size(); i++) {
            finalResults.set(
                    i,
                    applyCapitalization(
                            finalResults.get(i),
                            word
                    )
            );
        }

        return finalResults;
    }

    /**
     * Checks whether an exact word exists in the selected dictionary.
     */
    public synchronized boolean contains(
            String word,
            String languageTag) {

        if (TextUtils.isEmpty(word)) {
            return false;
        }

        String language = normalizeLanguage(languageTag);

        loadDictionary(language);

        List<String> dictionary =
                dictionaries.get(language);

        if (dictionary == null) {
            return false;
        }

        return Collections.binarySearch(
                dictionary,
                word.toLowerCase(Locale.ROOT)
        ) >= 0;
    }

    /**
     * Loads one dictionary from:
     *
     * assets/dictionaries/es-AR.txt
     * assets/dictionaries/en.txt
     * assets/dictionaries/de.txt
     *
     * Each line must contain one word.
     */
    private void loadDictionary(String language) {

        if (Boolean.TRUE.equals(
                loadedLanguages.get(language))) {
            return;
        }

        loadedLanguages.put(language, true);

        List<String> words = new ArrayList<>();

        String assetName =
                "dictionaries/" + getAssetName(language);

        try (InputStream inputStream =
                     context.getAssets().open(assetName);
             BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     inputStream,
                                     StandardCharsets.UTF_8
                             )
                     )) {

            String line;

            while ((line = reader.readLine()) != null) {

                line = line.trim();

                if (TextUtils.isEmpty(line)) {
                    continue;
                }

                /*
                 * Ignore comments.
                 */
                if (line.startsWith("#")) {
                    continue;
                }

                /*
                 * If the dictionary later contains additional
                 * information separated by tabs/spaces, keep
                 * only the first field.
                 */
                int separator = line.indexOf('\t');

                if (separator > 0) {
                    line = line.substring(0, separator);
                }

                separator = line.indexOf(' ');

                if (separator > 0) {
                    line = line.substring(0, separator);
                }

                line = line.trim().toLowerCase(Locale.ROOT);

                if (isValidWord(line)) {
                    words.add(line);
                }

                if (words.size() >= MAX_DICTIONARY_WORDS) {
                    break;
                }
            }

        } catch (IOException ignored) {
            /*
             * The dictionary may not exist yet.
             *
             * This is expected during development because we
             * will add the actual dictionaries later.
             */
        }

        /*
         * Remove duplicates and sort alphabetically.
         */
        words = new ArrayList<>(
                new LinkedHashSet<>(words)
        );

        Collections.sort(words);

        dictionaries.put(language, words);
    }

    private String getAssetName(String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.txt";

            case "de":
                return "de.txt";

            case "en":
            default:
                return "en.txt";
        }
    }

    /**
     * Normalizes Android language tags.
     *
     * Examples:
     *
     * es-AR -> es-AR
     * es-ES -> es-AR
     * en-US -> en
     * en-GB -> en
     * de-DE -> de
     */
    private String normalizeLanguage(String languageTag) {

        if (TextUtils.isEmpty(languageTag)) {
            return "en";
        }

        Locale locale =
                Locale.forLanguageTag(languageTag);

        String language =
                locale.getLanguage();

        String country =
                locale.getCountry();

        if ("es".equals(language)) {
            return "es-AR";
        }

        if ("de".equals(language)) {
            return "de";
        }

        if ("en".equals(language)) {
            return "en";
        }

        /*
         * Fallback.
         */
        return "en";
    }

    private boolean isValidWord(String word) {

        if (word.length() < 1 || word.length() > 64) {
            return false;
        }

        for (int i = 0; i < word.length(); i++) {

            char c = word.charAt(i);

            /*
             * Keep letters, apostrophes and hyphens.
             */
            if (Character.isLetter(c) ||
                    c == '\'' ||
                    c == '-') {
                continue;
            }

            return false;
        }

        return true;
    }

    private int getMaximumDistance(String word) {

        int length = word.length();

        if (length <= 3) {
            return 1;
        }

        if (length <= 6) {
            return 2;
        }

        if (length <= 10) {
            return 3;
        }

        return 4;
    }

    /**
     * Levenshtein edit distance.
     */
    private int levenshteinDistance(
            String first,
            String second) {

        if (first.equals(second)) {
            return 0;
        }

        if (first.isEmpty()) {
            return second.length();
        }

        if (second.isEmpty()) {
            return first.length();
        }

        /*
         * Keep the second string as the shorter array
         * where possible to reduce memory usage.
         */
        if (first.length() < second.length()) {
            String temp = first;
            first = second;
            second = temp;
        }

        int[] previous =
                new int[second.length() + 1];

        int[] current =
                new int[second.length() + 1];

        for (int j = 0; j <= second.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= first.length(); i++) {

            current[0] = i;

            char firstChar =
                    first.charAt(i - 1);

            for (int j = 1; j <= second.length(); j++) {

                char secondChar =
                        second.charAt(j - 1);

                int substitutionCost =
                        firstChar == secondChar ? 0 : 1;

                current[j] = Math.min(
                        Math.min(
                                current[j - 1] + 1,
                                previous[j] + 1
                        ),
                        previous[j - 1]
                                + substitutionCost
                );
            }

            int[] temp = previous;
            previous = current;
            current = temp;
        }

        return previous[second.length()];
    }

    private String applyCapitalization(
            String suggestion,
            String original) {

        if (TextUtils.isEmpty(original)) {
            return suggestion;
        }

        boolean allUpper = true;
        boolean firstUpper =
                Character.isUpperCase(
                        original.charAt(0)
                );

        for (int i = 0; i < original.length(); i++) {

            if (Character.isLetter(
                    original.charAt(i))) {

                if (!Character.isUpperCase(
                        original.charAt(i))) {

                    allUpper = false;
                    break;
                }
            }
        }

        if (allUpper) {
            return suggestion.toUpperCase(Locale.ROOT);
        }

        if (firstUpper) {
            return capitalizeFirstLetter(suggestion);
        }

        return suggestion;
    }

    private String capitalizeFirstLetter(
            String text) {

        if (TextUtils.isEmpty(text)) {
            return text;
        }

        return Character.toUpperCase(
                text.charAt(0)
        ) + text.substring(1);
    }

    private static class ScoredWord {

        private final String word;
        private final int distance;

        ScoredWord(
                String word,
                int distance) {

            this.word = word;
            this.distance = distance;
        }

        String getWord() {
            return word;
        }

        int getDistance() {
            return distance;
        }
    }
}
