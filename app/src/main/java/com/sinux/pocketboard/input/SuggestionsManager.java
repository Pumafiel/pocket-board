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
import com.sinux.pocketboard.utils.CharacterUtils;
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
                        .getInteger(R.integer.suggestions_count);

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

        var suggestionAllowedEditor =
                InputUtils.isSuggestionAllowedEditor(attribute)
                        && !InputUtils.isNumericEditor(attribute);

        var suggestionsPanelVisible =
                pocketBoardIME.isShouldShowIme()
                        && preferencesHolder.isShowSuggestionsEnabled();

        dictionarySuggestionsAllowed =
                suggestionAllowedEditor
                        && (suggestionsPanelVisible
                        || preferencesHolder.isDictShortcutsEnabled());

        spellcheckerSuggestionsAllowed =
                suggestionAllowedEditor
                        && (suggestionsPanelVisible
                        || preferencesHolder.isAutoCorrectionEnabled())
                        && startSpellCheckerSession(
                        currentInputMethodSubtype
                );

        updateCurrentLanguage(currentInputMethodSubtype);
    }

    public void onStartInputView(
            InputMethodSubtype currentInputMethodSubtype) {

        updateCurrentLanguage(currentInputMethodSubtype);

        if (spellcheckerSuggestionsAllowed
                && spellCheckerSession == null) {

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
         *
         * This works even when Android has no system spellchecker
         * selected.
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
         *
         * Optional. If Android provides one, we use it as an
         * additional source of suggestions.
         * =========================================================
         */
        if (spellcheckerSuggestionsAllowed) {

            if (spellCheckerSession != null
                    && !spellCheckerSession.isSessionDisconnected()) {

                String spellText = composingText;

                /*
                 * Magic trick for AOSP/OpenBoard spellchecker.
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

                spellCheckerSession
                        .getSentenceSuggestions(
                                textInfos,
                                suggestionsCount
                        );
            }
        }

        showSuggestions();
    }

    public void update(CompletionInfo[] completions) {

        if (isPaused) {
            return;
        }

        /*
         * Completions supplied by the current editor are optional.
         */
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

        showSuggestions();
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
                        (si.getSuggestionsAttributes()
                                & SuggestionsInfo
                                .RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS)
                                != 0;

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

        } else {

            return false;
        }
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
         * Dictionary suggestions come first.
         *
         * This means PocketBoard's own dictionary works even
         * when Android has no spellchecker selected.
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

            Locale locale =
                    subtype.getLocale() != null
                            ? Locale.forLanguageTag(
                            subtype.getLocale()
                    )
                            : Locale.ENGLISH;

            languageTag =
                    locale.toLanguageTag();
        }

        currentLanguageTag =
                languageTag;
    }

    private boolean startSpellCheckerSession(
            InputMethodSubtype inputMethodSubtype) {

        if (inputMethodSubtype == null) {
            return false;
        }

        Locale locale =
                Locale.forLanguageTag(
                        inputMethodSubtype.getLanguageTag()
                );

        TextServicesManager tsm =
                (TextServicesManager)
                        pocketBoardIME.getSystemService(
                                Context.TEXT_SERVICES_MANAGER_SERVICE
                        );

        if (tsm == null) {
            return false;
        }

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
    }
}
