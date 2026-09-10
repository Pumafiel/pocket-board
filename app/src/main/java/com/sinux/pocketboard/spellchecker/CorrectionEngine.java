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
     * Este límite no significa que toda palabra con una distancia
     * superior sea imposible. Es solamente un límite de seguridad
     * para evitar sugerencias absurdamente alejadas.
     */
    private static final int MAX_CANDIDATE_DISTANCE = 8;

    /*
     * El score más bajo es el mejor.
     */
    private static final int EXACT_BONUS = 1000;

    /*
     * Costos base.
     *
     * Son pesos relativos, no probabilidades absolutas.
     */
    private static final int INSERTION_PENALTY = 3;
    private static final int DELETION_PENALTY = 3;

    private static final int NORMAL_SUBSTITUTION_PENALTY = 4;

    private static final int KEYBOARD_NEIGHBOR_PENALTY = 1;
    private static final int KEYBOARD_DIAGONAL_PENALTY = 2;
    private static final int KEYBOARD_FAR_PENALTY = 4;

    private static final int TRANSPOSITION_PENALTY = 1;

    /*
     * Error muy frecuente:
     *
     *     helllo -> hello
     *
     * Una repetición accidental debe costar menos que una
     * eliminación arbitraria.
     */
    private static final int REPETITION_PENALTY = 1;

    /*
     * Diferencia de longitud utilizada solamente como señal
     * secundaria. La distancia principal ya contempla inserciones
     * y eliminaciones.
     */
    private static final int LENGTH_PENALTY = 1;

    /*
     * Prefijo:
     *
     *     ca -> casa
     *
     * es una suggestion válida y frecuente.
     *
     * No debe, sin embargo, dominar una corrección claramente mejor.
     */
    private static final int PREFIX_BONUS = 2;

    /*
     * Una transformación lingüística válida no debe convertir dos
     * palabras distintas en equivalentes.
     *
     * El costo bajo se aplica solamente cuando la diferencia concreta
     * de caracteres es realmente una diferencia lingüística.
     */
    private static final int LANGUAGE_DIACRITIC_COST = 1;
    private static final int LANGUAGE_N_TILDE_COST = 1;
    private static final int LANGUAGE_GERMAN_COST = 1;

    /*
     * ------------------------------------------------------------
     * CONSTRUCTOR
     * ------------------------------------------------------------
     */

    public CorrectionEngine() {
        /*
         * KeyboardErrorModel utiliza actualmente una API estática.
         *
         * No necesitamos crear ni almacenar una instancia.
         */
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
                Math.max(
                        1,
                        Math.min(
                                maxResults,
                                DEFAULT_MAX_RESULTS
                        )
                );

        List<ScoredCandidate> scoredCandidates =
                new ArrayList<>();

        /*
         * Evitamos duplicados normalizados.
         */
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

            int score =
                    scoreCandidate(
                            normalizedInput,
                            normalizedCandidate,
                            language
                    );

            /*
             * No agregamos candidatos que estén claramente
             * fuera del rango útil.
             */
            if (score >
                    MAX_CANDIDATE_DISTANCE) {

                continue;
            }

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

            if (results.size() >= resultLimit) {
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
     * SCORE DE UNA PALABRA
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
                normalize(input);

        String second =
                normalize(candidate);

        if (first.equals(second)) {
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
         * PREFIJO
         * --------------------------------------------------------
         *
         * Solamente damos una pequeña ventaja.
         *
         * Nunca debe superar una corrección lingüística claramente
         * mejor.
         */
        if (second.startsWith(first) &&
                first.length() < second.length()) {

            score -= PREFIX_BONUS;
        }

        /*
         * --------------------------------------------------------
         * LONGITUD
         * --------------------------------------------------------
         *
         * Es una señal secundaria.
         */
        int lengthDifference =
                Math.abs(
                        first.length()
                                - second.length()
                );

        if (lengthDifference > 0) {

            score +=
                    lengthDifference
                            * LENGTH_PENALTY;
        }

        /*
         * --------------------------------------------------------
         * ESTRUCTURA
         * --------------------------------------------------------
         *
         * Cuando la mayoría de la palabra se conserva, damos una
         * pequeña ventaja. Esto ayuda a diferenciar palabras que
         * tienen la misma cantidad de errores pero estructuras
         * distintas.
         */
        score -=
                getCommonPrefixBonus(
                        first,
                        second
                );

        /*
         * Nunca permitimos que una corrección termine siendo mejor
         * que una coincidencia exacta.
         */
        return Math.max(
                -EXACT_BONUS + 1,
                score
        );
    }

    /*
     * ------------------------------------------------------------
     * DAMERAU-LEVENSHTEIN PONDERADO
     * ------------------------------------------------------------
     *
     * Incluye:
     *
     * - sustituciones;
     * - teclas vecinas;
     * - diagonales;
     * - inserciones;
     * - eliminaciones;
     * - repeticiones;
     * - transposiciones;
     * - reglas lingüísticas.
     *
     * Todas las operaciones forman parte de la misma matriz de
     * distancia.
     */

    private int weightedDamerauLevenshtein(
            String first,
            String second,
            String languageTag) {

        if (first.equals(second)) {
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

        int firstLength =
                first.length();

        int secondLength =
                second.length();

        /*
         * Tres filas:
         *
         * previousPrevious
         * previous
         * current
         *
         * son suficientes para las transposiciones adyacentes.
         */
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

            char typedChar =
                    first.charAt(
                            i - 1
                    );

            for (int j = 1;
                 j <= secondLength;
                 j++) {

                char candidateChar =
                        second.charAt(
                                j - 1
                        );

                /*
                 * ------------------------------------------------
                 * SUSTITUCIÓN
                 * ------------------------------------------------
                 */

                int substitutionCost;

                if (typedChar ==
                        candidateChar) {

                    substitutionCost = 0;

                } else {

                    substitutionCost =
                            getSubstitutionCost(
                                    typedChar,
                                    candidateChar,
                                    languageTag
                            );
                }

                int substitution =
                        previous[j - 1]
                                + substitutionCost;

                /*
                 * ------------------------------------------------
                 * INSERCIÓN
                 * ------------------------------------------------
                 *
                 * El candidato tiene un carácter que el usuario
                 * no escribió.
                 */
                int insertion =
                        current[j - 1]
                                + getInsertionCost(
                                candidateChar,
                                languageTag
                        );

                /*
                 * ------------------------------------------------
                 * ELIMINACIÓN
                 * ------------------------------------------------
                 *
                 * El usuario escribió un carácter que no aparece
                 * en el candidato.
                 */
                int deletionCost =
                        getDeletionCost(
                                typedChar,
                                first,
                                i - 1,
                                languageTag
                        );

                int deletion =
                        previous[j]
                                + deletionCost;

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
                 * TRANSPOSICIÓN
                 * ------------------------------------------------
                 *
                 *     teh -> the
                 *     qeu -> que
                 */
                if (i > 1 &&
                        j > 1) {

                    char previousTyped =
                            first.charAt(
                                    i - 2
                            );

                    char previousCandidate =
                            second.charAt(
                                    j - 2
                            );

                    if (typedChar ==
                            previousCandidate &&
                            previousTyped ==
                                    candidateChar) {

                        int transposition =
                                previousPrevious[j - 2]
                                        + getTranspositionCost(
                                        previousTyped,
                                        typedChar,
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

            int[] temporary =
                    previousPrevious;

            previousPrevious =
                    previous;

            previous =
                    current;

            current =
                    temporary;
        }

        return previous[
                secondLength
        ];
    }

    /*
     * ------------------------------------------------------------
     * SUSTITUCIÓN
     * ------------------------------------------------------------
     *
     * Orden de prioridad:
     *
     * 1. carácter exacto;
     * 2. regla lingüística;
     * 3. vecino de teclado;
     * 4. diagonal;
     * 5. tecla alejada;
     * 6. sustitución normal.
     */

    private int getSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return 0;
        }

        /*
         * Primero idioma.
         *
         * Esto es importante para:
         *
         *     manana -> mañana
         *
         * La ñ no es una vocal acentuada.
         */
        int languageCost =
                LanguageRules.getCharacterSubstitutionCost(
                        typed,
                        candidate,
                        languageTag
                );

        if (LanguageRules.isSpanishLanguage(
                languageTag
        ) &&
                LanguageRules.isSpanishNPair(
                        typed,
                        candidate
                )) {

            return LANGUAGE_N_TILDE_COST;
        }

        if (LanguageRules.isGermanLanguage(
                languageTag
        ) &&
                (
                        LanguageRules.isGermanUmlautPair(
                                typed,
                                candidate
                        ) ||
                        LanguageRules.isGermanSharpSPair(
                                typed,
                                candidate
                        )
                )) {

            return LANGUAGE_GERMAN_COST;
        }

        /*
         * Diacríticos reales.
         *
         * Como -> cómo NO es equivalencia exacta.
         *
         * Simplemente representa una posible sustitución de
         * carácter de costo bajo.
         */
        if (LanguageRules.isLikelyAccentError(
                typed,
                candidate,
                languageTag
        )) {

            return LANGUAGE_DIACRITIC_COST;
        }

        /*
         * Si LanguageRules conoce otra transformación de bajo costo,
         * la conservamos.
         */
        if (languageCost <=
                LANGUAGE_DIACRITIC_COST) {

            return languageCost;
        }

        /*
         * --------------------------------------------------------
         * TECLADO
         * --------------------------------------------------------
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

        if (keyboardCost <=
                KEYBOARD_FAR_PENALTY) {

            return KEYBOARD_FAR_PENALTY;
        }

        return NORMAL_SUBSTITUTION_PENALTY;
    }

    /*
     * ------------------------------------------------------------
     * INSERCIÓN
     * ------------------------------------------------------------
     */

    private int getInsertionCost(
            char character,
            String languageTag) {

        /*
         * La inserción de una vocal acentuada frente a su variante
         * sin acento puede ser una transformación razonable, pero
         * no podemos decidirla aisladamente sin contexto.
         *
         * Por eso mantenemos el costo base.
         */
        return INSERTION_PENALTY;
    }

    /*
     * ------------------------------------------------------------
     * ELIMINACIÓN
     * ------------------------------------------------------------
     */

    private int getDeletionCost(
            char character,
            String input,
            int index,
            String languageTag) {

        /*
         * Repetición accidental:
         *
         *     helllo -> hello
         *     maaana -> maana
         *
         * Si el carácter eliminado está pegado a otro igual,
         * consideramos que probablemente fue una pulsación doble.
         */
        if (index > 0 &&
                input.charAt(index - 1)
                        == character) {

            return REPETITION_PENALTY;
        }

        if (index + 1 <
                input.length() &&
                input.charAt(index + 1)
                        == character) {

            return REPETITION_PENALTY;
        }

        return DELETION_PENALTY;
    }

    /*
     * ------------------------------------------------------------
     * TRANSPOSICIÓN
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

        /*
         * Una transposición de teclas cercanas es especialmente
         * probable al escribir rápido.
         */
        if (keyboardCost <=
                KEYBOARD_DIAGONAL_PENALTY) {

            return TRANSPOSITION_PENALTY;
        }

        return TRANSPOSITION_PENALTY + 1;
    }

    /*
     * ------------------------------------------------------------
     * BONUS DE PREFIJO / ESTRUCTURA
     * ------------------------------------------------------------
     */

    private int getCommonPrefixBonus(
            String first,
            String second) {

        if (first.isEmpty() ||
                second.isEmpty()) {

            return 0;
        }

        int commonLength =
                0;

        int maximum =
                Math.min(
                        first.length(),
                        second.length()
                );

        while (commonLength <
                maximum &&
                first.charAt(commonLength)
                        == second.charAt(commonLength)) {

            commonLength++;
        }

        /*
         * No queremos que una palabra simplemente larga gane
         * demasiados puntos por compartir prefijo.
         *
         * Máximo bonus = 2.
         */
        if (commonLength >= 4) {
            return 2;
        }

        if (commonLength >= 2) {
            return 1;
        }

        return 0;
    }

    /*
     * ------------------------------------------------------------
     * NORMALIZACIÓN
     * ------------------------------------------------------------
     *
     * IMPORTANTE:
     *
     * Aquí NO quitamos diacríticos.
     *
     *     como != cómo
     *     manana != mañana
     *
     * La normalización solamente homogeniza mayúsculas/minúsculas
     * y espacios exteriores.
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
