package com.sinux.pocketboard.input;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;

import androidx.annotation.RequiresApi;

import com.sinux.pocketboard.PocketBoardIME;
import com.sinux.pocketboard.R;
import com.sinux.pocketboard.input.handler.KeyboardInputHandler;
import com.sinux.pocketboard.preferences.PreferencesHolder;
import com.sinux.pocketboard.spellchecker.DictionaryManager;
import com.sinux.pocketboard.ui.InputView;
import com.sinux.pocketboard.ui.SuggestionView;
import com.sinux.pocketboard.utils.InputUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SuggestionsManager implements SuggestionView.OnClickListener,
        SpellCheckerSession.SpellCheckerSessionListener {

    private static final String AOSP_SPELLCHECKER_PACKAGE =
            "com.android.inputmethod.latin";

    private static final String AOSP_SPELLCHECKER_PACKAGE_OPENBOARD =
            "org.dslul.openboard.inputmethod.latin";

    private final PocketBoardIME pocketBoardIME;
    private final PreferencesHolder preferencesHolder;
    private final KeyboardInputHandler keyboardInputHandler;
    private final DictionaryManager dictionaryManager;

    private final int suggestionsCount;

    private final List<CharSequence> dictionarySuggestions;
    private final List<CharSequence> spellcheckerSuggestions;

    private InputView inputView;
    private SpellCheckerSession spellCheckerSession;

    private boolean dictionarySuggestionsAllowed;
    private boolean spellcheckerSuggestionsAllowed;

    private boolean aospSpellchecker;

    private boolean hasRecommendedSpellcheckerSuggestion;

    private CharSequence lastRecommendedSuggestion;

    private boolean isPaused;

    /*
     * Language used by PocketBoard's internal dictionary.
     *
     * Supported:
     *   en
     *   es-AR
     *   de
     */
    private String currentLanguageTag = "en";

    public SuggestionsManager(
            PocketBoardIME pocketBoardIME,
            KeyboardInputHandler keyboardInputHandler) {

        this.pocketBoardIME = pocketBoardIME;

        this.preferencesHolder =
                pocketBoardIME.getPreferencesHolder();

        this.keyboardInputHandler =
                keyboardInputHandler;

        dictionaryManager =
                new DictionaryManager(pocketBoardIME);

        suggestionsCount =
                pocketBoardIME
                        .getResources()
                        .getInteger(
                                R.integer.suggestions_count
                        );

        dictionarySuggestions =
                new ArrayList<>(suggestionsCount);

        spellcheckerSuggestions =
                new ArrayList<>(suggestionsCount);
    }

    public void setInputView(InputView inputView) {
        this.inputView = inputView;
    }

    public void onStartInput(
            EditorInfo attribute,
            InputMethodSubtype currentInputMethodSubtype) {

        disallowSuggestions();

        updateCurrentLanguage(
                currentInputMethodSubtype
        );

        boolean suggestionAllowedEditor =
                InputUtils.isSuggestionAllowedEditor(attribute)
                        && !InputUtils.isNumericEditor(attribute);

        boolean suggestionsPanelVisible =
                pocketBoardIME.isShouldShowIme()
                        && preferencesHolder.isShowSuggestionsEnabled();

        dictionarySuggestionsAllowed =
                suggestionAllowedEditor
                        && (
                        suggestionsPanelVisible
                                || preferencesHolder.isDictShortcutsEnabled()
                );

        spellcheckerSuggestionsAllowed =
                suggestionAllowedEditor
                        && (
                        suggestionsPanelVisible
                                || preferencesHolder.isAutoCorrectionEnabled()
                )
                        && startSpellCheckerSession(
                        currentInputMethodSubtype
                );
    }

    public void onStartInputView(
            InputMethodSubtype currentInputMethodSubtype) {

        updateCurrentLanguage(
                currentInputMethodSubtype
        );

        if (spellcheckerSuggestionsAllowed
                && spellCheckerSession == null) {

            spellcheckerSuggestionsAllowed =
                    startSpellCheckerSession(
                            currentInputMethodSubtype
                    );
        }
    }

    public void onFinishInput() {
        closeSpellCheckerSession();
    }

    public void disallowSuggestions() {

        clear();

        closeSpellCheckerSession();

        dictionarySuggestionsAllowed = false;
        spellcheckerSuggestionsAllowed = false;
    }

    public boolean isSuggestionsAllowed() {
        return spellcheckerSuggestionsAllowed
                || dictionarySuggestionsAllowed;
    }

    public void clear() {

        dictionarySuggestions.clear();
        spellcheckerSuggestions.clear();

        hasRecommendedSpellcheckerSuggestion = false;
        lastRecommendedSuggestion = null;

        showSuggestions();
    }

    public void update() {

        if (isPaused) {
            return;
        }

        CharSequence composing =
                keyboardInputHandler
                        .getCurrentComposingText();

        if (TextUtils.isEmpty(composing)) {
            clear();
            return;
        }

        String composingText =
                composing.toString();

        /*
         * =========================================================
         * POCKETBOARD INTERNAL DICTIONARY
         * =========================================================
         */
        if (dictionarySuggestionsAllowed) {

            updateDictionarySuggestions(
                    composingText
            );
        }

        /*
         * =========================================================
         * SYSTEM SPELLCHECKER
         * =========================================================
         */
        if (spellcheckerSuggestionsAllowed
                && spellCheckerSession != null
                && !spellCheckerSession.isSessionDisconnected()) {

            String spellText =
                    composingText;

            /*
             * AOSP/OpenBoard sometimes require "#" at the end
             * of a composing word to return sentence suggestions.
             */
            if (aospSpellchecker) {
                spellText += "#";
            }

            TextInfo[] textInfos = {
                    new TextInfo(
                            spellText,
                            0,
                            spellText.length(),
                            0,
                            0
                    )
            };

            spellCheckerSession.getSentenceSuggestions(
                    textInfos,
                    suggestionsCount
            );
        }

        showSuggestions();
    }

    public void update(CompletionInfo[] completions) {

        if (isPaused) {
            return;
        }

        if (completions != null) {

            spellcheckerSuggestions.clear();

            hasRecommendedSpellcheckerSuggestion =
                    false;

            for (
                    int i = 0;
                    i < completions.length
                            && i < suggestionsCount;
                    i++
            ) {

                if (!TextUtils.isEmpty(
                        completions[i].getText()
                )) {

                    spellcheckerSuggestions.add(
                            completions[i]
                                    .getText()
                                    .toString()
                    );
                }
            }
        }

        showSuggestions();
    }

    private void updateDictionarySuggestions(
            String composingText) {

        dictionarySuggestions.clear();

        if (TextUtils.isEmpty(composingText)) {
            return;
        }

        List<String> suggestions =
                dictionaryManager.getSuggestions(
                        composingText,
                        currentLanguageTag,
                        suggestionsCount
                );

        if (suggestions != null) {

            dictionarySuggestions.addAll(
                    suggestions
            );
        }
    }

    public CharSequence getCurrentDictSuggestion() {

        if (!dictionarySuggestions.isEmpty()) {
            return dictionarySuggestions.get(0);
        }

        return null;
    }

    public CharSequence getCurrentSpellcheckerRecommendedSuggestion() {

        if (hasRecommendedSpellcheckerSuggestion
                && !spellcheckerSuggestions.isEmpty()) {

            lastRecommendedSuggestion =
                    spellcheckerSuggestions.get(0);

            return lastRecommendedSuggestion;
        }

        return null;
    }

    public void onInputMethodSubtypeChanged(
            InputMethodSubtype inputMethodSubtype) {

        closeSpellCheckerSession();

        updateCurrentLanguage(
                inputMethodSubtype
        );

        if (spellcheckerSuggestionsAllowed) {

            spellcheckerSuggestionsAllowed =
                    startSpellCheckerSession(
                            inputMethodSubtype
                    );
        }

        update();
    }

    @Override
    public void onClick(View v) {

        if (v instanceof SuggestionView) {

            applySuggestion(
                    ((SuggestionView) v).getText()
            );
        }
    }

    @Override
    public void onGetSuggestions(
            SuggestionsInfo[] results) {
        /*
         * PocketBoard uses sentence suggestions below.
         */
    }

    @Override
    public void onGetSentenceSuggestions(
            SentenceSuggestionsInfo[] results) {

        if (!spellcheckerSuggestionsAllowed) {
            return;
        }

        if (results == null) {
            return;
        }

        List<CharSequence> tempSuggestions =
                new ArrayList<>(suggestionsCount);

        boolean hasRecommended = false;

        for (SentenceSuggestionsInfo ssi : results) {

            if (ssi == null) {
                continue;
            }

            for (
                    int i = 0;
                    i < ssi.getSuggestionsCount();
                    i++
            ) {

                SuggestionsInfo si =
                        ssi.getSuggestionsInfoAt(i);

                if (si == null) {
                    continue;
                }

                hasRecommended |=
                        (
                                si.getSuggestionsAttributes()
                                        & SuggestionsInfo
                                        .RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
                        ) != 0;

                for (
                        int j = 0;
                        j < si.getSuggestionsCount();
                        j++
                ) {

                    String suggestion =
                            si.getSuggestionAt(j);

                    if (!TextUtils.isEmpty(
                            suggestion
                    )) {

                        tempSuggestions.add(
                                suggestion
                        );
                    }
                }
            }
        }

        if (!tempSuggestions.isEmpty()) {

            spellcheckerSuggestions.clear();

            spellcheckerSuggestions.addAll(
                    tempSuggestions
            );

            if (hasRecommended
                    && lastRecommendedSuggestion != null
                    && lastRecommendedSuggestion.equals(
                    tempSuggestions.get(0)
            )) {

                hasRecommendedSpellcheckerSuggestion =
                        false;

            } else {

                hasRecommendedSpellcheckerSuggestion =
                        hasRecommended;
            }

            showSuggestions();
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    public boolean showInlineSuggestions(
            List<InlineSuggestion> inlineSuggestions) {

        if (inputView != null) {

            return inputView.setInlineSuggestions(
                    inlineSuggestions
            );

        }

        return false;
    }

    public boolean isInlineSuggestionsShown() {

        if (inputView != null) {
            return inputView.isInlineSuggestionsShown();
        }

        return false;
    }

    public void cancelInlineSuggestions() {

        if (inputView != null) {
            inputView.cancelInlineSuggestions();
        }
    }

    public void pause() {

        isPaused = true;

        clear();
    }

    public void resume() {

        isPaused = false;

        update();
    }

    private void showSuggestions() {

        if (inputView == null) {
            return;
        }

        /*
         * Internal dictionary suggestions first,
         * then spellchecker suggestions.
         */
        List<CharSequence> merged =
                Stream.concat(
                        dictionarySuggestions.stream(),
                        spellcheckerSuggestions.stream()
                )
                .distinct()
                .limit(3)
                .collect(Collectors.toList());

        boolean hasRecommended =
                !dictionarySuggestions.isEmpty()
                        || hasRecommendedSpellcheckerSuggestion;

        inputView.setSuggestions(
                merged,
                hasRecommended
        );
    }

    private void applySuggestion(
            CharSequence text) {

        if (isPaused) {
            return;
        }

        if (!TextUtils.isEmpty(text)) {

            keyboardInputHandler.applySuggestion(
                    text,
                    pocketBoardIME
                            .getCurrentInputConnection(),
                    true
            );
        }
    }

    private void updateCurrentLanguage(
            InputMethodSubtype subtype) {

        if (subtype == null) {
            currentLanguageTag = "en";
            return;
        }

        String languageTag =
                subtype.getLanguageTag();

        if (TextUtils.isEmpty(languageTag)) {

            String localeString =
                    subtype.getLocale();

            if (!TextUtils.isEmpty(localeString)) {

                languageTag =
                        localeString.replace('_', '-');

            } else {

                languageTag = "en";
            }
        }

        Locale locale =
                Locale.forLanguageTag(languageTag);

        if (locale.getLanguage().isEmpty()) {
            locale = Locale.ENGLISH;
        }

        /*
         * Normalize PocketBoard's supported languages.
         *
         * Spanish variants -> es-AR
         * German variants  -> de
         * English variants -> en
         */
        currentLanguageTag =
                normalizeDictionaryLanguage(locale);
    }

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

    private boolean startSpellCheckerSession(
            InputMethodSubtype inputMethodSubtype) {

        if (inputMethodSubtype == null) {
            return false;
        }

        /*
         * Get the language from the keyboard subtype.
         */
        String languageTag =
                inputMethodSubtype.getLanguageTag();

        if (TextUtils.isEmpty(languageTag)) {

            String localeString =
                    inputMethodSubtype.getLocale();

            if (!TextUtils.isEmpty(localeString)) {

                languageTag =
                        localeString.replace('_', '-');

            } else {

                languageTag = "en";
            }
        }

        Locale locale =
                Locale.forLanguageTag(languageTag);

        if (locale.getLanguage().isEmpty()) {
            locale = Locale.ENGLISH;
        }

        /*
         * Keep PocketBoard's internal dictionary synchronized
         * with the selected keyboard language.
         */
        currentLanguageTag =
                normalizeDictionaryLanguage(locale);

        TextServicesManager tsm =
                (TextServicesManager)
                        pocketBoardIME.getSystemService(
                                Context.TEXT_SERVICES_MANAGER_SERVICE
                        );

        if (tsm == null) {
            return false;
        }

        /*
         * Request the Android spellchecker using the exact
         * language selected by the keyboard subtype.
         *
         * en-US -> English
         * es-AR -> Argentine Spanish
         * de-DE -> German
         */
        spellCheckerSession =
                tsm.newSpellCheckerSession(
                        null,
                        locale,
                        this,
                        false
                );

        if (spellCheckerSession != null) {

            aospSpellchecker =
                    spellCheckerSession
                            .getSpellChecker() != null
                            && (
                            AOSP_SPELLCHECKER_PACKAGE.equals(
                                    spellCheckerSession
                                            .getSpellChecker()
                                            .getPackageName()
                            )
                                    ||
                                    AOSP_SPELLCHECKER_PACKAGE_OPENBOARD.equals(
                                            spellCheckerSession
                                                    .getSpellChecker()
                                                    .getPackageName()
                                    )
                    );

            return true;
        }

        return false;
    }

    private void closeSpellCheckerSession() {

        if (spellCheckerSession != null) {

            spellCheckerSession.cancel();
            spellCheckerSession.close();

            spellCheckerSession = null;
        }

        aospSpellchecker = false;
    }
}
