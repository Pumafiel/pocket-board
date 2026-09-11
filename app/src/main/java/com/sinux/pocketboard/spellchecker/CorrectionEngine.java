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
 * The engine does not decide which words exist in a language.
 * It ranks the candidates supplied by DictionaryManager.
 *
 * Lower score = better candidate.
 *
 * Ranking is based on:
 *
 * 1. Actual transformation cost.
 * 2. Language-specific evidence used by that transformation.
 * 3. Keyboard evidence used by that transformation.
 * 4. Preserved prefix.
 * 5. Preserved suffix.
 * 6. Completion quality.
 *
 * There is deliberately no global preference for short or long
 * words.
 */
public final class CorrectionEngine {

    private static final int DEFAULT_MAX_RESULTS = 3;

    /*
     * ============================================================
     * BASE EDIT COSTS
     * ============================================================
     */

    private static final int INSERTION_COST = 3;
    private static final int DELETION_COST = 3;
    private static final int NORMAL_SUBSTITUTION_COST = 4;

    /*
     * Common typing mistakes.
     */
    private static final int TRANSPOSE_COST = 1;
    private static final int REPEATED_CHARACTER_COST = 1;

    /*
     * Maximum correction cost.
     */
    private static final int MAX_CORRECTION_COST = 12;

    /*
     * Conservative handling for very short input.
     */
    private static final int SHORT_INPUT_LENGTH = 4;

    /*
     * ============================================================
     * COMPLETION
     * ============================================================
     *
     * A prefix completion is not a spelling error.
     *
     * Example:
     *
     *     auto -> automóvil
     *
     * The added characters therefore do not accumulate normal
     * edit costs.
     *
     * There is no frequency information in CorrectionEngine, so
     * completion length is used only as a late tie-breaker.
     */
    private static final int COMPLETION_BASE_COST = 0;
    private static final int MAX_COMPLETION_ADDITION = 32;

    /*
     * A prefix of four or more characters is considered strong.
     */
    private static final int STRONG_PREFIX_LENGTH = 4;

    public CorrectionEngine() {
    }

    /*
     * ============================================================
     * PUBLIC API
     * ============================================================
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
             * Exact matches are intentionally not returned by this
             * method. DictionaryManager handles them separately and
             * places the exact word at position zero.
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
     * SCORE CREATION
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

        /*
         * --------------------------------------------------------
         * COMPLETION
         * --------------------------------------------------------
         */

        if (isPrefixCompletion(
                first,
                second
        )) {

            return createCompletionScore(
                    first,
                    second,
                    prefixLength,
                    suffixLength
            );
        }

        /*
         * --------------------------------------------------------
         * CORRECTION
         * --------------------------------------------------------
         */

        return createCorrectionScore(
                first,
                second,
                prefixLength,
                suffixLength,
                language
        );
    }

    /*
     * ============================================================
     * COMPLETION SCORE
     * ============================================================
     */

    private ScoreBreakdown createCompletionScore(
            String input,
            String candidate,
            int prefixLength,
            int suffixLength) {

        int added =
                candidate.length() -
                        input.length();

        if (added <= 0 ||
                added >
                        MAX_COMPLETION_ADDITION) {

            return ScoreBreakdown.invalid();
        }

        /*
         * All valid prefix completions begin with an exact prefix.
         *
         * We therefore do not punish the added letters as spelling
         * errors.
         */
        return new ScoreBreakdown(
                COMPLETION_BASE_COST,
                0,
                prefixLength,
                suffixLength,
                0,
                0,
                prefixLength >=
                        STRONG_PREFIX_LENGTH,
                true,
                true,
                added
        );
    }

    /*
     * ============================================================
     * CORRECTION SCORE
     * ============================================================
     */

    private ScoreBreakdown createCorrectionScore(
            String input,
            String candidate,
            int prefixLength,
            int suffixLength,
            String languageTag) {

        EditResult edit =
                weightedDamerauLevenshtein(
                        input,
                        candidate,
                        languageTag
                );

        if (!edit.valid ||
                edit.cost >
                        MAX_CORRECTION_COST) {

            return ScoreBreakdown.invalid();
        }

        /*
         * Short input is inherently ambiguous.
         *
         * Do not perform aggressive guesses.
         */
        if (input.length() <= 2 &&
                edit.cost > 2) {

            return ScoreBreakdown.invalid();
        }

        if (input.length() <=
                SHORT_INPUT_LENGTH &&
                edit.cost > 6) {

            return ScoreBreakdown.invalid();
        }

        return new ScoreBreakdown(
                edit.cost,
                edit.cost,
                prefixLength,
                suffixLength,
                edit.keyboardEvidence,
                edit.languageEvidence,
                prefixLength >=
                        STRONG_PREFIX_LENGTH,
                false,
                true,
                0
        );
    }

    /*
     * ============================================================
     * WEIGHTED DAMERAU-LEVENSHTEIN
     * ============================================================
     *
     * This version does more than return a number.
     *
     * It also preserves which operations were actually selected
     * by the optimal path.
     *
     * That is important because secondary evidence must describe
     * the transformation that actually produced the score.
     */
    private EditResult weightedDamerauLevenshtein(
            String first,
            String second,
            String languageTag) {

        if (first.equals(second)) {
            return EditResult.exact();
        }

        int n =
                first.length();

        int m =
                second.length();

        if (n == 0) {

            return EditResult.simple(
                    m * INSERTION_COST
            );
        }

        if (m == 0) {

            return EditResult.simple(
                    n * DELETION_COST
            );
        }

        if (Math.abs(n - m) >
                MAX_CORRECTION_COST) {

            return EditResult.invalid();
        }

        PathCell[][] dp =
                new PathCell[n + 1][m + 1];

        dp[0][0] =
                PathCell.start();

        /*
         * Deletions from the typed word.
         */
        for (int i = 1;
             i <= n;
             i++) {

            int cost =
                    getDeletionCost(
                            first,
                            i - 1
                    );

            PathCell previous =
                    dp[i - 1][0];

            dp[i][0] =
                    previous.extend(
                            cost,
                            Operation.DELETION,
                            0,
                            0
                    );
        }

        /*
         * Insertions into the candidate.
         */
        for (int j = 1;
             j <= m;
             j++) {

            PathCell previous =
                    dp[0][j - 1];

            dp[0][j] =
                    previous.extend(
                            INSERTION_COST,
                            Operation.INSERTION,
                            0,
                            0
                    );
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

                PathCell best =
                        null;

                /*
                 * ------------------------------------------------
                 * 1. Substitution / exact character
                 * ------------------------------------------------
                 */

                int substitutionCost =
                        getSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

                int substitutionLanguage =
                        getLanguageEvidence(
                                typed,
                                target,
                                languageTag
                        );

                int substitutionKeyboard =
                        getKeyboardEvidence(
                                typed,
                                target,
                                languageTag
                        );

                best =
                        chooseBetter(
                                best,
                                dp[i - 1][j - 1].extend(
                                        substitutionCost,
                                        substitutionCost == 0
                                                ? Operation.EXACT
                                                : Operation.SUBSTITUTION,
                                        substitutionLanguage,
                                        substitutionKeyboard
                                )
                        );

                /*
                 * ------------------------------------------------
                 * 2. Insertion
                 * ------------------------------------------------
                 */

                best =
                        chooseBetter(
                                best,
                                dp[i][j - 1].extend(
                                        INSERTION_COST,
                                        Operation.INSERTION,
                                        0,
                                        0
                                )
                        );

                /*
                 * ------------------------------------------------
                 * 3. Deletion
                 * ------------------------------------------------
                 */

                int deletionCost =
                        getDeletionCost(
                                first,
                                i - 1
                        );

                Operation deletionOperation =
                        deletionCost ==
                                REPEATED_CHARACTER_COST
                                ? Operation.REPETITION
                                : Operation.DELETION;

                best =
                        chooseBetter(
                                best,
                                dp[i - 1][j].extend(
                                        deletionCost,
                                        deletionOperation,
                                        0,
                                        0
                                )
                        );

                /*
                 * ------------------------------------------------
                 * 4. Repeated character
                 * ------------------------------------------------
                 *
                 * helllo -> hello
                 */
                if (i >= 2 &&
                        j >= 1 &&
                        first.charAt(i - 2) ==
                                typed &&
                        typed ==
                                target) {

                    best =
                            chooseBetter(
                                    best,
                                    dp[i - 2][j - 1].extend(
                                            REPEATED_CHARACTER_COST,
                                            Operation.REPETITION,
                                            0,
                                            0
                                    )
                            );
                }

                /*
                 * ------------------------------------------------
                 * 5. Adjacent transposition
                 * ------------------------------------------------
                 *
                 * teh -> the
                 * adn -> and
                 */
                if (i >= 2 &&
                        j >= 2 &&
                        first.charAt(i - 2) ==
                                target &&
                        typed ==
                                second.charAt(
                                        j - 2
                                )) {

                    char firstTransposed =
                            first.charAt(
                                    i - 2
                            );

                    char secondTransposed =
                            typed;

                    int transpositionCost =
                            getTranspositionCost(
                                    firstTransposed,
                                    secondTransposed,
                                    languageTag
                            );

                    int transpositionLanguage =
                            getLanguageEvidence(
                                    firstTransposed,
                                    secondTransposed,
                                    languageTag
                            );

                    int transpositionKeyboard =
                            getKeyboardEvidence(
                                    firstTransposed,
                                    secondTransposed,
                                    languageTag
                            );

                    best =
                            chooseBetter(
                                    best,
                                    dp[i - 2][j - 2].extend(
                                            transpositionCost,
                                            Operation.TRANSPOSITION,
                                            transpositionLanguage,
                                            transpositionKeyboard
                                    )
                            );
                }

                dp[i][j] =
                        best;
            }
        }

        PathCell result =
                dp[n][m];

        if (result == null) {
            return EditResult.invalid();
        }

        return new EditResult(
                result.cost,
                result.languageEvidence,
                result.keyboardEvidence,
                true
        );
    }

    /*
     * ============================================================
     * PATH SELECTION
     * ============================================================
     *
     * When two edit paths have the same total cost, preserve the
     * path with stronger linguistic evidence.
     *
     * This means:
     *
     *     n -> ñ
     *
     * can beat:
     *
     *     n -> b
     *
     * when both happen to have the same numeric cost.
     *
     * Keyboard evidence is considered after language evidence.
     */
    private PathCell chooseBetter(
            PathCell current,
            PathCell candidate) {

        if (candidate == null) {
            return current;
        }

        if (current == null) {
            return candidate;
        }

        int comparison =
                comparePathQuality(
                        candidate,
                        current
                );

        return comparison < 0
                ? candidate
                : current;
    }

    private int comparePathQuality(
            PathCell first,
            PathCell second) {

        int result =
                Integer.compare(
                        first.cost,
                        second.cost
                );

        if (result != 0) {
            return result;
        }

        /*
         * More language evidence is better when actual cost is equal.
         */
        result =
                Integer.compare(
                        second.languageEvidence,
                        first.languageEvidence
                );

        if (result != 0) {
            return result;
        }

        /*
         * More keyboard evidence is better when language evidence
         * is also equal.
         */
        result =
                Integer.compare(
                        second.keyboardEvidence,
                        first.keyboardEvidence
                );

        if (result != 0) {
            return result;
        }

        /*
         * Prefer fewer operations when the numerical result is
         * identical.
         */
        return Integer.compare(
                first.operationCount,
                second.operationCount
        );
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
         * Language rules have priority.
         *
         * Examples:
         *
         * n -> ñ
         * a -> á
         * u -> ü
         */
        int languageCost =
                LanguageRules
                        .getCharacterSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

        if (languageCost <
                KeyboardErrorModel.getUnknownCost()) {

            return languageCost;
        }

        /*
         * Then physical keyboard relationship.
         */
        int keyboardCost =
                KeyboardErrorModel
                        .getSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

        if (keyboardCost <
                KeyboardErrorModel.getUnknownCost()) {

            return keyboardCost;
        }

        /*
         * Completely unrelated substitution.
         */
        return NORMAL_SUBSTITUTION_COST;
    }

    private int getLanguageEvidence(
            char typed,
            char target,
            String languageTag) {

        if (typed == target) {
            return 0;
        }

        int cost =
                LanguageRules
                        .getCharacterSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

        return cost <
                KeyboardErrorModel.getUnknownCost()
                ? 1
                : 0;
    }

    private int getKeyboardEvidence(
            char typed,
            char target,
            String languageTag) {

        if (typed == target) {
            return 0;
        }

        /*
         * A language-specific relation should not also count as
         * keyboard evidence.
         */
        int languageCost =
                LanguageRules
                        .getCharacterSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

        if (languageCost <
                KeyboardErrorModel.getUnknownCost()) {

            return 0;
        }

        int keyboardCost =
                KeyboardErrorModel
                        .getSubstitutionCost(
                                typed,
                                target,
                                languageTag
                        );

        return keyboardCost <
                KeyboardErrorModel.getUnknownCost()
                ? 1
                : 0;
    }

    private int getDeletionCost(
            String input,
            int position) {

        /*
         * If the character being removed belongs to a repeated
         * pair, this is a very common typing mistake.
         */
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

        if (keyboardCost <
                KeyboardErrorModel.getUnknownCost()) {

            return keyboardCost;
        }

        return TRANSPOSE_COST;
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
     * SCORE BREAKDOWN
     * ============================================================
     */

    private static final class ScoreBreakdown {

        private final int totalScore;
        private final int editDistance;

        private final int prefixLength;
        private final int suffixLength;

        private final int keyboardEvidence;
        private final int languageEvidence;

        private final boolean strongPrefix;
        private final boolean completion;

        private final boolean valid;

        /*
         * Number of characters added by a completion.
         *
         * Used only as a late completion tie-breaker.
         */
        private final int completionAddition;

        private ScoreBreakdown(
                int totalScore,
                int editDistance,
                int prefixLength,
                int suffixLength,
                int keyboardEvidence,
                int languageEvidence,
                boolean strongPrefix,
                boolean completion,
                boolean valid,
                int completionAddition) {

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

            this.strongPrefix =
                    strongPrefix;

            this.completion =
                    completion;

            this.valid =
                    valid;

            this.completionAddition =
                    completionAddition;
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
                    false,
                    false,
                    Integer.MAX_VALUE
            );
        }
    }

    /*
     * ============================================================
     * EDIT PATH
     * ============================================================
     */

    private enum Operation {
        EXACT,
        SUBSTITUTION,
        INSERTION,
        DELETION,
        REPETITION,
        TRANSPOSITION
    }

    private static final class PathCell {

        private final int cost;

        private final int languageEvidence;
        private final int keyboardEvidence;

        private final int operationCount;

        private final Operation operation;

        private PathCell(
                int cost,
                int languageEvidence,
                int keyboardEvidence,
                int operationCount,
                Operation operation) {

            this.cost =
                    cost;

            this.languageEvidence =
                    languageEvidence;

            this.keyboardEvidence =
                    keyboardEvidence;

            this.operationCount =
                    operationCount;

            this.operation =
                    operation;
        }

        private static PathCell start() {

            return new PathCell(
                    0,
                    0,
                    0,
                    0,
                    Operation.EXACT
            );
        }

        private PathCell extend(
                int addedCost,
                Operation operation,
                int languageEvidence,
                int keyboardEvidence) {

            return new PathCell(
                    safeAdd(
                            this.cost,
                            addedCost
                    ),
                    safeAdd(
                            this.languageEvidence,
                            languageEvidence
                    ),
                    safeAdd(
                            this.keyboardEvidence,
                            keyboardEvidence
                    ),
                    safeAdd(
                            this.operationCount,
                            operation ==
                                    Operation.EXACT
                                    ? 0
                                    : 1
                    ),
                    operation
            );
        }
    }

    private static final class EditResult {

        private final int cost;
        private final int languageEvidence;
        private final int keyboardEvidence;
        private final boolean valid;

        private EditResult(
                int cost,
                int languageEvidence,
                int keyboardEvidence,
                boolean valid) {

            this.cost =
                    cost;

            this.languageEvidence =
                    languageEvidence;

            this.keyboardEvidence =
                    keyboardEvidence;

            this.valid =
                    valid;
        }

        private static EditResult exact() {

            return new EditResult(
                    0,
                    0,
                    0,
                    true
            );
        }

        private static EditResult simple(
                int cost) {

            return new EditResult(
                    cost,
                    0,
                    0,
                    true
            );
        }

        private static EditResult invalid() {

            return new EditResult(
                    Integer.MAX_VALUE,
                    0,
                    0,
                    false
            );
        }
    }

    /*
     * ============================================================
     * SCORED CANDIDATE
     * ============================================================
     */

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
     * Accuracy hierarchy:
     *
     * 1. Actual transformation cost.
     * 2. Language evidence used by that transformation.
     * 3. Keyboard evidence used by that transformation.
     * 4. Preserved prefix.
     * 5. Preserved suffix.
     * 6. Strong prefix.
     * 7. Completion addition length, only for otherwise equal
     *    completions.
     * 8. Deterministic lexical order.
     *
     * There is NO universal preference for short or long words.
     */
    private static final class CandidateComparator
            implements Comparator<ScoredCandidate> {

        @Override
        public int compare(
                ScoredCandidate first,
                ScoredCandidate second) {

            /*
             * ----------------------------------------------------
             * 1. Actual score
             * ----------------------------------------------------
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
             * ----------------------------------------------------
             * 2. Actual edit distance
             * ----------------------------------------------------
             *
             * Kept separate for clarity and for future scoring
             * extensions.
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
             * ----------------------------------------------------
             * 3. Language evidence
             * ----------------------------------------------------
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
             * ----------------------------------------------------
             * 4. Keyboard evidence
             * ----------------------------------------------------
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
             * ----------------------------------------------------
             * 5. Preserved prefix
             * ----------------------------------------------------
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
             * ----------------------------------------------------
             * 6. Preserved suffix
             * ----------------------------------------------------
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
             * ----------------------------------------------------
             * 7. Strong prefix
             * ----------------------------------------------------
             */
            if (first.breakdown.strongPrefix !=
                    second.breakdown.strongPrefix) {

                return first.breakdown.strongPrefix
                        ? -1
                        : 1;
            }

            /*
             * ----------------------------------------------------
             * 8. Completion-specific tie-breaker
             * ----------------------------------------------------
             *
             * Only applies when everything above is identical.
             *
             * This is NOT a global preference for short words.
             */
            if (first.breakdown.completion &&
                    second.breakdown.completion) {

                result =
                        Integer.compare(
                                first.breakdown.completionAddition,
                                second.breakdown.completionAddition
                        );

                if (result != 0) {
                    return result;
                }
            }

            /*
             * ----------------------------------------------------
             * 9. Completion vs correction
             * ----------------------------------------------------
             *
             * Only reached after all meaningful quality signals
             * are equal.
             */
            if (first.breakdown.completion !=
                    second.breakdown.completion) {

                return first.breakdown.completion
                        ? -1
                        : 1;
            }

            /*
             * ----------------------------------------------------
             * 10. Deterministic final ordering
             * ----------------------------------------------------
             */
            return first.word.compareTo(
                    second.word
            );
        }
    }

    /*
     * ============================================================
     * SAFE INTEGER ADDITION
     * ============================================================
     */

    private static int safeAdd(
            int first,
            int second) {

        if (first == Integer.MAX_VALUE ||
                second == Integer.MAX_VALUE) {

            return Integer.MAX_VALUE;
        }

        if (second > 0 &&
                first >
                        Integer.MAX_VALUE - second) {

            return Integer.MAX_VALUE;
        }

        return first + second;
    }
}
