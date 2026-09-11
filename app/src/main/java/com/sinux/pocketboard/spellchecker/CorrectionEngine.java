ackage com.sinux.pocketboard.spellchecker;

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
 * It only ranks the candidates supplied by DictionaryManager.
 *
 * Lower score = better candidate.
 *
 * The ranking is based primarily on the actual transformation
 * required to turn the typed text into the candidate.
 *
 * Secondary signals:
 *
 * - keyboard proximity
 * - language-specific spelling relationships
 * - preserved prefix
 * - preserved suffix
 *
 * There is deliberately no global preference for short or long
 * words.
 */
public final class CorrectionEngine {

    private static final int DEFAULT_MAX_RESULTS = 3;

    /*
     * ------------------------------------------------------------
     * Base edit costs
     * ------------------------------------------------------------
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
     * Maximum cost for a normal correction.
     */
    private static final int MAX_CORRECTION_COST = 12;

    /*
     * Short input is inherently ambiguous.
     *
     * We therefore become conservative rather than guessing wildly.
     */
    private static final int SHORT_INPUT_LENGTH = 4;

    /*
     * ------------------------------------------------------------
     * Completion scoring
     * ------------------------------------------------------------
     *
     * Completion is fundamentally different from correction.
     *
     * If the user typed:
     *
     *     "auto"
     *
     * then:
     *
     *     "automovil"
     *     "automatico"
     *
     * are both legitimate completions.
     *
     * The additional characters therefore must NOT be treated as
     * spelling errors.
     *
     * However, a completion with an extremely large addition should
     * not automatically dominate a more natural candidate.
     */
    private static final int COMPLETION_BASE_COST = 1;

    /*
     * The first few added characters are cheap.
     * Later additions become progressively less influential.
     *
     * This is intentionally much weaker than a linear
     * "one point per character" penalty.
     */
    private static final int COMPLETION_FIRST_CHARS = 3;
    private static final int COMPLETION_LATER_COST = 1;

    private static final int MAX_COMPLETION_ADDITION = 16;

    /*
     * Prefix evidence.
     */
    private static final int STRONG_PREFIX_LENGTH = 4;

    /*
     * ------------------------------------------------------------
     * Public API
     * ------------------------------------------------------------
     */

    public CorrectionEngine() {
    }

    /**
     * Ranks dictionary candidates.
     *
     * The input list can contain corrections and prefix completions.
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

            /*
             * Do not rank the same normalized candidate twice.
             */
            if (!seen.add(
                    normalizedCandidate
            )) {
                continue;
            }

            /*
             * Exact matches are handled by DictionaryManager.
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
         * Completion
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
                    suffixLength,
                    language
            );
        }

        /*
         * --------------------------------------------------------
         * Correction
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
     * COMPLETION
     * ============================================================
     */

    private ScoreBreakdown createCompletionScore(
            String input,
            String candidate,
            int prefixLength,
            int suffixLength,
            String languageTag) {

        int added =
                candidate.length() -
                        input.length();

        if (added <= 0 ||
                added >
                        MAX_COMPLETION_ADDITION) {

            return ScoreBreakdown.invalid();
        }

        /*
         * Every completion starts with a very small base cost.
         */
        int score =
                COMPLETION_BASE_COST;

        /*
         * The first few characters added are expected.
         * Longer additions have only a mild influence.
         *
         * This avoids:
         *
         *     "longer = automatically worse"
         *
         * while still preventing absurdly long candidates from
         * receiving exactly the same score as short natural ones.
         */
        if (added >
                COMPLETION_FIRST_CHARS) {

            score +=
                    (added -
                            COMPLETION_FIRST_CHARS)
                            *
                            COMPLETION_LATER_COST;
        }

        /*
         * Strong prefix evidence is extremely important.
         *
         * This is not a negative bonus. It is a tie-breaking
         * quality signal kept separate from the actual score.
         */
        boolean strongPrefix =
                prefixLength >=
                        STRONG_PREFIX_LENGTH;

        /*
         * The completion has no edit error inside the typed prefix.
         *
         * Check whether the candidate's completed portion contains
         * useful linguistic information. We do NOT punish ordinary
         * accented words here.
         */
        int languageEvidence =
                completionLanguageEvidence(
                        input,
                        candidate,
                        languageTag
                );

        /*
         * A completion must not receive keyboard evidence merely
         * because some newly-added character happens to be adjacent
         * to something on the keyboard.
         *
         * Only the typed prefix matters.
         */
        int keyboardEvidence = 0;

        return new ScoreBreakdown(
                score,
                added,
                prefixLength,
                suffixLength,
                keyboardEvidence,
                languageEvidence,
                strongPrefix,
                true,
                true
        );
    }

    /**
     * A completion does not need to be a language variant.
     *
     * This method only records useful evidence when the candidate
     * preserves a meaningful language-specific spelling relation
     * inside the already typed portion.
     */
    private int completionLanguageEvidence(
            String input,
            String candidate,
            String languageTag) {

        int limit =
                Math.min(
                        input.length(),
                        candidate.length()
                );

        int evidence = 0;

        for (int i = 0;
             i < limit;
             i++) {

            char typed =
                    input.charAt(i);

            char target =
                    candidate.charAt(i);

            if (typed == target) {
                continue;
            }

            int cost =
                    LanguageRules
                            .getCharacterSubstitutionCost(
                                    typed,
                                    target,
                                    languageTag
                            );

            if (cost <
                    KeyboardErrorModel.getUnknownCost()) {

                evidence++;
            }
        }

        return evidence;
    }

    /*
     * ============================================================
     * CORRECTION
     * ============================================================
     */

    private ScoreBreakdown createCorrectionScore(
            String input,
            String candidate,
            int prefixLength,
            int suffixLength,
            String languageTag) {

        int editCost =
                weightedDamerauLevenshtein(
                        input,
                        candidate,
                        languageTag
                );

        if (editCost >
                MAX_CORRECTION_COST) {

            return ScoreBreakdown.invalid();
        }

        /*
         * No arbitrary global bonuses.
         *
         * The edit distance already contains:
         *
         * - language-aware substitutions
         * - keyboard-aware substitutions
         * - insertions
         * - deletions
         * - repetitions
         * - transpositions
         */
        int score =
                editCost;

        /*
         * Prefix and suffix are evidence for the comparator, not
         * artificial score subtraction.
         *
         * This is important:
         *
         * "score = distance - bonus"
         *
         * can make unrelated candidates tie at zero.
         *
         * Keeping the signals separate preserves information.
         */

        int languageEvidence =
                countLanguageEvidence(
                        input,
                        candidate,
                        languageTag
                );

        int keyboardEvidence =
                countKeyboardEvidence(
                        input,
                        candidate,
                        languageTag
                );

        /*
         * Short input needs conservative correction.
         */
        if (input.length() <= 2 &&
                editCost > 2) {

            return ScoreBreakdown.invalid();
        }

        if (input.length() <=
                SHORT_INPUT_LENGTH &&
                editCost > 6) {

            return ScoreBreakdown.invalid();
        }

        return new ScoreBreakdown(
                score,
                editCost,
                prefixLength,
                suffixLength,
                keyboardEvidence,
                languageEvidence,
                prefixLength >=
                        STRONG_PREFIX_LENGTH,
                false,
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
                    INSERTION_COST;
        }

        if (m == 0) {
            return n *
                    DELETION_COST;
        }

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

                /*
                 * 1. Substitution.
                 */
                int substitution =
                        dp[i - 1][j - 1] +
                                getSubstitutionCost(
                                        typed,
                                        target,
                                        languageTag
                                );

                /*
                 * 2. Insertion into the candidate.
                 */
                int insertion =
                        dp[i][j - 1] +
                                INSERTION_COST;

                /*
                 * 3. Deletion from the typed input.
                 */
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
                 * 4. Repeated character.
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
                 * 5. Adjacent transposition.
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
         * Language-specific spelling relation first.
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
         * Completely unrelated substitutions remain expensive.
         */
        return NORMAL_SUBSTITUTION_COST;
    }

    private int getDeletionCost(
            String input,
            int position) {

        /*
         * If the character being removed is one of a duplicated
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
     * LANGUAGE EVIDENCE
     * ============================================================
     *
     * This is measured separately from edit distance.
     *
     * A candidate can therefore be:
     *
     *   distance = 1
     *   languageEvidence = 1
     *
     * without changing the distance into zero.
     */

    private int countLanguageEvidence(
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

            char target =
                    second.charAt(i);

            if (typed == target) {
                continue;
            }

            int cost =
                    LanguageRules
                            .getCharacterSubstitutionCost(
                                    typed,
                                    target,
                                    languageTag
                            );

            if (cost <
                    KeyboardErrorModel.getUnknownCost()) {

                evidence++;
            }
        }

        return evidence;
    }

    /*
     * ============================================================
     * KEYBOARD EVIDENCE
     * ============================================================
     *
     * Count actual keyboard substitutions inside the word.
     *
     * This is intentionally NOT a global bonus.
     */
    private int countKeyboardEvidence(
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

            char target =
                    second.charAt(i);

            if (typed == target) {
                continue;
            }

            /*
             * Language-specific relationships should not also count
             * as keyboard evidence.
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

                continue;
            }

            int keyboardCost =
                    KeyboardErrorModel
                            .getSubstitutionCost(
                                    typed,
                                    target,
                                    languageTag
                            );

            if (keyboardCost <
                    KeyboardErrorModel.getUnknownCost()) {

                evidence++;
            }
        }

        return evidence;
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
     * SCORE OBJECT
     * ============================================================
     */

    private static final class ScoreBreakdown {

        private final int totalScore;

        /*
         * Actual transformation effort.
         */
        private final int editDistance;

        /*
         * Number of preserved characters at the beginning/end.
         */
        private final int prefixLength;
        private final int suffixLength;

        /*
         * Actual keyboard/language evidence.
         */
        private final int keyboardEvidence;
        private final int languageEvidence;

        /*
         * Whether the candidate preserves a strong prefix.
         */
        private final boolean strongPrefix;

        /*
         * Correction or completion.
         */
        private final boolean completion;

        private final boolean valid;

        private ScoreBreakdown(
                int totalScore,
                int editDistance,
                int prefixLength,
                int suffixLength,
                int keyboardEvidence,
                int languageEvidence,
                boolean strongPrefix,
                boolean completion,
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

            this.strongPrefix =
                    strongPrefix;

            this.completion =
                    completion;

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
                    false,
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
     * The ordering is intentionally hierarchical.
     *
     * 1. Actual plausibility/effort.
     * 2. Actual edit distance.
     * 3. Language-specific evidence.
     * 4. Keyboard evidence.
     * 5. Preserved prefix.
     * 6. Preserved suffix.
     * 7. Completion quality.
     * 8. Deterministic lexical ordering.
     *
     * There is NO:
     *
     * - global shortest-word preference
     * - global longest-word preference
     * - global completion preference
     * - global correction preference
     */

    private static final class CandidateComparator
            implements Comparator<ScoredCandidate> {

        @Override
        public int compare(
                ScoredCandidate first,
                ScoredCandidate second) {

            /*
             * ----------------------------------------------------
             * 1. Primary score
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
             * 2. Actual transformation effort
             * ----------------------------------------------------
             *
             * This is especially important for preventing
             * secondary signals from overpowering accuracy.
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
             * 3. Language-specific evidence
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
             *
             * Especially important for completions.
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
             * 7. Strong-prefix evidence
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
             * 8. Completion vs correction
             * ----------------------------------------------------
             *
             * No universal preference.
             *
             * If everything else is equal, preserve the original
             * candidate type deterministically.
             */
            if (first.breakdown.completion !=
                    second.breakdown.completion) {

                /*
                 * A prefix completion is slightly preferable only
                 * when every real quality signal is exactly equal.
                 *
                 * This is NOT a global completion bonus.
                 */
                return first.breakdown.completion
                        ? -1
                        : 1;
            }

            /*
             * ----------------------------------------------------
             * 9. Deterministic final ordering.
             *
             * No length preference.
             * ----------------------------------------------------
             */
            return first.word.compareTo(
                    second.word
            );
        }
    }
}
