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
import com.sinux.pocketboard.spellchecker.LearnedWords;
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
    private boolean learnWords;

    private final LearnedWords learnedWords;

    /*
     * The last correction applied by the spacebar, kept only so that
     * a Backspace pressed right after it can undo it (see
     * undoAutoCorrection). Cleared by any other key.
     */
    private AutoCorrection lastAutoCorrection;

    /*
     * Set after an undo: the next time this exact word is committed
     * it must not be corrected again.
     */
    private String skipAutocorrectWord;

    private static final class AutoCorrection {

        final String original;
        final String corrected;

        AutoCorrection(
                String original,
                String corrected) {

            this.original = original;
            this.corrected = corrected;
        }
    }

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
                new MultipressController(
                        preferencesHolder::getMultipressTimeout
                );

        this.learnedWords =
                LearnedWords.get(
                        pocketBoardIME
                );
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

        learnWords =
                composingEnabled
                        && preferencesHolder
                        .isLearnWordsEnabled();

        lastAutoCorrection = null;
        skipAutocorrectWord = null;

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

        learnedWords.flush();

        lastAutoCorrection = null;
        skipAutocorrectWord = null;

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

        // Only a Backspace pressed right after the correction may
        // undo it; modifiers (Shift, Alt...) don't count as "another
        // key".
        if (keyCode != KeyEvent.KEYCODE_DEL
                && !KeyEvent.isModifierKey(
                keyCode
        )) {

            lastAutoCorrection = null;
        }

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

                if (undoAutoCorrection(
                        inputConnection
                )) {

                    return;
                }

                inputConnection.beginBatchEdit();

                deleteLastCharacterAndComposeWord(
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

    /**
     * Deletes the character before the cursor and then puts the word
     * that now ends at the cursor back into composing state, so it can
     * keep being edited and corrected.
     *
     * The text before the cursor is read ONCE, before anything is
     * changed, and everything else is calculated from that single
     * snapshot. The previous version deleted the character and then
     * read the text again to find the word. Many editors (WebView and
     * Chrome-based fields, apps with their own text engine) answer that
     * second read with the text as it was BEFORE the delete, so the
     * word came back one letter too long: the keyboard then removed one
     * character too many, which was the space, and re-wrote the word
     * with the deleted letter still in it, giving "holamundo" instead
     * of "hola mund".
     */
    private void deleteLastCharacterAndComposeWord(
            InputConnection inputConnection) {

        if (rawInputMode
                || !TextUtils.isEmpty(
                inputConnection.getSelectedText(0)
        )) {

            // Raw key events and selections keep the plain behaviour.
            deleteLastCharacter(
                    inputConnection
            );

            findAndComposeLastWord(
                    inputConnection
            );

            return;
        }

        CharSequence before =
                inputConnection
                        .getTextBeforeCursor(
                                wordLookupLength,
                                0
                        );

        if (TextUtils.isEmpty(before)) {
            return;
        }

        int deletedLength =
                CharacterUtils
                        .getLastCharacterLength(
                                before
                        );

        CharSequence remaining =
                before.subSequence(
                        0,
                        before.length()
                                - deletedLength
                );

        int wordStart =
                remaining.length() == 0
                        ? 0
                        : CharacterUtils
                        .getLastWordStartIndex(
                                remaining,
                                nonLetterOrDigitExclusions
                        );

        int wordLength =
                remaining.length()
                        - wordStart;

        lastCursorPosition -=
                deletedLength;

        if (wordLength <= 0) {

            // Nothing to compose, just remove the character.
            inputConnection
                    .deleteSurroundingText(
                            deletedLength,
                            0
                    );

            return;
        }

        textComposer.append(
                remaining,
                wordStart,
                remaining.length()
        );

        /*
         * One cursor-relative delete covers both the character being
         * removed and the word that is about to be re-composed,
         * followed by the composing text without that character.
         */
        inputConnection.finishComposingText();

        inputConnection.deleteSurroundingText(
                deletedLength + wordLength,
                0
        );

        inputConnection.setComposingText(
                textComposer,
                1
        );
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

        CharSequence word =
                str.subSequence(
                        regionStart,
                        regionEnd
                );

        textComposer.append(
                word
        );

        /*
         * setComposingRegion(start, end) needs ABSOLUTE positions,
         * which here meant trusting lastCursorPosition -- our own
         * tracked copy of where the cursor is, kept in sync mainly by
         * the async onUpdateSelection callback. That callback can
         * lag behind fast typing, so lastCursorPosition could be
         * stale by the time this ran, marking the wrong span as
         * composing; replacing it later (e.g. with an autocorrect
         * suggestion) would then eat into whatever text that stale
         * span actually covered, which could reach back past the
         * intended word into text already confirmed.
         *
         * deleteSurroundingText()/setComposingText() below are
         * CURSOR-RELATIVE instead -- always relative to wherever the
         * cursor genuinely is right now -- so this no longer depends
         * on lastCursorPosition being accurate at all.
         */
        inputConnection.finishComposingText();

        inputConnection.deleteSurroundingText(
                word.length(),
                0
        );

        inputConnection.setComposingText(
                textComposer,
                1
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

        /*
         * This is meant to catch a genuine SECOND tap of the space
         * bar shortly after the first one -- not just "any key
         * within keyLongPressDuration", which eventTime -
         * lastKeyDownTime alone can't tell apart. Without also
         * requiring the previous key to have BEEN space and this to
         * be a fresh tap (not an OS key-repeat from holding space
         * down), a normal fast typist would satisfy the timing check
         * on nearly every single space press (gap since the last
         * LETTER, not since a previous space), and simply holding
         * space a little long would fire it repeatedly via repeat
         * events -- both turning an ordinary space into an
         * unintended ". " or extra spaces.
         */
        if (doubleSpacePeriod
                && eventRepeatCount == 0
                && lastKeyCode == KeyEvent.KEYCODE_SPACE
                && eventTime - lastKeyDownTime
                <= keyLongPressDuration) {

            if (composingEnabled) {

                if (!handleDictAndAutocorrection()) {

                    commitComposingText(
                            inputConnection,
                            true
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
                            inputConnection,
                            true
                    );
                }
            }

            inputConnection.commitText(
                    " ",
                    1
            );
        }

        // Same reasoning as in handlePunctuationCharacter(): a space
        // is often the other half of a sentence boundary ("word. "),
        // so re-check capitalization right now instead of waiting on
        // onUpdateSelection.
        pocketBoardIME
                .updateMetaState();
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

            /*
             * Whether this press is still "fast enough" to continue
             * a multipress sequence is decided ENTIRELY by
             * multipressController.process() above, against its own
             * MULTIPRESS_TIMEOUT (750ms). There used to be a second,
             * independent check here against keyLongPressDuration
             * (400ms, meant for long-press/ALT detection -- a
             * completely different gesture). Being shorter than
             * MULTIPRESS_TIMEOUT, it could silently reject a press
             * multipressController had already accepted: the
             * character shown would fall back to the base value
             * while multipressController's internal index kept
             * advancing, desynchronizing the two. The deeper into
             * the <Add> cycle (the more consecutive fast presses
             * required), the more likely this was to misfire --
             * e.g. reaching a key's 2nd <Add> entry (3rd press) was
             * far less reliable than reaching its 1st.
             */

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
                    inputConnection,
                    true
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
                    inputConnection,
                    true
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

        // Re-check auto-capitalization right now rather than waiting
        // for onUpdateSelection: that callback can lag behind fast
        // typing, so the very next letter could get composed before
        // Shift was actually turned on.
        pocketBoardIME
                .updateMetaState();
    }

    private void commitComposingText(
            InputConnection inputConnection) {

        commitComposingText(
                inputConnection,
                false
        );
    }

    /**
     * @param typedByUser true when the word is being committed exactly
     *                    as the user typed it (space, punctuation...),
     *                    which is what the keyboard learns from. False
     *                    when the text is a suggestion or something
     *                    the keyboard produced.
     */
    private void commitComposingText(
            InputConnection inputConnection,
            boolean typedByUser) {

        if (inputConnection != null
                && textComposer.length() > 0) {

            if (typedByUser) {

                learnFromTypedWord(
                        textComposer
                );
            }

            inputConnection.commitText(
                    textComposer,
                    1
            );

            textComposer.setLength(0);
        }
    }

    /**
     * Counts a word the user kept as typed. Only words the
     * dictionary does not know are counted; once one has been kept
     * enough times it is known (see LearnedWords).
     */
    private void learnFromTypedWord(
            CharSequence typed) {

        if (!learnWords
                || !composingEnabled
                || !LearnedWords.isLearnable(
                typed
        )) {

            return;
        }

        String word = typed.toString();

        // Already learned: just keep it fresh.
        if (learnedWords.contains(word)) {

            learnedWords.noteTyped(
                    word
            );

            return;
        }

        SuggestionsManager suggestionsManager =
                pocketBoardIME
                        .getSuggestionsManager();

        if (suggestionsManager == null
                || suggestionsManager.isKnownWord(
                word
        )) {

            return;
        }

        learnedWords.noteTyped(
                word
        );
    }

    /**
     * Backspace right after the spacebar corrected a word: put the
     * word back the way it was typed, still editable, and remember
     * the user meant it so it is not corrected again.
     *
     * Only acts if the text before the cursor is exactly the
     * corrected word plus the space, so a stale record can never
     * touch unrelated text.
     */
    private boolean undoAutoCorrection(
            InputConnection inputConnection) {

        AutoCorrection correction =
                lastAutoCorrection;

        lastAutoCorrection = null;

        if (correction == null
                || inputConnection == null) {

            return false;
        }

        String expected =
                correction.corrected + " ";

        CharSequence before =
                inputConnection
                        .getTextBeforeCursor(
                                expected.length(),
                                0
                        );

        if (before == null
                || !expected.contentEquals(
                before
        )) {

            return false;
        }

        inputConnection.beginBatchEdit();

        inputConnection.finishComposingText();

        inputConnection.deleteSurroundingText(
                expected.length(),
                0
        );

        textComposer.setLength(0);
        textComposer.append(
                correction.original
        );

        inputConnection.setComposingText(
                textComposer,
                1
        );

        inputConnection.endBatchEdit();

        lastCursorPosition +=
                correction.original.length()
                        - expected.length();

        skipAutocorrectWord =
                correction.original;

        if (learnWords
                && LearnedWords.isLearnable(
                correction.original
        )) {

            learnedWords.keepAsTyped(
                    correction.original
            );
        }

        SuggestionsManager suggestionsManager =
                pocketBoardIME
                        .getSuggestionsManager();

        if (suggestionsManager != null) {

            suggestionsManager.update();
        }

        return true;
    }

    private boolean handleDictAndAutocorrection() {

        if (!composingEnabled) {
            return false;
        }

        /*
         * The user just undid a correction of this word: leave it as
         * typed this time.
         */
        if (skipAutocorrectWord != null) {

            boolean sameWord =
                    skipAutocorrectWord.contentEquals(
                            textComposer
                    );

            skipAutocorrectWord = null;

            if (sameWord) {
                return false;
            }
        }

        if (dictShortcuts) {

            CharSequence dictSuggestion =
                    pocketBoardIME
                            .getSuggestionsManager()
                            .getCurrentDictSuggestion();

            if (dictSuggestion != null) {

                applyAndRemember(
                        dictSuggestion
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

                applyAndRemember(
                        recommendedSuggestion
                );

                return true;
            }
        }

        return false;
    }

    /**
     * Applies the suggestion the spacebar chose and, if it changed
     * the word, remembers what was typed so Backspace can undo it.
     */
    private void applyAndRemember(
            CharSequence suggestion) {

        String original =
                textComposer.toString();

        applySuggestion(
                suggestion,
                pocketBoardIME
                        .getCurrentInputConnection(),
                false
        );

        String applied =
                suggestion.toString();

        lastAutoCorrection =
                original.isEmpty()
                        || original.equals(applied)
                        ? null
                        : new AutoCorrection(
                        original,
                        applied
                );
    }
}
