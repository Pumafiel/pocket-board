package com.sinux.pocketboard.input;

import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InputMethodSubtype;

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
     * Both lists are kept because the rest of PocketBoard
     * distinguishes between normal dictionary suggestions
     * and a recommended spelling correction.
     *
     * They are populated from ONE DictionaryManager query.
     */
    private final List<CharSequence> dictionarySuggestions;
    private final List<CharSequence> spellcheckerSuggestions;

    private InputView inputView;

    private boolean dictionarySuggestionsAllowed;
    private boolean spellcheckerSuggestionsAllowed;

    private boolean hasRecommendedSpellcheckerSuggestion;

    private CharSequence lastRecommendedSuggestion;

    private boolean isPaused;

    /*
     * Runtime language used by the native PocketBoard dictionary.
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

        /*
         * PocketBoard's native dictionary/correction engine.
         */
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

        /*
         * Suggestions/correction only make sense in text editors.
         */
        boolean suggestionAllowedEditor =
                InputUtils.isSuggestionAllowedEditor(
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
         * No Android SpellCheckerSession is used here.
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
         * One query only.
         *
         * DictionaryManager is responsible for deciding whether
         * the result is:
         *
         *     - prefix completion
         *     - correction candidate
         *
         * We must not ask it twice and potentially produce
         * inconsistent results.
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
         * An exact dictionary word does not need correction.
         *
         * Also, a prefix completion must not become an automatic
         * spelling correction.
         *
         * Example:
         *
         *     "hol" -> "hola"
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
     * ============================================================
     * EXTERNAL COMPLETIONS
     * ============================================================
     *
     * Android can call onDisplayCompletions().
     *
     * These CompletionInfo values are deliberately ignored.
     *
     * PocketBoard's own dictionary and correction engine are the
     * source of Suggestions.
     *
     * The method remains because PocketBoardIME still calls it.
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

            /*
             * A result that starts with the complete typed
             * sequence is a completion.
             */
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
         * All Spanish locales use the PocketBoard
         * Argentine Spanish dictionary.
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

        /*
         * Safe fallback.
         */
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
         * If there is still room, add correction candidates.
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

    /*
     * ============================================================
     * INLINE SUGGESTIONS
     * ============================================================
     *
     * These methods are kept temporarily because PocketBoardIME
     * still references them.
     *
     * They are NOT part of the native PocketBoard dictionary or
     * correction engine.
     *
     * Once PocketBoardIME is cleaned from Autofill/Inline
     * Suggestions, these methods will be removed as well.
     */

    @RequiresApi(Build.VERSION_CODES.R)
    public boolean showInlineSuggestions(
            List<InlineSuggestion> inlineSuggestions) {

        if (inputView == null) {

            return false;
        }

        return inputView.setInlineSuggestions(
                inlineSuggestions
        );
    }

    public boolean isInlineSuggestionsShown() {

        if (inputView == null) {

            return false;
        }

        return inputView.isInlineSuggestionsShown();
    }

    public void cancelInlineSuggestions() {

        if (inputView == null) {

            return;
        }

        inputView.cancelInlineSuggestions();
    }
}
