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

    private final Context context;

    private final Map<String, Dictionary> dictionaries =
            new HashMap<>();

    private final Set<String> loadedLanguages =
            new LinkedHashSet<>();

    public DictionaryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized List<String> getSuggestions(
            String word,
            String languageTag,
            int maxResults) {

        if (TextUtils.isEmpty(word) || maxResults <= 0) {
            return Collections.emptyList();
        }

        String language =
                normalizeLanguage(languageTag);

        Dictionary dictionary =
                getDictionary(language);

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return Collections.emptyList();
        }

        String normalized =
                word.toLowerCase(Locale.ROOT);

        Set<String> results =
                new LinkedHashSet<>();

        int prefixLimit =
                Math.min(
                        maxResults,
                        MAX_PREFIX_RESULTS
                );

        for (int i = 0;
             i < dictionary.size() &&
                     results.size() < prefixLimit;
             i++) {

            String candidate =
                    dictionary.get(i);

            if (candidate.startsWith(normalized)) {
                results.add(candidate);
            }
        }

        if (results.size() < maxResults) {

            int maxDistance =
                    getMaximumDistance(normalized);

            List<ScoredWord> scored =
                    new ArrayList<>();

            for (int i = 0;
                 i < dictionary.size();
                 i++) {

                String candidate =
                        dictionary.get(i);

                if (results.contains(candidate)) {
                    continue;
                }

                if (Math.abs(
                        candidate.length()
                                - normalized.length()
                ) > maxDistance) {

                    continue;
                }

                int distance =
                        levenshteinDistance(
                                normalized,
                                candidate
                        );

                if (distance <= maxDistance) {

                    scored.add(
                            new ScoredWord(
                                    candidate,
                                    distance
                            )
                    );
                }
            }

            scored.sort(
                    Comparator
                            .comparingInt(
                                    ScoredWord::getDistance
                            )
                            .thenComparing(
                                    ScoredWord::getWord
                            )
            );

            for (ScoredWord item : scored) {

                results.add(
                        item.getWord()
                );

                if (results.size() >= maxResults) {
                    break;
                }
            }
        }

        List<String> output =
                new ArrayList<>(results);

        for (int i = 0; i < output.size(); i++) {

            output.set(
                    i,
                    applyCapitalization(
                            output.get(i),
                            word
                    )
            );
        }

        return output;
    }

    public synchronized boolean contains(
            String word,
            String languageTag) {

        if (TextUtils.isEmpty(word)) {
            return false;
        }

        String language =
                normalizeLanguage(languageTag);

        Dictionary dictionary =
                getDictionary(language);

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return false;
        }

        return dictionary.contains(
                word.toLowerCase(Locale.ROOT)
        );
    }

    private Dictionary getDictionary(
            String language) {

        Dictionary dictionary =
                dictionaries.get(language);

        if (dictionary != null) {
            return dictionary;
        }

        if (loadedLanguages.contains(language)) {
            return null;
        }

        loadedLanguages.add(language);

        dictionary =
                loadDictionary(language);

        dictionaries.put(
                language,
                dictionary
        );

        return dictionary;
    }

    private Dictionary loadDictionary(
            String language) {

        List<String> words =
                new ArrayList<>();

        String assetName =
                "dictionaries/" +
                        getAssetName(language);

        try (InputStream inputStream =
                     context.getAssets().open(
                             assetName
                     );
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

                if (TextUtils.isEmpty(line) ||
                        line.startsWith("#")) {

                    continue;
                }

                int separator =
                        line.indexOf('\t');

                if (separator > 0) {
                    line =
                            line.substring(
                                    0,
                                    separator
                            );
                }

                separator =
                        line.indexOf(' ');

                if (separator > 0) {
                    line =
                            line.substring(
                                    0,
                                    separator
                            );
                }

                line =
                        line.trim()
                                .toLowerCase(
                                        Locale.ROOT
                                );

                if (isValidWord(line)) {
                    words.add(line);
                }

                if (words.size()
                        >= MAX_DICTIONARY_WORDS) {

                    break;
                }
            }

        } catch (IOException ignored) {
            // Dictionary unavailable.
        }

        if (words.isEmpty()) {
            return new Dictionary(
                    Collections.emptyList()
            );
        }

        Collections.sort(words);

        List<String> unique =
                new ArrayList<>(
                        words.size()
                );

        String previous = null;

        for (String word : words) {

            if (!word.equals(previous)) {

                unique.add(word);
                previous = word;
            }
        }

        return new Dictionary(unique);
    }

    private String getAssetName(
            String language) {

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

    private String normalizeLanguage(
            String languageTag) {

        if (TextUtils.isEmpty(languageTag)) {
            return "en";
        }

        Locale locale =
                Locale.forLanguageTag(
                        languageTag.replace(
                                '_',
                                '-'
                        )
                );

        String language =
                locale.getLanguage();

        if ("es".equals(language)) {
            return "es-AR";
        }

        if ("de".equals(language)) {
            return "de";
        }

        if ("en".equals(language)) {
            return "en";
        }

        return "en";
    }

    private boolean isValidWord(
            String word) {

        if (word.length() < 1 ||
                word.length() > 64) {

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

    private int getMaximumDistance(
            String word) {

        int length =
                word.length();

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

        if (first.length() < second.length()) {

            String temp = first;
            first = second;
            second = temp;
        }

        int[] previous =
                new int[second.length() + 1];

        int[] current =
                new int[second.length() + 1];

        for (int j = 0;
             j <= second.length();
             j++) {

            previous[j] = j;
        }

        for (int i = 1;
             i <= first.length();
             i++) {

            current[0] = i;

            char firstChar =
                    first.charAt(i - 1);

            for (int j = 1;
                 j <= second.length();
                 j++) {

                char secondChar =
                        second.charAt(j - 1);

                int cost =
                        firstChar == secondChar
                                ? 0
                                : 1;

                current[j] =
                        Math.min(
                                Math.min(
                                        current[j - 1] + 1,
                                        previous[j] + 1
                                ),
                                previous[j - 1] + cost
                        );
            }

            int[] temp =
                    previous;

            previous =
                    current;

            current =
                    temp;
        }

        return previous[
                second.length()
        ];
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

        if (firstUpper) {
            return capitalizeFirstLetter(
                    suggestion
            );
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

    private static final class ScoredWord {

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
