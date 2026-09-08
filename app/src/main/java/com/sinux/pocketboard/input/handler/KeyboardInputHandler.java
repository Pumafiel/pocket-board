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

    public KeyboardInputHandler(
            PocketBoardIME pocketBoardIME) {

        this.pocketBoardIME =
                pocketBoardIME;

        this.inputMethodManager =
                pocketBoardIME.getInputMethodManager();

        this.preferencesHolder =
                pocketBoardIME.getPreferencesHolder();

        keyboardMappingManager =
                new KeyboardMappingManager(
                        pocketBoardIME,
                        inputMethodManager
                );

        textComposer =
                new StringBuilder();

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
                preferencesHolder
                        .getLongKeyPressDuration();

        layoutChangeShortcutEventRepeatCount =
                pocketBoardIME.getResources()
                        .getInteger(
                                R.integer
                                        .layout_change_shortcut_event_repeat_count
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
                attribute != null
                        && rawInputEditors.contains(
                                attribute.packageName
                        );

        /*
         * Numeric editors are handled as direct input.
         *
         * This is intentionally separate from the normal
         * composing/multipress path.
         */
        numericInputMode =
                attribute != null
                        && InputUtils.isNumericEditor(
                                attribute
                        );

        composingEnabled =
                suggestionsAllowed
                        && !rawInputMode
                        && !numericInputMode;

        /*
         * A numeric editor uses the dedicated numeric mapping.
         *
         * This is NOT the SYM/SymPad mapping.
         */
        if (numericInputMode) {

            keyboardMappingManager
                    .switchToNumericKeyboardMapping();

        } else {

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

        textComposer.setLength(0);
        currentSelectedText = "";

        multipressController.reset();

        keyIterationCounter = 0;
        lastKeyDownTime = 0;
        lastKeyCode =
                KeyEvent.KEYCODE_UNKNOWN;
        lastShiftEnabled = false;
        lastAltEnabled = false;

        lastCursorPosition =
                cursorPosition;
    }

    public void onFinishInput() {

        textComposer.setLength(0);
        currentSelectedText = "";

        multipressController.reset();

        keyIterationCounter = 0;
        lastKeyDownTime = 0;
        lastKeyCode =
                KeyEvent.KEYCODE_UNKNOWN;
        lastShiftEnabled = false;
        lastAltEnabled = false;

        numericInputMode = false;
        composingEnabled = false;
        rawInputMode = false;
    }

    public void onUpdateSelection(
            InputConnection inputConnection,
            int newSelStart,
            int newSelEnd,
            int candidatesEnd) {

        if (composingEnabled) {

            currentSelectedText = "";

            if (textComposer.length() > 0
                    && (
                    newSelStart != candidatesEnd
                            || newSelEnd != candidatesEnd
            )) {

                textComposer.setLength(0);

                if (inputConnection != null) {
                    inputConnection.finishComposingText();
                }

                multipressController.reset();

            } else if (newSelStart != newSelEnd
                    && inputConnection != null) {

                currentSelectedText =
                        inputConnection
                                .getSelectedText(0);
            }
        }

        lastCursorPosition =
                Math.min(
                        newSelStart,
                        newSelEnd
                );
    }

    public void onInputMethodSubtypeChanged(
            InputMethodSubtype inputMethodSubtype,
            boolean suggestionsAllowed) {

        /*
         * Numeric mode belongs to the current editor, not to the
         * selected language/layout.
         *
         * Therefore do not replace the numeric mapping when the
         * keyboard language changes.
         */
        if (numericInputMode) {
            multipressController.reset();
            return;
        }

        if (composingEnabled) {

            InputConnection inputConnection =
                    pocketBoardIME
                            .getCurrentInputConnection();

            if (inputConnection != null) {

                commitComposingText(
                        inputConnection
                );
            }
        }

        composingEnabled =
                suggestionsAllowed
                        && !rawInputMode;

        keyboardMappingManager
                .switchToKeyboardMapping(
                        inputMethodSubtype
                );

        multipressController.reset();
    }

    public CharSequence getCurrentComposingText() {

        if (!TextUtils.isEmpty(textComposer)) {
            return textComposer;
        }

        return currentSelectedText;
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

    public void commitEmoji(
            CharSequence emoji) {

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

            commitComposingText(
                    inputConnection
            );
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

            inputConnection.setComposingText(
                    textComposer,
                    1
            );

            if (appendSpace) {

                textComposer.append(' ');

                inputConnection.commitText(
                        textComposer,
                        1
                );

                textComposer.setLength(0);

                lastKeyDownTime =
                        SystemClock.uptimeMillis();

            } else {

                commitComposingText(
                        inputConnection
                );
            }

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

        if (inputConnection == null) {
            return false;
        }

        long eventTime =
                event.getEventTime();

        /*
         * ---------------------------------------------------------
         * BACKSPACE
         * ---------------------------------------------------------
         *
         * Numeric fields do not use composing text.
         * Delete directly through InputConnection.
         */
        if (keyCode == KeyEvent.KEYCODE_DEL) {

            multipressController.reset();

            /*
             * Numeric mode:
             *
             * Every real DEL event deletes directly.
             * We deliberately do not use sendDownUpKeyEvents()
             * because some numeric applications do not handle
             * that path consistently.
             */
            if (numericInputMode) {

                if (event.getRepeatCount() == 0
                        || eventTime - lastKeyDownTime
                        > keyLongPressDuration) {

                    handleBackspace(
                            inputConnection
                    );

                    lastKeyDownTime =
                            eventTime;

                    lastKeyCode =
                            keyCode;
                }

                return true;
            }

            /*
             * Normal keyboard behavior.
             */
            if (event.getRepeatCount() == 0) {

                handleBackspace(
                        inputConnection
                );

                lastKeyDownTime =
                        eventTime;

                lastKeyCode =
                        keyCode;

            } else if (eventTime
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
                    lastKeyDownTime =
                            eventTime;
                }
            }

            notifySuggestions();

            return true;
        }

        /*
         * ---------------------------------------------------------
         * SPACE
         * ---------------------------------------------------------
         *
         * Space is not valid in a numeric field.
         */
        if (keyCode == KeyEvent.KEYCODE_SPACE) {

            if (numericInputMode) {
                return true;
            }

            multipressController.reset();

            handleSpace(
                    inputConnection,
                    eventTime,
                    event.getRepeatCount()
            );

            lastKeyDownTime =
                    eventTime;

            lastKeyCode =
                    keyCode;

            notifySuggestions();

            return true;
        }

        /*
         * ---------------------------------------------------------
         * CHARACTER
         * ---------------------------------------------------------
         *
         * IMPORTANT:
         *
         * We obtain the character from KeyMapping, not from
         * event.getUnicodeChar().
         *
         * This is required because the physical QWERTY key can
         * represent a different character in our mapping.
         *
         * Example in numeric mapping:
         *
         *   Q -> 0
         *   W -> 1
         *   E -> 2
         *   ...
         *   B -> .
         *   N -> ,
         */
        if (handleCharacter(
                keyCode,
                event,
                inputConnection,
                shiftEnabled,
                altEnabled,
                eventTime)) {

            lastKeyDownTime =
                    eventTime;

            lastKeyCode =
                    keyCode;

            notifySuggestions();

            return true;
        }

        return false;
    }

    public boolean handleKeyUp(
            int keyCode,
            KeyEvent event) {

        /*
         * DEL and SPACE have already been completely handled
         * by handleKeyDown().
         */
        if (keyCode == KeyEvent.KEYCODE_DEL
                || keyCode == KeyEvent.KEYCODE_SPACE) {

            return true;
        }

        /*
         * Numeric mode:
         *
         * Do not reinterpret the key or use Unicode from the
         * physical keyboard here.
         *
         * If handleKeyDown() consumed the mapped character,
         * consume the corresponding key-up as well.
         *
         * This prevents the original physical key from leaking
         * a letter/symbol into the target application.
         */
        if (numericInputMode) {

            KeyMapping keyMapping =
                    keyboardMappingManager
                            .getCurrentMapping()
                            .getKeyMapping(
                                    keyCode
                            );

            return keyMapping != null;
        }

        /*
         * Normal keyboard behavior.
         *
         * Keep the original Unicode check so keys that are not
         * text characters can continue through the normal IME
         * path.
         */
        if (event.getUnicodeChar() == 0) {
            return false;
        }

        KeyMapping keyMapping =
                keyboardMappingManager
                        .getCurrentMapping()
                        .getKeyMapping(
                                keyCode
                        );

        return keyMapping != null;
    }

    private void notifySuggestions() {

        if (composingEnabled
                && pocketBoardIME
                        .getSuggestionsManager()
                        != null) {

            pocketBoardIME
                    .getSuggestionsManager()
                    .update();
        }
    }

    private void handleBackspace(
            InputConnection inputConnection) {

        if (inputConnection == null) {
            return;
        }

        /*
         * ---------------------------------------------------------
         * NUMERIC BACKSPACE
         * ---------------------------------------------------------
         *
         * No composing text exists in numeric mode.
         *
         * Delete the selected text or the previous Unicode code
         * point directly from the target application.
         */
        if (numericInputMode) {

            CharSequence selectedText =
                    inputConnection.getSelectedText(0);

            if (!TextUtils.isEmpty(selectedText)) {

                inputConnection.commitText(
                        "",
                        1
                );

            } else {

                inputConnection
                        .deleteSurroundingTextInCodePoints(
                                1,
                                0
                        );

                if (lastCursorPosition > 0) {
                    lastCursorPosition--;
                }
            }

            return;
        }

        /*
         * ---------------------------------------------------------
         * NORMAL BACKSPACE
         * ---------------------------------------------------------
         */
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

        if (inputConnection == null) {
            return;
        }

        if (rawInputMode) {

            pocketBoardIME
                    .sendDownUpKeyEvents(
                            KeyEvent.KEYCODE_DEL
                    );

            return;
        }

        if (TextUtils.isEmpty(
                inputConnection.getSelectedText(0))) {

            CharSequence str =
                    inputConnection
                            .getTextBeforeCursor(
                                    wordLookupLength,
                                    0
                            );

            if (!TextUtils.isEmpty(str)) {

                int beforeLength =
                        CharacterUtils
                                .getLastCharacterLength(
                                        str
                                );

                inputConnection
                        .deleteSurroundingText(
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
                inputConnection
                        .getTextBeforeCursor(
                                wordLookupLength,
                                0
                        );

        if (!TextUtils.isEmpty(str)) {

            int regionStart =
                    CharacterUtils
                            .getLastWordStartIndex(
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

            if (eventRepeatCount ==
                    layoutChangeShortcutEventRepeatCount) {

                handleBackspace(
                        inputConnection
                );

                pocketBoardIME
                        .switchToNextInputMethod(
                                true
                        );

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
                    inputConnection
                            .getTextBeforeCursor(
                                    3,
                                    0
                            );

            if (CharacterUtils
                    .isLetterOrDigitAndSpace(
                            lastChars
                    )) {

                inputConnection.beginBatchEdit();

                inputConnection
                        .deleteSurroundingText(
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

        if (inputConnection == null) {
            return false;
        }

        KeyMapping keyMapping =
                keyboardMappingManager
                        .getCurrentMapping()
                        .getKeyMapping(
                                keyCode
                        );

        if (keyMapping == null) {
            multipressController.reset();
            return false;
        }

        /*
         * ---------------------------------------------------------
         * NUMERIC INPUT
         * ---------------------------------------------------------
         *
         * Numeric mode is completely isolated from:
         *
         *   - composing
         *   - suggestions
         *   - multipress
         *   - SHIFT
         *   - ALT
         *
         * The numeric XML mapping may contain characters such as
         * +, -, /, *, #, (, ), ;, etc.
         *
         * They are intentionally filtered here.
         *
         * Only these characters can reach the application:
         *
         *   0 1 2 3 4 5 6 7 8 9
         *   .
         *   ,
         */
        if (numericInputMode) {

            /*
             * Android can generate repeated DOWN events when a
             * physical key is held. A numeric key must only be
             * inserted once per physical press.
             */
            if (event.getRepeatCount() != 0) {
                return true;
            }

            /*
             * Always use the numeric mapping itself.
             *
             * Do NOT use:
             *
             *   event.getUnicodeChar()
             *
             * because that would give us the physical QWERTY
             * character instead of the mapped numeric character.
             */
            int character =
                    keyMapping.getValue(
                            false,
                            false,
                            (byte) 0
                    );

            /*
             * Filter the numeric mapping.
             */
            if (!isAllowedNumericCharacter(
                    character
            )) {

                /*
                 * The key was intentionally consumed.
                 * Nothing is sent to the application.
                 */
                return true;
            }

            /*
             * Direct insertion.
             *
             * No composing text.
             * No multipress.
             * No suggestion pipeline.
             */
            inputConnection.commitText(
                    String.valueOf(
                            (char) character
                    ),
                    1
            );

            return true;
        }

        /*
         * ---------------------------------------------------------
         * NORMAL KEYBOARD / MULTIPRESS
         * ---------------------------------------------------------
         *
         * This path is intentionally kept separate so the
         * Spanish/German multipress behavior is preserved.
         */

        if (event.getRepeatCount() == 0) {

            boolean isMultipress =
                    multipressController.process(
                            event
                    );

            if (isMultipress) {

                keyIterationCounter = 0;

                if (!altEnabled) {

                    int specialCharacter =
                            getLanguageDoublePressCharacter(
                                    keyCode,
                                    shiftEnabled
                            );

                    if (specialCharacter != -1) {

                        replaceLastCharacter(
                                inputConnection,
                                specialCharacter
                        );

                        lastShiftEnabled =
                                shiftEnabled;

                        lastAltEnabled =
                                false;

                        return true;
                    }
                }

                return true;
            }

            boolean isNewKey =
                    lastKeyCode != keyCode;

            boolean isShortPress =
                    eventTime - lastKeyDownTime
                            <= keyLongPressDuration;

            boolean keyIterationModeEnabled;

            if (keyMapping.hasAdditionalValues(
                    lastAltEnabled
            )
                    && !isNewKey
                    && isShortPress) {

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

            if (!keyIterationModeEnabled) {

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
         * ---------------------------------------------------------
         * LONG PRESS
         * ---------------------------------------------------------
         */

        if (eventTime - lastKeyDownTime
                > keyLongPressDuration) {

            multipressController
                    .markLongPress();

            lastAltEnabled = true;
            keyIterationCounter = 0;

            replaceLastCharacter(
                    inputConnection,
                    keyMapping.getValue(
                            lastShiftEnabled,
                            true,
                            (byte) 0
                    )
            );

            lastKeyDownTime =
                    eventTime;

            return true;
        }

        return false;
    }

    /**
     * Numeric fields accept only:
     *
     *   0 - 9
     *   .
     *   ,
     *
     * Everything else in the numeric physical-key mapping is
     * ignored.
     */
    private boolean isAllowedNumericCharacter(
            int character) {

        return
                (character >= '0'
                        && character <= '9')
                        || character == '.'
                        || character == ',';
    }

    /**
     * Returns the language-specific character associated with
     * a double press of a physical key.
     *
     * This is used ONLY by the normal keyboard path.
     *
     * Numeric mode never reaches this method.
     *
     * ALT values are deliberately ignored here.
     */
    private int getLanguageDoublePressCharacter(
            int keyCode,
            boolean shiftEnabled) {

        KeyMapping keyMapping =
                keyboardMappingManager
                        .getCurrentMapping()
                        .getKeyMapping(
                                keyCode
                        );

        if (keyMapping == null) {
            return -1;
        }

        if (!keyMapping.hasAdditionalValues(false)) {
            return -1;
        }

        int character =
                keyMapping.getValue(
                        shiftEnabled,
                        false,
                        (byte) 1
                );

        if (!isLanguageSpecificCharacter(
                character
        )) {

            return -1;
        }

        return character;
    }

    private boolean isLanguageSpecificCharacter(
            int character) {

        return Character.isLetter(character)
                && !Character.isDigit(character);
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
                    String.valueOf(
                            (char) character
                    ),
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
                inputConnection
                        .getTextBeforeCursor(
                                2,
                                0
                        );

        if (!TextUtils.isEmpty(beforeCursor)) {

            int lastCharacterLength =
                    CharacterUtils
                            .getLastCharacterLength(
                                    beforeCursor
                            );

            inputConnection
                    .deleteSurroundingText(
                            lastCharacterLength,
                            0
                    );

            inputConnection.commitText(
                    String.valueOf(
                            (char) character
                    ),
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
        }
    }

    private boolean handleDictAndAutocorrection() {

        if (!composingEnabled
                || textComposer.length() == 0) {

            return false;
        }

        if (dictShortcuts
                && handleDictShortcut()) {

            return true;
        }

        if (autocorrection
                && handleAutocorrection()) {

            return true;
        }

        return false;
    }

    private boolean handleDictShortcut() {
        return false;
    }

    private boolean handleAutocorrection() {
        return false;
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
                String.valueOf(
                        (char) character
                ),
                1
        );
    }
}
