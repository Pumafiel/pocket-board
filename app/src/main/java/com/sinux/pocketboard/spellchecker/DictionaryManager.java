package com.sinux.pocketboard.spellchecker;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DictionaryManager {

    private static final int MAX_WORDS = 100000;
    private static final int MAX_PREFIX = 20;
    private static final int MAX_SPELLING = 10;

    private final Context context;

    private final Map<String, List<String>> dictionaries =
            new HashMap<>();

    private final Map<String, Set<String>> flags =
            new HashMap<>();

    private final Set<String> loaded =
            new HashSet<>();

    public DictionaryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized List<String> getSuggestions(
            String word,
            String languageTag,
            int maxResults) {

        if (TextUtils.isEmpty(word) || maxResults <= 0)
            return Collections.emptyList();

        String language = normalizeLanguage(languageTag);
        loadDictionary(language);

        List<String> dictionary = dictionaries.get(language);

        if (dictionary == null || dictionary.isEmpty())
            return Collections.emptyList();

        String input = word.toLowerCase(Locale.ROOT);

        List<ScoredWord> scored = new ArrayList<>();

        for (String candidate : dictionary) {

            if (!candidate.startsWith(input))
                continue;

            int score = prefixScore(input, candidate);

            scored.add(
                    new ScoredWord(candidate, score)
            );

            if (scored.size() >= MAX_PREFIX)
                break;
        }

        scored.sort(
                Comparator.comparingInt(
                        ScoredWord::getScore
                ).thenComparing(
                        ScoredWord::getWord
                )
        );

        List<String> result =
                new ArrayList<>();

        for (ScoredWord item : scored) {

            result.add(
                    applyCapitalization(
                            item.word,
                            word
                    )
            );

            if (result.size() >= maxResults)
                return result;
        }

        if (result.size() < maxResults) {

            int maxDistance =
                    maximumDistance(input);

            scored.clear();

            for (String candidate : dictionary) {

                if (candidate.startsWith(input))
                    continue;

                int lengthDifference =
                        Math.abs(
                                candidate.length()
                                        - input.length()
                        );

                if (lengthDifference > maxDistance)
                    continue;

                int distance =
                        weightedDistance(
                                input,
                                candidate
                        );

                if (distance > maxDistance * 3)
                    continue;

                int score =
                        distanceScore(
                                input,
                                candidate,
                                distance
                        );

                scored.add(
                        new ScoredWord(
                                candidate,
                                score
                        )
                );
            }

            scored.sort(
                    Comparator.comparingInt(
                            ScoredWord::getScore
                    ).thenComparing(
                            ScoredWord::getWord
                    )
            );

            int limit =
                    Math.min(
                            MAX_SPELLING,
                            maxResults - result.size()
                    );

            for (int i = 0;
                 i < scored.size() && i < limit;
                 i++) {

                String candidate =
                        scored.get(i).word;

                if (result.contains(
                        applyCapitalization(
                                candidate,
                                word
                        ))) {
                    continue;
                }

                result.add(
                        applyCapitalization(
                                candidate,
                                word
                        )
                );
            }
        }

        return result;
    }

    public synchronized boolean contains(
            String word,
            String languageTag) {

        if (TextUtils.isEmpty(word))
            return false;

        String language =
                normalizeLanguage(languageTag);

        loadDictionary(language);

        List<String> dictionary =
                dictionaries.get(language);

        if (dictionary == null)
            return false;

        String target =
                word.toLowerCase(Locale.ROOT);

        return Collections.binarySearch(
                dictionary,
                target
        ) >= 0;
    }

    private void loadDictionary(
            String language) {

        if (loaded.contains(language))
            return;

        loaded.add(language);

        List<String> words =
                new ArrayList<>();

        Set<String> wordFlags =
                new HashSet<>();

        String asset =
                "dictionaries/" +
                        assetName(language);

        try (
                InputStream input =
                        context.getAssets().open(asset);

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        input,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;
            boolean firstLine = true;

            while (
                    (line = reader.readLine()) != null
            ) {

                line = line.trim();

                if (line.isEmpty())
                    continue;

                if (line.startsWith("#"))
                    continue;

                if (firstLine) {

                    firstLine = false;

                    if (isNumber(line))
                        continue;
                }

                String word = line;

                int slash =
                        line.indexOf('/');

                if (slash >= 0) {

                    word =
                            line.substring(
                                    0,
                                    slash
                            );

                    String wordFlag =
                            line.substring(
                                    slash + 1
                            );

                    if (!wordFlag.isEmpty())
                        wordFlags.add(
                                wordFlag
                        );
                }

                word =
                        word.trim()
                                .toLowerCase(
                                        Locale.ROOT
                                );

                if (!isValidWord(word))
                    continue;

                words.add(word);

                if (words.size() >= MAX_WORDS)
                    break;
            }

        } catch (IOException ignored) {
        }

        words =
                new ArrayList<>(
                        new HashSet<>(words)
                );

        Collections.sort(words);

        dictionaries.put(
                language,
                words
        );

        flags.put(
                language,
                wordFlags
        );

        /*
         * Load the .aff file as well.
         *
         * It is intentionally not interpreted here yet.
         * Keeping it in assets allows the dictionary data to
         * remain complete without adding a large Hunspell
         * implementation to the application.
         */
        loadAffixFile(language);
    }

    private void loadAffixFile(
            String language) {

        String asset =
                "dictionaries/" +
                        affixName(language);

        try (
                InputStream input =
                        context.getAssets().open(asset);

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        input,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            while (reader.readLine() != null) {
                /*
                 * The .aff data is intentionally kept in the
                 * application assets. Future scoring can use
                 * its rules without changing the dictionary
                 * storage format.
                 */
            }

        } catch (IOException ignored) {
        }
    }

    private String assetName(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.dic";

            case "de":
                return "de.dic";

            default:
                return "en.dic";
        }
    }

    private String affixName(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.aff";

            case "de":
                return "de.aff";

            default:
                return "en.aff";
        }
    }

    private String normalizeLanguage(
            String languageTag) {

        if (TextUtils.isEmpty(languageTag))
            return "en";

        Locale locale =
                Locale.forLanguageTag(
                        languageTag.replace(
                                '_',
                                '-'
                        )
                );

        String language =
                locale.getLanguage();

        if ("es".equals(language))
            return "es-AR";

        if ("de".equals(language))
            return "de";

        return "en";
    }

    private boolean isValidWord(
            String word) {

        if (word.isEmpty() ||
                word.length() > 64)
            return false;

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

    private boolean isNumber(
            String value) {

        if (value.isEmpty())
            return false;

        for (int i = 0;
             i < value.length();
             i++) {

            if (!Character.isDigit(
                    value.charAt(i)
            )) {
                return false;
            }
        }

        return true;
    }

    private int prefixScore(
            String input,
            String candidate) {

        int score = 0;

        int remaining =
                candidate.length()
                        - input.length();

        score += remaining;

        /*
         * Prefer a candidate that is almost exactly
         * what the user has already typed.
         */
        if (remaining == 0)
            score -= 100;

        if (remaining == 1)
            score -= 20;

        if (remaining == 2)
            score -= 10;

        return score;
    }

    private int distanceScore(
            String input,
            String candidate,
            int distance) {

        int score =
                distance * 10;

        /*
         * Same normalized spelling but different
         * accents should be very close.
         */
        if (normalizeForComparison(input)
                .equals(
                        normalizeForComparison(
                                candidate
                        )
                )) {
            score -= 8;
        }

        /*
         * Reward similar length.
         */
        score +=
                Math.abs(
                        input.length()
                                - candidate.length()
                );

        /*
         * Common Spanish ñ/n substitution.
         */
        if (isSpanishLanguage(candidate) &&
                replacesNWithNtilde(
                        input,
                        candidate
                )) {
            score -= 4;
        }

        return score;
    }

    private boolean isSpanishLanguage(
            String word) {

        return word.indexOf('ñ') >= 0 ||
                word.indexOf('á') >= 0 ||
                word.indexOf('é') >= 0 ||
                word.indexOf('í') >= 0 ||
                word.indexOf('ó') >= 0 ||
                word.indexOf('ú') >= 0 ||
                word.indexOf('ü') >= 0;
    }

    private boolean replacesNWithNtilde(
            String a,
            String b) {

        if (a.length() != b.length())
            return false;

        int differences = 0;

        for (int i = 0;
             i < a.length();
             i++) {

            char x = a.charAt(i);
            char y = b.charAt(i);

            if (x == y)
                continue;

            if ((x == 'n' && y == 'ñ') ||
                    (x == 'ñ' && y == 'n')) {

                differences++;
                continue;
            }

            return false;
        }

        return differences == 1;
    }

    private String normalizeForComparison(
            String value) {

        String normalized =
                Normalizer.normalize(
                        value,
                        Normalizer.Form.NFD
                );

        return normalized
                .replaceAll(
                        "\\p{M}",
                        ""
                );
    }

    private int maximumDistance(
            String word) {

        int length =
                word.length();

        if (length <= 3)
            return 1;

        if (length <= 6)
            return 2;

        if (length <= 10)
            return 3;

        return 4;
    }

    private int weightedDistance(
            String a,
            String b) {

        if (a.equals(b))
            return 0;

        if (a.isEmpty())
            return b.length() * 3;

        if (b.isEmpty())
            return a.length() * 3;

        int[] previous =
                new int[b.length() + 1];

        int[] current =
                new int[b.length() + 1];

        for (int i = 0;
             i <= b.length();
             i++) {

            previous[i] =
                    i * 3;
        }

        for (int i = 1;
             i <= a.length();
             i++) {

            current[0] =
                    i * 3;

            char ca =
                    a.charAt(i - 1);

            for (int j = 1;
                 j <= b.length();
                 j++) {

                char cb =
                        b.charAt(j - 1);

                int substitution =
                        substitutionCost(
                                ca,
                                cb
                        );

                current[j] =
                        Math.min(
                                Math.min(
                                        current[j - 1] + 3,
                                        previous[j] + 3
                                ),
                                previous[j - 1]
                                        + substitution
                        );
            }

            int[] temp =
                    previous;

            previous =
                    current;

            current =
                    temp;
        }

        return previous[b.length()];
    }

    private int substitutionCost(
            char a,
            char b) {

        if (a == b)
            return 0;

        /*
         * Accent difference.
         */
        if (stripAccent(a) ==
                stripAccent(b)) {
            return 1;
        }

        /*
         * Spanish ñ/n.
         */
        if ((a == 'n' && b == 'ñ') ||
                (a == 'ñ' && b == 'n')) {
            return 1;
        }

        /*
         * Common nearby keyboard substitutions.
         */
        if (nearKeyboard(a, b))
            return 1;

        return 3;
    }

    private char stripAccent(
            char c) {

        String value =
                Normalizer.normalize(
                        String.valueOf(c),
                        Normalizer.Form.NFD
                );

        for (int i = 0;
             i < value.length();
             i++) {

            char x =
                    value.charAt(i);

            if (Character.isLetter(x))
                return x;
        }

        return c;
    }

    private boolean nearKeyboard(
            char a,
            char b) {

        String[] rows = {
                "qwertyuiop",
                "asdfghjklñ",
                "zxcvbnm"
        };

        for (String row : rows) {

            int ia =
                    row.indexOf(a);

            int ib =
                    row.indexOf(b);

            if (ia < 0 || ib < 0)
                continue;

            return Math.abs(
                    ia - ib
            ) <= 1;
        }

        return false;
    }

    private String applyCapitalization(
            String suggestion,
            String original) {

        if (TextUtils.isEmpty(original))
            return suggestion;

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

        if (allUpper)
            return suggestion.toUpperCase(
                    Locale.ROOT
            );

        if (Character.isUpperCase(
                original.charAt(0)
        )) {

            return Character.toUpperCase(
                    suggestion.charAt(0)
            ) + suggestion.substring(1);
        }

        return suggestion;
    }

    private static class ScoredWord {

        final String word;
        final int score;

        ScoredWord(
                String word,
                int score) {

            this.word = word;
            this.score = score;
        }

        String getWord() {
            return word;
        }

        int getScore() {
            return score;
        }
    }
}
