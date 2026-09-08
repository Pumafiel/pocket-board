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
                rawInputEditors.contains(
                        attribute.packageName
                );

        /*
         * Keep composing behavior identical to the original.
         *
         * Numeric mode does NOT disable composing here.
         * The numeric mapping is handled later inside
         * handleCharacter(), exactly like the original.
         */
        composingEnabled =
                suggestionsAllowed
                        && !rawInputMode;

        /*
         * Numeric editor detection.
         *
         * This only selects the numeric physical-key mapping.
         * It does not create a separate input pipeline.
         */
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

        if (composingEnabled) {
            textComposer.setLength(0);
        }

        currentSelectedText = "";

        multipressController.reset();

        keyIterationCounter = 0;

        lastKeyDownTime = 0;

        lastKeyCode =
                KeyEvent.KEYCODE_UNKNOWN;

        lastShiftEnabled = false;

        lastAltEnabled = false;

        numericInputMode = false;
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

        /*
         * Keep the original subtype behavior.
         *
         * Numeric mode is selected again when the editor starts
         * and is not converted into a separate keyboard system.
         */
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

            if (appendSpace) {

                textComposer.append(' ');

                lastKeyDownTime =
                        SystemClock.uptimeMillis();
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

            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_SPACE) {

            handleSpace(
                    inputConnection,
                    eventTime,
                    event.getRepeatCount()
            );

            lastKeyDownTime =
                    eventTime;

            lastKeyCode =
                    keyCode;

            return true;
        }

        /*
         * IMPORTANT:
         *
         * This check is intentionally retained from the original.
         *
         * The physical key still produces a Unicode character
         * (Q/W/E/etc.), while KeyMapping translates it to the
         * numeric value when numericInputMode is active.
         */
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

            lastKeyDownTime =
                    eventTime;

            lastKeyCode =
                    keyCode;

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
                        .getKeyMapping(
                                keyCode
                        );

        return keyMapping != null;
    }

    private void handleBackspace(
            InputConnection inputConnection) {

        if (inputConnection == null) {
            return;
        }

        /*
         * Do NOT create a special numeric backspace path.
         *
         * The original handler uses the same deletion mechanism
         * for numeric editors because numeric mode only changes
         * the character mapping.
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

        /*
         * ---------------------------------------------------------
         * FIRST KEY PRESS
         * ---------------------------------------------------------
         */

        if (event.getRepeatCount() == 0) {

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
             * Our multipress controller is used ONLY by the normal
             * keyboard path.
             *
             * Numeric mode keeps the original behavior:
             * mapped value is printed directly and never replaces
             * the previous character through multipress.
             */
            boolean isMultipress = false;

            if (!numericInputMode) {

                isMultipress =
                        multipressController.process(
                                event
                        );
            } else {

                multipressController.reset();
            }

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

            /*
             * THIS IS THE IMPORTANT ORIGINAL NUMERIC LOGIC.
             *
             * In numeric mode the mapped character is printed
             * instead of replacing the previous character.
             *
             * Therefore:
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
             *
             * B -> .
             * N -> ,
             *
             * No multipress replacement occurs.
             */
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
         * ---------------------------------------------------------
         * LONG PRESS
         * ---------------------------------------------------------
         *
         * Numeric mode must NOT enter the ALT/multipress long
         * press path.
         */
        if (!numericInputMode
                && !lastAltEnabled
                && eventTime - lastKeyDownTime
                > keyLongPressDuration) {

            lastAltEnabled = true;

            keyIterationCounter = 0;

            KeyMapping keyMapping =
                    keyboardMappingManager
                            .getCurrentMapping()
                            .getKeyMapping(
                                    keyCode
                            );

            if (keyMapping == null) {

                return false;
            }

            replaceLastCharacter(
                    inputConnection,
                    keyMapping.getValue(
                            lastShiftEnabled,
                            lastAltEnabled,
                            keyIterationCounter
                    )
            );

            lastKeyDownTime =
                    eventTime;

            return true;
        }

        return false;
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

        /*
         * Do not use this path for numeric input.
         *
         * This is specifically for our language multipress
         * handling: ñ, accented characters and German characters.
         */
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
            int keyCharacterCodePoint) {

        if (rawInputMode) {

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    keyCharacterCodePoint
                            )
                    ),
                    1
            );

            return;
        }

        /*
         * Keep the original punctuation handling.
         *
         * This is important for the normal keyboard path.
         */
        if (CharacterUtils
                .isPunctuationCharacter(
                        keyCharacterCodePoint
                )) {

            handlePunctuationCharacter(
                    inputConnection,
                    keyCharacterCodePoint,
                    false
            );

        } else if (composingEnabled) {

            composeNewCharacter(
                    inputConnection,
                    keyCharacterCodePoint
            );

        } else {

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    keyCharacterCodePoint
                            )
                    ),
                    1
            );
        }
    }

    private void replaceLastCharacter(
            InputConnection inputConnection,
            int keyCharacterCodePoint) {

        if (rawInputMode) {

            handleBackspace(
                    inputConnection
            );

            SystemClock.sleep(10);

            printNextCharacter(
                    inputConnection,
                    keyCharacterCodePoint
            );

            return;
        }

        if (CharacterUtils
                .isPunctuationCharacter(
                        keyCharacterCodePoint
                )) {

            handlePunctuationCharacter(
                    inputConnection,
                    keyCharacterCodePoint,
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
                        keyCharacterCodePoint
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
                                        keyCharacterCodePoint
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
                    keyCharacterCodePoint
            );

            inputConnection.endBatchEdit();
        }
    }

    private void composeNewCharacter(
            InputConnection inputConnection,
            int keyCharacterCodePoint) {

        if (inputConnection == null) {

            return;
        }

        textComposer.appendCodePoint(
                keyCharacterCodePoint
        );

        inputConnection.setComposingText(
                textComposer,
                1
        );

        /*
         * Do not leave punctuation composing.
         */
        if (!Character.isLetterOrDigit(
                keyCharacterCodePoint
        )
                && !nonLetterOrDigitExclusions.contains(
                new String(
                        Character.toChars(
                                keyCharacterCodePoint
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
            int keyCharacterCodePoint,
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
                                    keyCharacterCodePoint
                            )
                    ) + " ",
                    1
            );

        } else {

            inputConnection.commitText(
                    new String(
                            Character.toChars(
                                    keyCharacterCodePoint
                            )
                    ),
                    1
            );
        }

        inputConnection.endBatchEdit();
    }

    private boolean handleDictAndAutocorrection() {

        if (dictShortcuts) {

            if (pocketBoardIME
                    .getSuggestionsManager() != null) {

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
        }

        if (autocorrection) {

            if (pocketBoardIME
                    .getSuggestionsManager() != null) {

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
        }

        return false;
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
}
