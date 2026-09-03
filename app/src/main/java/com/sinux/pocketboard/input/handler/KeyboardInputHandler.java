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
import com.sinux.pocketboard.utils.InputUtils;
import com.sinux.pocketboard.utils.CharacterUtils;

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
        this.inputMethodManager = pocketBoardIME.getInputMethodManager();
        this.preferencesHolder = pocketBoardIME.getPreferencesHolder();

        keyboardMappingManager =
                new KeyboardMappingManager(
                        pocketBoardIME,
                        inputMethodManager
                );

        textComposer = new StringBuilder();

        nonLetterOrDigitExclusions =
                pocketBoardIME.getResources()
                        .getString(R.string.non_letter_or_digit_exclusions);

        wordLookupLength =
                pocketBoardIME.getResources()
                        .getInteger(R.integer.word_lookup_length);

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
                                .getStringArray(R.array.raw_input_editors)
                );

        multipressController = new MultipressController();
    }

    public void onStartInput(
            EditorInfo attribute,
            boolean suggestionsAllowed,
            int cursorPosition) {

        rawInputMode =
                rawInputEditors.contains(attribute.packageName);

        composingEnabled =
                suggestionsAllowed && !rawInputMode;

        if (InputUtils.isNumericEditor(attribute)) {
            numericInputMode = true;
            keyboardMappingManager.switchToNumericKeyboardMapping();
        } else {
            numericInputMode = false;
            keyboardMappingManager.switchToKeyboardMapping(
                    inputMethodManager.getCurrentInputMethodSubtype()
            );
        }

        layoutChangeShortcut =
                preferencesHolder.isLayoutChangeShortcutEnabled();

        doubleSpacePeriod =
                preferencesHolder.isDoubleSpacePeriodEnabled();

        dictShortcuts =
                composingEnabled
                        & preferencesHolder.isDictShortcutsEnabled();

        autocorrection =
                composingEnabled
                        && preferencesHolder.isAutoCorrectionEnabled();

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

            if (textComposer.length() > 0 &&
                    (newSelStart != candidatesEnd ||
                            newSelEnd != candidatesEnd)) {

                textComposer.setLength(0);

                if (inputConnection != null) {
                    inputConnection.finishComposingText();
                }

                multipressController.reset();

            } else if (newSelStart != newSelEnd) {

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
                    pocketBoardIME.getCurrentInputConnection();

            if (inputConnection != null) {
                commitComposingText(inputConnection);
            }
        }

        composingEnabled = suggestionsAllowed;

        multipressController.reset();

        keyboardMappingManager.switchToKeyboardMapping(
                inputMethodSubtype
        );
    }

    public CharSequence getCurrentComposingText() {
        return TextUtils.isEmpty(textComposer)
                ? currentSelectedText
                : textComposer;
    }

    public void applySuggestion(
            CharSequence text,
            InputConnection inputConnection,
            boolean appendSpace) {

        if (inputConnection != null) {

            if (composingEnabled) {

                textComposer.setLength(0);
                textComposer.append(text);

                if (appendSpace) {
                    textComposer.append(' ');
                    lastKeyDownTime =
                            SystemClock.uptimeMillis();
                }

                commitComposingText(inputConnection);

            } else {

                inputConnection.commitText(text, 1);
            }
        }

        multipressController.reset();
    }

    public boolean handleKeyDown(
            int keyCode,
            KeyEvent event,
            InputConnection inputConnection,
            boolean shiftEnabled,
            boolean altEnabled) {

        long eventTime = event.getEventTime();

        if (keyCode == KeyEvent.KEYCODE_DEL) {

            multipressController.reset();

            if (!composingEnabled ||
                    event.getRepeatCount() == 0) {

                handleBackspace(inputConnection);

                lastKeyDownTime = eventTime;
                lastKeyCode = keyCode;

            } else {

                if (composingEnabled &&
                        eventTime - lastKeyDownTime >
                                keyLongPressDuration) {

                    handleBackspace(inputConnection);

                    boolean hadComposingText =
                            textComposer.length() > 0;

                    inputConnection.beginBatchEdit();

                    textComposer.setLength(0);

                    inputConnection.commitText("", 1);

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

        if (keyCode == KeyEvent.KEYCODE_DEL ||
                keyCode == KeyEvent.KEYCODE_SPACE) {
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
                inputConnection.commitText("", 1);

            } else {

                inputConnection.beginBatchEdit();

                deleteLastCharacter(inputConnection);
                findAndComposeLastWord(inputConnection);

                inputConnection.endBatchEdit();
            }

        } else {

            deleteLastCharacter(inputConnection);
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
                                .getLastCharacterLength(str);

                inputConnection.deleteSurroundingText(
                        beforeLength,
                        0
                );

                lastCursorPosition -= beforeLength;
            }

        } else {

            inputConnection.commitText("", 1);
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

            int regionEnd = str.length();

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
                        lastCursorPosition - composingLength,
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

                handleBackspace(inputConnection);

                pocketBoardIME.switchToNextInputMethod(true);

                return;

            } else if (eventRepeatCount > 0) {

                return;
            }
        }

        if (doubleSpacePeriod &&
                eventTime - lastKeyDownTime <=
                        keyLongPressDuration) {

            if (composingEnabled) {

                if (!handleDictAndAutocorrection()) {
                    commitComposingText(inputConnection);
                }
            }

            CharSequence lastChars =
                    inputConnection.getTextBeforeCursor(
                            3,
                            0
                    );

            if (CharacterUtils.isLetterOrDigitAndSpace(
                    lastChars)) {

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
                    commitComposingText(inputConnection);
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
         * =========================================================
         * FIRST / SECOND NORMAL KEY PRESS
         * =========================================================
         */
        if (event.getRepeatCount() == 0) {

            KeyMapping keyMapping =
                    keyboardMappingManager
                            .getCurrentMapping()
                            .getKeyMapping(keyCode);

            if (keyMapping == null) {
                multipressController.reset();
                return false;
            }

            /*
             * Ask the controller whether this is the second
             * short press of the same physical key.
             */
            boolean isMultipress =
                    multipressController.process(event);

            /*
             * =====================================================
             * SPANISH DOUBLE PRESS
             *
             * Mapping from keyboard_mapping_es_es.xml:
             *
             * N:
             *     ALT[0] = ,
             *     ALT[1] = ñ
             *     ALT[2] = Ñ
             *
             * A:
             *     ALT[0] = @
             *     ALT[1] = á
             *     ALT[2] = Á
             *
             * etc.
             *
             * Desired behavior:
             *
             * N             -> n
             * N N           -> ñ
             * Shift + N N   -> Ñ
             * Alt + N       -> ,
             * Long press N  -> ,
             *
             * IMPORTANT:
             *
             * Alt is deliberately excluded here.
             * Alt selects ALT[0], which is the same character
             * used by long press.
             * =====================================================
             */
            if (!numericInputMode &&
                    isSpanishAccentKey(keyCode) &&
                    !altEnabled &&
                    isMultipress) {

                int character;

                if (shiftEnabled) {

                    /*
                     * ALT[2] = uppercase special character.
                     *
                     * N -> Ñ
                     * A -> Á
                     * E -> É
                     * I -> Í
                     * O -> Ó
                     * U -> Ú
                     */
                    character =
                            keyMapping.getValue(
                                    false,
                                    true,
                                    (byte) 2
                            );

                } else {

                    /*
                     * ALT[1] = lowercase special character.
                     *
                     * N -> ñ
                     * A -> á
                     * E -> é
                     * I -> í
                     * O -> ó
                     * U -> ú
                     */
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

                /*
                 * Do not let the normal additional-value cycling
                 * process this second press.
                 */
                lastAltEnabled = false;

                return true;
            }

            /*
             * =====================================================
             * NORMAL / ALT FIRST PRESS
             * =====================================================
             *
             * Normal:
             *     N -> n
             *
             * Alt:
             *     Alt + N -> ,
             *
             * The mapping itself supplies these values.
             */
            boolean isNewKey =
                    lastKeyCode != keyCode;

            boolean isShortPress =
                    eventTime - lastKeyDownTime <=
                            keyLongPressDuration;

            boolean keyIterationModeEnabled;

            /*
             * Existing PocketBoard additional-value mechanism.
             *
             * Spanish double-press was already consumed above.
             * Other mappings retain their original behavior.
             */
            if (keyMapping.hasAdditionalValues(lastAltEnabled) &&
                    !isNewKey &&
                    isShortPress &&
                    isMultipress) {

                keyIterationModeEnabled = true;
                keyIterationCounter++;

            } else {

                keyIterationModeEnabled = false;
                keyIterationCounter = 0;

                lastShiftEnabled = shiftEnabled;
                lastAltEnabled = altEnabled;
            }

            if (!keyIterationModeEnabled ||
                    numericInputMode) {

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
         * =========================================================
         * LONG PRESS
         * =========================================================
         *
         * Long press uses exactly the same ALT[0] value as
         * pressing ALT + the key.
         *
         * N:
         *
         *     Alt + N       -> ,
         *     Long press N  -> ,
         *
         * This is intentionally independent from double press.
         * =========================================================
         */
        if (!numericInputMode &&
                !lastAltEnabled &&
                (eventTime - lastKeyDownTime >
                        keyLongPressDuration)) {

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

            /*
             * ALT[0] is the same value selected by ALT + key.
             */
            replaceLastCharacter(
                    inputConnection,
                    keyMapping.getValue(
                            false,
                            true,
                            (byte) 0
                    )
            );

            lastKeyDownTime = eventTime;

            return true;
        }

        return false;
    }

    private boolean isSpanishAccentKey(int keyCode) {

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

        if (textComposer.length() == 0) {

            textComposer.append((char) character);

            inputConnection.setComposingText(
                    textComposer,
                    1
            );

            return;
        }

        if (dictShortcuts || autocorrection) {

            textComposer.append((char) character);

            inputConnection.setComposingText(
                    textComposer,
                    1
            );

        } else {

            inputConnection.commitText(
                    String.valueOf((char) character),
                    1
            );
        }
    }

    private void replaceLastCharacter(
            InputConnection inputConnection,
            int character) {

        if (textComposer.length() > 0) {

            textComposer.setLength(
                    textComposer.length()
                            - CharacterUtils
                            .getLastCharacterLength(
                                    textComposer
                            )
            );

            textComposer.append((char) character);

            inputConnection.setComposingText(
                    textComposer,
                    1
            );

        } else {

            inputConnection.deleteSurroundingText(
                    1,
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

        if (textComposer.length() > 0) {

            inputConnection.commitText(
                    textComposer,
                    1
            );

            textComposer.setLength(0);
        }
    }

    private boolean handleDictAndAutocorrection() {

        if (!composingEnabled ||
                textComposer.length() == 0) {
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
        return false;
    }

    private boolean handleAutocorrection() {
        return false;
    }

    private void handleEnter(
            InputConnection inputConnection) {

        if (composingEnabled) {

            if (!handleDictAndAutocorrection()) {
                commitComposingText(inputConnection);
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
                commitComposingText(inputConnection);
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
                commitComposingText(inputConnection);
            }
        }

        inputConnection.commitText(
                String.valueOf((char) character),
                1
        );
    }

        public void commitEmoji(CharSequence emoji) {
        InputConnection inputConnection =
                pocketBoardIME.getCurrentInputConnection();

        if (inputConnection == null) {
            return;
        }

        if (composingEnabled) {
            commitComposingText(inputConnection);
        }

        inputConnection.commitText(emoji, 1);
    }

    public void resetComposing(InputConnection inputConnection) {
        if (inputConnection == null) {
            return;
        }

        textComposer.setLength(0);
        inputConnection.finishComposingText();
        multipressController.reset();

        keyIterationCounter = 0;
        lastKeyDownTime = 0;
        lastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        lastAltEnabled = false;
    }

    public boolean isInRawInputMode() {
        return rawInputMode;
    }

}
