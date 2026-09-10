package com.sinux.pocketboard.spellchecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CorrectionEngine {

    private static final int DEFAULT_MAX_RESULTS = 3;

    private static final int MAX_CANDIDATE_DISTANCE = 6;

    private static final int PREFIX_BONUS = 3;

    private static final int EXACT_BONUS = 1000;

    private static final int LENGTH_PENALTY = 2;

    private static final int REPETITION_PENALTY = 1;

    private static final int DIACRITIC_PENALTY = 1;

    private static final int TRANSPOSITION_PENALTY = 1;

    private static final int KEYBOARD_NEIGHBOR_PENALTY = 1;

    private static final int KEYBOARD_DIAGONAL_PENALTY = 2;

    private static final int NORMAL_SUBSTITUTION_PENALTY = 4;

    private static final int INSERTION_PENALTY = 3;

    private static final int DELETION_PENALTY = 3;

    private static final int UNKNOWN_PENALTY = 5;

    private final KeyboardErrorModel keyboardErrorModel;

    public CorrectionEngine() {

        keyboardErrorModel =
                createKeyboardErrorModel();
    }

    private KeyboardErrorModel createKeyboardErrorModel() {

        /*
         * KeyboardErrorModel actualmente expone una API estática.
         *
         * Mantenemos esta instancia para dejar preparado el motor
         * para futuras variantes de layout.
         */
        return null;
    }

    /*
     * ------------------------------------------------------------
     * CORRECCIÓN PRINCIPAL
     * ------------------------------------------------------------
     */

    public List<String> rankCandidates(
            String input,
            List<String> candidates,
            String languageTag,
            int maxResults) {

        if (input == null ||
                input.isEmpty() ||
                candidates == null ||
                candidates.isEmpty() ||
                maxResults <= 0) {

            return new ArrayList<>();
        }

        String normalizedInput =
                normalize(
                        input
                );

        String language =
                LanguageRules.normalizeLanguage(
                        languageTag
                );

        int resultLimit =
                Math.max(
                        1,
                        maxResults
                );

        List<ScoredCandidate> scoredCandidates =
                new ArrayList<>();

        Set<String> seen =
                new HashSet<>();

        for (String candidate : candidates) {

            if (candidate == null ||
                    candidate.isEmpty()) {

                continue;
            }

            String normalizedCandidate =
                    normalize(
                            candidate
                    );

            if (normalizedCandidate.isEmpty()) {
                continue;
            }

            if (!seen.add(
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

            scoredCandidates.add(
                    new ScoredCandidate(
                            candidate,
                            score
                    )
            );
        }

        Collections.sort(
                scoredCandidates,
                new CandidateComparator()
        );

        List<String> results =
                new ArrayList<>(
                        Math.min(
                                resultLimit,
                                scoredCandidates.size()
                        )
                );

        for (ScoredCandidate candidate :
                scoredCandidates) {

            if (results.size()
                    >= resultLimit) {

                break;
            }

            results.add(
                    candidate.word
            );
        }

        return results;
    }


    /*
     * ------------------------------------------------------------
     * SCORING
     * ------------------------------------------------------------
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
                normalize(
                        input
                );

        String second =
                normalize(
                        candidate
                );

        if (first.equals(
                second
        )) {

            return -EXACT_BONUS;
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

        /*
         * --------------------------------------------------------
         * Prefix bonus
         * --------------------------------------------------------
         *
         * Si el candidato conserva el comienzo escrito por el
         * usuario, es una señal bastante fuerte de que puede ser
         * la palabra deseada.
         */

        if (second.startsWith(
                first
        )) {

            score -= PREFIX_BONUS;
        }

        /*
         * --------------------------------------------------------
         * Diferencia de longitud
         * --------------------------------------------------------
         */

        score +=
                Math.abs(
                        first.length()
                                - second.length()
                ) * LENGTH_PENALTY;

        /*
         * --------------------------------------------------------
         * Repeticiones
         * --------------------------------------------------------
         */

        if (hasAccidentalRepetition(
                first,
                second
        )) {

            score -= REPETITION_PENALTY;
        }

        /*
         * --------------------------------------------------------
         * Diacríticos
         * --------------------------------------------------------
         */

        if (LanguageRules.equivalentIgnoringDiacritics(
                first,
                second,
                language
        )) {

            score -= DIACRITIC_PENALTY;
        }

        /*
         * Nunca permitir un score menor que el exacto.
         */
        return score;
    }


    /*
     * ------------------------------------------------------------
     * DAMERAU-LEVENSHTEIN PONDERADO
     * ------------------------------------------------------------
     *
     * A diferencia del Levenshtein tradicional, esta versión
     * contempla transposiciones.
     *
     * Además cada operación tiene un costo diferente.
     */

    private int weightedDamerauLevenshtein(
            String first,
            String second,
            String languageTag) {

        if (first.equals(
                second
        )) {

            return 0;
        }

        if (first.isEmpty()) {

            return second.length()
                    * INSERTION_PENALTY;
        }

        if (second.isEmpty()) {

            return first.length()
                    * DELETION_PENALTY;
        }

        /*
         * Limitamos la cantidad de memoria a dos filas principales
         * más una fila anterior para detectar transposiciones.
         */

        int firstLength =
                first.length();

        int secondLength =
                second.length();

        int[] previousPrevious =
                new int[secondLength + 1];

        int[] previous =
                new int[secondLength + 1];

        int[] current =
                new int[secondLength + 1];

        for (int j = 0;
             j <= secondLength;
             j++) {

            previous[j] =
                    j * INSERTION_PENALTY;

            previousPrevious[j] =
                    previous[j];
        }

        for (int i = 1;
             i <= firstLength;
             i++) {

            current[0] =
                    i * DELETION_PENALTY;

            char firstChar =
                    first.charAt(
                            i - 1
                    );

            for (int j = 1;
                 j <= secondLength;
                 j++) {

                char secondChar =
                        second.charAt(
                                j - 1
                        );

                /*
                 * Sustitución.
                 */

                int substitutionCost;

                if (firstChar ==
                        secondChar) {

                    substitutionCost = 0;

                } else {

                    substitutionCost =
                            getSubstitutionCost(
                                    firstChar,
                                    secondChar,
                                    languageTag
                            );
                }

                int substitution =
                        previous[j - 1]
                                + substitutionCost;

                /*
                 * Inserción.
                 */

                int insertion =
                        current[j - 1]
                                + INSERTION_PENALTY;

                /*
                 * Eliminación.
                 */

                int deletion =
                        previous[j]
                                + DELETION_PENALTY;

                int best =
                        Math.min(
                                substitution,
                                Math.min(
                                        insertion,
                                        deletion
                                )
                        );

                /*
                 * ------------------------------------------------
                 * Transposición
                 * ------------------------------------------------
                 *
                 * Ejemplo:
                 *
                 *     qeu -> que
                 *
                 *     t h e
                 *     t e h
                 */

                if (i > 1 &&
                        j > 1) {

                    char previousFirst =
                            first.charAt(
                                    i - 2
                            );

                    char previousSecond =
                            second.charAt(
                                    j - 2
                            );

                    if (firstChar ==
                            previousSecond &&
                            previousFirst ==
                                    secondChar) {

                        int transposition =
                                previousPrevious[j - 2]
                                        + getTranspositionCost(
                                        previousFirst,
                                        firstChar,
                                        languageTag
                                );

                        best =
                                Math.min(
                                        best,
                                        transposition
                                );
                    }
                }

                current[j] =
                        best;
            }

            int[] temp =
                    previousPrevious;

            previousPrevious =
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


    /*
     * ------------------------------------------------------------
     * COSTO DE SUSTITUCIÓN
     * ------------------------------------------------------------
     */

    private int getSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        /*
         * Diferencia lingüística.
         */

        int languageCost =
                LanguageRules.getCharacterSubstitutionCost(
                        typed,
                        candidate,
                        languageTag
                );

        if (languageCost <=
                DIACRITIC_PENALTY) {

            return languageCost;
        }

        /*
         * Error producido por teclado.
         */

        int keyboardCost =
                KeyboardErrorModel.getSubstitutionCost(
                        typed,
                        candidate,
                        languageTag
                );

        if (keyboardCost <=
                KEYBOARD_NEIGHBOR_PENALTY) {

            return KEYBOARD_NEIGHBOR_PENALTY;
        }

        if (keyboardCost <=
                KEYBOARD_DIAGONAL_PENALTY) {

            return KEYBOARD_DIAGONAL_PENALTY;
        }

        /*
         * Sustitución normal.
         */

        return NORMAL_SUBSTITUTION_PENALTY;
    }


    /*
     * ------------------------------------------------------------
     * TRANSPOSE
     * ------------------------------------------------------------
     */

    private int getTranspositionCost(
            char first,
            char second,
            String languageTag) {

        int keyboardCost =
                KeyboardErrorModel.getTranspositionCost(
                        first,
                        second,
                        languageTag
                );

        if (keyboardCost <=
                KEYBOARD_NEIGHBOR_PENALTY) {

            return TRANSPOSITION_PENALTY;
        }

        return TRANSPOSITION_PENALTY + 1;
    }


    /*
     * ------------------------------------------------------------
     * REPETICIONES
     * ------------------------------------------------------------
     *
     * Detecta una letra adicional en el texto escrito.
     *
     * Ejemplos:
     *
     *     helllo -> hello
     *     mañanaaa -> mañana
     */

    private boolean hasAccidentalRepetition(
            String first,
            String second) {

        if (first.length() <=
                second.length()) {

            return false;
        }

        int firstIndex = 0;
        int secondIndex = 0;

        boolean repetitionDetected =
                false;

        while (firstIndex <
                first.length() &&
                secondIndex <
                        second.length()) {

            char firstChar =
                    first.charAt(
                            firstIndex
                    );

            char secondChar =
                    second.charAt(
                            secondIndex
                    );

            if (firstChar ==
                    secondChar) {

                firstIndex++;
                secondIndex++;

                continue;
            }

            /*
             * Si el carácter actual se repite inmediatamente,
             * tratamos la segunda aparición como posible error.
             */

            if (firstIndex + 1 <
                    first.length() &&
                    first.charAt(
                            firstIndex + 1
                    ) == firstChar) {

                firstIndex++;

                repetitionDetected =
                        true;

                continue;
            }

            return false;
        }

        if (secondIndex ==
                second.length()) {

            while (firstIndex <
                    first.length()) {

                if (firstIndex + 1 >=
                        first.length() ||
                        first.charAt(
                                firstIndex
                        ) != first.charAt(
                                firstIndex + 1
                        )) {

                    return false;
                }

                repetitionDetected =
                        true;

                firstIndex += 2;
            }
        }

        return repetitionDetected;
    }


    /*
     * ------------------------------------------------------------
     * NORMALIZACIÓN
     * ------------------------------------------------------------
     */

    private String normalize(
            String text) {

        if (text == null) {
            return "";
        }

        return text
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }


    /*
     * ------------------------------------------------------------
     * RESULTADO
     * ------------------------------------------------------------
     */

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

            int scoreComparison =
                    Integer.compare(
                            first.score,
                            second.score
                    );

            if (scoreComparison != 0) {
                return scoreComparison;
            }

            return first.word.compareTo(
                    second.word
            );
        }
    }
}
