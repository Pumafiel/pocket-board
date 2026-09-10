package com.sinux.pocketboard.spellchecker;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DictionaryManager {

    private static final String FORMAT_HEADER =
            "#POCKETBOARD-DICT-1";

    private static final int MAX_DICTIONARY_WORDS =
            250000;

    private static final int MAX_PREFIX_RESULTS =
            20;

    private final Context context;

    private final Map<String, Dictionary> dictionaries =
            new HashMap<>();

    public DictionaryManager(Context context) {
        this.context =
                context.getApplicationContext();
    }

    public synchronized List<String> getSuggestions(
            String word,
            String languageTag,
            int maxResults) {

        if (TextUtils.isEmpty(word) ||
                maxResults <= 0) {

            return new ArrayList<>();
        }

        String language =
                normalizeLanguage(languageTag);

        Dictionary dictionary =
                getDictionary(language);

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return new ArrayList<>();
        }

        String normalizedWord =
                word.toLowerCase(Locale.ROOT);

        List<String> results =
                new ArrayList<>(
                        Math.min(maxResults, 20)
                );

        int prefixLimit =
                Math.min(
                        maxResults,
                        MAX_PREFIX_RESULTS
                );

        int index =
                findPrefixStart(
                        dictionary,
                        normalizedWord
                );

        while (index < dictionary.size() &&
                results.size() < prefixLimit) {

            String candidate =
                    dictionary.get(index);

            if (!candidate.startsWith(
                    normalizedWord
            )) {
                break;
            }

            results.add(candidate);
            index++;
        }

        if (results.size() < maxResults) {

            int maxDistance =
                    getMaximumDistance(
                            normalizedWord
                    );

            List<ScoredWord> scored =
                    new ArrayList<>();

            for (int i = 0;
                 i < dictionary.size();
                 i++) {

                String candidate =
                        dictionary.get(i);

                if (candidate.startsWith(
                        normalizedWord
                )) {
                    continue;
                }

                int lengthDifference =
                        candidate.length()
                                - normalizedWord.length();

                if (lengthDifference > maxDistance ||
                        lengthDifference < -maxDistance) {

                    continue;
                }

                int distance =
                        levenshteinDistance(
                                normalizedWord,
                                candidate,
                                maxDistance
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

            for (ScoredWord scoredWord : scored) {

                String suggestion =
                        scoredWord.getWord();

                if (!results.contains(
                        suggestion
                )) {

                    results.add(
                            suggestion
                    );
                }

                if (results.size() >= maxResults) {
                    break;
                }
            }
        }

        for (int i = 0;
             i < results.size();
             i++) {

            results.set(
                    i,
                    applyCapitalization(
                            results.get(i),
                            word
                    )
            );
        }

        return results;
    }

    public synchronized boolean contains(
            String word,
            String languageTag) {

        if (TextUtils.isEmpty(word)) {
            return false;
        }

        Dictionary dictionary =
                getDictionary(
                        normalizeLanguage(
                                languageTag
                        )
                );

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

        String assetName =
                "dictionaries/" +
                        getAssetName(language);

        List<String> words =
                new ArrayList<>();

        List<String> flags =
                new ArrayList<>();

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

            String header =
                    reader.readLine();

            if (!FORMAT_HEADER.equals(header)) {
                return emptyDictionary();
            }

            String line;

            while ((line = reader.readLine()) != null) {

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
                        word.trim()
                                .toLowerCase(
                                        Locale.ROOT
                                );

                if (!isValidWord(word)) {
                    continue;
                }

                words.add(word);
                flags.add(wordFlags.trim());

                if (words.size()
                        >= MAX_DICTIONARY_WORDS) {

                    break;
                }
            }

        } catch (IOException ignored) {

            return emptyDictionary();
        }

        if (words.isEmpty()) {
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

        return new Dictionary(
                wordArray,
                flagArray
        );
    }

    private void sortEntries(
            String[] words,
            String[] flags) {

        Integer[] indexes =
                new Integer[words.length];

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

    private Dictionary emptyDictionary() {

        return new Dictionary(
                new String[0],
                new String[0]
        );
    }

    private String getAssetName(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.dict";

            case "de":
                return "de.dict";

            case "en":
            default:
                return "en.dict";
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

    private int findPrefixStart(
            Dictionary dictionary,
            String prefix) {

        int low = 0;
        int high = dictionary.size();

        while (low < high) {

            int mid =
                    (low + high) >>> 1;

            String candidate =
                    dictionary.get(mid);

            if (candidate.compareTo(prefix) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }

        return low;
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
            String second,
            int maximumDistance) {

        if (first.equals(second)) {
            return 0;
        }

        if (first.isEmpty()) {
            return second.length();
        }

        if (second.isEmpty()) {
            return first.length();
        }

        if (Math.abs(
                first.length() - second.length()
        ) > maximumDistance) {

            return maximumDistance + 1;
        }

        if (first.length() < second.length()) {

            String temp = first;
            first = second;
            second = temp;
        }

        int secondLength =
                second.length();

        int[] previous =
                new int[secondLength + 1];

        int[] current =
                new int[secondLength + 1];

        for (int j = 0;
             j <= secondLength;
             j++) {

            previous[j] = j;
        }

        for (int i = 1;
             i <= first.length();
             i++) {

            current[0] = i;

            int rowMinimum =
                    current[0];

            char firstChar =
                    first.charAt(i - 1);

            for (int j = 1;
                 j <= secondLength;
                 j++) {

                int cost =
                        firstChar ==
                                second.charAt(j - 1)
                                ? 0
                                : 1;

                int value =
                        Math.min(
                                Math.min(
                                        current[j - 1] + 1,
                                        previous[j] + 1
                                ),
                                previous[j - 1] + cost
                        );

                current[j] = value;

                if (value < rowMinimum) {
                    rowMinimum = value;
                }
            }

            if (rowMinimum > maximumDistance) {
                return maximumDistance + 1;
            }

            int[] temp =
                    previous;

            previous =
                    current;

            current =
                    temp;
        }

        return previous[
                secondLength
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

            if (suggestion.isEmpty()) {
                return suggestion;
            }

            return Character.toUpperCase(
                    suggestion.charAt(0)
            ) + suggestion.substring(1);
        }

        return suggestion;
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
