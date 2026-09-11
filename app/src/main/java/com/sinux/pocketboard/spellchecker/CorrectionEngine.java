package com.sinux.pocketboard.spellchecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native PocketBoard spelling correction engine.
 *
 * Responsibilities:
 *
 *  - Score dictionary candidates against the typed word.
 *  - Prefer realistic keyboard errors.
 *  - Handle duplicated characters.
 *  - Handle adjacent transpositions.
 *  - Handle language-specific substitutions.
 *  - Preserve common prefixes.
 *  - Rank candidates deterministically.
 *
 * Completion/prefix matching is intentionally NOT handled here.
 * DictionaryManager owns completion and candidate generation.
 */
public final class CorrectionEngine {

    private static final int DEFAULT_MAX_RESULTS = 3;

    /*
     * Absolute safety limit.
     *
     * Candidates farther away than this are not useful
     * corrections.
     */
    private static final int MAX_CANDIDATE_DISTANCE = 8;

    /*
     * Basic edit costs.
     *
     * A normal insertion/deletion is more expensive than
     * a duplicated character or an adjacent transposition.
     */
    private static final int INSERTION_PENALTY = 3;
    private static final int DELETION_PENALTY = 3;
    private static final int NORMAL_SUBSTITUTION_PENALTY = 4;

    private static final int TRANSPOSITION_PENALTY = 1;
    private static final int REPETITION_PENALTY = 1;

    /*
     * Length differences matter, but must not dominate the
     * actual edit distance.
     */
    private static final int LENGTH_PENALTY = 1;

    /*
     * Words sharing at least four initial characters are
     * generally much more plausible corrections.
     */
    private static final int COMMON_PREFIX_LENGTH = 4;
    private static final int COMMON_PREFIX_BONUS = 1;

    /*
     * A very short word should not accept a correction that
     * differs by almost the entire word.
     */
    private static final int SHORT_WORD_LENGTH = 4;

    public CorrectionEngine() {
    }

    /**
     * Rank dictionary candidates and return the best corrections.
     */
    public List<String> rankCandidates(
            String input,
            List<String> candidates,
            String languageTag,
            int maxResults) {

        if (input == null ||
                input.trim().isEmpty() ||
                candidates == null ||
                candidates.isEmpty() ||
                maxResults <= 0) {

            return new ArrayList<>();
        }

        String normalizedInput =
                normalize(input);

        if (normalizedInput.isEmpty()) {
            return new ArrayList<>();
        }

        String language =
                LanguageRules.normalizeLanguage(
                        languageTag
                );

        int resultLimit =
                Math.min(
                        maxResults,
                        DEFAULT_MAX_RESULTS
                );

        List<ScoredCandidate> scored =
                new ArrayList<>();

        Set<String> seen =
                new HashSet<>();

        for (String candidate : candidates) {

            if (candidate == null ||
                    candidate.trim().isEmpty()) {

                continue;
            }

            String normalizedCandidate =
                    normalize(candidate);

            if (normalizedCandidate.isEmpty() ||
                    !seen.add(
                            normalizedCandidate
                    )) {

                continue;
            }

            /*
             * Never suggest the exact word as a correction.
             */
            if (normalizedInput.equals(
                    normalizedCandidate
            )) {

                continue;
            }

            int score =
                    scoreCandidate(
                            normalizedInput,
                            normalizedCandidate,
                            language
                    );

            if (score <=
                    getMaximumUsefulScore(
                            normalizedInput
                    )) {

                scored.add(
                        new ScoredCandidate(
                                normalizedCandidate,
                                score
                        )
                );
            }
        }

        Collections.sort(
                scored,
                new CandidateComparator()
        );

        List<String> results =
                new ArrayList<>(
                        Math.min(
                                resultLimit,
                                scored.size()
                        )
                );

        for (ScoredCandidate candidate :
                scored) {

            if (results.size() >=
                    resultLimit) {

                break;
            }

            results.add(
                    candidate.word
            );
        }

        return results;
    }

    /**
     * Score one candidate.
     *
     * Lower is better.
     */
    public int scoreCandidate(
            String input,
            String candidate,
            String languageTag) {

        if (input == null ||
                candidate == null) {

            return Integer.MAX_VALUE;
        }

        String first =
                normalize(input);

        String second =
                normalize(candidate);

        if (first.isEmpty() ||
                second.isEmpty()) {

            return Integer.MAX_VALUE;
        }

        if (first.equals(second)) {
            return 0;
        }

        String language =
                LanguageRules.normalizeLanguage(
                        languageTag
                );

        int distance =
                weightedDamerauLevenshtein(
                        first,
                        second,
                        language
                );

        if (distance >
                MAX_CANDIDATE_DISTANCE) {

            return distance;
        }

        /*
         * Length difference is useful as a secondary signal,
         * but the edit distance already accounts for insertions
         * and deletions. Keep this contribution deliberately small.
         */
        int lengthDifference =
                Math.abs(
                        first.length() -
                                second.length()
                );

        int score =
                distance +
                        lengthDifference *
                                LENGTH_PENALTY;

        /*
         * Strongly prefer candidates that preserve the beginning
         * of the word. This is especially useful for natural typing
         * mistakes where the user got the beginning right.
         */
        score -=
                commonPrefixBonus(
                        first,
                        second
                );

        /*
         * Diacritic/language variants are very plausible.
         *
         * LanguageRules provides the language-specific knowledge;
         * the edit distance remains the final authority.
         */
        if (isLanguageVariant(
                first,
                second,
                language
        )) {

            score = Math.max(
                    0,
                    score - 1
            );
        }

        return score;
    }

    /**
     * Weighted Damerau-Levenshtein distance.
     *
     * Includes:
     *
     *  - insertion
     *  - deletion
     *  - substitution
     *  - duplicated character
     *  - adjacent transposition
     */
    private int weightedDamerauLevenshtein(
            String first,
            String second,
            String languageTag) {

        if (first.equals(second)) {
            return 0;
        }

        int n =
                first.length();

        int m =
                second.length();

        if (n == 0) {
            return m *
                    INSERTION_PENALTY;
        }

        if (m == 0) {
            return n *
                    DELETION_PENALTY;
        }

        /*
         * Fast rejection for extremely different short words.
         */
        if (Math.abs(n - m) >
                MAX_CANDIDATE_DISTANCE) {

            return MAX_CANDIDATE_DISTANCE + 1;
        }

        int[][] dp =
                new int[n + 1][m + 1];

        for (int i = 0;
             i <= n;
             i++) {

            dp[i][0] =
                    i *
                            DELETION_PENALTY;
        }

        for (int j = 0;
             j <= m;
             j++) {

            dp[0][j] =
                    j *
                            INSERTION_PENALTY;
        }

        for (int i = 1;
             i <= n;
             i++) {

            char typed =
                    first.charAt(
                            i - 1
                    );

            for (int j = 1;
                 j <= m;
                 j++) {

                char candidate =
                        second.charAt(
                                j - 1
                        );

                int substitutionCost =
                        getSubstitutionCost(
                                typed,
                                candidate,
                                languageTag
                        );

                int substitution =
                        dp[i - 1][j - 1] +
                                substitutionCost;

                int insertion =
                        dp[i][j - 1] +
                                INSERTION_PENALTY;

                int deletion =
                        dp[i - 1][j] +
                                getDeletionCost(
                                        typed,
                                        first,
                                        i - 1,
                                        languageTag
                                );

                int best =
                        Math.min(
                                substitution,
                                Math.min(
                                        insertion,
                                        deletion
                                )
                        );

                /*
                 * Repeated character:
                 *
                 * helllo -> hello
                 * comming -> coming
                 */
                if (i >= 2 &&
                        j >= 1 &&
                        first.charAt(i - 2) ==
                                typed &&
                        typed ==
                                candidate) {

                    best =
                            Math.min(
                                    best,
                                    dp[i - 2][j - 1] +
                                            REPETITION_PENALTY
                            );
                }

                /*
                 * Adjacent transposition:
                 *
                 * teh -> the
                 * que -> qeu
                 * qeu -> que
                 */
                if (i >= 2 &&
                        j >= 2 &&
                        first.charAt(i - 2) ==
                                candidate &&
                        typed ==
                                second.charAt(j - 2)) {

                    best =
                            Math.min(
                                    best,
                                    dp[i - 2][j - 2] +
                                            getTranspositionCost(
                                                    first.charAt(
                                                            i - 2
                                                    ),
                                                    typed,
                                                    languageTag
                                            )
                            );
                }

                dp[i][j] =
                        best;
            }
        }

        return dp[n][m];
    }

    /**
     * Character substitution cost.
     *
     * Priority:
     *
     *  1. exact character
     *  2. language-specific relationship
     *  3. physical keyboard relationship
     *  4. normal substitution
     */
    private int getSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return 0;
        }

        int languageCost =
                LanguageRules
                        .getCharacterSubstitutionCost(
                                typed,
                                candidate,
                                languageTag
                        );

        if (languageCost < 5) {
            return languageCost;
        }

        int keyboardCost =
                KeyboardErrorModel
                        .getSubstitutionCost(
                                typed,
                                candidate,
                                languageTag
                        );

        if (keyboardCost <= 2) {
            return keyboardCost;
        }

        return NORMAL_SUBSTITUTION_PENALTY;
    }

    /**
     * Deletion cost.
     *
     * Removing a duplicated character is cheap.
     */
    private int getDeletionCost(
            char character,
            String first,
            int position,
            String languageTag) {

        if (position > 0 &&
                first.charAt(
                        position - 1
                ) == character) {

            return REPETITION_PENALTY;
        }

        return DELETION_PENALTY;
    }

    /**
     * Adjacent transpositions are highly likely typing errors,
     * especially on small mobile keyboards.
     */
    private int getTranspositionCost(
            char first,
            char second,
            String languageTag) {

        int keyboardCost =
                KeyboardErrorModel
                        .getTranspositionCost(
                                first,
                                second,
                                languageTag
                        );

        if (keyboardCost <= 2) {
            return keyboardCost;
        }

        return TRANSPOSITION_PENALTY;
    }

    /**
     * Reward a strong common prefix.
     */
    private int commonPrefixBonus(
            String first,
            String second) {

        int limit =
                Math.min(
                        first.length(),
                        second.length()
                );

        int common = 0;

        while (
                common < limit &&
                first.charAt(common) ==
                        second.charAt(common)
        ) {

            common++;
        }

        if (common >=
                COMMON_PREFIX_LENGTH) {

            return COMMON_PREFIX_BONUS;
        }

        return 0;
    }

    /**
     * Determine how much distance is acceptable for a word.
     *
     * Short words need to be treated more strictly than long words.
     */
    private int getMaximumUsefulScore(
            String input) {

        int length =
                input.length();

        if (length <= 2) {
            return 2;
        }

        if (length <= SHORT_WORD_LENGTH) {
            return 4;
        }

        if (length <= 7) {
            return 6;
        }

        return MAX_CANDIDATE_DISTANCE;
    }

    /**
     * Detect a language-specific variant.
     *
     * We intentionally keep this conservative. LanguageRules remains
     * responsible for defining which variants are valid.
     */
    private boolean isLanguageVariant(
            String first,
            String second,
            String language) {

        if (first.equals(second)) {
            return false;
        }

        List<String> variants =
                LanguageRules.getDiacriticVariants(
                        first,
                        language
                );

        if (variants == null ||
                variants.isEmpty()) {

            return false;
        }

        for (String variant :
                variants) {

            if (second.equals(
                    normalize(variant)
            )) {

                return true;
            }
        }

        return false;
    }

    private String normalize(
            String value) {

        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private static final class ScoredCandidate {

        private final String word;
        private final int score;

        private ScoredCandidate(
                String word,
                int score) {

            this.word =
                    word;

            this.score =
                    score;
        }
    }

    private static final class CandidateComparator
            implements Comparator<ScoredCandidate> {

        @Override
        public int compare(
                ScoredCandidate first,
                ScoredCandidate second) {

            int score =
                    Integer.compare(
                            first.score,
                            second.score
                    );

            if (score != 0) {
                return score;
            }

            /*
             * If scores are equal, prefer the shorter correction.
             * This helps avoid unexpectedly replacing a typo with
             * a much longer dictionary word.
             */
            int length =
                    Integer.compare(
                            first.word.length(),
                            second.word.length()
                    );

            if (length != 0) {
                return length;
            }

            /*
             * Deterministic final ordering.
             */
            return first.word.compareTo(
                    second.word
            );
        }
    }
}
