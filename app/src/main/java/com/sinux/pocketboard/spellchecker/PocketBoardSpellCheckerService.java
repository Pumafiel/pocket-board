package com.sinux.pocketboard.spellchecker;

import android.service.textservice.SpellCheckerService;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import java.util.ArrayList;
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
             * Session#getLocale() returns a language tag String
             * on the Android API used by this project.
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

                return emptySuggestions();
            }

            String word = textInfo.getText();

            /*
             * PocketBoardSpellCheckerService may receive the "#"
             * suffix used by SuggestionsManager when talking to
             * the AOSP spellchecker.
             */
            if (word.endsWith("#")) {
                word = word.substring(
                        0,
                        word.length() - 1
                );
            }

            word = word.trim();

            if (word.isEmpty()) {
                return emptySuggestions();
            }

            /*
             * If the word exists in our dictionary, explicitly tell
             * Android that it is a valid dictionary word.
             *
             * This is important: returning suggestions for every
             * prefix would otherwise make correctly typed words look
             * like spelling errors.
             */
            boolean exactMatch =
                    dictionaryManager.contains(
                            word,
                            locale.toLanguageTag()
                    );

            if (exactMatch) {
                return new SuggestionsInfo(
                        SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
                        new String[0]
                );
            }

            /*
             * Word is not in the dictionary.
             *
             * Ask DictionaryManager for possible corrections.
             */
            List<String> suggestions =
                    dictionaryManager.getSuggestions(
                            word,
                            locale.toLanguageTag(),
                            suggestionsLimit
                    );

            if (suggestions == null ||
                    suggestions.isEmpty()) {

                /*
                 * No correction was found, but the word is not
                 * present in the dictionary.
                 *
                 * RESULT_ATTR_LOOKS_LIKE_TYPO tells Android that
                 * this is a spelling error.
                 */
                return new SuggestionsInfo(
                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
                        new String[0]
                );
            }

            String[] result =
                    suggestions.toArray(
                            new String[0]
                    );

            /*
             * The first suggestion is considered the recommended
             * correction.
             */
            return new SuggestionsInfo(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                            | SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
                    result
            );
        }

        @Override
        public SentenceSuggestionsInfo[] onGetSentenceSuggestionsMultiple(
                TextInfo[] textInfos,
                int suggestionsLimit) {

            if (textInfos == null ||
                    textInfos.length == 0 ||
                    suggestionsLimit <= 0) {

                return new SentenceSuggestionsInfo[0];
            }

            List<SentenceSuggestionsInfo> results =
                    new ArrayList<>();

            for (TextInfo textInfo : textInfos) {
                if (textInfo == null ||
                        textInfo.getText() == null) {

                    results.add(
                            new SentenceSuggestionsInfo(
                                    new SuggestionsInfo[0],
                                    new int[0],
                                    new int[0]
                            )
                    );

                    continue;
                }

                String text = textInfo.getText();

                /*
                 * This service receives either a word or a sentence.
                 *
                 * Split the sentence while preserving the positions
                 * of each word so Android knows which text should be
                 * underlined.
                 */
                List<TextToken> tokens =
                        tokenize(text);

                List<SuggestionsInfo> suggestionsInfos =
                        new ArrayList<>();

                List<Integer> offsets =
                        new ArrayList<>();

                List<Integer> lengths =
                        new ArrayList<>();

                for (TextToken token : tokens) {

                    SuggestionsInfo suggestionsInfo =
                            onGetSuggestions(
                                    new TextInfo(token.text),
                                    suggestionsLimit
                            );

                    if (suggestionsInfo == null) {
                        continue;
                    }

                    /*
                     * Only add actual spelling problems or words
                     * for which the spellchecker has suggestions.
                     */
                    int attributes =
                            suggestionsInfo.getSuggestionsAttributes();

                    boolean isTypo =
                            (attributes &
                                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO)
                                    != 0;

                    boolean hasSuggestions =
                            suggestionsInfo.getSuggestionsCount() > 0;

                    if (isTypo || hasSuggestions) {

                        suggestionsInfos.add(
                                suggestionsInfo
                        );

                        offsets.add(token.start);
                        lengths.add(token.text.length());
                    }
                }

                SuggestionsInfo[] infoArray =
                        suggestionsInfos.toArray(
                                new SuggestionsInfo[0]
                        );

                int[] offsetArray =
                        new int[offsets.size()];

                int[] lengthArray =
                        new int[lengths.size()];

                for (int i = 0; i < offsets.size(); i++) {
                    offsetArray[i] = offsets.get(i);
                    lengthArray[i] = lengths.get(i);
                }

                results.add(
                        new SentenceSuggestionsInfo(
                                infoArray,
                                offsetArray,
                                lengthArray
                        )
                );
            }

            return results.toArray(
                    new SentenceSuggestionsInfo[0]
            );
        }

        private SuggestionsInfo emptySuggestions() {
            return new SuggestionsInfo(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
                    new String[0]
            );
        }

        private List<TextToken> tokenize(String text) {

            List<TextToken> tokens =
                    new ArrayList<>();

            int start = -1;

            for (int i = 0; i < text.length(); i++) {

                char c = text.charAt(i);

                boolean isWordCharacter =
                        Character.isLetterOrDigit(c)
                                || c == '\''
                                || c == '-';

                if (isWordCharacter) {

                    if (start < 0) {
                        start = i;
                    }

                } else {

                    if (start >= 0) {
                        tokens.add(
                                new TextToken(
                                        text.substring(
                                                start,
                                                i
                                        ),
                                        start
                                )
                        );

                        start = -1;
                    }
                }
            }

            if (start >= 0) {

                tokens.add(
                        new TextToken(
                                text.substring(start),
                                start
                        )
                );
            }

            return tokens;
        }
    }

    private static class TextToken {

        private final String text;
        private final int start;

        TextToken(
                String text,
                int start) {
            this.text = text;
            this.start = start;
        }
    }
}
