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

    private final List<CharSequence> dictionarySuggestions;
    private final List<CharSequence> spellcheckerSuggestions;

    private InputView inputView;

    private boolean dictionarySuggestionsAllowed;
    private boolean spellcheckerSuggestionsAllowed;

    private boolean hasRecommendedSpellcheckerSuggestion;

    private CharSequence lastRecommendedSuggestion;

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

        dictionarySuggestions =
                new ArrayList<>(
                        suggestionsCount
                );

        spellcheckerSuggestions =
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
         * Normal dictionary suggestions.
         */
        dictionarySuggestionsAllowed =
                suggestionAllowedEditor
                        && (
                        preferencesHolder
                                .isShowSuggestionsEnabled()
                                || preferencesHolder
                                .isDictShortcutsEnabled()
                );

        /*
         * Native spelling correction.
         *
         * PocketBoard does not use Android's external
         * SpellCheckerSession here.
         */
        spellcheckerSuggestionsAllowed =
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

        dictionarySuggestionsAllowed =
                false;

        spellcheckerSuggestionsAllowed =
                false;
    }

    public boolean isSuggestionsAllowed() {

        return dictionarySuggestionsAllowed
                || spellcheckerSuggestionsAllowed;
    }

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    public void clear() {

        dictionarySuggestions.clear();

        spellcheckerSuggestions.clear();

        hasRecommendedSpellcheckerSuggestion =
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
         * DictionaryManager owns the complete native
         * suggestion/correction pipeline.
         *
         * It decides internally between:
         *
         *     1. prefix completion
         *     2. exact dictionary word
         *     3. spelling correction
         */
        boolean exactDictionaryWord =
                dictionaryManager.contains(
                        composingText,
                        currentLanguageTag
                );

        List<String> suggestions =
                dictionaryManager.getSuggestions(
                        composingText,
                        currentLanguageTag,
                        suggestionsCount
                );

        if (suggestions == null) {

            suggestions =
                    new ArrayList<>();
        }

        /*
         * Reset current state.
         */
        dictionarySuggestions.clear();

        spellcheckerSuggestions.clear();

        hasRecommendedSpellcheckerSuggestion =
                false;

        lastRecommendedSuggestion =
                null;

        /*
         * ========================================================
         * NORMAL DICTIONARY SUGGESTIONS
         * ========================================================
         */

        if (dictionarySuggestionsAllowed) {

            addUniqueSuggestions(
                    dictionarySuggestions,
                    suggestions
            );
        }

        /*
         * ========================================================
         * SPELLING CORRECTION
         * ========================================================
         *
         * Exact dictionary words are already correct.
         *
         * Prefix completion must remain completion and must not
         * become an automatic correction.
         *
         * Example:
         *
         *     hol -> hola
         *
         * is completion, not correction.
         */
        if (spellcheckerSuggestionsAllowed
                && !exactDictionaryWord
                && !isPrefixCompletion(
                composingText,
                suggestions
        )) {

            addUniqueSuggestions(
                    spellcheckerSuggestions,
                    suggestions
            );

            if (!spellcheckerSuggestions.isEmpty()) {

                lastRecommendedSuggestion =
                        spellcheckerSuggestions.get(0);

                hasRecommendedSpellcheckerSuggestion =
                        true;
            }
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
     * SUGGESTION CLASSIFICATION
     * ============================================================
     */

    private boolean isPrefixCompletion(
            String composingText,
            List<String> suggestions) {

        if (TextUtils.isEmpty(
                composingText
        )) {

            return false;
        }

        String normalizedInput =
                composingText
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalizedInput.isEmpty()) {

            return false;
        }

        if (suggestions == null ||
                suggestions.isEmpty()) {

            return false;
        }

        for (String suggestion :
                suggestions) {

            if (TextUtils.isEmpty(
                    suggestion
            )) {

                continue;
            }

            String normalizedSuggestion =
                    suggestion
                            .trim()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            if (normalizedSuggestion.startsWith(
                    normalizedInput
            )
                    && !normalizedSuggestion.equals(
                    normalizedInput
            )) {

                return true;
            }
        }

        return false;
    }

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
                        existing.toString()
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

            if (unique.add(
                    suggestion
            )) {

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

    /*
     * ============================================================
     * CURRENT SUGGESTIONS
     * ============================================================
     */

    public CharSequence getCurrentDictSuggestion() {

        if (!dictionarySuggestions.isEmpty()) {

            return dictionarySuggestions.get(0);
        }

        return null;
    }

    public CharSequence
    getCurrentSpellcheckerRecommendedSuggestion() {

        if (hasRecommendedSpellcheckerSuggestion
                && !spellcheckerSuggestions.isEmpty()) {

            lastRecommendedSuggestion =
                    spellcheckerSuggestions.get(0);

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

        List<CharSequence> merged =
                new ArrayList<>(
                        suggestionsCount
                );

        Set<String> unique =
                new LinkedHashSet<>();

        /*
         * Normal dictionary suggestions have priority.
         */
        for (CharSequence suggestion :
                dictionarySuggestions) {

            if (suggestion == null) {
                continue;
            }

            if (unique.add(
                    suggestion.toString()
            )) {

                merged.add(
                        suggestion
                );
            }

            if (merged.size()
                    >= suggestionsCount) {

                break;
            }
        }

        /*
         * Add correction candidates if there is still room.
         */
        if (merged.size()
                < suggestionsCount) {

            for (CharSequence suggestion :
                    spellcheckerSuggestions) {

                if (suggestion == null) {
                    continue;
                }

                if (unique.add(
                        suggestion.toString()
                )) {

                    merged.add(
                            suggestion
                    );
                }

                if (merged.size()
                        >= suggestionsCount) {

                    break;
                }
            }
        }

        boolean hasRecommended =
                !dictionarySuggestions.isEmpty()
                        || hasRecommendedSpellcheckerSuggestion;

        inputView.setSuggestions(
                merged,
                hasRecommended
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
