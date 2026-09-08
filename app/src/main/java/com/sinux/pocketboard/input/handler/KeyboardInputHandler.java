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
                pocketBoardIME
                        .getInputMethodManager();

        this.preferencesHolder =
                pocketBoardIME
                        .getPreferencesHolder();

        this.keyboardMappingManager =
                new KeyboardMappingManager(
                        pocketBoardIME,
                        inputMethodManager
                );

        this.textComposer =
                new StringBuilder();

        this.nonLetterOrDigitExclusions =
                pocketBoardIME
                        .getResources()
                        .getString(
                                R.string
                                        .non_letter_or_digit_exclusions
                        );

        this.wordLookupLength =
                pocketBoardIME
                        .getResources()
                        .getInteger(
                                R.integer
                                        .word_lookup_length
                        );

        this.keyLongPressDuration =
                preferencesHolder
                        .getLongKeyPressDuration();

        this.layoutChangeShortcutEventRepeatCount =
                pocketBoardIME
                        .getResources()
                        .getInteger(
                                R.integer
                                        .layout_change_shortcut_event_repeat_count
                        );

        this.rawInputEditors =
                Arrays.asList(
                        pocketBoardIME
                                .getResources()
                                .getStringArray(
                                        R.array
                                                .raw_input_editors
                                )
                );

        this.multipressController =
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

        numericInputMode =
                attribute != null
                        && InputUtils.isNumericEditor(
                        attribute
                );

        if (numericInputMode) {

            composingEnabled = false;

            keyboardMappingManager
                    .switchToNumericKeyboardMapping();

        } else {

            composingEnabled =
                    suggestionsAllowed
                            && !rawInputMode;

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
                keyIterationCounter = 0;

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

        if (numericInputMode) {

            composingEnabled = false;

            textComposer.setLength(0);
            multipressController.reset();

            keyIterationCounter = 0;

            lastKeyCode =
                    KeyEvent.KEYCODE_UNKNOWN;

            lastShiftEnabled = false;
            lastAltEnabled = false;

            keyboardMappingManager
                    .switchToNumericKeyboardMapping();

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
            keyIterationCounter = 0;

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

        if (inputConnection == null
                || event == null) {

            return false;
        }

        long eventTime =
                event.getEventTime();

        /*
         * =========================================================
         * BACKSPACE
         * =========================================================
         */

        if (keyCode == KeyEvent.KEYCODE_DEL) {

            if (numericInputMode) {

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
                keyIterationCounter = 0;

                return true;
            }

            if (!composingEnabled
                    || event.getRepeatCount() == 0) {

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
         * =========================================================
         * SPACE
         * =========================================================
         */

        if (keyCode == KeyEvent.KEYCODE_SPACE) {

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
         * =========================================================
         * CHARACTER
         * =========================================================
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

        if (event == null) {
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_DEL
                || keyCode == KeyEvent.KEYCODE_SPACE) {

            return true;
        }

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

        if (TextUtils.isEmpty(str)) {
            return;
        }

        int regionStart =
                CharacterUtils
                        .getLastWordStartIndex(
                                str,
                                nonLetterOrDigitExclusions
                        );

        int regionEnd =
                str.length();

        if (regionStart == regionEnd) {
            return;
        }

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

    private void handleSpace(
            InputConnection inputConnection,
            long eventTime,
            int eventRepeatCount) {

        if (numericInputMode) {
            return;
        }

        if (layoutChangeShortcut) {

            if (eventRepeatCount
                    == layoutChangeShortcutEventRepeatCount) {

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

    /**
     * Mapping is the single source of truth for characters.
     *
     * Normal:
     *     value
     *
     * Multipress:
     *     Add[0], Add[1], ...
     *
     * SHIFT:
     *     shiftValue
     *
     * ALT / long press:
     *     Alt value
     *
     * ALT + SHIFT:
     *     Alt shiftValue
     */
    private boolean handleCharacter(
            int keyCode,
            KeyEvent event,
            InputConnection inputConnection,
            boolean shiftEnabled,
            boolean altEnabled,
            long eventTime) {

        if (inputConnection == null
                || event == null) {

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
            keyIterationCounter = 0;

            return false;
        }

        /*
         * =========================================================
         * NUMERIC MODE
         * =========================================================
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
         * =========================================================
         * NEW PHYSICAL KEY PRESS
         * =========================================================
         */

        if (event.getRepeatCount() == 0) {

            boolean continuesMultipress =
                    multipressController.process(
                            event
                    );

            boolean sameKey =
                    lastKeyCode == keyCode;

            boolean shortPress =
                    eventTime - lastKeyDownTime
                            <= keyLongPressDuration;

            /*
             * An explicit ALT modifier always selects the ALT
             * mapping. It must never enter the normal multipress
             * sequence.
             */
            if (altEnabled) {

                keyIterationCounter = 0;

                lastShiftEnabled =
                        shiftEnabled;

                lastAltEnabled = true;

                int character =
                        keyMapping.getValue(
                                shiftEnabled,
                                true,
                                (byte) 0
                        );

                printNextCharacter(
                        inputConnection,
                        character
                );

                return true;
            }

            /*
             * A second/later short press of the same physical key
             * selects the next XML <Add> entry.
             */
            if (continuesMultipress
                    && sameKey
                    && shortPress
                    && keyMapping
                    .hasAdditionalValues(false)) {

                int additionalIndex =
                        multipressController
                                .getAdditionalValueIndex();

                if (additionalIndex >= 0) {

                    keyIterationCounter =
                            (byte) (
                                    additionalIndex + 1
                            );

                    int character =
                            keyMapping.getAdditionalValue(
                                    shiftEnabled,
                                    (byte) (
                                            additionalIndex + 1
                                    )
                            );

                    replaceLastCharacter(
                            inputConnection,
                            character
                    );

                    lastShiftEnabled =
                            shiftEnabled;

                    lastAltEnabled = false;

                    return true;
                }
            }

            /*
             * New sequence or a key without <Add> values.
             */
            keyIterationCounter = 0;

            lastShiftEnabled =
                    shiftEnabled;

            lastAltEnabled = false;

            int character =
                    keyMapping.getValue(
                            shiftEnabled,
                            false,
                            (byte) 0
                    );

            printNextCharacter(
                    inputConnection,
                    character
            );

            return true;
        }

        /*
         * =========================================================
         * LONG PRESS / ALT
         * =========================================================
         */

        if (eventTime - lastKeyDownTime
                > keyLongPressDuration) {

            multipressController
                    .markLongPress();

            keyIterationCounter = 0;

            lastAltEnabled = true;

            int character =
                    keyMapping.getValue(
                            lastShiftEnabled,
                            true,
                            (byte) 0
                    );

            replaceLastCharacter(
                    inputConnection,
                    character
            );

            lastKeyDownTime =
                    eventTime;

            return true;
        }

        return false;
    }

    /**
     * Numeric mappings are filtered by the numeric editor mode.
     *
     * The physical mapping itself remains defined in XML.
     */
    private boolean isAllowedNumericCharacter(
            int character) {

        return
                (character >= '0'
                        && character <= '9')
                        || character == '.'
                        || character == ',';
    }

    private void printNextCharacter(
            InputConnection inputConnection,
            int character) {

        if (inputConnection == null) {
            return;
        }

        if (numericInputMode) {

            if (isAllowedNumericCharacter(
                    character
            )) {

                inputConnection.commitText(
                        new String(
                                Character.toChars(
                                        character
                                )
                        ),
                        1
                );
            }

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

        if (inputConnection == null) {
            return;
        }

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
}
