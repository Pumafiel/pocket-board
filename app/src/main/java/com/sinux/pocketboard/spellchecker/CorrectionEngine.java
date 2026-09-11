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
 *  - Preserve meaningful prefixes and suffixes.
 *  - Rank candidates by accuracy rather than word length.
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
     * a duplicated character or adjacent transposition.
     */
    private static final int INSERTION_PENALTY = 3;
    private static final int DELETION_PENALTY = 3;
    private static final int NORMAL_SUBSTITUTION_PENALTY = 4;

    private static final int TRANSPOSITION_PENALTY = 1;
    private static final int REPETITION_PENALTY = 1;

    /*
     * We deliberately DO NOT apply a general word-length penalty.
     *
     * A longer word is not inherently worse than a shorter word.
     * The edit distance already accounts for insertions/deletions.
     *
     * This is important for:
     *
     *     haci -> hacia
     *     haci -> hacer
     *     escribistes -> escribiste
     *
     * The correct result must be decided by accuracy, not length.
     */

    private static final int COMMON_PREFIX_THRESHOLD = 3;

    /*
     * Prefix preservation is useful for both corrections and
     * incomplete typing, but it must not overpower edit quality.
     */
    private static final int COMMON_PREFIX_BONUS = 2;

    /*
     * Preserve a correct ending as a secondary signal.
     */
    private static final int COMMON_SUFFIX_THRESHOLD = 2;
    private static final int COMMON_SUFFIX_BONUS = 1;

    /*
     * Language variants receive a small additional preference.
     */
    private static final int LANGUAGE_VARIANT_BONUS = 2;

    /*
     * A candidate with a realistic keyboard substitution should
     * beat an equally distant arbitrary substitution.
     */
    private static final int KEYBOARD_ERROR_BONUS = 1;

    /*
     * Very short words require stricter correction.
     */
    private static final int SHORT_WORD_LENGTH = 4;

    public CorrectionEngine() {
    }

    /**
     * Rank dictionary candidates and return the best corrections.
     *
     * Lower base score is better.
     *
     * The final ordering additionally considers:
     *
     *  - edit distance
     *  - language relationship
     *  - keyboard plausibility
     *  - common prefix
     *  - common suffix
     *
     * Word length is NOT used as a preference.
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
             * An exact match is not a correction.
             */
            if (normalizedInput.equals(
                    normalizedCandidate
            )) {

                continue;
            }

            ScoreBreakdown breakdown =
                    scoreBreakdown(
                            normalizedInput,
                            normalizedCandidate,
                            language
                    );

            if (breakdown.totalScore <=
                    getMaximumUsefulScore(
                            normalizedInput
                    )) {

                scored.add(
                        new ScoredCandidate(
                                normalizedCandidate,
                                breakdown
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
     * Public score API retained for compatibility.
     *
     * Lower is better.
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

        if (breakdown == null) {
            return Integer.MAX_VALUE;
        }

        return breakdown.totalScore;
    }

    /*
     * ============================================================
     * SCORE
     * ============================================================
     */

    private ScoreBreakdown scoreBreakdown(
            String input,
            String candidate,
            String language) {

        ScoreBreakdown breakdown =
                createScoreBreakdown(
                        input,
                        candidate,
                        language
                );

        if (breakdown == null) {

            return ScoreBreakdown.invalid();
        }

        return breakdown;
    }

    private ScoreBreakdown createScoreBreakdown(
            String input,
            String candidate,
            String languageTag) {

        if (input == null ||
                candidate == null) {

            return null;
        }

        String first =
                normalize(input);

        String second =
                normalize(candidate);

        if (first.isEmpty() ||
                second.isEmpty()) {

            return null;
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
                    false
            );
        }

        int distance =
                weightedDamerauLevenshtein(
                        first,
                        second,
                        language
                );

        if (distance >
                MAX_CANDIDATE_DISTANCE) {

            return new ScoreBreakdown(
                    distance,
                    distance,
                    0,
                    0,
                    0,
                    0,
                    false
            );
        }

        /*
         * The primary score starts with the actual weighted edit
         * distance.
         *
         * There is intentionally NO generic length penalty.
         */
        int score =
                distance;

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
         * Prefix bonus.
         *
         * Three or more preserved characters indicate that the
         * candidate belongs strongly to the same word stem.
         */
        if (prefixLength >=
                COMMON_PREFIX_THRESHOLD) {

            score -=
                    COMMON_PREFIX_BONUS;
        }

        /*
         * Suffix bonus.
         *
         * Useful for mistakes in the middle of a word where the
         * ending remains correct.
         */
        if (suffixLength >=
                COMMON_SUFFIX_THRESHOLD) {

            score -=
                    COMMON_SUFFIX_BONUS;
        }

        /*
         * Language-aware variants are highly plausible.
         *
         * Example:
         *
         * manana -> mañana
         */
        if (languageVariant) {

            score -=
                    LANGUAGE_VARIANT_BONUS;
        }

        /*
         * A realistic keyboard error receives a small bonus.
         *
         * This is deliberately weaker than edit distance so that
         * keyboard proximity cannot turn an otherwise bad candidate
         * into the best result.
         */
        if (keyboardEvidence > 0) {

            score -=
                    KEYBOARD_ERROR_BONUS;
        }

        /*
         * Never allow heuristic bonuses to make a candidate
         * artificially negative.
         */
        score =
                Math.max(
                        0,
                        score
                );

        return new ScoreBreakdown(
                score,
                distance,
                prefixLength,
                suffixLength,
                keyboardEvidence,
                languageVariant ? 1 : 0,
                true
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

        /*
         * Fast rejection for extremely different words.
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
                 * Repeated character.
                 *
                 * helllo -> hello
                 * comming -> coming
                 * mañana with duplicated character, etc.
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
                 * Adjacent transposition.
                 *
                 * teh -> the
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
         * Language relationship has priority over generic keyboard
         * proximity.
         *
         * Example:
         *
         * n <-> ñ
         * accented/unaccented variants
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
         * Keyboard proximity is intentionally strong.
         *
         * A user hitting an adjacent key is much more likely than
         * an arbitrary substitution.
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
         * Removing a duplicated character is extremely plausible.
         */
        if (position > 0 &&
                first.charAt(
                        position - 1
                ) == character) {

            return REPETITION_PENALTY;
        }

        /*
         * Language-specific omissions can be slightly cheaper.
         */
        if (position > 0 &&
                LanguageRules
                        .getCharacterSubstitutionCost(
                                first.charAt(
                                        position - 1
                                ),
                                character,
                                languageTag
                        ) <= 2) {

            return DELETION_PENALTY;
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
     * KEYBOARD / LINGUISTIC SIGNALS
     * ============================================================
     */

    /**
     * Detect whether the candidate contains at least one
     * substitution that is strongly supported by the keyboard model.
     *
     * This is intentionally only a secondary signal.
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

        int evidence = 0;

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
                 * One strong keyboard relationship is enough for
                 * the ranking bonus.
                 */
                break;
            }
        }

        return evidence;
    }

    /**
     * Count the exact common prefix.
     */
    private int commonPrefixLength(
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

        return common;
    }

    /**
     * Count the exact common suffix.
     */
    private int commonSuffixLength(
            String first,
            String second) {

        int firstIndex =
                first.length() - 1;

        int secondIndex =
                second.length() - 1;

        int common = 0;

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

    /**
     * Determine whether the candidate is a language-specific variant.
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
     * MAXIMUM ACCEPTABLE SCORE
     * ============================================================
     */

    /**
     * Short words are inherently ambiguous.
     *
     * We therefore reject distant candidates aggressively.
     *
     * This is NOT a length preference between two valid candidates.
     * It only limits obviously bad corrections.
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

        private ScoreBreakdown(
                int totalScore,
                int editDistance,
                int prefixLength,
                int suffixLength,
                int keyboardEvidence,
                int languageEvidence,
                boolean valid) {

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
        }

        private static ScoreBreakdown invalid() {

            return new ScoreBreakdown(
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    0,
                    0,
                    0,
                    0,
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
     * There is deliberately NO:
     *
     *     "shorter word wins"
     *
     * rule here.
     *
     * Accuracy comes first.
     */
    private static final class CandidateComparator
            implements Comparator<ScoredCandidate> {

        @Override
        public int compare(
                ScoredCandidate first,
                ScoredCandidate second) {

            /*
             * 1. Overall score.
             *
             * This is the main accuracy signal.
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
             * 2. Raw edit distance.
             *
             * If heuristic bonuses produced the same total score,
             * prefer the candidate requiring fewer actual edits.
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
             * 3. Language relationship.
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
             * 7. Deterministic final ordering only.
             *
             * Alphabetical order is used solely as a final
             * deterministic tie-breaker.
             *
             * Word length is intentionally absent.
             */
            return first.word.compareTo(
                    second.word
            );
        }
    }
}
