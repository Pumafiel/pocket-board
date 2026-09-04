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
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * Idioma actualmente seleccionado por el teclado.
     *
     * PocketBoard soporta:
     *
     * es-AR -> español
     * en    -> inglés
     * de    -> alemán
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

        /*
         * ESTE es el único motor de diccionario/corrección
         * utilizado por PocketBoard.
         */
        this.dictionaryManager =
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

    /**
     * Se llama al comenzar una nueva entrada.
     *
     * El idioma se obtiene directamente del subtype
     * seleccionado por el teclado.
     */
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

        /*
         * Las suggestions normales vienen de nuestro diccionario.
         */
        dictionarySuggestionsAllowed =
                suggestionAllowedEditor
                        && (
                        suggestionsPanelVisible
                                || preferencesHolder.isDictShortcutsEnabled()
                );

        /*
         * La autocorrección también utiliza NUESTRO diccionario.
         *
         * No se crea SpellCheckerSession.
         * No se consulta AOSP.
         * No se consulta OpenBoard.
         */
        spellcheckerSuggestionsAllowed =
                suggestionAllowedEditor
                        && (
                        suggestionsPanelVisible
                                || preferencesHolder.isAutoCorrectionEnabled()
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

    /**
     * Actualiza las suggestions utilizando exclusivamente
     * DictionaryManager.
     */
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
         * POCKETBOARD DICTIONARY
         * =========================================================
         *
         * Tanto las suggestions como las correcciones salen
         * del mismo diccionario correspondiente al idioma actual.
         */
        if (dictionarySuggestionsAllowed
                || spellcheckerSuggestionsAllowed) {

            updateDictionarySuggestions(
                    composingText
            );
        } else {
            dictionarySuggestions.clear();
        }

        /*
         * La lista de correcciones internas se mantiene separada
         * para que la lógica existente de autocorrección pueda
         * utilizarla.
         */
        if (spellcheckerSuggestionsAllowed) {

            updateSpellcheckerSuggestions(
                    composingText
            );

        } else {

            spellcheckerSuggestions.clear();

            hasRecommendedSpellcheckerSuggestion = false;
            lastRecommendedSuggestion = null;
        }

        showSuggestions();
    }

    /**
     * Mantiene compatibilidad con CompletionInfo.
     *
     * Si Android entrega completions, se pueden mostrar, pero
     * esto NO reemplaza nuestro diccionario para la corrección
     * ortográfica normal.
     */
    public void update(CompletionInfo[] completions) {

        if (isPaused) {
            return;
        }

        if (completions != null) {

            for (
                    int i = 0;
                    i < completions.length
                            && i < suggestionsCount;
                    i++
            ) {

                if (completions[i] == null) {
                    continue;
                }

                CharSequence text =
                        completions[i].getText();

                if (!TextUtils.isEmpty(text)
                        && !spellcheckerSuggestions.contains(text)) {

                    spellcheckerSuggestions.add(text);
                }
            }
        }

        showSuggestions();
    }

    /**
     * Obtiene suggestions desde DictionaryManager.
     */
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

    /**
     * Genera las correcciones ortográficas usando exactamente
     * el mismo diccionario y el mismo idioma seleccionado.
     *
     * Por ejemplo:
     *
     * es-AR:
     *   "csa" -> "casa"
     *
     * en:
     *   "helo" -> "hello"
     *
     * de:
     *   "hause" -> "haus"
     */
    private void updateSpellcheckerSuggestions(
            String composingText) {

        spellcheckerSuggestions.clear();

        if (TextUtils.isEmpty(composingText)) {
            hasRecommendedSpellcheckerSuggestion = false;
            return;
        }

        /*
         * Si la palabra exacta existe, no necesitamos
         * autocorrección.
         */
        if (dictionaryManager.contains(
                composingText,
                currentLanguageTag
        )) {

            hasRecommendedSpellcheckerSuggestion = false;
            return;
        }

        /*
         * Pedimos al MISMO DictionaryManager las correcciones.
         */
        List<String> corrections =
                dictionaryManager.getSuggestions(
                        composingText,
                        currentLanguageTag,
                        suggestionsCount
                );

        if (corrections == null ||
                corrections.isEmpty()) {

            hasRecommendedSpellcheckerSuggestion = false;
            return;
        }

        spellcheckerSuggestions.addAll(
                corrections
        );

        /*
         * La primera corrección se considera recomendada.
         */
        hasRecommendedSpellcheckerSuggestion =
                !spellcheckerSuggestions.isEmpty();

        if (!spellcheckerSuggestions.isEmpty()) {

            lastRecommendedSuggestion =
                    spellcheckerSuggestions.get(0);
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

    /**
     * Cambia inmediatamente el diccionario cuando cambia
     * el idioma del teclado.
     */
    public void onInputMethodSubtypeChanged(
            InputMethodSubtype inputMethodSubtype) {

        updateCurrentLanguage(
                inputMethodSubtype
        );

        /*
         * Importante:
         *
         * No reiniciamos ningún SpellCheckerSession porque
         * PocketBoard ya no depende del servicio de Android.
         */
        clear();

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

    /**
     * Combina las suggestions del diccionario y las
     * correcciones propias de PocketBoard.
     *
     * Máximo: 3 suggestions visibles.
     */
    private void showSuggestions() {

        if (inputView == null) {
            return;
        }

        List<CharSequence> merged =
                Stream.concat(
                        dictionarySuggestions.stream(),
                        spellcheckerSuggestions.stream()
                )
                .distinct()
                .limit(3)
                .collect(Collectors.toList());

        boolean hasRecommended =
                hasRecommendedSpellcheckerSuggestion;

        /*
         * Si no hay corrección ortográfica, las suggestions
         * normales siguen mostrándose sin marcarse como
         * autocorrección.
         */
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

    /**
     * Obtiene el idioma del subtype del teclado.
     */
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

                currentLanguageTag = "en";
                return;
            }
        }

        Locale locale =
                Locale.forLanguageTag(languageTag);

        if (TextUtils.isEmpty(
                locale.getLanguage()
        )) {

            currentLanguageTag = "en";
            return;
        }

        currentLanguageTag =
                normalizeDictionaryLanguage(
                        locale
                );
    }

    /**
     * Convierte cualquier variante compatible al diccionario
     * que realmente existe en PocketBoard.
     *
     * Español:
     *   es
     *   es-AR
     *   es-ES
     *   es-MX
     *   etc.
     *       -> es-AR
     *
     * Inglés:
     *   en
     *   en-US
     *   en-GB
     *   etc.
     *       -> en
     *
     * Alemán:
     *   de
     *   de-DE
     *   de-AT
     *   etc.
     *       -> de
     */
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

        /*
         * Idioma no soportado actualmente.
         */
        return "en";
    }
}
