package com.sinux.pocketboard.input.handler;

import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.sinux.pocketboard.PocketBoardIME;
import com.sinux.pocketboard.R;
import com.sinux.pocketboard.input.SuggestionsManager;
import com.sinux.pocketboard.input.mapping.KeyMapping;
import com.sinux.pocketboard.input.mapping.KeyboardMappingManager;
import com.sinux.pocketboard.preferences.PreferencesHolder;
import com.sinux.pocketboard.utils.CharacterUtils;
import com.sinux.pocketboard.utils.InputUtils;

import java.util.Arrays;
import java.util.List;

public class KeyboardInputHandler {

    private final PocketBoardIME pocketBoardIME;
    private final InputMethodManager inputMethodManager;
    private final PreferencesHolder preferencesHolder;
    private final KeyboardMappingManager keyboardMappingManager;

    private final StringBuilder textComposer;
    private final String nonLetterOrDigitExclusions;
    private final int wordLookupLength;
    private final long keyLongPressDuration;
    private final int layoutChangeShortcutEventRepeatCount;

    private final MultipressController multipressController;

    private boolean composingEnabled;
    private boolean numericInputMode;
    private boolean layoutChangeShortcut;
    private boolean doubleSpacePeriod;
    private boolean dictShortcuts;
    private boolean autocorrection;

    private CharSequence currentSelectedText;
    private byte keyIterationCounter;
    private long lastKeyDownTime;
    private int lastKeyCode;
    private boolean lastShiftEnabled;
    private boolean lastAltEnabled;
    private int lastCursorPosition;

    private final List<String> rawInputEditors;
    private boolean rawInputMode;

    public KeyboardInputHandler(PocketBoardIME pocketBoardIME) {

        this.pocketBoardIME = pocketBoardIME;
        this.inputMethodManager =
                pocketBoardIME.getInputMethodManager();

        this.preferencesHolder =
                pocketBoardIME.getPreferencesHolder();

        keyboardMappingManager =
                new KeyboardMappingManager(
                        pocketBoardIME,
                        inputMethodManager
                );

        textComposer = new StringBuilder();

        nonLetterOrDigitExclusions =
                pocketBoardIME.getResources()
                        .getString(
                                R.string.non_letter_or_digit_exclusions
                        );

        wordLookupLength =
                pocketBoardIME.getResources()
                        .getInteger(
                                R.integer.word_lookup_length
                        );

        keyLongPressDuration =
                preferencesHolder.getLongKeyPressDuration();

        layoutChangeShortcutEventRepeatCount =
                pocketBoardIME.getResources()
                        .getInteger(
                                R.integer.layout_change_shortcut_event_repeat_count
                        );

        rawInputEditors =
                Arrays.asList(
                        pocketBoardIME.getResources()
                                .getStringArray(
                                        R.array.raw_input_editors
                                )
                );

        multipressController =
                new MultipressController();
    }

    public void onStartInput(
            EditorInfo attribute,
            boolean suggestionsAllowed,
            int cursorPosition) {

        rawInputMode =
                rawInputEditors.contains(
                        attribute.packageName
                );

        composingEnabled =
                suggestionsAllowed && !rawInputMode;

        if (InputUtils.isNumericEditor(attribute)) {

            numericInputMode = true;

            keyboardMappingManager
                    .switchToNumericKeyboardMapping();

        } else {

            numericInputMode = false;

            keyboardMappingManager
                    .switchToKeyboardMapping(
                            inputMethodManager
                                    .getCurrentInputMethodSubtype()
                    );
        }

        layoutChangeShortcut =
                preferencesHolder
                        .isLayoutChangeShortcutEnabled();

        doubleSpacePeriod =
                preferencesHolder
                        .isDoubleSpacePeriodEnabled();

        dictShortcuts =
                composingEnabled
                        && preferencesHolder
                        .isDictShortcutsEnabled();

        autocorrection =
                composingEnabled
                        && preferencesHolder
                        .isAutoCorrectionEnabled();

        if (composingEnabled) {
            textComposer.setLength(0);
        }

        multipressController.reset();

        keyIterationCounter = 0;
        lastKeyDownTime = 0;
        lastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        lastShiftEnabled = false;
        lastAltEnabled = false;

        lastCursorPosition = cursorPosition;
    }

    public void onFinishInput() {

        if (composingEnabled) {
            textComposer.setLength(0);
        }

        multipressController.reset();

        keyIterationCounter = 0;
        lastKeyDownTime = 0;
        lastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        lastShiftEnabled = false;
        lastAltEnabled = false;
    }

    public void onUpdateSelection(
            InputConnection inputConnection,
            int newSelStart,
            int newSelEnd,
            int candidatesEnd) {

        if (composingEnabled) {

            currentSelectedText = "";

            if (textComposer.length() > 0
                    && (newSelStart != candidatesEnd
                    || newSelEnd != candidatesEnd)) {

                textComposer.setLength(0);

                if (inputConnection != null) {
                    inputConnection.finishComposingText();
                }

                multipressController.reset();

            } else if (newSelStart != newSelEnd
                    && inputConnection != null) {

                currentSelectedText =
                        inputConnection.getSelectedText(0);
            }
        }

        lastCursorPosition =
                Math.min(newSelStart, newSelEnd);
    }

    public void onInputMethodSubtypeChanged(
            InputMethodSubtype inputMethodSubtype,
            boolean suggestionsAllowed) {

        if (composingEnabled) {

            InputConnection inputConnection =
                    pocketBoardIME
                            .getCurrentInputConnection();

            if (inputConnection != null) {
                commitComposingText(inputConnection);
            }
        }

        composingEnabled =
                suggestionsAllowed && !rawInputMode;

        multipressController.reset();

        keyboardMappingManager
                .switchToKeyboardMapping(
                        inputMethodSubtype
                );
    }

    public CharSequence getCurrentComposingText() {

        return TextUtils.isEmpty(textComposer)
                ? currentSelectedText
                : textComposer;
    }

    public boolean isInRawInputMode() {
        return rawInputMode;
    }

    public void resetComposing(
            InputConnection inputConnection) {

        if (inputConnection == null) {

            textComposer.setLength(0);
            multipressController.reset();

            return;
        }

        if (textComposer.length() > 0) {

            textComposer.setLength(0);
            inputConnection.finishComposingText();
        }

        multipressController.reset();
        keyIterationCounter = 0;
    }

    public void commitEmoji(CharSequence emoji) {

        if (emoji == null) {
            return;
        }

        InputConnection inputConnection =
                pocketBoardIME
                        .getCurrentInputConnection();

        if (inputConnection == null) {
            return;
        }

        if (composingEnabled) {
            commitComposingText(inputConnection);
        }

        inputConnection.commitText(
                emoji,
                1
        );

        multipressController.reset();
        keyIterationCounter = 0;
    }

    public void applySuggestion(
            CharSequence text,
            InputConnection inputConnection,
            boolean appendSpace) {

        if (inputConnection == null
                || TextUtils.isEmpty(text)) {
            return;
        }

        if (composingEnabled) {

            textComposer.setLength(0);
            textComposer.append(text);

            if (appendSpace) {

                textComposer.append(' ');

                lastKeyDownTime =
                        SystemClock.uptimeMillis();

                lastKeyCode =
                        KeyEvent.KEYCODE_SPACE;
            }

            commitComposingText(
                    inputConnection
            );

        } else {

            inputConnection.commitText(
                    text,
                    1
            );
        }

        multipressController.reset();
        keyIterationCounter = 0;
    }

    public boolean handleKeyDown(
            int keyCode,
            KeyEvent event,
            InputConnection inputConnection,
            boolean shiftEnabled,
            boolean altEnabled) {

        long eventTime =
                event.getEventTime();

        if (keyCode == KeyEvent.KEYCODE_DEL) {

            multipressController.reset();

            if (!composingEnabled
                    || event.getRepeatCount() == 0) {

                handleBackspace(
                        inputConnection
                );

                lastKeyDownTime = eventTime;
                lastKeyCode = keyCode;

            } else {

                if (composingEnabled
                        && eventTime
                        - lastKeyDownTime
                        > keyLongPressDuration) {

                    handleBackspace(
                            inputConnection
                    );

                    boolean hadComposingText =
                            textComposer.length() > 0;

                    inputConnection.beginBatchEdit();

                    textComposer.setLength(0);

                    inputConnection.commitText(
                            "",
                            1
                    );

                    inputConnection.endBatchEdit();

                    if (hadComposingText) {
                        lastKeyDownTime = eventTime;
                    }
                }
            }

            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_SPACE) {

            multipressController.reset();

            handleSpace(
                    inputConnection,
                    eventTime,
                    event.getRepeatCount()
            );

            lastKeyDownTime = eventTime;
            lastKeyCode = keyCode;

            return true;
        }

        if (event.getUnicodeChar() == 0) {
            return false;
        }

        if (handleCharacter(
                keyCode,
                event,
                inputConnection,
                shiftEnabled,
                altEnabled,
                eventTime)) {

            lastKeyDownTime = eventTime;
            lastKeyCode = keyCode;

            return true;
        }

        return false;
    }

    public boolean handleKeyUp(
            int keyCode,
            KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_DEL
                || keyCode == KeyEvent.KEYCODE_SPACE) {

            return true;
        }

        if (event.getUnicodeChar() == 0) {
            return false;
        }

        KeyMapping keyMapping =
                keyboardMappingManager
                        .getCurrentMapping()
                        .getKeyMapping(keyCode);

        return keyMapping != null;
    }

    private void handleBackspace(
            InputConnection inputConnection) {

        if (composingEnabled) {

            int composingLength =
                    textComposer.length();

            if (composingLength > 1) {

                textComposer.setLength(
                        textComposer.length()
                                - CharacterUtils
                                .getLastCharacterLength(
                                        textComposer
                                )
                );

                inputConnection.setComposingText(
                        textComposer,
                        1
                );

            } else if (composingLength > 0) {

                textComposer.setLength(0);

                inputConnection.commitText(
                        "",
                        1
                );

            } else {

                inputConnection.beginBatchEdit();

                deleteLastCharacter(
                        inputConnection
                );

                findAndComposeLastWord(
                        inputConnection
                );

                inputConnection.endBatchEdit();
            }

        } else {

            deleteLastCharacter(
                    inputConnection
            );
        }
    }

    private void deleteLastCharacter(
            InputConnection inputConnection) {

        if (rawInputMode) {

            pocketBoardIME.sendDownUpKeyEvents(
                    KeyEvent.KEYCODE_DEL
            );

            return;
        }

        if (TextUtils.isEmpty(
                inputConnection.getSelectedText(0))) {

            CharSequence str =
                    inputConnection.getTextBeforeCursor(
                            wordLookupLength,
                            0
                    );

            if (!TextUtils.isEmpty(str)) {

                int beforeLength =
                        CharacterUtils
                                .getLastCharacterLength(
                                        str
                                );

                inputConnection.deleteSurroundingText(
                        beforeLength,
                        0
                );

                lastCursorPosition -=
                        beforeLength;
            }

        } else {

            inputConnection.commitText(
                    "",
                    1
            );
        }
    }

    private void findAndComposeLastWord(
            InputConnection inputConnection) {

        CharSequence str =
                inputConnection.getTextBeforeCursor(
                        wordLookupLength,
                        0
                );

        if (!TextUtils.isEmpty(str)) {

            int regionStart =
                    CharacterUtils.getLastWordStartIndex(
                            str,
                            nonLetterOrDigitExclusions
                    );

            int regionEnd =
                    str.length();

            if (regionStart != regionEnd) {

                textComposer.append(
                        str.subSequence(
                                regionStart,
                                regionEnd
                        )
                );

                int composingLength =
                        textComposer.length();

                inputConnection.finishComposingText();

                inputConnection.setComposingRegion(
                        lastCursorPosition
                                - composingLength,
                        lastCursorPosition
                );
            }
        }
    }

    private void handleSpace(
            InputConnection inputConnection,
            long eventTime,
            int eventRepeatCount) {

        if (layoutChangeShortcut) {

            if (eventRepeatCount
                    == layoutChangeShortcutEventRepeatCount) {

                handleBackspace(
                        inputConnection
                );

                pocketBoardIME
                        .switchToNextInputMethod(true);

                return;

            } else if (eventRepeatCount > 0) {

                return;
            }
        }

        if (doubleSpacePeriod
                && eventTime - lastKeyDownTime
                <= keyLongPressDuration) {

            if (composingEnabled) {

                if (!handleDictAndAutocorrection()) {
                    commitComposingText(
                            inputConnection
                    );
                }
            }

            CharSequence lastChars =
                    inputConnection.getTextBeforeCursor(
                            3,
                            0
                    );

            if (CharacterUtils
                    .isLetterOrDigitAndSpace(lastChars)) {

                inputConnection.beginBatchEdit();

                inputConnection.deleteSurroundingText(
                        1,
                        0
                );

                inputConnection.commitText(
                        ". ",
                        1
                );

                inputConnection.endBatchEdit();

            } else {

                inputConnection.commitText(
                        " ",
                        1
                );
            }

        } else {

            if (composingEnabled) {

                if (!handleDictAndAutocorrection()) {
                    commitComposingText(
                            inputConnection
                    );
                }
            }

            inputConnection.commitText(
                    " ",
                    1
            );
        }
    }

    private boolean handleCharacter(
            int keyCode,
            KeyEvent event,
            InputConnection inputConnection,
            boolean shiftEnabled,
            boolean altEnabled,
            long eventTime) {

        if (event.getRepeatCount() == 0) {

            KeyMapping keyMapping =
                    keyboardMappingManager
                            .getCurrentMapping()
                            .getKeyMapping(keyCode);

            if (keyMapping == null) {

                multipressController.reset();
                return false;
            }

            boolean isMultipress =
                    multipressController.process(
                            event
                    );

            /*
             * DOUBLE PRESS ACCENT MODE
             *
             * N -> n
             * N N -> ñ
             *
             * Shift + N N -> Ñ
             */
            if (!numericInputMode
                    && !altEnabled
                    && isSpanishAccentKey(keyCode)
                    && isMultipress) {

                int character;

                if (shiftEnabled) {

                    character =
                            keyMapping.getValue(
                                    false,
                                    true,
                                    (byte) 2
                            );

                } else {

                    character =
                            keyMapping.getValue(
                                    false,
                                    true,
                                    (byte) 1
                            );
                }

                replaceLastCharacter(
                        inputConnection,
                        character
                );

                keyIterationCounter = 0;

                return true;
            }

            boolean isNewKey =
                    lastKeyCode != keyCode;

            boolean isShortPress =
                    eventTime - lastKeyDownTime
                            <= keyLongPressDuration;

            boolean keyIterationModeEnabled;

            if (keyMapping.hasAdditionalValues(
                    lastAltEnabled)
                    && !isNewKey
                    && isShortPress
                    && isMultipress) {

                keyIterationModeEnabled = true;
                keyIterationCounter++;

            } else {

                keyIterationModeEnabled = false;
                keyIterationCounter = 0;

                lastShiftEnabled =
                        shiftEnabled;

                lastAltEnabled =
                        altEnabled;
            }

            if (!keyIterationModeEnabled
                    || numericInputMode) {

                printNextCharacter(
                        inputConnection,
                        keyMapping.getValue(
                                lastShiftEnabled,
                                lastAltEnabled,
                                keyIterationCounter
                        )
                );

            } else {

                replaceLastCharacter(
                        inputConnection,
                        keyMapping.getValue(
                                lastShiftEnabled,
                                lastAltEnabled,
                                keyIterationCounter
                        )
                );
            }

            return true;
        }

        /*
         * LONG PRESS
         */
        if (!numericInputMode
                && !lastAltEnabled
                && eventTime - lastKeyDownTime
                > keyLongPressDuration) {

            multipressController.markLongPress();

            lastAltEnabled = true;
            keyIterationCounter = 0;

            KeyMapping keyMapping =
                    keyboardMappingManager
                            .getCurrentMapping()
                            .getKeyMapping(keyCode);

            if (keyMapping == null) {
                return false;
            }

            replaceLastCharacter(
                    inputConnection,
                    keyMapping.getValue(
                            lastShiftEnabled,
                            lastAltEnabled,
                            (byte) 0
                    )
            );

            lastKeyDownTime = eventTime;

            return true;
        }

        return false;
    }

    private boolean isSpanishAccentKey(
            int keyCode) {

        return keyCode == KeyEvent.KEYCODE_A
                || keyCode == KeyEvent.KEYCODE_E
                || keyCode == KeyEvent.KEYCODE_I
                || keyCode == KeyEvent.KEYCODE_O
                || keyCode == KeyEvent.KEYCODE_U
                || keyCode == KeyEvent.KEYCODE_N;
    }

    private void printNextCharacter(
            InputConnection inputConnection,
            int character) {

        if (composingEnabled) {

            composeNewCharacter(
                    inputConnection,
                    character
            );

        } else {

            inputConnection.commitText(
                    String.valueOf((char) character),
                    1
            );
        }
    }

    private void composeNewCharacter(
            InputConnection inputConnection,
            int character) {

        if (inputConnection == null) {
            return;
        }

        if (textComposer.length() == 0) {

            textComposer.append(
                    (char) character
            );

            inputConnection.setComposingText(
                    textComposer,
                    1
            );

            return;
        }

        if (dictShortcuts || autocorrection) {

            textComposer.append(
                    (char) character
            );

            inputConnection.setComposingText(
                    textComposer,
                    1
            );

            /*
             * Ask SuggestionsManager for updated suggestions.
             */
            pocketBoardIME
                    .getSuggestionsManager()
                    .update();

            return;
        }

        commitComposingText(
                inputConnection
        );

        textComposer.append(
                (char) character
        );

        inputConnection.setComposingText(
                textComposer,
                1
        );
    }

    private void replaceLastCharacter(
            InputConnection inputConnection,
            int character) {

        if (inputConnection == null) {
            return;
        }

        if (textComposer.length() > 0) {

            textComposer.setLength(
                    textComposer.length()
                            - CharacterUtils
                            .getLastCharacterLength(
                                    textComposer
                            )
            );

            textComposer.append(
                    (char) character
            );

            inputConnection.setComposingText(
                    textComposer,
                    1
            );

            return;
        }

        CharSequence beforeCursor =
                inputConnection.getTextBeforeCursor(
                        2,
                        0
                );

        if (!TextUtils.isEmpty(beforeCursor)) {

            int lastCharacterLength =
                    CharacterUtils
                            .getLastCharacterLength(
                                    beforeCursor
                            );

            inputConnection.deleteSurroundingText(
                    lastCharacterLength,
                    0
            );

            inputConnection.commitText(
                    String.valueOf((char) character),
                    1
            );
        }
    }

    private void commitComposingText(
            InputConnection inputConnection) {

        if (inputConnection == null) {
            return;
        }

        if (textComposer.length() > 0) {

            inputConnection.commitText(
                    textComposer,
                    1
            );

            textComposer.setLength(0);

            pocketBoardIME
                    .getSuggestionsManager()
                    .clear();
        }
    }

    private boolean handleDictAndAutocorrection() {

        if (!composingEnabled
                || textComposer.length() == 0) {

            return false;
        }

        if (dictShortcuts) {

            if (handleDictShortcut()) {
                return true;
            }
        }

        if (autocorrection) {

            if (handleAutocorrection()) {
                return true;
            }
        }

        return false;
    }

    private boolean handleDictShortcut() {

        SuggestionsManager suggestionsManager =
                pocketBoardIME
                        .getSuggestionsManager();

        CharSequence suggestion =
                suggestionsManager
                        .getCurrentDictSuggestion();

        if (TextUtils.isEmpty(suggestion)) {
            return false;
        }

        /*
         * Only use a dictionary suggestion when it actually
         * differs from the typed word.
         */
        if (suggestion.toString()
                .equalsIgnoreCase(
                        textComposer.toString()
                )) {

            return false;
        }

        replaceCurrentComposingWord(
                suggestion
        );

        return true;
    }

    private boolean handleAutocorrection() {

        SuggestionsManager suggestionsManager =
                pocketBoardIME
                        .getSuggestionsManager();

        CharSequence suggestion =
                suggestionsManager
                        .getCurrentSpellcheckerRecommendedSuggestion();

        if (TextUtils.isEmpty(suggestion)) {
            return false;
        }

        /*
         * If the spellchecker suggestion is exactly what the user
         * typed, there is nothing to autocorrect.
         */
        if (suggestion.toString()
                .equalsIgnoreCase(
                        textComposer.toString()
                )) {

            return false;
        }

        replaceCurrentComposingWord(
                suggestion
        );

        return true;
    }

    private void replaceCurrentComposingWord(
            CharSequence replacement) {

        InputConnection inputConnection =
                pocketBoardIME
                        .getCurrentInputConnection();

        if (inputConnection == null
                || TextUtils.isEmpty(replacement)) {

            return;
        }

        /*
         * textComposer is currently the word being edited.
         *
         * Replace that composing word with the selected
         * dictionary/spellchecker suggestion.
         */
        textComposer.setLength(0);
        textComposer.append(replacement);

        inputConnection.setComposingText(
                textComposer,
                1
        );

        /*
         * Commit the corrected word.
         */
        commitComposingText(
                inputConnection
        );
    }

    private void handleEnter(
            InputConnection inputConnection) {

        if (composingEnabled) {

            if (!handleDictAndAutocorrection()) {

                commitComposingText(
                        inputConnection
                );
            }
        }

        inputConnection.sendKeyEvent(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_ENTER
                )
        );

        inputConnection.sendKeyEvent(
                new KeyEvent(
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_ENTER
                )
        );
    }

    private void handleTab(
            InputConnection inputConnection) {

        if (composingEnabled) {

            if (!handleDictAndAutocorrection()) {

                commitComposingText(
                        inputConnection
                );
            }
        }

        inputConnection.sendKeyEvent(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_TAB
                )
        );

        inputConnection.sendKeyEvent(
                new KeyEvent(
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_TAB
                )
        );
    }

    private void handlePunctuation(
            InputConnection inputConnection,
            int character) {

        if (composingEnabled) {

            if (!handleDictAndAutocorrection()) {

                commitComposingText(
                        inputConnection
                );
            }
        }

        inputConnection.commitText(
                String.valueOf((char) character),
                1
        );
    }
}
