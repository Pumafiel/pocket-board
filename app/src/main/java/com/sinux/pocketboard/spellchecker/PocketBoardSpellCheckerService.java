package com.sinux.pocketboard.spellchecker;

import android.service.textservice.SpellCheckerService;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import java.util.List;
import java.util.Locale;

public class PocketBoardSpellCheckerService
        extends SpellCheckerService {

    private DictionaryManager dictionaryManager;

    @Override
    public void onCreate() {
        super.onCreate();

        dictionaryManager = new DictionaryManager(this);
    }

    @Override
    public Session createSession() {
        return new PocketBoardSpellCheckerSession();
    }

    private class PocketBoardSpellCheckerSession
            extends SpellCheckerService.Session {

        /*
         * Do NOT default the actual requested language to English.
         *
         * English is only used when Android genuinely does not
         * provide a usable locale.
         */
        private Locale locale;

        @Override
        public void onCreate() {

            String languageTag = getLocale();

            locale = resolveLocale(languageTag);
        }

        @Override
        public SuggestionsInfo onGetSuggestions(
                TextInfo textInfo,
                int suggestionsLimit) {

            if (textInfo == null ||
                    textInfo.getText() == null ||
                    suggestionsLimit <= 0) {

                return new SuggestionsInfo(
                        0,
                        new String[0],
                        getCookie(textInfo),
                        getSequence(textInfo)
                );
            }

            String originalWord =
                    textInfo.getText();

            String word =
                    originalWord;

            /*
             * Android/AOSP may append "#" to the currently
             * composing word.
             */
            if (word.endsWith("#")) {
                word = word.substring(
                        0,
                        word.length() - 1
                );
            }

            word = word.trim();

            if (word.isEmpty()) {

                return new SuggestionsInfo(
                        0,
                        new String[0],
                        textInfo.getCookie(),
                        textInfo.getSequence()
                );
            }

            /*
             * Resolve the language that DictionaryManager should use.
             *
             * es-AR -> es-AR
             * es-*  -> es-AR
             * de-*  -> de
             * en-*  -> en
             */
            String languageTag =
                    normalizeDictionaryLanguage(
                            locale
                    );

            /*
             * Check whether this is an actual dictionary word.
             */
            boolean exactMatch =
                    dictionaryManager.contains(
                            word,
                            languageTag
                    );

            if (exactMatch) {

                return new SuggestionsInfo(
                        SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
                        new String[0],
                        textInfo.getCookie(),
                        textInfo.getSequence()
                );
            }

            /*
             * The word isn't present in the selected language
             * dictionary. Ask that SAME dictionary for corrections.
             */
            List<String> suggestions =
                    dictionaryManager.getSuggestions(
                            word,
                            languageTag,
                            suggestionsLimit
                    );

            if (suggestions == null ||
                    suggestions.isEmpty()) {

                return new SuggestionsInfo(
                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
                        new String[0],
                        textInfo.getCookie(),
                        textInfo.getSequence()
                );
            }

            String[] result =
                    suggestions.toArray(
                            new String[0]
                    );

            return new SuggestionsInfo(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                            | SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
                    result,
                    textInfo.getCookie(),
                    textInfo.getSequence()
            );
        }

        /**
         * Convert the locale supplied by Android into the languages
         * supported by PocketBoard.
         */
        private Locale resolveLocale(
                String languageTag) {

            if (languageTag == null ||
                    languageTag.trim().isEmpty()) {

                /*
                 * There is genuinely no locale information.
                 * This is the only case where we use English.
                 */
                return Locale.ENGLISH;
            }

            Locale parsed =
                    Locale.forLanguageTag(
                            languageTag.replace('_', '-')
                    );

            String language =
                    parsed.getLanguage();

            if ("es".equals(language)) {

                /*
                 * PocketBoard uses the Argentine Spanish
                 * dictionary for all Spanish variants.
                 */
                return Locale.forLanguageTag("es-AR");
            }

            if ("de".equals(language)) {
                return Locale.GERMAN;
            }

            if ("en".equals(language)) {

                return Locale.ENGLISH;
            }

            /*
             * Unknown language.
             *
             * PocketBoard currently supports only:
             * es-AR, de and en.
             */
            return Locale.ENGLISH;
        }

        /**
         * Maps the resolved Locale to the exact dictionary names
         * understood by DictionaryManager.
         */
        private String normalizeDictionaryLanguage(
                Locale locale) {

            if (locale == null) {
                return "en";
            }

            String language =
                    locale.getLanguage();

            if ("es".equals(language)) {
                return "es-AR";
            }

            if ("de".equals(language)) {
                return "de";
            }

            if ("en".equals(language)) {
                return "en";
            }

            return "en";
        }

        private int getCookie(TextInfo textInfo) {

            if (textInfo == null) {
                return 0;
            }

            return textInfo.getCookie();
        }

        private int getSequence(TextInfo textInfo) {

            if (textInfo == null) {
                return 0;
            }

            return textInfo.getSequence();
        }
    }
}
