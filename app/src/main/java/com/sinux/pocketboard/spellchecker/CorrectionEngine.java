package com.sinux.pocketboard.spellchecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CorrectionEngine {

    private static final int DEFAULT_MAX_RESULTS = 3;

    /*
     * A correction farther than this is considered too weak.
     */
    private static final int MAX_CANDIDATE_DISTANCE = 8;

    private static final int INSERTION_PENALTY = 3;
    private static final int DELETION_PENALTY = 3;
    private static final int NORMAL_SUBSTITUTION_PENALTY = 4;

    private static final int TRANSPOSITION_PENALTY = 1;
    private static final int REPETITION_PENALTY = 1;

    private static final int LENGTH_PENALTY = 1;

    private static final int COMMON_PREFIX_BONUS = 1;

    public CorrectionEngine() {
    }

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

            if (normalizedCandidate.isEmpty() ||
                    !seen.add(
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
                    MAX_CANDIDATE_DISTANCE) {

                scored.add(
                        new ScoredCandidate(
                                candidate,
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

        int score =
                distance;

        int lengthDifference =
                Math.abs(
                        first.length() -
                                second.length()
                );

        score +=
                lengthDifference *
                        LENGTH_PENALTY;

        score -=
                commonPrefixBonus(
                        first,
                        second
                );

        /*
         * Prefix is deliberately NOT rewarded here.
         *
         * Completion and correction are different pipelines.
         */

        return score;
    }

    private int weightedDamerauLevenshtein(
            String first,
            String second,
            String languageTag) {

        if (first.equals(second)) {
            return 0;
        }

        int n = first.length();
        int m = second.length();

        if (n == 0) {
            return m *
                    INSERTION_PENALTY;
        }

        if (m == 0) {
            return n *
                    DELETION_PENALTY;
        }

        int[][] dp =
                new int[n + 1][m + 1];

        for (int i = 0; i <= n; i++) {
            dp[i][0] =
                    i * DELETION_PENALTY;
        }

        for (int j = 0; j <= m; j++) {
            dp[0][j] =
                    j * INSERTION_PENALTY;
        }

        for (int i = 1; i <= n; i++) {

            char typed =
                    first.charAt(i - 1);

            for (int j = 1; j <= m; j++) {

                char candidate =
                        second.charAt(j - 1);

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
                 * Accidental duplicated character.
                 *
                 * helllo -> hello
                 */
                if (i >= 2 &&
                        j >= 1 &&
                        first.charAt(i - 2)
                                == typed &&
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
                        first.charAt(i - 2)
                                == candidate &&
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

    private int getDeletionCost(
            char character,
            String first,
            int position,
            String languageTag) {

        /*
         * Repeated characters are cheap to delete.
         */
        if (position > 0 &&
                first.charAt(position - 1)
                        == character) {

            return REPETITION_PENALTY;
        }

        return DELETION_PENALTY;
    }

    private int getTranspositionCost(
            char first,
            char second,
            String languageTag) {

        return KeyboardErrorModel
                .getTranspositionCost(
                        first,
                        second,
                        languageTag
                );
    }

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
                first.charAt(common)
                        == second.charAt(common)
        ) {
            common++;
        }

        if (common >= 4) {
            return COMMON_PREFIX_BONUS;
        }

        return 0;
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

            this.word = word;
            this.score = score;
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

            return first.word.compareTo(
                    second.word
            );
        }
    }
}
