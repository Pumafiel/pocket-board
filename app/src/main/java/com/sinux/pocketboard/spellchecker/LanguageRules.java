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
     * LANGUAGE
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
                        .replace('_', '-')
                        .toLowerCase(Locale.ROOT);

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

    public static boolean isSpanishLanguage(
            String languageTag) {

        return "es-AR".equals(
                normalizeLanguage(languageTag)
        );
    }

    public static boolean isGermanLanguage(
            String languageTag) {

        return "de".equals(
                normalizeLanguage(languageTag)
        );
    }

    /*
     * ============================================================
     * CHARACTER SUBSTITUTION
     * ============================================================
     *
     * This is intentionally character-level.
     *
     * The CorrectionEngine decides whether the complete word is
     * a good candidate. LanguageRules only says how plausible a
     * particular character substitution is.
     */

    public static int getCharacterSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        if (typed == candidate) {
            return COST_EXACT;
        }

        char first =
                Character.toLowerCase(typed);

        char second =
                Character.toLowerCase(candidate);

        if (first == second) {
            return COST_EXACT;
        }

        String language =
                normalizeLanguage(languageTag);

        /*
         * Spanish.
         *
         * n <-> ñ is explicitly Spanish-specific.
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
         * German.
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
         * English may tolerate accented variants, but does not
         * inherit Spanish or German-specific rules.
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
                normalizeLanguage(languageTag);

        if ("es-AR".equals(language) ||
                "de".equals(language)) {

            return COST_DIACRITIC;
        }

        return 2;
    }

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
     * DIACRITICS
     * ============================================================
     */

    public static boolean areDiacriticVariants(
            char first,
            char second) {

        if (first == second) {
            return true;
        }

        char a =
                Character.toLowerCase(first);

        char b =
                Character.toLowerCase(second);

        /*
         * ñ is NOT a generic n diacritic for our engine.
         *
         * Spanish n <-> ñ is handled explicitly above.
         */
        if (isSpanishNPair(a, b)) {
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
                normalizeLanguage(languageTag);

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
     * SPANISH
     * ============================================================
     */

    public static boolean isSpanishNPair(
            char first,
            char second) {

        char a =
                Character.toLowerCase(first);

        char b =
                Character.toLowerCase(second);

        return
                (a == 'n' && b == 'ñ') ||
                (a == 'ñ' && b == 'n');
    }

    /*
     * ============================================================
     * GERMAN
     * ============================================================
     */

    public static boolean isGermanUmlautPair(
            char first,
            char second) {

        char a =
                Character.toLowerCase(first);

        char b =
                Character.toLowerCase(second);

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
                Character.toLowerCase(first);

        char b =
                Character.toLowerCase(second);

        return
                (a == 'ß' && b == 's') ||
                (a == 's' && b == 'ß');
    }

    /*
     * ============================================================
     * TEXT NORMALIZATION
     * ============================================================
     *
     * This method does NOT remove accents.
     *
     * It is used only to obtain a stable comparison form while
     * preserving the actual spelling.
     */

    public static String normalizeForComparison(
            String text,
            String languageTag) {

        if (text == null) {
            return "";
        }

        return text
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /*
     * ============================================================
     * REMOVE DIACRITICS
     * ============================================================
     *
     * Utility only.
     *
     * Do not use this method as the spelling equality test for
     * Spanish, because Unicode normalization turns ñ into n.
     */

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

            result.append(c);
        }

        return Normalizer.normalize(
                result.toString(),
                Normalizer.Form.NFC
        );
    }

    /*
     * ============================================================
     * COMPLETE-WORD EQUIVALENCE
     * ============================================================
     *
     * IMPORTANT:
     *
     * This method is intentionally conservative.
     *
     * It does NOT transform n <-> ñ.
     *
     * It is only an "ignoring accent marks" comparison.
     *
     * The CorrectionEngine should normally prefer its
     * character-level scoring instead of relying on this method.
     */

    public static boolean equivalentIgnoringDiacritics(
            String first,
            String second,
            String languageTag) {

        if (first == null ||
                second == null) {

            return false;
        }

        String a =
                first
                        .trim()
                        .toLowerCase(Locale.ROOT);

        String b =
                second
                        .trim()
                        .toLowerCase(Locale.ROOT);

        if (a.equals(b)) {
            return true;
        }

        /*
         * Spanish n/ñ must remain distinct.
         */
        if (isSpanishLanguage(
                languageTag
        ) &&
                containsSpanishNDifference(
                        a,
                        b
                )) {

            return false;
        }

        return removeDiacritics(a)
                .equals(
                        removeDiacritics(b)
                );
    }

    private static boolean containsSpanishNDifference(
            String first,
            String second) {

        if (first.length() !=
                second.length()) {

            return false;
        }

        for (int i = 0;
             i < first.length();
             i++) {

            char a =
                    first.charAt(i);

            char b =
                    second.charAt(i);

            if (a == b) {
                continue;
            }

            if (isSpanishNPair(a, b)) {
                return true;
            }
        }

        return false;
    }

    /*
     * ============================================================
     * DIACRITIC VARIANT GENERATION
     * ============================================================
     *
     * Only generates meaningful one-character variants.
     *
     * It does NOT generate arbitrary vowel substitutions.
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
                normalizeLanguage(languageTag);

        for (int i = 0;
             i < input.length();
             i++) {

            char current =
                    Character.toLowerCase(
                            input.charAt(i)
                    );

            char[] replacements;

            if ("es-AR".equals(language)) {

                replacements =
                        getSpanishVariants(current);

            } else if ("de".equals(language)) {

                replacements =
                        getGermanVariants(current);

            } else {

                replacements =
                        getEnglishVariants(current);
            }

            for (char replacement :
                    replacements) {

                if (replacement == current) {
                    continue;
                }

                StringBuilder builder =
                        new StringBuilder(input);

                builder.setCharAt(
                        i,
                        replacement
                );

                variants.add(
                        builder.toString()
                );
            }
        }

        variants.remove(input);

        return new ArrayList<>(variants);
    }

    /*
     * ============================================================
     * SPANISH VARIANTS
     * ============================================================
     */

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
             * n <-> ñ is a language-specific relation.
             *
             * It is intentionally included here because this
             * method is used to generate dictionary candidates.
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

    /*
     * ============================================================
     * GERMAN VARIANTS
     * ============================================================
     */

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

    /*
     * ============================================================
     * ENGLISH VARIANTS
     * ============================================================
     */

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
}
