package com.sinux.pocketboard.spellchecker;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LanguageRules {

    private static final int COST_EXACT = 0;
    private static final int COST_DIACRITIC = 1;
    private static final int COST_LANGUAGE = 1;
    private static final int COST_UNKNOWN = 5;

    private LanguageRules() {
    }

    /*
     * ============================================================
     * LANGUAGE NORMALIZATION
     * ============================================================
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

        /*
         * Keep the existing PocketBoard fallback.
         */
        return "en";
    }

    /*
     * ============================================================
     * CHARACTER SUBSTITUTION
     * ============================================================
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
         * Spanish-specific relationships.
         *
         * n <-> ñ is NOT a generic Unicode diacritic relation.
         * It is explicitly handled as a Spanish language rule.
         */
        if ("es-AR".equals(language)) {

            if (isSpanishNPair(
                    first,
                    second
            )) {

                return COST_LANGUAGE;
            }

            if (areDiacriticVariants(
                    first,
                    second
            )) {

                return COST_DIACRITIC;
            }
        }

        /*
         * German-specific relationships.
         */
        if ("de".equals(language)) {

            if (isGermanUmlautPair(
                    first,
                    second
            )) {

                return COST_LANGUAGE;
            }

            if (isGermanSharpSPair(
                    first,
                    second
            )) {

                return COST_LANGUAGE;
            }

            if (areDiacriticVariants(
                    first,
                    second
            )) {

                return COST_DIACRITIC;
            }
        }

        /*
         * English can still tolerate an accented character as a
         * low-cost variation, but it does NOT get Spanish ñ rules.
         */
        if ("en".equals(language) &&
                areDiacriticVariants(
                        first,
                        second
                )) {

            return 2;
        }

        return COST_UNKNOWN;
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

        return 2;
    }

    /*
     * ============================================================
     * DIACRITIC RELATION
     * ============================================================
     */

    public static boolean areDiacriticVariants(
            char first,
            char second) {

        if (first == second) {
            return true;
        }

        char a =
                Character.toLowerCase(
                        first
                );

        char b =
                Character.toLowerCase(
                        second
                );

        /*
         * ñ is a distinct Spanish letter, not merely an accented n
         * for the purposes of generic Unicode comparison.
         *
         * Spanish n <-> ñ is handled explicitly by isSpanishNPair().
         */
        if (isSpanishNPair(
                a,
                b
        )) {

            return false;
        }

        String firstBase =
                removeDiacritics(
                        String.valueOf(a)
                );

        String secondBase =
                removeDiacritics(
                        String.valueOf(b)
                );

        return firstBase.equalsIgnoreCase(
                secondBase
        );
    }

    /*
     * ============================================================
     * SPANISH
     * ============================================================
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

        return
                (a == 'n' && b == 'ñ') ||
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
     * ============================================================
     * GERMAN
     * ============================================================
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
     * ============================================================
     * ERROR CLASSIFICATION
     * ============================================================
     */

    public static boolean isLikelyAccentError(
            char typed,
            char candidate,
            String languageTag) {

        return typed != candidate &&
                getDiacriticCost(
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
     * ============================================================
     * TEXT NORMALIZATION
     * ============================================================
     */

    public static String normalizeForComparison(
            String text,
            String languageTag) {

        if (text == null) {
            return "";
        }

        return text
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
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

            char c =
                    normalized.charAt(i);

            if (Character.getType(c) ==
                    Character.NON_SPACING_MARK) {

                continue;
            }

            result.append(
                    c
            );
        }

        return Normalizer.normalize(
                result.toString(),
                Normalizer.Form.NFC
        );
    }

    /*
     * ============================================================
     * EQUIVALENCE
     * ============================================================
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

        String a =
                removeDiacritics(
                        first.toLowerCase(
                                Locale.ROOT
                        )
                );

        String b =
                removeDiacritics(
                        second.toLowerCase(
                                Locale.ROOT
                        )
                );

        /*
         * Do not allow Unicode normalization to erase the semantic
         * distinction between Spanish n and ñ.
         */
        if (isSpanishLanguage(
                languageTag
        )) {

            boolean firstHasN =
                    first.indexOf('ñ') >= 0 ||
                    first.indexOf('Ñ') >= 0;

            boolean secondHasN =
                    second.indexOf('ñ') >= 0 ||
                    second.indexOf('Ñ') >= 0;

            if (firstHasN != secondHasN) {

                return false;
            }
        }

        if (a.equals(b)) {
            return true;
        }

        /*
         * Spanish comparison may optionally tolerate n/ñ as a
         * language-specific relation, but only when both strings
         * actually represent the corresponding Spanish spelling.
         */
        if (isSpanishLanguage(
                languageTag
        )) {

            return replaceSpanishN(a)
                    .equals(
                            replaceSpanishN(b)
                    );
        }

        return false;
    }

    /*
     * ============================================================
     * LANGUAGE ADJUSTMENT
     * ============================================================
     */

    public static int getLanguageAdjustment(
            char typed,
            char candidate,
            String languageTag) {

        return getCharacterSubstitutionCost(
                typed,
                candidate,
                languageTag
        );
    }

    /*
     * ============================================================
     * DIACRITIC VARIANT GENERATION
     * ============================================================
     *
     * Generates only meaningful one-character variants.
     *
     * The old implementation tried every vowel/letter replacement
     * at every position. For example, an 'a' could generate:
     *
     *     á, é, í, ó, ú
     *
     * even though only a/á is linguistically related.
     *
     * That creates unnecessary candidate work for DictionaryManager.
     */

    public static List<String> getDiacriticVariants(
            String input,
            String languageTag) {

        Set<String> variants =
                new LinkedHashSet<>();

        if (input == null ||
                input.isEmpty()) {

            return new ArrayList<>();
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        for (int i = 0;
             i < input.length();
             i++) {

            char current =
                    Character.toLowerCase(
                            input.charAt(i)
                    );

            addVariantsForCharacter(
                    input,
                    i,
                    current,
                    language,
                    variants
            );
        }

        variants.remove(
                input
        );

        return new ArrayList<>(
                variants
        );
    }

    private static void addVariantsForCharacter(
            String input,
            int index,
            char current,
            String language,
            Set<String> variants) {

        char[] replacements;

        if ("es-AR".equals(
                language
        )) {

            replacements =
                    getSpanishVariants(
                            current
                    );

        } else if ("de".equals(
                language
        )) {

            replacements =
                    getGermanVariants(
                            current
                    );

        } else {

            replacements =
                    getEnglishVariants(
                            current
                    );
        }

        for (char replacement :
                replacements) {

            if (replacement ==
                    current) {

                continue;
            }

            StringBuilder builder =
                    new StringBuilder(
                            input
                    );

            builder.setCharAt(
                    index,
                    replacement
            );

            variants.add(
                    builder.toString()
            );
        }
    }

    private static char[] getSpanishVariants(
            char current) {

        switch (current) {

            case 'a':
            case 'á':
                return new char[] {
                        'a',
                        'á'
                };

            case 'e':
            case 'é':
                return new char[] {
                        'e',
                        'é'
                };

            case 'i':
            case 'í':
                return new char[] {
                        'i',
                        'í'
                };

            case 'o':
            case 'ó':
                return new char[] {
                        'o',
                        'ó'
                };

            case 'u':
            case 'ú':
                return new char[] {
                        'u',
                        'ú'
                };

            /*
             * n <-> ñ is handled as a language-specific pair.
             */
            case 'n':
            case 'ñ':
                return new char[] {
                        'n',
                        'ñ'
                };

            default:
                return new char[0];
        }
    }

    private static char[] getGermanVariants(
            char current) {

        switch (current) {

            case 'a':
            case 'ä':
                return new char[] {
                        'a',
                        'ä'
                };

            case 'o':
            case 'ö':
                return new char[] {
                        'o',
                        'ö'
                };

            case 'u':
            case 'ü':
                return new char[] {
                        'u',
                        'ü'
                };

            /*
             * Keep ß/s available because the rest of the engine
             * already treats this as a German-specific relation.
             */
            case 's':
            case 'ß':
                return new char[] {
                        's',
                        'ß'
                };

            default:
                return new char[0];
        }
    }

    private static char[] getEnglishVariants(
            char current) {

        switch (current) {

            case 'a':
            case 'á':
                return new char[] {
                        'a',
                        'á'
                };

            case 'e':
            case 'é':
                return new char[] {
                        'e',
                        'é'
                };

            case 'i':
            case 'í':
                return new char[] {
                        'i',
                        'í'
                };

            case 'o':
            case 'ó':
                return new char[] {
                        'o',
                        'ó'
                };

            case 'u':
            case 'ú':
                return new char[] {
                        'u',
                        'ú'
                };

            default:
                return new char[0];
        }
    }

    /*
     * ============================================================
     * SPANISH NORMALIZATION HELPER
     * ============================================================
     */

    private static String replaceSpanishN(
            String text) {

        return text.replace(
                'ñ',
                'n'
        );
    }
}
