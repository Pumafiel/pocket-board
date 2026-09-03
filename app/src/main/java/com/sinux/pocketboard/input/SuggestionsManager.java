package com.sinux.pocketboard.input;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.UserDictionary;
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

        CharSequence composingText =
                keyboardInputHandler.getCurrentComposingText();

        if (TextUtils.isEmpty(composingText)) {
            clear();
            return;
        }

        if (dictionarySuggestionsAllowed) {
            updateDictionarySuggestions(composingText);
        } else {
            dictionarySuggestions.clear();
        }

        if (spellcheckerSuggestionsAllowed) {

            if (spellCheckerSession != null
                    && !spellCheckerSession.isSessionDisconnected()) {

                String text = composingText.toString();

                /*
                 * AOSP spellchecker often gives better results
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
        }
    }

    public void update(CompletionInfo[] completions) {

        if (isPaused) {
            return;
        }

        spellcheckerSuggestions.clear();
        hasRecommendedSpellcheckerSuggestion = false;

        if (completions != null) {

            if (spellCheckerSession != null) {
                spellCheckerSession.cancel();
            }

            for (int i = 0;
                 i < completions.length && i < suggestionsCount;
                 i++) {

                CompletionInfo completion = completions[i];

                if (completion != null
                        && !TextUtils.isEmpty(completion.getText())) {

                    addUniqueSuggestion(
                            spellcheckerSuggestions,
                            completion.getText()
                    );
                }
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
         * UserDictionary is optional.
         *
         * If it is empty or unavailable, spellchecker
         * suggestions continue working normally.
         */
        String selection =
                UserDictionary.Words.SHORTCUT + " LIKE ?";

        String[] selectionArgs = {
                String.valueOf(composingText)
        };

        String sortOrder =
                UserDictionary.Words.FREQUENCY + " DESC";

        try (Cursor cursor = resolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                sortOrder)) {

            if (cursor != null) {

                int wordIndex =
                        cursor.getColumnIndex(
                                UserDictionary.Words.WORD
                        );

                if (wordIndex >= 0) {

                    var capitalizationType =
                            CharacterUtils.getCapitalizationType(
                                    composingText
                            );

                    while (cursor.moveToNext()) {

                        String word =
                                cursor.getString(wordIndex);

                        if (TextUtils.isEmpty(word)) {
                            continue;
                        }

                        String formattedWord;

                        switch (capitalizationType) {

                            case ALL_UPPER:
                                formattedWord =
                                        word.toUpperCase();

                                break;

                            case FIRST_UPPER:
                                formattedWord =
                                        CharacterUtils
                                                .capitalizeFirstLetter(word);

                                break;

                            case NONE:
                            default:
                                formattedWord = word;
                                break;
                        }

                        addUniqueSuggestion(
                                dictionarySuggestions,
                                formattedWord
                        );

                        if (dictionarySuggestions.size()
                                >= suggestionsCount) {
                            break;
                        }
                    }
                }
            }

        } catch (Exception ignored) {
            /*
             * UserDictionary is not required.
             */
        }

        showSuggestions();
    }

    private void addUniqueSuggestion(
            List<CharSequence> list,
            CharSequence suggestion) {

        if (TextUtils.isEmpty(suggestion)) {
            return;
        }

        String value = suggestion.toString();

        for (CharSequence existing : list) {

            if (existing != null
                    && value.equalsIgnoreCase(
                    existing.toString())) {

                return;
            }
        }

        list.add(suggestion);
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

                hasRecommended |=
                        (si.getSuggestionsAttributes()
                                & SuggestionsInfo
                                .RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS)
                                != 0;

                for (int j = 0;
                     j < si.getSuggestionsCount();
                     j++) {

                    String suggestion =
                            si.getSuggestionAt(j);

                    if (!TextUtils.isEmpty(suggestion)) {

                        addUniqueSuggestion(
                                tempSuggestions,
                                suggestion
                        );

                        if (tempSuggestions.size()
                                >= suggestionsCount) {
                            break;
                        }
                    }
                }
            }
        }

        if (!tempSuggestions.isEmpty()) {

            spellcheckerSuggestions.clear();
            spellcheckerSuggestions.addAll(
                    tempSuggestions
            );

            if (hasRecommended) {

                if (lastRecommendedSuggestion != null
                        && lastRecommendedSuggestion.equals(
                        tempSuggestions.get(0))) {

                    hasRecommendedSpellcheckerSuggestion =
                            false;

                } else {

                    hasRecommendedSpellcheckerSuggestion =
                            true;
                }

            } else {

                hasRecommendedSpellcheckerSuggestion =
                        false;
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

        List<CharSequence> merged =
                new ArrayList<>(suggestionsCount);

        /*
         * Dictionary suggestions first.
         */
        for (CharSequence suggestion :
                dictionarySuggestions) {

            if (merged.size() >= suggestionsCount) {
                break;
            }

            addUniqueSuggestion(
                    merged,
                    suggestion
            );
        }

        /*
         * Then spellchecker suggestions.
         */
        for (CharSequence suggestion :
                spellcheckerSuggestions) {

            if (merged.size() >= suggestionsCount) {
                break;
            }

            addUniqueSuggestion(
                    merged,
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
            locale = Locale.getDefault();
        }

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
                    spellCheckerSession.getSpellChecker() != null
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
