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
 * DictionaryManager supplies the candidates.
 *
 * Candidates can be:
 *
 * - spelling corrections
 * - keyboard corrections
 * - language variants
 * - prefix completions
 *
 * All candidates are ranked using one common plausibility scale.
 *
 * Lower score = better candidate.
 */
public final class CorrectionEngine {

    private static final int DEFAULT_MAX_RESULTS = 3;

    /*
     * Costs are intentionally kept on one common scale.
     *
     * A normal character substitution is more expensive than
     * a keyboard-neighbour error.
     *
     * A transposition or duplicated character is very cheap
     * because these are common real-world typing errors.
     */
    private static final int INSERTION_COST = 3;
    private static final int DELETION_COST = 3;
    private static final int NORMAL_SUBSTITUTION_COST = 4;

    private static final int TRANSPOSE_COST = 1;
    private static final int REPEATED_CHARACTER_COST = 1;

    /*
     * Completion is not "free".
     *
     * The user has only typed a prefix. Adding characters is
     * expected, but a completion still has to earn its position.
     *
     * This is deliberately much cheaper than treating every
     * added character as a spelling error, while still making
     * long arbitrary completions less attractive.
     */
    private static final int COMPLETION_BASE_COST = 2;
    private static final int COMPLETION_CHARACTER_COST = 2;

    /*
     * Structural bonuses are deliberately small.
     *
     * They help break close calls but cannot overpower a real
     * spelling/keyboard error.
     */
    private static final int PREFIX_BONUS = 1;
    private static final int SUFFIX_BONUS = 1;

    private static final int PREFIX_THRESHOLD = 3;
    private static final int SUFFIX_THRESHOLD = 2;

    /*
     * Strong evidence that the user made a real typing error.
     */
    private static final int KEYBOARD_BONUS = 3;
    private static final int LANGUAGE_VARIANT_BONUS = 3;

    /*
     * A candidate farther than this is not useful as a correction.
     *
     * Prefix completions use their own bounded completion metric.
     */
    private static final int MAX_CORRECTION_COST = 12;

    private static final int MAX_COMPLETION_ADDITION = 12;

    /*
     * Short input needs more conservative correction.
     */
    private static final int SHORT_INPUT_LENGTH = 4;

    public CorrectionEngine() {
    }

    /**
     * Ranks dictionary candidates.
     *
     * The input list may contain corrections and completions
     * at the same time.
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

            if (normalizedCandidate.isEmpty()) {
                continue;
            }

            if (!seen.add(
                    normalizedCandidate
            )) {
                continue;
            }

            /*
             * DictionaryManager handles exact matches separately.
             */
            if (normalizedInput.equals(
                    normalizedCandidate
            )) {
                continue;
            }

            ScoreBreakdown score =
                    createScore(
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
     * Lower score means a better candidate.
     */
    public int scoreCandidate(
            String input,
            String candidate,
            String languageTag) {

        ScoreBreakdown score =
                createScore(
                        input,
                        candidate,
                        languageTag
                );

        if (score == null ||
                !score.valid) {

            return Integer.MAX_VALUE;
        }

        return score.totalScore;
    }

    /*
     * ============================================================
     * UNIFIED SCORE
     * ============================================================
     */

    private ScoreBreakdown createScore(
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

        if (first.equals(second)) {

            return ScoreBreakdown.invalid();
        }

        String language =
                LanguageRules.normalizeLanguage(
                        languageTag
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

        boolean completion =
                isPrefixCompletion(
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
         * Completion is still part of the same score model.
         *
         * Example:
         *
         *     hac -> haciendo
         *
         * The cost comes from the characters that have to be
         * supplied, not from the total length of the word.
         *
         * Therefore a longer word is NOT automatically better.
         */
        if (completion) {

            int added =
                    second.length() -
                            first.length();

            if (added <= 0 ||
                    added >
                            MAX_COMPLETION_ADDITION) {

                return ScoreBreakdown.invalid();
            }

            int score =
                    COMPLETION_BASE_COST +
                            added *
                                    COMPLETION_CHARACTER_COST;

            /*
             * Exact prefix preservation is the defining evidence
             * of a completion.
             */
            if (prefixLength >=
                    PREFIX_THRESHOLD) {

                score -=
                        PREFIX_BONUS;
            }

            /*
             * A matching suffix is useful, but only as a
             * secondary signal.
             */
            if (suffixLength >=
                    SUFFIX_THRESHOLD) {

                score -=
                        SUFFIX_BONUS;
            }

            /*
             * These signals normally matter little for a pure
             * completion, but can help when the candidate is also
             * a language variant.
             */
            if (languageVariant) {

                score -=
                        LANGUAGE_VARIANT_BONUS;
            }

            /*
             * Do NOT give a keyboard bonus merely because the
             * candidate contains a nearby character somewhere.
             *
             * For a true completion, keyboard evidence is useful
             * only when there is actually a mismatch inside the
             * typed portion.
             */
            if (keyboardEvidence > 0) {

                score -=
                        KEYBOARD_BONUS;
            }

            score =
                    Math.max(
                            0,
                            score
                    );

            return new ScoreBreakdown(
                    score,
                    added,
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
         * CORRECTION
         * --------------------------------------------------------
         */

        int editCost =
                weightedDamerauLevenshtein(
                        first,
                        second,
                        language
                );

        if (editCost >
                MAX_CORRECTION_COST) {

            return ScoreBreakdown.invalid();
        }

        int score =
                editCost;

        /*
         * Structural evidence.
         *
         * These bonuses are intentionally small.
         */
        if (prefixLength >=
                PREFIX_THRESHOLD) {

            score -=
                    PREFIX_BONUS;
        }

        if (suffixLength >=
                SUFFIX_THRESHOLD) {

            score -=
                    SUFFIX_BONUS;
        }

        /*
         * Language-specific spelling relationship.
         *
         * Examples:
         *
         *     n  -> ñ
         *     a  -> á
         *     e  -> é
         */
        if (languageVariant) {

            score -=
                    LANGUAGE_VARIANT_BONUS;
        }

        /*
         * Keyboard proximity is strong evidence of actual user
         * intent.
         */
        if (keyboardEvidence > 0) {

            score -=
                    KEYBOARD_BONUS;
        }

        score =
                Math.max(
                        0,
                        score
                );

        /*
         * Short inputs should not produce wild corrections.
         */
        if (first.length() <= 2 &&
                score > 2) {

            return ScoreBreakdown.invalid();
        }

        if (first.length() <=
                SHORT_INPUT_LENGTH &&
                score > 6) {

            return ScoreBreakdown.invalid();
        }

        return new ScoreBreakdown(
                score,
                editCost,
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
     * COMPLETION
     * ============================================================
     */

    private boolean isPrefixCompletion(
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
     * DAMERAU-LEVENSHTEIN
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
                    INSERTION_COST;
        }

        if (m == 0) {
            return n *
                    DELETION_COST;
        }

        /*
         * Very large length differences cannot represent a useful
         * spelling correction.
         */
        if (Math.abs(n - m) >
                MAX_CORRECTION_COST) {

            return MAX_CORRECTION_COST + 1;
        }

        int[][] dp =
                new int[n + 1][m + 1];

        for (int i = 0;
             i <= n;
             i++) {

            dp[i][0] =
                    i *
                            DELETION_COST;
        }

        for (int j = 0;
             j <= m;
             j++) {

            dp[0][j] =
                    j *
                            INSERTION_COST;
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

                char target =
                        second.charAt(
                                j - 1
                        );

                int substitution =
                        dp[i - 1][j - 1] +
                                getSubstitutionCost(
                                        typed,
                                        target,
                                        languageTag
                                );

                int insertion =
                        dp[i][j - 1] +
                                INSERTION_COST;

                int deletion =
                        dp[i - 1][j] +
                                getDeletionCost(
                                        first,
                                        i - 1
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
                 * Repeated character.
                 *
                 * helllo -> hello
                 * commming -> comming
                 */
                if (i >= 2 &&
                        j >= 1 &&
                        first.charAt(i - 2) ==
                                typed &&
                        typed ==
                                target) {

                    best =
                            Math.min(
                                    best,
                                    dp[i - 2][j - 1] +
                                            REPEATED_CHARACTER_COST
                            );
                }

                /*
                 * Adjacent transposition.
                 *
                 * teh -> the
                 * adn -> and
                 */
                if (i >= 2 &&
                        j >= 2 &&
                        first.charAt(i - 2) ==
                                target &&
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
            char target,
            String languageTag) {

        if (typed == target) {
            return 0;
        }

        /*
         * Language relation gets first priority.
         */
        int languageCost =
                LanguageRules
                        .getCharacterSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

        if (languageCost < 5) {
            return languageCost;
        }

        /*
         * Physical keyboard relation.
         */
        int keyboardCost =
                KeyboardErrorModel
                        .getSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

        if (keyboardCost <= 2) {
            return keyboardCost;
        }

        return NORMAL_SUBSTITUTION_COST;
    }

    private int getDeletionCost(
            String input,
            int position) {

        if (position > 0 &&
                input.charAt(position - 1) ==
                        input.charAt(position)) {

            return REPEATED_CHARACTER_COST;
        }

        return DELETION_COST;
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

        return TRANSPOSE_COST;
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

        /*
         * We only inspect the portion that the user actually
         * typed.
         *
         * This is important for completions: characters added
         * after the typed prefix must not create fake keyboard
         * evidence.
         */
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

            char target =
                    second.charAt(i);

            if (typed == target) {
                continue;
            }

            int keyboardCost =
                    KeyboardErrorModel
                            .getSubstitutionCost(
                                    typed,
                                    target,
                                    languageTag
                            );

            if (keyboardCost <= 2) {

                evidence++;

                /*
                 * One strong keyboard relationship is enough.
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
     * There is intentionally NO global preference for:
     *
     * - shorter words
     * - longer words
     * - completions
     * - corrections
     *
     * The score determines the primary order.
     *
     * The remaining comparisons are only deterministic tie-breakers.
     */
    private static final class CandidateComparator
            implements Comparator<ScoredCandidate> {

        @Override
        public int compare(
                ScoredCandidate first,
                ScoredCandidate second) {

            /*
             * 1. Unified plausibility score.
             */
            int result =
                    Integer.compare(
                            first.breakdown.totalScore,
                            second.breakdown.totalScore
                    );

            if (result != 0) {
                return result;
            }

            /*
             * 2. Real edit/completion effort.
             *
             * This prevents heuristic bonuses from making a much
             * less accurate candidate appear equal to a very close
             * candidate.
             */
            result =
                    Integer.compare(
                            first.breakdown.editDistance,
                            second.breakdown.editDistance
                    );

            if (result != 0) {
                return result;
            }

            /*
             * 3. Language-specific evidence.
             */
            result =
                    Integer.compare(
                            second.breakdown.languageEvidence,
                            first.breakdown.languageEvidence
                    );

            if (result != 0) {
                return result;
            }

            /*
             * 4. Keyboard evidence.
             */
            result =
                    Integer.compare(
                            second.breakdown.keyboardEvidence,
                            first.breakdown.keyboardEvidence
                    );

            if (result != 0) {
                return result;
            }

            /*
             * 5. Preserve the typed prefix.
             */
            result =
                    Integer.compare(
                            second.breakdown.prefixLength,
                            first.breakdown.prefixLength
                    );

            if (result != 0) {
                return result;
            }

            /*
             * 6. Preserve the typed suffix.
             */
            result =
                    Integer.compare(
                            second.breakdown.suffixLength,
                            first.breakdown.suffixLength
                    );

            if (result != 0) {
                return result;
            }

            /*
             * 7. Deterministic final ordering.
             *
             * No length preference.
             */
            return first.word.compareTo(
                    second.word
            );
        }
    }
}
