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

        private Locale locale = Locale.ENGLISH;

        @Override
        public void onCreate() {

            /*
             * Android 11 returns the session locale as a String.
             */
            String languageTag = getLocale();

            if (languageTag != null &&
                    !languageTag.isEmpty()) {

                Locale parsedLocale =
                        Locale.forLanguageTag(languageTag);

                if (!parsedLocale.getLanguage().isEmpty()) {
                    locale = parsedLocale;
                }
            }
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
             * Android may append "#" when asking for suggestions
             * for the word currently being edited.
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

            String languageTag =
                    locale.toLanguageTag();

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
             * The word is not in the dictionary.
             *
             * Ask DictionaryManager for corrections.
             */
            List<String> suggestions =
                    dictionaryManager.getSuggestions(
                            word,
                            languageTag,
                            suggestionsLimit
                    );

            if (suggestions == null ||
                    suggestions.isEmpty()) {

                /*
                 * Important:
                 *
                 * Even when we have no replacement suggestion,
                 * tell Android that the word looks like a typo.
                 */
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

        /*
         * We intentionally do NOT override
         * onGetSentenceSuggestionsMultiple().
         *
         * Android's SpellCheckerService provides the default
         * implementation. It splits sentences into words and
         * calls onGetSuggestionsMultiple(), preserving the
         * TextInfo metadata required by the framework.
         *
         * This is especially important for Android 11.
         */

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
