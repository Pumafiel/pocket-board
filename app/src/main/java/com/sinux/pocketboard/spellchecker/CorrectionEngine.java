package com.sinux.pocketboard.spellchecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native PocketBoard correction and suggestion ranking engine.
 *
 * This engine does NOT generate dictionary words.
 * DictionaryManager supplies the candidates.
 *
 * The candidates may be:
 *
 *  - exact spelling alternatives
 *  - keyboard corrections
 *  - spelling corrections
 *  - language variants
 *  - prefix completions
 *
 * All of them are ranked together.
 *
 * Position 0 is always the best candidate.
 */
public final class CorrectionEngine {

    private static final int DEFAULT_MAX_RESULTS = 3;

    /*
     * Maximum useful edit cost for a real correction.
     *
     * Completion candidates are handled separately because:
     *
     *     haci -> haciendo
     *
     * is a valid completion even though adding three characters
     * would be too expensive under ordinary edit distance.
     */
    private static final int MAX_CORRECTION_DISTANCE = 8;

    private static final int INSERTION_PENALTY = 3;
    private static final int DELETION_PENALTY = 3;
    private static final int NORMAL_SUBSTITUTION_PENALTY = 4;

    private static final int TRANSPOSITION_PENALTY = 1;
    private static final int REPETITION_PENALTY = 1;

    /*
     * Language-specific substitutions are intentionally cheap.
     *
     * Examples:
     *
     *     n    -> ñ
     *     a    -> á
     *     u    -> ú
     */
    private static final int LANGUAGE_VARIANT_BONUS = 3;

    /*
     * Keyboard mistakes are one of the strongest signals because
     * they are very common in real typing.
     */
    private static final int KEYBOARD_ERROR_BONUS = 2;

    /*
     * Prefix preservation is useful, but it must never overwhelm
     * actual edit accuracy.
     */
    private static final int PREFIX_BONUS = 1;

    /*
     * Correct endings are useful secondary evidence.
     */
    private static final int SUFFIX_BONUS = 1;

    private static final int PREFIX_THRESHOLD = 3;
    private static final int SUFFIX_THRESHOLD = 2;

    /*
     * Completion scoring.
     *
     * Adding one character is much more plausible than adding five.
     * However, completion length is NOT used as a global word-length
     * preference.
     */
    private static final int COMPLETION_CHAR_COST = 1;

    private static final int MAX_COMPLETION_LENGTH_DELTA = 12;

    /*
     * Very short inputs are ambiguous.
     */
    private static final int SHORT_WORD_LENGTH = 4;

    public CorrectionEngine() {
    }

    /**
     * Rank all supplied dictionary candidates.
     *
     * The caller may supply both completions and corrections.
     *
     * The returned order is:
     *
     *     [0] best
     *     [1] alternative
     *     [2] alternative
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

        for (String candidate :
                candidates) {

            if (candidate == null ||
                    candidate.trim().isEmpty()) {

                continue;
            }

            String normalizedCandidate =
                    normalize(candidate);

            if (normalizedCandidate.isEmpty()) {
                continue;
            }

            if (!seen.add(
                    normalizedCandidate
            )) {
                continue;
            }

            /*
             * Exact matches are not correction candidates.
             *
             * DictionaryManager already handles the exact-word
             * case and puts it at position 0.
             */
            if (normalizedInput.equals(
                    normalizedCandidate
            )) {

                continue;
            }

            ScoreBreakdown score =
                    createScoreBreakdown(
                            normalizedInput,
                            normalizedCandidate,
                            language
                    );

            if (!score.valid) {
                continue;
            }

            scored.add(
                    new ScoredCandidate(
                            normalizedCandidate,
                            score
                    )
            );
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
     * Compatibility API.
     *
     * Lower score is better.
     */
    public int scoreCandidate(
            String input,
            String candidate,
            String languageTag) {

        ScoreBreakdown breakdown =
                createScoreBreakdown(
                        input,
                        candidate,
                        languageTag
                );

        if (breakdown == null ||
                !breakdown.valid) {

            return Integer.MAX_VALUE;
        }

        return breakdown.totalScore;
    }

    /*
     * ============================================================
     * SCORE
     * ============================================================
     */

    private ScoreBreakdown createScoreBreakdown(
            String input,
            String candidate,
            String languageTag) {

        if (input == null ||
                candidate == null) {

            return ScoreBreakdown.invalid();
        }

        String first =
                normalize(input);

        String second =
                normalize(candidate);

        if (first.isEmpty() ||
                second.isEmpty()) {

            return ScoreBreakdown.invalid();
        }

        String language =
                LanguageRules.normalizeLanguage(
                        languageTag
                );

        if (first.equals(second)) {

            return new ScoreBreakdown(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    true
            );
        }

        boolean completion =
                isCompletion(
                        first,
                        second
                );

        int prefixLength =
                commonPrefixLength(
                        first,
                        second
                );

        int suffixLength =
                commonSuffixLength(
                        first,
                        second
                );

        boolean languageVariant =
                isLanguageVariant(
                        first,
                        second,
                        language
                );

        int keyboardEvidence =
                keyboardEvidence(
                        first,
                        second,
                        language
                );

        /*
         * --------------------------------------------------------
         * COMPLETION
         * --------------------------------------------------------
         *
         * Completion candidates are deliberately not evaluated
         * with the ordinary insertion cost.
         *
         * Otherwise:
         *
         *     haci -> haciendo
         *
         * would be rejected simply because three characters were
         * added.
         */
        if (completion) {

            int addedCharacters =
                    second.length() -
                            first.length();

            if (addedCharacters >
                    MAX_COMPLETION_LENGTH_DELTA) {

                return ScoreBreakdown.invalid();
            }

            int score =
                    addedCharacters *
                            COMPLETION_CHAR_COST;

            /*
             * A completion which preserves a substantial portion
             * of the typed word is a strong candidate.
             */
            if (prefixLength >=
                    PREFIX_THRESHOLD) {

                score -=
                        PREFIX_BONUS;
            }

            /*
             * Preserve a correct ending when possible.
             */
            if (suffixLength >=
                    SUFFIX_THRESHOLD) {

                score -=
                        SUFFIX_BONUS;
            }

            if (languageVariant) {

                score -=
                        LANGUAGE_VARIANT_BONUS;
            }

            /*
             * A completion can also contain a keyboard error.
             *
             * Example:
             *
             *     hol -> hola
             *
             * is completion, while:
             *
             *     hol -> bola
             *
             * should not receive the same advantage.
             */
            if (keyboardEvidence > 0) {

                score -=
                        KEYBOARD_ERROR_BONUS;
            }

            score =
                    Math.max(
                            0,
                            score
                    );

            return new ScoreBreakdown(
                    score,
                    addedCharacters,
                    prefixLength,
                    suffixLength,
                    keyboardEvidence,
                    languageVariant ? 1 : 0,
                    true,
                    true
            );
        }

        /*
         * --------------------------------------------------------
         * REAL CORRECTION
         * --------------------------------------------------------
         */

        int distance =
                weightedDamerauLevenshtein(
                        first,
                        second,
                        language
                );

        if (distance >
                MAX_CORRECTION_DISTANCE) {

            return ScoreBreakdown.invalid();
        }

        int maximumUsefulScore =
                getMaximumUsefulScore(
                        first
                );

        /*
         * Raw edit distance is the foundation.
         */
        int score =
                distance;

        /*
         * Prefix is a secondary signal only.
         */
        if (prefixLength >=
                PREFIX_THRESHOLD) {

            score -=
                    PREFIX_BONUS;
        }

        /*
         * Suffix is also secondary.
         */
        if (suffixLength >=
                SUFFIX_THRESHOLD) {

            score -=
                    SUFFIX_BONUS;
        }

        /*
         * Language variants are highly plausible.
         */
        if (languageVariant) {

            score -=
                    LANGUAGE_VARIANT_BONUS;
        }

        /*
         * Keyboard proximity is strong evidence.
         */
        if (keyboardEvidence > 0) {

            score -=
                    KEYBOARD_ERROR_BONUS;
        }

        score =
                Math.max(
                        0,
                        score
                );

        /*
         * Do not accept obviously distant corrections.
         */
        if (score >
                maximumUsefulScore) {

            return ScoreBreakdown.invalid();
        }

        return new ScoreBreakdown(
                score,
                distance,
                prefixLength,
                suffixLength,
                keyboardEvidence,
                languageVariant ? 1 : 0,
                true,
                false
        );
    }

    /*
     * ============================================================
     * COMPLETION DETECTION
     * ============================================================
     */

    private boolean isCompletion(
            String input,
            String candidate) {

        if (input.length() >=
                candidate.length()) {

            return false;
        }

        return candidate.startsWith(
                input
        );
    }

    /*
     * ============================================================
     * WEIGHTED DAMERAU-LEVENSHTEIN
     * ============================================================
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

        if (Math.abs(n - m) >
                MAX_CORRECTION_DISTANCE) {

            return MAX_CORRECTION_DISTANCE + 1;
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
                 *     helllo -> hello
                 *     comming -> coming
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
                 *     teh -> the
                 *     qeu -> que
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

    /*
     * ============================================================
     * CHARACTER COSTS
     * ============================================================
     */

    private int getSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return 0;
        }

        /*
         * Language relationship first.
         *
         * n <-> ñ
         * a <-> á
         * e <-> é
         */
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

        /*
         * Keyboard proximity next.
         */
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

    private int getDeletionCost(
            char character,
            String first,
            int position,
            String languageTag) {

        /*
         * Duplicate character deletion is highly plausible.
         */
        if (position > 0 &&
                first.charAt(
                        position - 1
                ) == character) {

            return REPETITION_PENALTY;
        }

        return DELETION_PENALTY;
    }

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

    /*
     * ============================================================
     * KEYBOARD EVIDENCE
     * ============================================================
     */

    private int keyboardEvidence(
            String first,
            String second,
            String languageTag) {

        int limit =
                Math.min(
                        first.length(),
                        second.length()
                );

        int evidence =
                0;

        for (int i = 0;
             i < limit;
             i++) {

            char typed =
                    first.charAt(i);

            char candidate =
                    second.charAt(i);

            if (typed == candidate) {
                continue;
            }

            int keyboardCost =
                    KeyboardErrorModel
                            .getSubstitutionCost(
                                    typed,
                                    candidate,
                                    languageTag
                            );

            if (keyboardCost <= 2) {

                evidence++;

                /*
                 * One strong keyboard relation is enough.
                 */
                break;
            }
        }

        return evidence;
    }

    /*
     * ============================================================
     * PREFIX / SUFFIX
     * ============================================================
     */

    private int commonPrefixLength(
            String first,
            String second) {

        int limit =
                Math.min(
                        first.length(),
                        second.length()
                );

        int common =
                0;

        while (
                common < limit &&
                first.charAt(common) ==
                        second.charAt(common)
        ) {

            common++;
        }

        return common;
    }

    private int commonSuffixLength(
            String first,
            String second) {

        int firstIndex =
                first.length() - 1;

        int secondIndex =
                second.length() - 1;

        int common =
                0;

        while (
                firstIndex >= 0 &&
                secondIndex >= 0 &&
                first.charAt(firstIndex) ==
                        second.charAt(secondIndex)
        ) {

            common++;

            firstIndex--;
            secondIndex--;
        }

        return common;
    }

    /*
     * ============================================================
     * LANGUAGE
     * ============================================================
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

    /*
     * ============================================================
     * SCORE LIMIT
     * ============================================================
     */

    private int getMaximumUsefulScore(
            String input) {

        int length =
                input.length();

        /*
         * Short words are inherently ambiguous.
         */
        if (length <= 2) {
            return 2;
        }

        if (length <= SHORT_WORD_LENGTH) {
            return 4;
        }

        if (length <= 7) {
            return 6;
        }

        return MAX_CORRECTION_DISTANCE;
    }

    /*
     * ============================================================
     * NORMALIZATION
     * ============================================================
     */

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

    /*
     * ============================================================
     * SCORE OBJECT
     * ============================================================
     */

    private static final class ScoreBreakdown {

        private final int totalScore;
        private final int editDistance;
        private final int prefixLength;
        private final int suffixLength;
        private final int keyboardEvidence;
        private final int languageEvidence;
        private final boolean valid;
        private final boolean completion;

        private ScoreBreakdown(
                int totalScore,
                int editDistance,
                int prefixLength,
                int suffixLength,
                int keyboardEvidence,
                int languageEvidence,
                boolean valid,
                boolean completion) {

            this.totalScore =
                    totalScore;

            this.editDistance =
                    editDistance;

            this.prefixLength =
                    prefixLength;

            this.suffixLength =
                    suffixLength;

            this.keyboardEvidence =
                    keyboardEvidence;

            this.languageEvidence =
                    languageEvidence;

            this.valid =
                    valid;

            this.completion =
                    completion;
        }

        private static ScoreBreakdown invalid() {

            return new ScoreBreakdown(
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false
            );
        }
    }

    private static final class ScoredCandidate {

        private final String word;
        private final ScoreBreakdown breakdown;

        private ScoredCandidate(
                String word,
                ScoreBreakdown breakdown) {

            this.word =
                    word;

            this.breakdown =
                    breakdown;
        }
    }

    /*
     * ============================================================
     * FINAL RANKING
     * ============================================================
     *
     * IMPORTANT:
     *
     * There is NO:
     *
     *     "shorter word wins"
     *
     * rule.
     *
     * There is also NO:
     *
     *     "longer word wins"
     *
     * rule.
     *
     * The ranking attempts to identify the most plausible
     * interpretation of what the user typed.
     */
    private static final class CandidateComparator
            implements Comparator<ScoredCandidate> {

        @Override
        public int compare(
                ScoredCandidate first,
                ScoredCandidate second) {

            /*
             * 1. Main accuracy score.
             */
            int score =
                    Integer.compare(
                            first.breakdown.totalScore,
                            second.breakdown.totalScore
                    );

            if (score != 0) {
                return score;
            }

            /*
             * 2. Real edit distance.
             *
             * This is particularly important when heuristic
             * bonuses make two candidates look equally good.
             */
            int distance =
                    Integer.compare(
                            first.breakdown.editDistance,
                            second.breakdown.editDistance
                    );

            if (distance != 0) {
                return distance;
            }

            /*
             * 3. Language-specific relationship.
             */
            int language =
                    Integer.compare(
                            second.breakdown.languageEvidence,
                            first.breakdown.languageEvidence
                    );

            if (language != 0) {
                return language;
            }

            /*
             * 4. Keyboard plausibility.
             */
            int keyboard =
                    Integer.compare(
                            second.breakdown.keyboardEvidence,
                            first.breakdown.keyboardEvidence
                    );

            if (keyboard != 0) {
                return keyboard;
            }

            /*
             * 5. Preserve more of the typed beginning.
             *
             * This helps completion and spelling candidates alike,
             * but only after the actual accuracy score is equal.
             */
            int prefix =
                    Integer.compare(
                            second.breakdown.prefixLength,
                            first.breakdown.prefixLength
                    );

            if (prefix != 0) {
                return prefix;
            }

            /*
             * 6. Preserve more of the typed ending.
             */
            int suffix =
                    Integer.compare(
                            second.breakdown.suffixLength,
                            first.breakdown.suffixLength
                    );

            if (suffix != 0) {
                return suffix;
            }

            /*
             * 7. Prefer a completion only when everything else is
             * genuinely tied.
             *
             * This prevents completion from being unfairly promoted
             * simply because it shares a prefix.
             */
            if (first.breakdown.completion !=
                    second.breakdown.completion) {

                return first.breakdown.completion
                        ? -1
                        : 1;
            }

            /*
             * 8. Deterministic final tie-breaker.
             *
             * Alphabetical order is ONLY used here.
             *
             * Word length is never used as a global preference.
             */
            return first.word.compareTo(
                    second.word
            );
        }
    }
}
