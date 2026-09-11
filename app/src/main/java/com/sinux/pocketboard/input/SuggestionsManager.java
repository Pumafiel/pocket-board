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

    public void onStartInput(
            EditorInfo attribute,
            InputMethodSubtype currentInputMethodSubtype) {

        /*
         * Limpiamos cualquier resultado anterior
         * antes de configurar el nuevo editor.
         */
        disallowSuggestions();

        updateCurrentLanguage(
                currentInputMethodSubtype
        );

        /*
         * El editor debe permitir sugerencias y no ser
         * un campo numérico.
         */
        boolean suggestionAllowedEditor =
                InputUtils.isSuggestionAllowedEditor(
                        attribute
                )
                        && !InputUtils.isNumericEditor(
                        attribute
                );

        /*
         * IMPORTANTE:
         *
         * Suggestions NO dependen de
         * PocketBoardIME.isShouldShowIme().
         *
         * isShouldShowIme() controla la visibilidad del
         * teclado cuando existe un teclado físico y no debe
         * decidir si el motor de Suggestions funciona.
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
         * El corrector nativo de PocketBoard utiliza
         * DictionaryManager.
         *
         * No utilizamos:
         *
         *     TextServicesManager
         *     SpellCheckerSession
         *     corrector del sistema
         *
         * Si el usuario habilita autocorrección, el motor
         * puede trabajar aunque el panel visual de
         * Suggestions esté oculto.
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

        /*
         * Volvemos a obtener el idioma cuando se crea
         * la vista de entrada.
         */
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

    public void clear() {

        dictionarySuggestions.clear();

        spellcheckerSuggestions.clear();

        hasRecommendedSpellcheckerSuggestion =
                false;

        lastRecommendedSuggestion =
                null;

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
         * SUGERENCIAS DEL DICCIONARIO DE POCKETBOARD
         * =========================================================
         */

        if (dictionarySuggestionsAllowed) {

            updateDictionarySuggestions(
                    composingText
            );

        } else {

            dictionarySuggestions.clear();
        }

        /*
         * =========================================================
         * CORRECTOR ORTOGRÁFICO NATIVO DE POCKETBOARD
         * =========================================================
         *
         * El corrector utiliza exactamente el mismo
         * DictionaryManager que las Suggestions.
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
     * Android puede enviar completions externas.
     *
     * Las conservamos como una fuente adicional,
     * pero el motor propio de PocketBoard no depende
     * de ellas.
     */
    public void update(
            CompletionInfo[] completions) {

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

                CompletionInfo completion =
                        completions[i];

                if (completion == null) {
                    continue;
                }

                CharSequence text =
                        completion.getText();

                if (!TextUtils.isEmpty(text)) {

                    spellcheckerSuggestions.add(
                            text
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
         * Si la palabra ya existe en el diccionario
         * seleccionado, no necesitamos corregirla.
         */
        if (dictionaryManager.contains(
                composingText,
                currentLanguageTag
        )) {

            return;
        }

        /*
         * Las correcciones salen del MISMO motor
         * y diccionario utilizado por Suggestions.
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
         * La primera corrección es la recomendada.
         */
        if (!spellcheckerSuggestions.isEmpty()) {

            lastRecommendedSuggestion =
                    spellcheckerSuggestions.get(0);

            hasRecommendedSpellcheckerSuggestion =
                    true;
        }
    }

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

    public void onInputMethodSubtypeChanged(
            InputMethodSubtype inputMethodSubtype) {

        updateCurrentLanguage(
                inputMethodSubtype
        );

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

    /*
     * ============================================================
     * INLINE SUGGESTIONS
     * ============================================================
     *
     * Estos métodos se conservan TEMPORALMENTE para que
     * PocketBoardIME siga compilando durante esta primera etapa.
     *
     * En el siguiente archivo eliminaremos la integración
     * Inline/Autofill completa.
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

        List<CharSequence> merged =
                new ArrayList<>(
                        suggestionsCount
                );

        Set<CharSequence> unique =
                new LinkedHashSet<>();

        /*
         * Primero las sugerencias normales del diccionario.
         */
        unique.addAll(
                dictionarySuggestions
        );

        /*
         * Después las correcciones ortográficas.
         */
        unique.addAll(
                spellcheckerSuggestions
        );

        /*
         * El panel de PocketBoard muestra como máximo
         * tres resultados.
         */
        for (CharSequence suggestion :
                unique) {

            if (merged.size() >= 3) {
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

        /*
         * Compatibilidad con dispositivos/subtypes
         * que todavía proporcionan getLocale().
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

                languageTag = "en";
            }
        }

        Locale locale =
                Locale.forLanguageTag(
                        languageTag
                );

        if (TextUtils.isEmpty(
                locale.getLanguage()
        )) {

            locale = Locale.ENGLISH;
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
         * Fallback seguro.
         */
        return "en";
    }
}
