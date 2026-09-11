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

    private final List<CharSequence> dictionarySuggestions;
    private final List<CharSequence> spellcheckerSuggestions;

    private InputView inputView;

    private boolean dictionarySuggestionsAllowed;
    private boolean spellcheckerSuggestionsAllowed;

    private boolean hasRecommendedSpellcheckerSuggestion;

    private CharSequence lastRecommendedSuggestion;

    private boolean isPaused;

    /*
     * Idioma actualmente seleccionado por el subtype
     * del teclado.
     *
     * PocketBoard soporta:
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
         * El DictionaryManager es el motor nativo de PocketBoard.
         *
         * Tanto las sugerencias como las correcciones pasan
         * por el mismo diccionario y por el mismo motor.
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
         * El motor solamente funciona en editores donde
         * tiene sentido sugerir texto.
         */
        boolean suggestionAllowedEditor =
                InputUtils.isSuggestionAllowedEditor(
                        attribute
                )
                        && !InputUtils.isNumericEditor(
                        attribute
                );

        /*
         * Las sugerencias visuales dependen de la preferencia
         * de Suggestions o de los shortcuts del diccionario.
         *
         * NO dependen de isShouldShowIme().
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
         * El corrector nativo puede trabajar cuando:
         *
         *  - Suggestions están habilitadas, o
         *  - autocorrección está habilitada.
         *
         * No utilizamos el corrector del sistema.
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

        return spellcheckerSuggestionsAllowed
                || dictionarySuggestionsAllowed;
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
         * ========================================================
         * DICTIONARY SUGGESTIONS
         * ========================================================
         *
         * DictionaryManager handles:
         *
         *  - prefix completion
         *  - dictionary lookup
         *  - correction candidates
         *
         * Therefore this is always the native PocketBoard
         * source of suggestions.
         */

        if (dictionarySuggestionsAllowed) {

            updateDictionarySuggestions(
                    composingText
            );

        } else {

            dictionarySuggestions.clear();
        }

        /*
         * ========================================================
         * SPELLCHECKER / CORRECTION
         * ========================================================
         *
         * The correction path uses the SAME DictionaryManager.
         *
         * There is no Android SpellCheckerSession here.
         */

        if (spellcheckerSuggestionsAllowed) {

            updateSpellcheckerSuggestions(
                    composingText
            );

        } else {

            spellcheckerSuggestions.clear();

            hasRecommendedSpellcheckerSuggestion =
                    false;
        }

        showSuggestions();
    }

    /*
     * ============================================================
     * EXTERNAL COMPLETIONS
     * ============================================================
     *
     * Android may call onDisplayCompletions().
     *
     * These completions must NOT replace the PocketBoard
     * dictionary/correction engine.
     *
     * The old implementation cleared our native correction
     * results here and inserted external CompletionInfo values.
     *
     * That meant an application could effectively disable or
     * replace PocketBoard's own Suggestions.
     *
     * We keep the method for API compatibility with
     * PocketBoardIME, but simply refresh the native engine.
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
     * DICTIONARY
     * ============================================================
     */

    private void updateDictionarySuggestions(
            String composingText) {

        dictionarySuggestions.clear();

        if (TextUtils.isEmpty(
                composingText
        )) {

            return;
        }

        List<String> suggestions =
                dictionaryManager.getSuggestions(
                        composingText,
                        currentLanguageTag,
                        suggestionsCount
                );

        if (suggestions == null) {
            return;
        }

        for (String suggestion :
                suggestions) {

            if (!TextUtils.isEmpty(
                    suggestion
            )) {

                dictionarySuggestions.add(
                        suggestion
                );
            }

            if (dictionarySuggestions.size()
                    >= suggestionsCount) {

                break;
            }
        }
    }

    /*
     * ============================================================
     * SPELLCHECKER / CORRECTION
     * ============================================================
     */

    private void updateSpellcheckerSuggestions(
            String composingText) {

        spellcheckerSuggestions.clear();

        hasRecommendedSpellcheckerSuggestion =
                false;

        if (TextUtils.isEmpty(
                composingText
        )) {

            return;
        }

        /*
         * A word already present in the selected dictionary
         * does not require spelling correction.
         */
        if (dictionaryManager.contains(
                composingText,
                currentLanguageTag
        )) {

            return;
        }

        /*
         * Corrections come from exactly the same native engine
         * used by dictionary suggestions.
         */
        List<String> suggestions =
                dictionaryManager.getSuggestions(
                        composingText,
                        currentLanguageTag,
                        suggestionsCount
                );

        if (suggestions == null ||
                suggestions.isEmpty()) {

            return;
        }

        Set<String> uniqueSuggestions =
                new LinkedHashSet<>();

        for (String suggestion :
                suggestions) {

            if (!TextUtils.isEmpty(
                    suggestion
            )) {

                uniqueSuggestions.add(
                        suggestion
                );
            }

            if (uniqueSuggestions.size()
                    >= suggestionsCount) {

                break;
            }
        }

        spellcheckerSuggestions.addAll(
                uniqueSuggestions
        );

        /*
         * The first correction is the recommended correction.
         */
        if (!spellcheckerSuggestions.isEmpty()) {

            lastRecommendedSuggestion =
                    spellcheckerSuggestions.get(0);

            hasRecommendedSpellcheckerSuggestion =
                    true;
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
         * Compatibility with devices/subtypes that still
         * provide getLocale().
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
         * Spanish dictionary.
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

        Set<CharSequence> unique =
                new LinkedHashSet<>();

        /*
         * Dictionary completions/candidates first.
         */
        unique.addAll(
                dictionarySuggestions
        );

        /*
         * Corrections second.
         *
         * Duplicates are removed automatically.
         */
        unique.addAll(
                spellcheckerSuggestions
        );

        /*
         * PocketBoard currently displays at most three
         * suggestions.
         */
        for (CharSequence suggestion :
                unique) {

            if (merged.size() >=
                    suggestionsCount) {

                break;
            }

            merged.add(
                    suggestion
            );
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

        if (v instanceof SuggestionView) {

            applySuggestion(
                    ((SuggestionView) v).getText()
            );
        }
    }

    private void applySuggestion(
            CharSequence text) {

        if (isPaused) {

            return;
        }

        if (!TextUtils.isEmpty(
                text
        )) {

            keyboardInputHandler.applySuggestion(
                    text,
                    pocketBoardIME
                            .getCurrentInputConnection(),
                    true
            );
        }
    }

    /*
     * ============================================================
     * INLINE SUGGESTIONS
     * ============================================================
     *
     * These methods remain temporarily because PocketBoardIME
     * still contains the old Inline Suggestions integration.
     *
     * They are NOT used by the native PocketBoard dictionary
     * or correction engine.
     *
     * They will be removed together with the corresponding
     * PocketBoardIME integration in the next stage.
     */

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
}
