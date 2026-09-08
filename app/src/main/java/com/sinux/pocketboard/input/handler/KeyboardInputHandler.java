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
         * Keep the original PocketBoard behavior:
         *
         * composingEnabled is independent from numericInputMode.
         * Numeric mode itself controls how the mapped character
         * is delivered.
         */
        composingEnabled =
                suggestionsAllowed
                        && !rawInputMode;

        /*
         * Numeric editors use the dedicated numeric mapping.
         *
         * This is the physical numeric mapping, NOT the SYM
         * mapping and NOT the current language mapping.
         */
        if (attribute != null
                && InputUtils.isNumericEditor(
                attribute
        )) {

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

                    inputConnection
                            .finishComposingText();
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
         * IMPORTANT:
         *
         * A numeric editor has already selected the numeric
         * KeyboardMapping in onStartInput().
         *
         * Do NOT replace it with the Spanish/German mapping when
         * Android reports a subtype/language change.
         *
         * This was the critical problem in the previous version.
         */
        if (numericInputMode) {

            composingEnabled = false;

            textComposer.setLength(0);
            multipressController.reset();

            keyIterationCounter = 0;
            lastKeyCode =
                    KeyEvent.KEYCODE_UNKNOWN;

            lastShiftEnabled = false;
            lastAltEnabled = false;

            return;
        }

        /*
         * Normal keyboard mode:
         * preserve the original subtype behavior.
         */
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

        keyIterationCounter = 0;
        lastKeyCode =
                KeyEvent.KEYCODE_UNKNOWN;

        lastShiftEnabled = false;
        lastAltEnabled = false;
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

            inputConnection
                    .finishComposingText();
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
         */

        if (keyCode == KeyEvent.KEYCODE_DEL) {

            if (numericInputMode) {

                /*
                 * Numeric fields use direct deletion.
                 *
                 * This keeps Backspace working without sending a
                 * synthetic hardware event to the target app.
                 */
                if (event.getRepeatCount() == 0
                        || eventTime
                        - lastKeyDownTime
                        > keyLongPressDuration) {

                    handleBackspace(
                            inputConnection
                    );

                    lastKeyDownTime =
                            eventTime;

                    lastKeyCode =
                            keyCode;
                }

                multipressController.reset();

                return true;
            }

            /*
             * Original normal-keyboard behavior.
             */
            if (!composingEnabled
                    || event.getRepeatCount() == 0) {

                handleBackspace(
                        inputConnection
                );

                lastKeyDownTime =
                        eventTime;

                lastKeyCode =
                        keyCode;

            } else {

                if (eventTime
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
            }

            notifySuggestions();

            return true;
        }

        /*
         * ---------------------------------------------------------
         * SPACE
         * ---------------------------------------------------------
         */

        if (keyCode == KeyEvent.KEYCODE_SPACE) {

            /*
             * Numeric fields never receive spaces.
             */
            if (numericInputMode) {
                return true;
            }

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
         * Keep the original Unicode gate for normal input.
         *
         * Numeric mode bypasses this check because the physical
         * key's Unicode value is not necessarily the mapped value.
         */
        if (event.getUnicodeChar() == 0
                && !numericInputMode) {

            return false;
        }

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

        if (keyCode == KeyEvent.KEYCODE_DEL
                || keyCode == KeyEvent.KEYCODE_SPACE) {

            return true;
        }

        /*
         * Numeric mapping must not depend on the physical
         * keyboard Unicode character.
         */
        if (event.getUnicodeChar() == 0
                && !numericInputMode) {

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
         */

        if (numericInputMode) {

            CharSequence selectedText =
                    inputConnection
                            .getSelectedText(0);

            if (!TextUtils.isEmpty(
                    selectedText
            )) {

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
                inputConnection.getSelectedText(0)
        )) {

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

        if (numericInputMode) {
            return;
        }

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
         * NUMERIC MODE
         * ---------------------------------------------------------
         *
         * This is the important part.
         *
         * The physical key is translated through the numeric
         * KeyboardMapping:
         *
         * Q -> 0
         * W -> 1
         * E -> 2
         * R -> 3
         * S -> 4
         * D -> 5
         * F -> 6
         * X -> 7
         * C -> 8
         * V -> 9
         * B -> .
         * N -> ,
         *
         * Other mapped symbols are consumed but never sent.
         *
         * No multipress.
         * No ALT.
         * No SHIFT.
         */
        if (numericInputMode) {

            if (event.getRepeatCount() != 0) {
                return true;
            }

            int character =
                    keyMapping.getValue(
                            false,
                            false,
                            (byte) 0
                    );

            if (!isAllowedNumericCharacter(
                    character
            )) {

                return true;
            }

            /*
             * Numeric input is committed directly.
             *
             * This is deliberately independent from the
             * composing/multipress system.
             */
            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    character
                            )
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
         * Everything below remains outside numeric mode.
         *
         * This preserves the Spanish/German multipress and ALT
         * behavior.
         */

        if (event.getRepeatCount() == 0) {

            boolean isMultipress =
                    multipressController.process(
                            event
                    );

            if (isMultipress) {

                keyIterationCounter = 0;

                /*
                 * Double press language characters.
                 *
                 * This is where the additional Spanish/German
                 * characters such as ñ and accented/special
                 * characters remain supported.
                 */
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
         * LONG PRESS / ALT
         * ---------------------------------------------------------
         *
         * Numeric mode never reaches this section.
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

    private boolean isAllowedNumericCharacter(
            int character) {

        return
                (character >= '0'
                        && character <= '9')
                        || character == '.'
                        || character == ',';
    }

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

        if (inputConnection == null) {
            return;
        }

        /*
         * Numeric mode is already handled before this method,
         * but keeping this guard makes the output path safe.
         */
        if (numericInputMode) {

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    character
                            )
                    ),
                    1
            );

            return;
        }

        if (rawInputMode) {

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    character
                            )
                    ),
                    1
            );

            return;
        }

        if (CharacterUtils
                .isPunctuationCharacter(
                        character
                )) {

            handlePunctuationCharacter(
                    inputConnection,
                    character,
                    false
            );

        } else if (composingEnabled) {

            composeNewCharacter(
                    inputConnection,
                    character
            );

        } else {

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    character
                            )
                    ),
                    1
            );
        }
    }

    private void replaceLastCharacter(
            InputConnection inputConnection,
            int character) {

        if (inputConnection == null) {
            return;
        }

        if (rawInputMode) {

            handleBackspace(
                    inputConnection
            );

            SystemClock.sleep(10);

            printNextCharacter(
                    inputConnection,
                    character
            );

            return;
        }

        if (CharacterUtils
                .isPunctuationCharacter(
                        character
                )) {

            handlePunctuationCharacter(
                    inputConnection,
                    character,
                    true
            );

        } else if (composingEnabled) {

            if (textComposer.length() > 0) {

                textComposer.setLength(
                        textComposer.length()
                                - CharacterUtils
                                .getLastCharacterLength(
                                        textComposer
                                )
                );

                composeNewCharacter(
                        inputConnection,
                        character
                );

            } else {

                inputConnection.beginBatchEdit();

                inputConnection
                        .deleteSurroundingTextInCodePoints(
                                1,
                                0
                        );

                inputConnection.commitText(
                        new String(
                                Character.toChars(
                                        character
                                )
                        ),
                        1
                );

                findAndComposeLastWord(
                        inputConnection
                );

                inputConnection.endBatchEdit();
            }

        } else {

            inputConnection.beginBatchEdit();

            inputConnection
                    .deleteSurroundingTextInCodePoints(
                            1,
                            0
                    );

            printNextCharacter(
                    inputConnection,
                    character
            );

            inputConnection.endBatchEdit();
        }
    }

    private void composeNewCharacter(
            InputConnection inputConnection,
            int character) {

        if (inputConnection == null) {
            return;
        }

        textComposer.appendCodePoint(
                character
        );

        inputConnection.setComposingText(
                textComposer,
                1
        );

        if (!Character.isLetterOrDigit(
                character
        )
                && !nonLetterOrDigitExclusions.contains(
                new String(
                        Character.toChars(
                                character
                        )
                )
        )) {

            commitComposingText(
                    inputConnection
            );
        }
    }

    private void handlePunctuationCharacter(
            InputConnection inputConnection,
            int character,
            boolean removeLastCharacter) {

        inputConnection.beginBatchEdit();

        if (composingEnabled) {

            commitComposingText(
                    inputConnection
            );
        }

        if (removeLastCharacter) {

            inputConnection
                    .deleteSurroundingTextInCodePoints(
                            1,
                            0
                    );
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

            inputConnection
                    .deleteSurroundingText(
                            1,
                            0
                    );

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    character
                            )
                    ) + " ",
                    1
            );

        } else {

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    character
                            )
                    ),
                    1
            );
        }

        inputConnection.endBatchEdit();
    }

    private void commitComposingText(
            InputConnection inputConnection) {

        if (inputConnection != null
                && textComposer.length() > 0) {

            inputConnection.commitText(
                    textComposer,
                    1
            );

            textComposer.setLength(0);
        }
    }

    private boolean handleDictAndAutocorrection() {

        if (!composingEnabled) {
            return false;
        }

        if (dictShortcuts) {

            CharSequence dictSuggestion =
                    pocketBoardIME
                            .getSuggestionsManager()
                            .getCurrentDictSuggestion();

            if (dictSuggestion != null) {

                applySuggestion(
                        dictSuggestion,
                        pocketBoardIME
                                .getCurrentInputConnection(),
                        false
                );

                return true;
            }
        }

        if (autocorrection) {

            CharSequence recommendedSuggestion =
                    pocketBoardIME
                            .getSuggestionsManager()
                            .getCurrentSpellcheckerRecommendedSuggestion();

            if (recommendedSuggestion != null) {

                applySuggestion(
                        recommendedSuggestion,
                        pocketBoardIME
                                .getCurrentInputConnection(),
                        false
                );

                return true;
            }
        }

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
