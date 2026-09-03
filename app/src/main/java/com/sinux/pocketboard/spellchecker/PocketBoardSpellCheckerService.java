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

        dictionaryManager =
                new DictionaryManager(this);
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
             * Session.onCreate() is abstract in this Android API,
             * so there is no super.onCreate() call.
             *
             * SpellCheckerService.Session#getLocale() returns
             * a language tag String.
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
                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
                        new String[0]
                );
            }

            String word = textInfo.getText();

            /*
             * The AOSP spellchecker can send an artificial "#"
             * suffix to improve prefix suggestions.
             *
             * Remove it before querying our dictionary.
             */
            if (word.endsWith("#")) {
                word = word.substring(
                        0,
                        word.length() - 1
                );
            }

            if (word.isEmpty()) {
                return new SuggestionsInfo(
                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
                        new String[0]
                );
            }

            List<String> suggestions =
                    dictionaryManager.getSuggestions(
                            word,
                            locale.toLanguageTag(),
                            suggestionsLimit
                    );

            if (suggestions.isEmpty()) {

                return new SuggestionsInfo(
                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
                        new String[0]
                );
            }

            String[] result =
                    suggestions.toArray(
                            new String[0]
                    );

            boolean exactMatch =
                    dictionaryManager.contains(
                            word,
                            locale.toLanguageTag()
                    );

            int attributes;

            if (exactMatch) {

                attributes =
                        SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY;

            } else {

                attributes =
                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                                | SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS;
            }

            return new SuggestionsInfo(
                    attributes,
                    result
            );
        }
    }
}
