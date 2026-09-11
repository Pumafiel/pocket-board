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

    public static boolean areDiacriticVariants(
            char first,
            char second) {

        if (first == second) {
            return true;
        }

        String a =
                removeDiacritics(
                        String.valueOf(first)
                );

        String b =
                removeDiacritics(
                        String.valueOf(second)
                );

        return a.equalsIgnoreCase(b);
    }

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

    public static String normalizeForComparison(
            String text,
            String languageTag) {

        if (text == null) {
            return "";
        }

        return text.toLowerCase(
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

        for (
                int i = 0;
                i < normalized.length();
                i++
        ) {
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

        if (a.equals(b)) {
            return true;
        }

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
     * Generates only one-character language variants.
     *
     * This is intentionally bounded. The dictionary manager verifies
     * every generated word against the real dictionary.
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

        for (
                int i = 0;
                i < input.length();
                i++
        ) {
            char current =
                    input.charAt(i);

            addVariantsForCharacter(
                    input,
                    i,
                    current,
                    language,
                    variants
            );
        }

        variants.remove(input);

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

        if ("es-AR".equals(language)) {
            replacements = new char[] {
                    'a', 'á',
                    'e', 'é',
                    'i', 'í',
                    'o', 'ó',
                    'u', 'ú',
                    'n', 'ñ'
            };
        } else if ("de".equals(language)) {
            replacements = new char[] {
                    'a', 'ä',
                    'o', 'ö',
                    'u', 'ü',
                    's', 'ß'
            };
        } else {
            replacements = new char[] {
                    'a', 'á',
                    'e', 'é',
                    'i', 'í',
                    'o', 'ó',
                    'u', 'ú'
            };
        }

        for (char replacement : replacements) {

            if (replacement == current) {
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

    private static String replaceSpanishN(
            String text) {

        return text.replace(
                'ñ',
                'n'
        );
    }
}
