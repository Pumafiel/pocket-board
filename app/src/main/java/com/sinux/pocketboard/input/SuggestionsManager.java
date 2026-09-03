package com.sinux.pocketboard.input;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.UserDictionary;
import android.database.Cursor;
import android.content.ContentResolver;
import android.net.Uri;
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
import com.sinux.pocketboard.ui.InputView;
import com.sinux.pocketboard.ui.SuggestionView;
import com.sinux.pocketboard.utils.CharacterUtils;
import com.sinux.pocketboard.utils.InputUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.autofill.inline.UiVersions;
import android.util.Size;
import android.widget.inline.InlinePresentationSpec;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import androidx.annotation.NonNull;

public class SuggestionsManager implements SuggestionView.OnClickListener,
        SpellCheckerSession.SpellCheckerSessionListener {

    private static final String AOSP_SPELLCHECKER_PACKAGE =
            "com.android.inputmethod.latin";

    private static final String AOSP_SPELLCHECKER_PACKAGE_OPENBOARD =
            "org.dslul.openboard.inputmethod.latin";

    private final PocketBoardIME pocketBoardIME;
    private final PreferencesHolder preferencesHolder;
    private final KeyboardInputHandler keyboardInputHandler;

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

    public SuggestionsManager(
            PocketBoardIME pocketBoardIME,
            KeyboardInputHandler keyboardInputHandler) {

        this.pocketBoardIME = pocketBoardIME;
        this.preferencesHolder = pocketBoardIME.getPreferencesHolder();
        this.keyboardInputHandler = keyboardInputHandler;

        suggestionsCount =
                pocketBoardIME.getResources()
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

        boolean suggestionAllowedEditor =
                InputUtils.isSuggestionAllowedEditor(attribute)
                        && !InputUtils.isNumericEditor(attribute);

        boolean suggestionsPanelVisible =
                pocketBoardIME.isShouldShowIme()
                        && preferencesHolder.isShowSuggestionsEnabled();

        /*
         * User Dictionary is OPTIONAL.
         *
         * SpellChecker is the main source of suggestions.
         */
        dictionarySuggestionsAllowed =
                suggestionAllowedEditor
                        && (suggestionsPanelVisible
                        || preferencesHolder.isDictShortcutsEnabled());

        spellcheckerSuggestionsAllowed =
                suggestionAllowedEditor
                        && (suggestionsPanelVisible
                        || preferencesHolder.isAutoCorrectionEnabled())
                        && startSpellCheckerSession(currentInputMethodSubtype);
    }

    public void onStartInputView(
            InputMethodSubtype currentInputMethodSubtype) {

        if (spellcheckerSuggestionsAllowed
                && spellCheckerSession == null) {

            startSpellCheckerSession(currentInputMethodSubtype);
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
        return dictionarySuggestionsAllowed
                || spellcheckerSuggestionsAllowed;
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

        CharSequence composingText =
                keyboardInputHandler.getCurrentComposingText();

        if (TextUtils.isEmpty(composingText)) {
            clear();
            return;
        }

        /*
         * User Dictionary is only an additional source.
         */
        if (dictionarySuggestionsAllowed) {
            updateDictionarySuggestions(composingText);
        } else {
            dictionarySuggestions.clear();
        }

        /*
         * SpellChecker is the main suggestion provider.
         */
        if (spellcheckerSuggestionsAllowed
                && spellCheckerSession != null
                && !spellCheckerSession.isSessionDisconnected()) {

            String text = composingText.toString();

            /*
             * AOSP SpellChecker often gives better word suggestions
             * when the composing word is terminated with '#'.
             */
            if (aospSpellchecker) {
                text += "#";
            }

            TextInfo[] textInfos = {
                    new TextInfo(
                            text,
                            0,
                            text.length(),
                            0,
                            0
                    )
            };

            spellCheckerSession.getSentenceSuggestions(
                    textInfos,
                    suggestionsCount
            );
        }

        /*
         * Show whatever is already available.
         */
        showSuggestions();
    }

    public void update(CompletionInfo[] completions) {

        if (isPaused) {
            return;
        }

        if (completions == null || completions.length == 0) {
            return;
        }

        spellcheckerSuggestions.clear();
        hasRecommendedSpellcheckerSuggestion = false;

        if (spellCheckerSession != null) {
            spellCheckerSession.cancel();
        }

        for (int i = 0;
             i < completions.length && i < suggestionsCount;
             i++) {

            CompletionInfo completion = completions[i];

            if (completion != null
                    && !TextUtils.isEmpty(completion.getText())) {

                spellcheckerSuggestions.add(
                        completion.getText()
                );
            }
        }

        showSuggestions();
    }

    private void updateDictionarySuggestions(
            CharSequence composingText) {

        dictionarySuggestions.clear();

        if (TextUtils.isEmpty(composingText)) {
            return;
        }

        ContentResolver resolver =
                pocketBoardIME.getContentResolver();

        Uri contentUri =
                UserDictionary.Words.CONTENT_URI;

        String[] projection = {
                UserDictionary.Words.WORD
        };

        /*
         * IMPORTANT:
         *
         * Do not use SHORTCUT here.
         *
         * We want words that START with the currently typed
         * text, not words whose shortcut equals it.
         */
        String selection =
                UserDictionary.Words.WORD + " LIKE ?";

        String[] selectionArgs = {
                composingText.toString() + "%"
        };

        String sortOrder =
                UserDictionary.Words.FREQUENCY + " DESC";

        try (Cursor cursor = resolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
        )) {

            if (cursor == null) {
                return;
            }

            int wordIndex =
                    cursor.getColumnIndex(
                            UserDictionary.Words.WORD
                    );

            if (wordIndex < 0) {
                return;
            }

            var capitalizationType =
                    CharacterUtils.getCapitalizationType(
                            composingText
                    );

            while (cursor.moveToNext()
                    && dictionarySuggestions.size()
                    < suggestionsCount) {

                String word =
                        cursor.getString(wordIndex);

                if (TextUtils.isEmpty(word)) {
                    continue;
                }

                switch (capitalizationType) {

                    case ALL_UPPER:
                        dictionarySuggestions.add(
                                word.toUpperCase()
                        );
                        break;

                    case FIRST_UPPER:
                        dictionarySuggestions.add(
                                CharacterUtils
                                        .capitalizeFirstLetter(word)
                        );
                        break;

                    case NONE:
                    default:
                        dictionarySuggestions.add(word);
                        break;
                }
            }

        } catch (Exception ignored) {
            /*
             * User Dictionary is optional.
             *
             * If it is unavailable, suggestions from the
             * SpellChecker must continue working normally.
             */
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

        if (spellcheckerSuggestionsAllowed) {
            spellcheckerSuggestionsAllowed =
                    startSpellCheckerSession(
                            inputMethodSubtype
                    );
        }
    }

    @Override
    public void onClick(View v) {
        applySuggestion(
                ((SuggestionView) v).getText()
        );
    }

    @Override
    public void onGetSuggestions(
            SuggestionsInfo[] results) {
        /*
         * We use sentence suggestions instead.
         */
    }

    @Override
    public void onGetSentenceSuggestions(
            SentenceSuggestionsInfo[] results) {

        if (!spellcheckerSuggestionsAllowed
                || results == null) {
            return;
        }

        List<CharSequence> tempSuggestions =
                new ArrayList<>(suggestionsCount);

        boolean hasRecommended = false;

        for (SentenceSuggestionsInfo ssi : results) {

            if (ssi == null) {
                continue;
            }

            for (int i = 0;
                 i < ssi.getSuggestionsCount();
                 i++) {

                SuggestionsInfo si =
                        ssi.getSuggestionsInfoAt(i);

                if (si == null) {
                    continue;
                }

                int attributes =
                        si.getSuggestionsAttributes();

                if ((attributes
                        & SuggestionsInfo
                        .RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0) {

                    hasRecommended = true;
                }

                for (int j = 0;
                     j < si.getSuggestionsCount();
                     j++) {

                    String suggestion =
                            si.getSuggestionAt(j);

                    if (!TextUtils.isEmpty(suggestion)) {

                        /*
                         * Avoid duplicates.
                         */
                        if (!tempSuggestions.contains(
                                suggestion)) {

                            tempSuggestions.add(
                                    suggestion
                            );
                        }
                    }

                    if (tempSuggestions.size()
                            >= suggestionsCount) {
                        break;
                    }
                }

                if (tempSuggestions.size()
                        >= suggestionsCount) {
                    break;
                }
            }

            if (tempSuggestions.size()
                    >= suggestionsCount) {
                break;
            }
        }

        if (!tempSuggestions.isEmpty()) {

            /*
             * Do not replace a valid recommendation with
             * the exact same recommendation repeatedly.
             */
            if (hasRecommended
                    && lastRecommendedSuggestion != null
                    && lastRecommendedSuggestion.equals(
                            tempSuggestions.get(0))) {

                hasRecommendedSpellcheckerSuggestion = false;

            } else {

                hasRecommendedSpellcheckerSuggestion =
                        hasRecommended;
            }

            spellcheckerSuggestions.clear();

            spellcheckerSuggestions.addAll(
                    tempSuggestions
            );

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

        List<CharSequence> merged =
                new ArrayList<>(suggestionsCount);

        /*
         * User Dictionary first.
         */
        for (CharSequence suggestion
                : dictionarySuggestions) {

            if (!merged.contains(suggestion)) {
                merged.add(suggestion);
            }

            if (merged.size() >= 3) {
                break;
            }
        }

        /*
         * SpellChecker after User Dictionary.
         */
        if (merged.size() < 3) {

            for (CharSequence suggestion
                    : spellcheckerSuggestions) {

                if (!merged.contains(suggestion)) {
                    merged.add(suggestion);
                }

                if (merged.size() >= 3) {
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

    private void applySuggestion(CharSequence text) {

        if (isPaused) {
            return;
        }

        if (!TextUtils.isEmpty(text)) {

            keyboardInputHandler.applySuggestion(
                    text,
                    pocketBoardIME.getCurrentInputConnection(),
                    true
            );
        }
    }

    private boolean startSpellCheckerSession(
            InputMethodSubtype inputMethodSubtype) {

        if (inputMethodSubtype == null) {
            return false;
        }

        String languageTag =
                inputMethodSubtype.getLanguageTag();

        Locale locale;

        if (!TextUtils.isEmpty(languageTag)) {
            locale = Locale.forLanguageTag(languageTag);
        } else {
            locale = inputMethodSubtype.getLocale() != null
                    ? Locale.forLanguageTag(
                            inputMethodSubtype.getLocale()
                    )
                    : Locale.getDefault();
        }

        TextServicesManager tsm =
                (TextServicesManager)
                        pocketBoardIME.getSystemService(
                                Context.TEXT_SERVICES_MANAGER_SERVICE
                        );

        if (tsm == null) {
            return false;
        }

        try {

            spellCheckerSession =
                    tsm.newSpellCheckerSession(
                            null,
                            locale,
                            this,
                            false
                    );

        } catch (Exception ignored) {

            spellCheckerSession = null;
        }

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

            try {
                spellCheckerSession.cancel();
            } catch (Exception ignored) {
            }

            try {
                spellCheckerSession.close();
            } catch (Exception ignored) {
            }

            spellCheckerSession = null;
        }
    }
}
