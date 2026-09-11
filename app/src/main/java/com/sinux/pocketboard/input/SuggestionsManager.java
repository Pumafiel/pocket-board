package com.sinux.pocketboard.input;

import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.sinux.pocketboard.PocketBoardIME;
import com.sinux.pocketboard.R;
import com.sinux.pocketboard.input.handler.KeyboardInputHandler;
import com.sinux.pocketboard.preferences.PreferencesHolder;
import com.sinux.pocketboard.spellchecker.DictionaryManager;
import com.sinux.pocketboard.ui.InputView;
import com.sinux.pocketboard.ui.SuggestionView;
import com.sinux.pocketboard.utils.InputUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SuggestionsManager
        implements SuggestionView.OnClickListener {

    private final PocketBoardIME pocketBoardIME;
    private final PreferencesHolder preferencesHolder;
    private final KeyboardInputHandler keyboardInputHandler;
    private final DictionaryManager dictionaryManager;

    private final int suggestionsCount;

    /*
     * Single unified suggestion list.
     *
     * Position 0 is always the best candidate.
     * InputView places position 0 in the center.
     */
    private final List<CharSequence> suggestions;

    private InputView inputView;

    private boolean suggestionsAllowed;

    /*
     * The best candidate returned by the native engine.
     *
     * Kept separately because other PocketBoard components may ask
     * for the current recommended suggestion.
     */
    private CharSequence lastRecommendedSuggestion;

    private boolean hasRecommendedSuggestion;

    private boolean isPaused;

    /*
     * Language used by the native PocketBoard dictionary.
     *
     * Supported dictionary identifiers:
     *
     *     es-AR
     *     en
     *     de
     */
    private String currentLanguageTag = "en";

    public SuggestionsManager(
            PocketBoardIME pocketBoardIME,
            KeyboardInputHandler keyboardInputHandler) {

        this.pocketBoardIME =
                pocketBoardIME;

        this.preferencesHolder =
                pocketBoardIME.getPreferencesHolder();

        this.keyboardInputHandler =
                keyboardInputHandler;

        this.dictionaryManager =
                new DictionaryManager(
                        pocketBoardIME
                );

        suggestionsCount =
                pocketBoardIME
                        .getResources()
                        .getInteger(
                                R.integer.suggestions_count
                        );

        suggestions =
                new ArrayList<>(
                        suggestionsCount
                );
    }

    public void setInputView(
            InputView inputView) {

        this.inputView =
                inputView;
    }

    /*
     * ============================================================
     * INPUT LIFECYCLE
     * ============================================================
     */

    public void onStartInput(
            EditorInfo attribute,
            InputMethodSubtype currentInputMethodSubtype) {

        clear();

        updateCurrentLanguage(
                currentInputMethodSubtype
        );

        boolean suggestionAllowedEditor =
                attribute != null
                        && InputUtils.isSuggestionAllowedEditor(
                        attribute
                )
                        && !InputUtils.isNumericEditor(
                        attribute
                );

        /*
         * PocketBoard uses its own native dictionary/suggestion
         * engine.
         *
         * No Android SpellCheckerSession is involved.
         */
        suggestionsAllowed =
                suggestionAllowedEditor
                        && (
                        preferencesHolder
                                .isShowSuggestionsEnabled()
                                || preferencesHolder
                                .isAutoCorrectionEnabled()
                );
    }

    public void onStartInputView(
            InputMethodSubtype currentInputMethodSubtype) {

        updateCurrentLanguage(
                currentInputMethodSubtype
        );
    }

    public void onFinishInput() {

        clear();
    }

    public void disallowSuggestions() {

        clear();

        suggestionsAllowed =
                false;
    }

    public boolean isSuggestionsAllowed() {

        return suggestionsAllowed;
    }

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    public void clear() {

        suggestions.clear();

        hasRecommendedSuggestion =
                false;

        lastRecommendedSuggestion =
                null;

        showSuggestions();
    }

    public void pause() {

        isPaused =
                true;

        clear();
    }

    public void resume() {

        isPaused =
                false;

        update();
    }

    /*
     * ============================================================
     * NATIVE POCKETBOARD ENGINE
     * ============================================================
     */

    public void update() {

        if (isPaused) {
            return;
        }

        if (!suggestionsAllowed) {

            clear();

            return;
        }

        CharSequence composing =
                keyboardInputHandler
                        .getCurrentComposingText();

        if (TextUtils.isEmpty(
                composing
        )) {

            clear();

            return;
        }

        String composingText =
                composing.toString();

        /*
         * DictionaryManager owns candidate generation and ranking.
         *
         * SuggestionsManager deliberately does NOT try to decide
         * whether a result is:
         *
         *     - completion
         *     - correction
         *     - spelling variant
         *     - keyboard correction
         *
         * The native engine decides the order.
         *
         * Position 0 is therefore the best candidate.
         */
        List<String> nativeSuggestions =
                dictionaryManager.getSuggestions(
                        composingText,
                        currentLanguageTag,
                        suggestionsCount
                );

        suggestions.clear();

        addUniqueSuggestions(
                suggestions,
                nativeSuggestions
        );

        /*
         * The first result is the recommendation.
         *
         * InputView puts result 0 in the center.
         */
        if (!suggestions.isEmpty()) {

            lastRecommendedSuggestion =
                    suggestions.get(0);

            hasRecommendedSuggestion =
                    true;

        } else {

            lastRecommendedSuggestion =
                    null;

            hasRecommendedSuggestion =
                    false;
        }

        showSuggestions();
    }

    /*
     * Android may still call onDisplayCompletions().
     *
     * These CompletionInfo values are NOT used as the source
     * of PocketBoard suggestions.
     *
     * The native PocketBoard DictionaryManager remains the
     * only source.
     */
    public void update(
            CompletionInfo[] completions) {

        if (isPaused) {
            return;
        }

        update();
    }

    /*
     * ============================================================
     * SUGGESTION LIST
     * ============================================================
     */

    private void addUniqueSuggestions(
            List<CharSequence> target,
            List<String> source) {

        if (source == null ||
                source.isEmpty()) {

            return;
        }

        Set<String> unique =
                new LinkedHashSet<>();

        for (CharSequence existing :
                target) {

            if (existing != null) {

                unique.add(
                        normalizeSuggestionKey(
                                existing.toString()
                        )
                );
            }
        }

        for (String suggestion :
                source) {

            if (TextUtils.isEmpty(
                    suggestion
            )) {

                continue;
            }

            String key =
                    normalizeSuggestionKey(
                            suggestion
                    );

            if (key.isEmpty()) {
                continue;
            }

            if (unique.add(key)) {

                target.add(
                        suggestion
                );
            }

            if (target.size()
                    >= suggestionsCount) {

                break;
            }
        }
    }

    private String normalizeSuggestionKey(
            String value) {

        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }

    /*
     * ============================================================
     * CURRENT RECOMMENDATION
     * ============================================================
     */

    public CharSequence getCurrentDictSuggestion() {

        if (!suggestions.isEmpty()) {

            return suggestions.get(0);
        }

        return null;
    }

    public CharSequence
    getCurrentSpellcheckerRecommendedSuggestion() {

        if (hasRecommendedSuggestion
                && !suggestions.isEmpty()) {

            lastRecommendedSuggestion =
                    suggestions.get(0);

            return lastRecommendedSuggestion;
        }

        return null;
    }

    /*
     * ============================================================
     * LANGUAGE
     * ============================================================
     */

    public void onInputMethodSubtypeChanged(
            InputMethodSubtype inputMethodSubtype) {

        updateCurrentLanguage(
                inputMethodSubtype
        );

        update();
    }

    private void updateCurrentLanguage(
            InputMethodSubtype subtype) {

        if (subtype == null) {

            currentLanguageTag =
                    "en";

            return;
        }

        String languageTag =
                subtype.getLanguageTag();

        /*
         * Compatibility with older subtype definitions.
         */
        if (TextUtils.isEmpty(
                languageTag
        )) {

            String localeString =
                    subtype.getLocale();

            if (!TextUtils.isEmpty(
                    localeString
            )) {

                languageTag =
                        localeString.replace(
                                '_',
                                '-'
                        );

            } else {

                languageTag =
                        "en";
            }
        }

        Locale locale =
                Locale.forLanguageTag(
                        languageTag
                );

        if (TextUtils.isEmpty(
                locale.getLanguage()
        )) {

            locale =
                    Locale.ENGLISH;
        }

        currentLanguageTag =
                normalizeDictionaryLanguage(
                        locale
                );
    }

    private String normalizeDictionaryLanguage(
            Locale locale) {

        if (locale == null) {

            return "en";
        }

        String language =
                locale.getLanguage();

        /*
         * PocketBoard uses the Argentine Spanish
         * dictionary for Spanish input.
         */
        if ("es".equals(
                language
        )) {

            return "es-AR";
        }

        if ("de".equals(
                language
        )) {

            return "de";
        }

        if ("en".equals(
                language
        )) {

            return "en";
        }

        return "en";
    }

    /*
     * ============================================================
     * SUGGESTION UI
     * ============================================================
     */

    private void showSuggestions() {

        if (inputView == null) {
            return;
        }

        /*
         * IMPORTANT:
         *
         * We pass the native engine's order unchanged.
         *
         * InputView places element 0 in the CENTER.
         *
         * Therefore:
         *
         *     suggestions[0] = best candidate
         *     suggestions[1] = alternative
         *     suggestions[2] = alternative
         */
        inputView.setSuggestions(
                suggestions,
                hasRecommendedSuggestion
        );
    }

    /*
     * ============================================================
     * USER SELECTED SUGGESTION
     * ============================================================
     */

    @Override
    public void onClick(View v) {

        if (!(v instanceof SuggestionView)) {

            return;
        }

        applySuggestion(
                ((SuggestionView) v).getText()
        );
    }

    private void applySuggestion(
            CharSequence text) {

        if (isPaused) {
            return;
        }

        if (TextUtils.isEmpty(
                text
        )) {

            return;
        }

        keyboardInputHandler.applySuggestion(
                text,
                pocketBoardIME
                        .getCurrentInputConnection(),
                true
        );
    }
}
