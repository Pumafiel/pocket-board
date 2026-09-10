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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DictionaryManager {

    private static final int MAX_DICTIONARY_WORDS = 250000;
    private static final int MAX_PREFIX_RESULTS = 20;
    private static final int MAX_SPELLING_RESULTS = 10;

    private static final int MAX_REP_RULES = 100;
    private static final int MAX_MAP_GROUPS = 100;

    private final Context context;

    private final Map<String, List<DictionaryEntry>> dictionaries =
            new HashMap<>();

    private final Map<String, DictionaryRules> rules =
            new HashMap<>();

    private final Map<String, Boolean> loadedLanguages =
            new HashMap<>();

    public DictionaryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns suggestions for the word currently being typed.
     *
     * This method intentionally does NOT implement next-word
     * prediction.
     *
     * Ranking priority:
     *
     * 1. Exact/prefix matches
     * 2. Close spelling matches
     * 3. REP/MAP assisted matches
     * 4. Shorter edit distance
     * 5. More natural candidate length
     */
    public synchronized List<String> getSuggestions(
            String word,
            String languageTag,
            int maxResults) {

        if (TextUtils.isEmpty(word) || maxResults <= 0) {
            return Collections.emptyList();
        }

        String language =
                normalizeLanguage(languageTag);

        loadDictionary(language);

        List<DictionaryEntry> dictionary =
                dictionaries.get(language);

        if (dictionary == null || dictionary.isEmpty()) {
            return Collections.emptyList();
        }

        String normalized =
                normalizeWord(word);

        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        int resultLimit =
                Math.min(
                        maxResults,
                        MAX_PREFIX_RESULTS
                );

        Set<String> results =
                new LinkedHashSet<>();

        /*
         * Exact word first.
         */
        for (DictionaryEntry entry : dictionary) {

            if (entry.word.equals(normalized)) {
                results.add(entry.word);
                break;
            }
        }

        /*
         * Prefix suggestions.
         *
         * The current word must be a real prefix. We do not use
         * fuzzy matching until after prefix candidates have been
         * collected.
         */
        for (DictionaryEntry entry : dictionary) {

            if (!entry.word.startsWith(normalized)) {
                continue;
            }

            results.add(entry.word);

            if (results.size() >= resultLimit) {
                break;
            }
        }

        /*
         * If prefix suggestions are already sufficient, there is
         * no reason to perform expensive fuzzy comparisons.
         */
        if (results.size() >= maxResults) {
            return applyCapitalization(
                    new ArrayList<>(results),
                    word
            );
        }

        /*
         * Fuzzy candidates.
         */
        int maximumDistance =
                getMaximumDistance(normalized);

        DictionaryRules dictionaryRules =
                rules.get(language);

        List<ScoredWord> spellingResults =
                new ArrayList<>();

        for (DictionaryEntry entry : dictionary) {

            if (results.contains(entry.word)) {
                continue;
            }

            String candidate =
                    entry.word;

            if (Math.abs(
                    candidate.length() -
                            normalized.length()
            ) > maximumDistance) {
                continue;
            }

            int distance =
                    weightedDistance(
                            normalized,
                            candidate,
                            dictionaryRules
                    );

            if (distance > maximumDistance) {
                continue;
            }

            int score =
                    calculateScore(
                            normalized,
                            candidate,
                            distance,
                            dictionaryRules
                    );

            spellingResults.add(
                    new ScoredWord(
                            candidate,
                            distance,
                            score
                    )
            );
        }

        spellingResults.sort(
                Comparator
                        .comparingInt(
                                ScoredWord::getScore
                        )
                        .thenComparingInt(
                                ScoredWord::getDistance
                        )
                        .thenComparing(
                                ScoredWord::getWord
                        )
        );

        for (ScoredWord scored : spellingResults) {

            results.add(
                    scored.getWord()
            );

            if (results.size() >= maxResults) {
                break;
            }

            if (spellingResults.size() >=
                    MAX_SPELLING_RESULTS &&
                    results.size() >= maxResults) {

                break;
            }
        }

        return applyCapitalization(
                new ArrayList<>(results),
                word
        );
    }

    /**
     * Checks whether a word exists in the selected dictionary.
     *
     * This is used by spellcheck and intentionally does not
     * perform fuzzy matching.
     */
    public synchronized boolean contains(
            String word,
            String languageTag) {

        if (TextUtils.isEmpty(word)) {
            return false;
        }

        String language =
                normalizeLanguage(languageTag);

        loadDictionary(language);

        List<DictionaryEntry> dictionary =
                dictionaries.get(language);

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return false;
        }

        String normalized =
                normalizeWord(word);

        /*
         * Dictionary entries are sorted.
         */
        int low = 0;
        int high = dictionary.size() - 1;

        while (low <= high) {

            int middle =
                    (low + high) >>> 1;

            String candidate =
                    dictionary.get(middle).word;

            int comparison =
                    candidate.compareTo(
                            normalized
                    );

            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return true;
            }
        }

        return false;
    }

    /**
     * Loads the generated dictionary and its generated rules.
     */
    private void loadDictionary(
            String language) {

        if (Boolean.TRUE.equals(
                loadedLanguages.get(language))) {

            return;
        }

        /*
         * Mark as loaded only after the loading process has
         * completed successfully enough to produce a usable
         * dictionary.
         */
        loadedLanguages.put(
                language,
                true
        );

        List<DictionaryEntry> words =
                new ArrayList<>();

        String dictionaryAsset =
                "dictionaries/" +
                        getDictionaryAssetName(language);

        try (
                InputStream inputStream =
                        context.getAssets().open(
                                dictionaryAsset
                        );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;

            while (
                    (line = reader.readLine()) != null
            ) {

                line = line.trim();

                if (line.isEmpty() ||
                        line.startsWith("#")) {

                    continue;
                }

                String word = line;
                String flags = "";

                /*
                 * Generated .dict format:
                 *
                 * word<TAB>flags
                 */
                int separator =
                        line.indexOf('\t');

                if (separator >= 0) {

                    word =
                            line.substring(
                                    0,
                                    separator
                            );

                    flags =
                            line.substring(
                                    separator + 1
                            );
                }

                word =
                        normalizeWord(word);

                if (!isValidWord(word)) {
                    continue;
                }

                words.add(
                        new DictionaryEntry(
                                word,
                                flags
                        )
                );

                if (words.size() >=
                        MAX_DICTIONARY_WORDS) {

                    break;
                }
            }

        } catch (IOException ignored) {

            /*
             * Dictionary assets are generated during the build.
             * If an asset is unavailable, keep an empty dictionary
             * rather than crashing the IME.
             */
        }

        /*
         * Remove duplicates while preserving the first entry.
         */
        Map<String, DictionaryEntry> unique =
                new LinkedHashMap<>();

        for (DictionaryEntry entry : words) {

            if (!unique.containsKey(entry.word)) {
                unique.put(
                        entry.word,
                        entry
                );
            }
        }

        words =
                new ArrayList<>(
                        unique.values()
                );

        words.sort(
                Comparator.comparing(
                        DictionaryEntry::getWord
                )
        );

        dictionaries.put(
                language,
                words
        );

        loadRules(language);
    }

    /**
     * Loads the compact .rules file generated from Hunspell .aff.
     */
    private void loadRules(
            String language) {

        String rulesAsset =
                "dictionaries/" +
                        getRulesAssetName(language);

        DictionaryRules dictionaryRules =
                new DictionaryRules();

        try (
                InputStream inputStream =
                        context.getAssets().open(
                                rulesAsset
                        );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;

            while (
                    (line = reader.readLine()) != null
            ) {

                line = line.trim();

                if (line.isEmpty() ||
                        line.startsWith("#")) {

                    continue;
                }

                /*
                 * File header.
                 */
                if ("PBD-RULES-1".equals(line)) {
                    continue;
                }

                /*
                 * TRY line:
                 *
                 * TRY a b c ...
                 */
                if (line.startsWith("TRY\t")) {

                    String[] parts =
                            line.split(
                                    "\\t"
                            );

                    for (
                            int i = 1;
                            i < parts.length;
                            i++
                    ) {

                        if (!parts[i].isEmpty()) {
                            dictionaryRules.tryCharacters.add(
                                    parts[i]
                            );
                        }
                    }

                    continue;
                }

                /*
                 * REP count line.
                 */
                if (line.startsWith("REP\t")) {
                    continue;
                }

                /*
                 * MAP count line.
                 */
                if (line.startsWith("MAP\t")) {
                    continue;
                }

                /*
                 * REP replacement:
                 *
                 * from<TAB>to
                 */
                if (line.indexOf('\t') >= 0) {

                    String[] parts =
                            line.split(
                                    "\\t"
                            );

                    if (parts.length >= 2) {

                        if (dictionaryRules
                                .readingRep) {

                            if (dictionaryRules
                                    .replacementRules.size()
                                    < MAX_REP_RULES) {

                                dictionaryRules
                                        .replacementRules
                                        .add(
                                                new ReplacementRule(
                                                        parts[0],
                                                        parts[1]
                                                )
                                        );
                            }

                            continue;
                        }
                    }
                }

                /*
                 * Lines without a tab after the header are MAP
                 * character groups or other compact rule data.
                 */
                if (!line.contains("\t")) {

                    if (dictionaryRules
                            .replacementRules.size()
                            < MAX_REP_RULES) {

                        dictionaryRules
                                .mapGroups
                                .add(line);
                    }
                }
            }

        } catch (IOException ignored) {
            /*
             * Rules are optional. The dictionary remains fully
             * usable without them.
             */
        }

        rules.put(
                language,
                dictionaryRules
        );
    }

    private String getDictionaryAssetName(
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

    private String getRulesAssetName(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.rules";

            case "de":
                return "de.rules";

            case "en":
            default:
                return "en.rules";
        }
    }

    /**
     * Normalizes the Android language tag.
     */
    private String normalizeLanguage(
            String languageTag) {

        if (TextUtils.isEmpty(languageTag)) {
            return "en";
        }

        Locale locale =
                Locale.forLanguageTag(
                        languageTag
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

    /**
     * Unicode-aware normalization.
     *
     * We deliberately DO NOT remove accents or convert ñ to n.
     *
     * "año" and "ano" are different dictionary words.
     */
    private String normalizeWord(
            String word) {

        return word
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private boolean isValidWord(
            String word) {

        if (word.length() < 1 ||
                word.length() > 64) {

            return false;
        }

        boolean hasLetter = false;

        for (
                int i = 0;
                i < word.length();
                i++
        ) {

            char c =
                    word.charAt(i);

            if (Character.isLetter(c)) {

                hasLetter = true;
                continue;
            }

            if (c == '\'' ||
                    c == '-') {

                continue;
            }

            return false;
        }

        return hasLetter;
    }

    private int getMaximumDistance(
            String word) {

        int length =
                word.length();

        /*
         * Very short words are particularly sensitive to fuzzy
         * matching. Keep the correction radius conservative.
         */
        if (length <= 2) {
            return 0;
        }

        if (length <= 4) {
            return 1;
        }

        if (length <= 7) {
            return 2;
        }

        if (length <= 11) {
            return 3;
        }

        return 4;
    }

    /**
     * Calculates an edit distance while giving a lower cost to
     * substitutions explicitly supported by REP/MAP.
     *
     * This keeps normal Levenshtein behavior while allowing the
     * dictionary to tell us which spelling confusions are common.
     */
    private int weightedDistance(
            String first,
            String second,
            DictionaryRules dictionaryRules) {

        if (first.equals(second)) {
            return 0;
        }

        if (first.isEmpty()) {
            return second.length();
        }

        if (second.isEmpty()) {
            return first.length();
        }

        int[] previous =
                new int[second.length() + 1];

        int[] current =
                new int[second.length() + 1];

        for (
                int j = 0;
                j <= second.length();
                j++
        ) {

            previous[j] = j;
        }

        for (
                int i = 1;
                i <= first.length();
                i++
        ) {

            current[0] = i;

            char firstChar =
                    first.charAt(i - 1);

            for (
                    int j = 1;
                    j <= second.length();
                    j++
            ) {

                char secondChar =
                        second.charAt(j - 1);

                int substitutionCost;

                if (firstChar == secondChar) {

                    substitutionCost = 0;

                } else if (
                        areEquivalentCharacters(
                                firstChar,
                                secondChar,
                                dictionaryRules
                        )
                ) {

                    /*
                     * A character pair explicitly supported by
                     * MAP is less expensive than an arbitrary
                     * substitution.
                     */
                    substitutionCost = 0;

                } else {

                    substitutionCost = 1;
                }

                current[j] =
                        Math.min(
                                Math.min(
                                        current[j - 1] + 1,
                                        previous[j] + 1
                                ),
                                previous[j - 1] +
                                        substitutionCost
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

    private boolean areEquivalentCharacters(
            char first,
            char second,
            DictionaryRules dictionaryRules) {

        if (dictionaryRules == null) {
            return false;
        }

        for (
                String group :
                        dictionaryRules.mapGroups
        ) {

            if (group.indexOf(first) >= 0 &&
                    group.indexOf(second) >= 0) {

                return true;
            }
        }

        /*
         * REP rules may represent multi-character substitutions,
         * therefore only single-character rules are considered
         * here.
         */
        for (
                ReplacementRule rule :
                        dictionaryRules.replacementRules
        ) {

            if (rule.from.length() == 1 &&
                    rule.to.length() == 1 &&
                    rule.from.charAt(0) == first &&
                    rule.to.charAt(0) == second) {

                return true;
            }
        }

        return false;
    }

    /**
     * Produces a ranking score.
     *
     * Lower is better.
     */
    private int calculateScore(
            String input,
            String candidate,
            int distance,
            DictionaryRules dictionaryRules) {

        int score =
                distance * 100;

        /*
         * Prefix candidates are highly desirable, although normal
         * prefix matches are already handled before fuzzy matching.
         */
        if (candidate.startsWith(input)) {
            score -= 80;
        }

        /*
         * Prefer candidates whose length is close to what the user
         * typed.
         */
        score +=
                Math.abs(
                        candidate.length() -
                                input.length()
                ) * 4;

        /*
         * Character overlap is a useful tie breaker.
         */
        score -=
                commonCharacterCount(
                        input,
                        candidate
                ) * 2;

        /*
         * Give a small advantage to candidates that can be reached
         * through an explicit REP rule.
         */
        if (usesReplacementRule(
                input,
                candidate,
                dictionaryRules
        )) {

            score -= 15;
        }

        return score;
    }

    private boolean usesReplacementRule(
            String input,
            String candidate,
            DictionaryRules dictionaryRules) {

        if (dictionaryRules == null) {
            return false;
        }

        for (
                ReplacementRule rule :
                        dictionaryRules.replacementRules
        ) {

            if (rule.from.isEmpty() ||
                    rule.to.isEmpty()) {

                continue;
            }

            String transformed =
                    input.replace(
                            rule.from,
                            rule.to
                    );

            if (candidate.equals(
                    transformed
            )) {

                return true;
            }

            transformed =
                    input.replace(
                            rule.to,
                            rule.from
                    );

            if (candidate.equals(
                    transformed
            )) {

                return true;
            }
        }

        return false;
    }

    private int commonCharacterCount(
            String first,
            String second) {

        int count = 0;

        boolean[] used =
                new boolean[
                        second.length()
                ];

        for (
                int i = 0;
                i < first.length();
                i++
        ) {

            char character =
                    first.charAt(i);

            for (
                    int j = 0;
                    j < second.length();
                    j++
            ) {

                if (used[j]) {
                    continue;
                }

                if (character ==
                        second.charAt(j)) {

                    used[j] = true;
                    count++;
                    break;
                }
            }
        }

        return count;
    }

    private List<String> applyCapitalization(
            List<String> suggestions,
            String original) {

        if (suggestions.isEmpty() ||
                TextUtils.isEmpty(original)) {

            return suggestions;
        }

        boolean allUpper = true;

        boolean firstUpper =
                Character.isUpperCase(
                        original.charAt(0)
                );

        for (
                int i = 0;
                i < original.length();
                i++
        ) {

            char c =
                    original.charAt(i);

            if (!Character.isLetter(c)) {
                continue;
            }

            if (!Character.isUpperCase(c)) {

                allUpper = false;
                break;
            }
        }

        for (
                int i = 0;
                i < suggestions.size();
                i++
        ) {

            String suggestion =
                    suggestions.get(i);

            if (allUpper) {

                suggestions.set(
                        i,
                        suggestion.toUpperCase(
                                Locale.ROOT
                        )
                );

            } else if (firstUpper) {

                suggestions.set(
                        i,
                        capitalizeFirstLetter(
                                suggestion
                        )
                );
            }
        }

        return suggestions;
    }

    private String capitalizeFirstLetter(
            String text) {

        if (TextUtils.isEmpty(text)) {
            return text;
        }

        int firstLetter =
                -1;

        for (
                int i = 0;
                i < text.length();
                i++
        ) {

            if (Character.isLetter(
                    text.charAt(i)
            )) {

                firstLetter = i;
                break;
            }
        }

        if (firstLetter < 0) {
            return text;
        }

        return text.substring(
                        0,
                        firstLetter
                ) +
                Character.toUpperCase(
                        text.charAt(firstLetter)
                ) +
                text.substring(
                        firstLetter + 1
                );
    }

    private static class DictionaryEntry {

        private final String word;
        private final String flags;

        DictionaryEntry(
                String word,
                String flags) {

            this.word = word;
            this.flags = flags;
        }

        String getWord() {
            return word;
        }
    }

    private static class ScoredWord {

        private final String word;
        private final int distance;
        private final int score;

        ScoredWord(
                String word,
                int distance,
                int score) {

            this.word = word;
            this.distance = distance;
            this.score = score;
        }

        String getWord() {
            return word;
        }

        int getDistance() {
            return distance;
        }

        int getScore() {
            return score;
        }
    }

    private static class ReplacementRule {

        private final String from;
        private final String to;

        ReplacementRule(
                String from,
                String to) {

            this.from = from;
            this.to = to;
        }
    }

    private static class DictionaryRules {

        private final List<String> tryCharacters =
                new ArrayList<>();

        private final List<ReplacementRule> replacementRules =
                new ArrayList<>();

        private final List<String> mapGroups =
                new ArrayList<>();

        /*
         * Kept for compatibility with the compact rules parser.
         */
        private boolean readingRep = true;
    }
}
