package com.sinux.pocketboard.spellchecker;

import java.text.Normalizer;
import java.util.Locale;

public final class LanguageRules {

    private LanguageRules() {
    }

    /*
     * ------------------------------------------------------------
     * Costos base
     * ------------------------------------------------------------
     *
     * Los valores no representan probabilidades absolutas.
     * Son pesos relativos utilizados por CorrectionEngine.
     */

    private static final int COST_EXACT = 0;

    private static final int COST_DIACRITIC = 1;

    private static final int COST_SPANISH_N_NTILDE = 1;

    private static final int COST_GERMAN_UMLAUT = 1;

    private static final int COST_GERMAN_SHARP_S = 1;

    private static final int COST_NORMALIZED_DIFFERENCE = 2;

    private static final int COST_LANGUAGE_SPECIFIC = 3;

    private static final int COST_UNKNOWN = 5;


    /*
     * ------------------------------------------------------------
     * Idiomas soportados por PocketBoard
     * ------------------------------------------------------------
     */

    public static String normalizeLanguage(
            String languageTag) {

        if (languageTag == null ||
                languageTag.trim().isEmpty()) {

            return "en";
        }

        String normalized =
                languageTag
                        .trim()
                        .replace(
                                '_',
                                '-'
                        )
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalized.startsWith("es")) {
            return "es-AR";
        }

        if (normalized.startsWith("de")) {
            return "de";
        }

        if (normalized.startsWith("en")) {
            return "en";
        }

        return "en";
    }


    /*
     * ------------------------------------------------------------
     * Comparación lingüística
     * ------------------------------------------------------------
     *
     * Devuelve un costo para sustituir "typed" por "candidate".
     *
     * 0 = iguales
     * 1 = diferencia muy probable / tolerable
     * 2 = diferencia lingüística moderada
     * 3 = diferencia específica
     * 5 = diferencia no relacionada
     */

    public static int getCharacterSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return COST_EXACT;
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        char first =
                Character.toLowerCase(
                        typed
                );

        char second =
                Character.toLowerCase(
                        candidate
                );

        if (first == second) {
            return COST_EXACT;
        }

        /*
         * --------------------------------------------------------
         * Español
         * --------------------------------------------------------
         *
         * n <-> ñ merece un tratamiento especial.
         *
         * "manana" -> "mañana"
         *
         * No se considera una sustitución normal.
         */

        if ("es-AR".equals(language)) {

            if (isSpanishNPair(
                    first,
                    second
            )) {

                return COST_SPANISH_N_NTILDE;
            }

            if (isDiacriticPair(
                    first,
                    second
            )) {

                return COST_DIACRITIC;
            }
        }

        /*
         * --------------------------------------------------------
         * Alemán
         * --------------------------------------------------------
         *
         * ä -> a
         * ö -> o
         * ü -> u
         * ß -> ss
         *
         * Las equivalencias de un carácter contra dos caracteres
         * serán tratadas por CorrectionEngine como inserción/
         * eliminación o transformación especial.
         */

        if ("de".equals(language)) {

            if (isGermanUmlautPair(
                    first,
                    second
            )) {

                return COST_GERMAN_UMLAUT;
            }

            if (isGermanSharpSPair(
                    first,
                    second
            )) {

                return COST_GERMAN_SHARP_S;
            }

            if (isDiacriticPair(
                    first,
                    second
            )) {

                return COST_DIACRITIC;
            }
        }

        /*
         * --------------------------------------------------------
         * Inglés
         * --------------------------------------------------------
         *
         * No damos una ventaja especial a diacríticos en inglés.
         * Se mantiene una tolerancia pequeña para que palabras
         * internacionales no queden completamente descartadas.
         */

        if ("en".equals(language)) {

            if (isDiacriticPair(
                    first,
                    second
            )) {

                return COST_NORMALIZED_DIFFERENCE;
            }
        }

        return COST_UNKNOWN;
    }


    /*
     * ------------------------------------------------------------
     * Diacríticos
     * ------------------------------------------------------------
     *
     * Determina si dos caracteres son iguales salvo por
     * diacríticos.
     */

    public static boolean areDiacriticVariants(
            char first,
            char second) {

        if (first == second) {
            return true;
        }

        String firstString =
                String.valueOf(
                        first
                );

        String secondString =
                String.valueOf(
                        second
                );

        String normalizedFirst =
                removeDiacritics(
                        firstString
                );

        String normalizedSecond =
                removeDiacritics(
                        secondString
                );

        return normalizedFirst.equals(
                normalizedSecond
        );
    }


    public static int getDiacriticCost(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return COST_EXACT;
        }

        if (!areDiacriticVariants(
                typed,
                candidate
        )) {

            return COST_UNKNOWN;
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        if ("es-AR".equals(language) ||
                "de".equals(language)) {

            return COST_DIACRITIC;
        }

        return COST_NORMALIZED_DIFFERENCE;
    }


    /*
     * ------------------------------------------------------------
     * Ñ
     * ------------------------------------------------------------
     */

    public static boolean isSpanishNPair(
            char first,
            char second) {

        char a =
                Character.toLowerCase(
                        first
                );

        char b =
                Character.toLowerCase(
                        second
                );

        return (a == 'n' && b == 'ñ') ||
                (a == 'ñ' && b == 'n');
    }


    public static boolean isSpanishLanguage(
            String languageTag) {

        return "es-AR".equals(
                normalizeLanguage(
                        languageTag
                )
        );
    }


    /*
     * ------------------------------------------------------------
     * Alemán
     * ------------------------------------------------------------
     */

    public static boolean isGermanLanguage(
            String languageTag) {

        return "de".equals(
                normalizeLanguage(
                        languageTag
                )
        );
    }


    public static boolean isGermanUmlautPair(
            char first,
            char second) {

        char a =
                Character.toLowerCase(
                        first
                );

        char b =
                Character.toLowerCase(
                        second
                );

        return
                (a == 'a' && b == 'ä') ||
                (a == 'ä' && b == 'a') ||
                (a == 'o' && b == 'ö') ||
                (a == 'ö' && b == 'o') ||
                (a == 'u' && b == 'ü') ||
                (a == 'ü' && b == 'u');
    }


    public static boolean isGermanSharpSPair(
            char first,
            char second) {

        char a =
                Character.toLowerCase(
                        first
                );

        char b =
                Character.toLowerCase(
                        second
                );

        return
                (a == 'ß' && b == 's') ||
                (a == 's' && b == 'ß');
    }


    /*
     * ------------------------------------------------------------
     * Clasificación de errores
     * ------------------------------------------------------------
     */

    public static boolean isLikelyAccentError(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return false;
        }

        return getDiacriticCost(
                typed,
                candidate,
                languageTag
        ) <= COST_DIACRITIC;
    }


    public static boolean isLikelyLanguageSpecificError(
            char typed,
            char candidate,
            String languageTag) {

        String language =
                normalizeLanguage(
                        languageTag
                );

        if ("es-AR".equals(language)) {

            return isSpanishNPair(
                    typed,
                    candidate
            );
        }

        if ("de".equals(language)) {

            return isGermanUmlautPair(
                        typed,
                        candidate
                ) ||
                isGermanSharpSPair(
                        typed,
                        candidate
                );
        }

        return false;
    }


    /*
     * ------------------------------------------------------------
     * Normalización para comparación
     * ------------------------------------------------------------
     *
     * Se utiliza únicamente para encontrar similitudes.
     *
     * NO debe utilizarse para reemplazar directamente la palabra
     * que escribió el usuario.
     */

    public static String normalizeForComparison(
            String text,
            String languageTag) {

        if (text == null) {
            return "";
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        String normalized =
                text.toLowerCase(
                        Locale.ROOT
                );

        /*
         * Para español mantenemos ñ como carácter distinto.
         *
         * Esto es importante:
         *
         *     n != ñ
         *
         * pero CorrectionEngine puede darles un costo bajo.
         */

        if ("es-AR".equals(language)) {
            return normalized;
        }

        /*
         * Para alemán tampoco destruimos las diferencias
         * lingüísticas. Se conservan para que el ranking pueda
         * decidir si realmente corresponde tratarlas como error.
         */

        if ("de".equals(language)) {
            return normalized;
        }

        return normalized;
    }


    public static String removeDiacritics(
            String text) {

        if (text == null ||
                text.isEmpty()) {

            return "";
        }

        String normalized =
                Normalizer.normalize(
                        text,
                        Normalizer.Form.NFD
                );

        StringBuilder result =
                new StringBuilder(
                        normalized.length()
                );

        for (int i = 0;
             i < normalized.length();
             i++) {

            char character =
                    normalized.charAt(i);

            int type =
                    Character.getType(
                            character
                    );

            if (type ==
                    Character.NON_SPACING_MARK) {

                continue;
            }

            result.append(
                    character
            );
        }

        return Normalizer.normalize(
                result.toString(),
                Normalizer.Form.NFC
        );
    }


    /*
     * ------------------------------------------------------------
     * Comparación de dos palabras
     * ------------------------------------------------------------
     *
     * Ayuda a CorrectionEngine a detectar si dos palabras son
     * esencialmente iguales salvo por acentos o reglas lingüísticas.
     */

    public static boolean equivalentIgnoringDiacritics(
            String first,
            String second,
            String languageTag) {

        if (first == null ||
                second == null) {

            return false;
        }

        if (first.equalsIgnoreCase(
                second
        )) {

            return true;
        }

        String normalizedFirst =
                removeDiacritics(
                        first.toLowerCase(
                                Locale.ROOT
                        )
                );

        String normalizedSecond =
                removeDiacritics(
                        second.toLowerCase(
                                Locale.ROOT
                        )
                );

        if (normalizedFirst.equals(
                normalizedSecond
        )) {

            return true;
        }

        /*
         * Español:
         *
         * "manana" y "mañana"
         *
         * deben poder reconocerse como variantes cercanas.
         */

        if (isSpanishLanguage(
                languageTag
        )) {

            String spanishFirst =
                    replaceSpanishN(
                            normalizedFirst
                    );

            String spanishSecond =
                    replaceSpanishN(
                            normalizedSecond
                    );

            return spanishFirst.equals(
                    spanishSecond
            );
        }

        return false;
    }


    private static String replaceSpanishN(
            String text) {

        if (text == null ||
                text.isEmpty()) {

            return "";
        }

        return text.replace(
                'ñ',
                'n'
        );
    }


    /*
     * ------------------------------------------------------------
     * Costo de transformación de palabra
     * ------------------------------------------------------------
     *
     * Este método no calcula una distancia completa.
     * Sirve para que CorrectionEngine pueda consultar cuánto
     * debería penalizar una diferencia lingüística concreta.
     */

    public static int getLanguageAdjustment(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return COST_EXACT;
        }

        int cost =
                getCharacterSubstitutionCost(
                        typed,
                        candidate,
                        languageTag
                );

        if (cost <= COST_DIACRITIC) {
            return cost;
        }

        if (isLikelyLanguageSpecificError(
                typed,
                candidate,
                languageTag
        )) {

            return COST_LANGUAGE_SPECIFIC;
        }

        return COST_UNKNOWN;
    }
}
