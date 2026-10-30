package com.sinux.pocketboard;

import android.annotation.SuppressLint;
import android.inputmethodservice.InputMethodService;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.sinux.pocketboard.input.SuggestionsManager;
import com.sinux.pocketboard.input.SymbolLayer;
import com.sinux.pocketboard.input.handler.KeyboardInputHandler;
import com.sinux.pocketboard.input.MetaKeyManager;
import com.sinux.pocketboard.preferences.PreferencesHolder;
import com.sinux.pocketboard.ui.InputView;
import com.sinux.pocketboard.utils.InputUtils;
import com.sinux.pocketboard.utils.ToastMessageUtils;

import java.util.Arrays;
import java.util.List;

public class PocketBoardIME extends InputMethodService {

    private InputMethodManager inputMethodManager;
    private PreferencesHolder preferencesHolder;
    private MetaKeyManager metaKeyManager;
    private KeyboardInputHandler keyboardInputHandler;

    private InputView inputView;
    private SuggestionsManager suggestionsManager;

    private boolean autoCapitalization;
    private boolean symJustUsed;

    // Keys whose DOWN was typed as a symbol, so their UP can be
    // swallowed to match.
    private final java.util.Set<Integer> symbolKeysDown =
            new java.util.HashSet<>();

    private List<String> directInputEditors;

    @Override
    public void onCreate() {
        super.onCreate();

        inputMethodManager =
                (InputMethodManager) getSystemService(
                        INPUT_METHOD_SERVICE
                );

        preferencesHolder =
                new PreferencesHolder(this);

        metaKeyManager =
                new MetaKeyManager(this);

        keyboardInputHandler =
                new KeyboardInputHandler(this);

        suggestionsManager =
                new SuggestionsManager(
                        this,
                        keyboardInputHandler
                );

        directInputEditors =
                Arrays.asList(
                        getResources().getStringArray(
                                R.array.direct_input_editors
                        )
                );
    }

    @Override
    public void onDestroy() {

        metaKeyManager.destroy();

        super.onDestroy();
    }

    @Override
    public void onWindowHidden() {

        super.onWindowHidden();

        if (inputView != null) {

            inputView.hideEmojiPanel();
        }
    }

    @SuppressLint("InflateParams")
    @Override
    public View onCreateInputView() {

        inputView =
                (InputView) getLayoutInflater()
                        .inflate(
                                R.layout.input,
                                null
                        );

        suggestionsManager.setInputView(
                inputView
        );

        metaKeyManager.setMetaKeyStateChangeListener(
                inputView
        );

        return inputView;
    }

    @Override
    public void onStartInput(
            EditorInfo attribute,
            boolean restarting) {

        super.onStartInput(
                attribute,
                restarting
        );

        autoCapitalization =
                preferencesHolder
                        .isAutoCapitalizationEnabled();

        if (!restarting) {

            metaKeyManager.reset();
        }

        InputMethodSubtype currentInputMethodSubtype =
                inputMethodManager
                        .getCurrentInputMethodSubtype();

        suggestionsManager.onStartInput(
                attribute,
                currentInputMethodSubtype
        );

        int cursorPosition = -1;

        InputConnection inputConnection =
                getCurrentInputConnection();

        if (inputConnection != null) {

            ExtractedText extractedText =
                    inputConnection.getExtractedText(
                            new ExtractedTextRequest(),
                            0
                    );

            if (extractedText != null) {

                cursorPosition =
                        Math.min(
                                extractedText.selectionStart,
                                extractedText.selectionEnd
                        );
            }
        }

        keyboardInputHandler.onStartInput(
                attribute,
                suggestionsManager.isSuggestionsAllowed(),
                cursorPosition
        );

        updateMetaState();
    }

    @Override
    public void onStartInputView(
            EditorInfo attribute,
            boolean restarting) {

        super.onStartInputView(
                attribute,
                restarting
        );

        InputMethodSubtype currentInputMethodSubtype =
                inputMethodManager
                        .getCurrentInputMethodSubtype();

        suggestionsManager.onStartInputView(
                currentInputMethodSubtype
        );

        /*
         * Force an initial refresh of the native PocketBoard
         * dictionary/suggestion engine.
         */
        suggestionsManager.update();

        inputView.onStartInputView(
                attribute,
                currentInputMethodSubtype,
                suggestionsManager.isSuggestionsAllowed()
        );
    }

    @Override
    public void onFinishInputView(
            boolean finishingInput) {

        super.onFinishInputView(
                finishingInput
        );

        suggestionsManager.onFinishInput();

        keyboardInputHandler.onFinishInput();
    }

    @Override
    public boolean onEvaluateInputViewShown() {

        super.onEvaluateInputViewShown();

        /*
         * Force keyboard show to allow the user to toggle
         * the emoji panel even in hidden mode.
         */
        return true;
    }

    @Override
    protected void onCurrentInputMethodSubtypeChanged(
            InputMethodSubtype newSubtype) {

        super.onCurrentInputMethodSubtypeChanged(
                newSubtype
        );

        if (preferencesHolder
                .isLayoutChangeIndicationEnabled()) {

            ToastMessageUtils.showMessage(
                    this,
                    newSubtype.getNameResId()
            );
        }

        /*
         * Keep the native dictionary language synchronized
         * with the active keyboard subtype.
         */
        suggestionsManager
                .onInputMethodSubtypeChanged(
                        newSubtype
                );

        keyboardInputHandler
                .onInputMethodSubtypeChanged(
                        newSubtype,
                        suggestionsManager
                                .isSuggestionsAllowed()
                );

        if (inputView != null) {

            inputView
                    .onInputMethodSubtypeChanged(
                            newSubtype,
                            suggestionsManager
                                    .isSuggestionsAllowed()
                    );
        }

        updateMetaState();
    }

    @Override
    public boolean onKeyDown(
            int keyCode,
            KeyEvent event) {

        switch (keyCode) {

            case KeyEvent.KEYCODE_BACK:

                if (isInputViewShown()) {

                    if (inputView != null &&
                            inputView.isEmojiPanelVisible()) {

                        inputView.hideEmojiPanel();

                    } else {

                        requestHideSelf(0);
                    }

                    return true;
                }

                return false;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:

                return false;
        }

        InputConnection inputConnection =
                getCurrentInputConnection();

        EditorInfo editorInfo =
                getCurrentInputEditorInfo();

        if (editorInfo != null &&
                directInputEditors.contains(
                        editorInfo.packageName
                )) {

            return false;
        }

        /*
         * Meta keys and shortcuts.
         */
        if (metaKeyManager.handleKeyDown(
                keyCode,
                event,
                inputConnection
        )) {

            return true;
        }

        /*
         * Skip CTRL+X shortcuts.
         */
        if (event.isCtrlPressed() &&
                !metaKeyManager.isSymFixed()) {

            return false;
        }

        if (inputConnection == null) {

            return false;
        }

        /*
         * SYM held or locked: letter keys type their symbol (see
         * SymbolLayer). Anything SymbolLayer doesn't map -- Backspace,
         * Space, Enter, arrows... -- falls through and behaves as
         * usual.
         */
        if (metaKeyManager.isSymFixed()) {

            String symbol =
                    SymbolLayer.getSymbol(
                            keyCode
                    );

            if (symbol != null) {

                keyboardInputHandler
                        .resetComposing(
                                inputConnection
                        );

                inputConnection.commitText(
                        symbol,
                        1
                );

                symJustUsed = true;

                symbolKeysDown.add(
                        keyCode
                );

                return true;
            }
        }

        switch (keyCode) {

            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:

                return false;
        }

        /*
         * Normal keyboard input.
         */
        if (editorInfo != null &&
                (
                        editorInfo.inputType !=
                                InputType.TYPE_NULL
                                ||
                                keyboardInputHandler
                                        .isInRawInputMode()
                )) {

            /*
             * Emoji shortcuts.
             */
            if (inputView != null &&
                    inputView.isEmojiPanelVisible() &&
                    inputView.handleEmojiShortcut(
                            keyCode
                    )) {

                return true;
            }

            /*
             * Native PocketBoard input handler.
             *
             * Suggestions are refreshed through the normal
             * selection/composing lifecycle.
             */
            if (keyboardInputHandler.handleKeyDown(
                    keyCode,
                    event,
                    inputConnection,
                    metaKeyManager.isShiftEnabled(),
                    metaKeyManager.isAltEnabled()
            )) {

                if (!isInputViewShown()) {

                    requestShowSelf(
                            InputMethodManager.SHOW_FORCED
                    );
                }
            }

        } else {

            return false;
        }

        return true;
    }

    @Override
    public boolean onKeyUp(
            int keyCode,
            KeyEvent event) {

        switch (keyCode) {

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:

                return false;
        }

        InputConnection inputConnection =
                getCurrentInputConnection();

        EditorInfo editorInfo =
                getCurrentInputEditorInfo();

        if (editorInfo != null &&
                directInputEditors.contains(
                        editorInfo.packageName
                )) {

            return false;
        }

        /*
         * Meta keys.
         */
        if (metaKeyManager.handleKeyUp(
                keyCode,
                event,
                inputConnection
        )) {

            if (keyCode ==
                    KeyEvent.KEYCODE_SYM ||
                    keyCode ==
                            KeyEvent.KEYCODE_PICTSYMBOLS) {

                if (!symJustUsed &&
                        !metaKeyManager.isSymFixed()) {

                    if (isInputViewShown()) {

                        inputView.toggleEmojiPanel();
                    }

                } else {

                    symJustUsed = false;
                }
            }

            return true;
        }

        /*
         * Skip CTRL+X shortcuts.
         */
        if (event.isCtrlPressed() &&
                !metaKeyManager.isSymFixed()) {

            return false;
        }

        if (inputConnection == null) {

            return false;
        }

        /*
         * A symbol key's DOWN was consumed above, so swallow its UP
         * too -- even if SYM itself was released first, otherwise
         * the plain letter's UP would reach the normal input
         * handler with no matching DOWN.
         */
        if (symbolKeysDown.remove(
                keyCode
        )) {

            return true;
        }

        switch (keyCode) {

            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:

                return false;
        }

        return editorInfo != null &&
                (
                        editorInfo.inputType !=
                                InputType.TYPE_NULL
                                ||
                                keyboardInputHandler
                                        .isInRawInputMode()
                ) &&
                keyboardInputHandler.handleKeyUp(
                        keyCode,
                        event
                );
    }

    @Override
    public void onUpdateSelection(
            int oldSelStart,
            int oldSelEnd,
            int newSelStart,
            int newSelEnd,
            int candidatesStart,
            int candidatesEnd) {

        super.onUpdateSelection(
                oldSelStart,
                oldSelEnd,
                newSelStart,
                newSelEnd,
                candidatesStart,
                candidatesEnd
        );

        keyboardInputHandler.onUpdateSelection(
                getCurrentInputConnection(),
                newSelStart,
                newSelEnd,
                candidatesEnd
        );

        updateMetaState();

        /*
         * This is one of the main entry points for the native
         * PocketBoard Suggestions/Correction engine.
         */
        suggestionsManager.update();
    }

    /*
     * Android can provide editor completions.
     *
     * PocketBoard deliberately does not use those completions
     * as its suggestion source. SuggestionsManager simply
     * refreshes the native dictionary engine.
     */
    @Override
    public void onDisplayCompletions(
            CompletionInfo[] completions) {

        suggestionsManager.update(
                completions
        );
    }

    @Override
    public void hideStatusIcon() {

        super.hideStatusIcon();

        symJustUsed = true;
    }

    /**
     * Re-reads getCursorCapsMode() and enables/disables Shift to
     * match. Called automatically on selection changes (below), but
     * ALSO called directly, synchronously, by KeyboardInputHandler
     * right after it commits punctuation or a space -- see the calls
     * from handlePunctuationCharacter() and handleSpace() there.
     * Waiting only for onUpdateSelection (an async callback from the
     * editor) to re-trigger this is what let auto-capitalization fall
     * behind when typing fast: querying the InputConnection ourselves
     * right after our own edit does not have that lag, since it is a
     * direct, synchronous call rather than a notification the editor
     * has to round-trip back to us.
     */
    public void updateMetaState() {

        /*
         * Auto-capitalize.
         */
        if (autoCapitalization) {

            EditorInfo editorInfo =
                    getCurrentInputEditorInfo();

            if (editorInfo != null &&
                    (
                            editorInfo.inputType !=
                                    InputType.TYPE_NULL
                                    ||
                                    keyboardInputHandler
                                            .isInRawInputMode()
                    )) {

                InputConnection inputConnection =
                        getCurrentInputConnection();

                if (inputConnection != null) {

                    if (
                            inputConnection
                                    .getCursorCapsMode(
                                            TextUtils
                                                    .CAP_MODE_SENTENCES
                                    ) > 0
                                    &&
                                    InputUtils
                                            .isSuggestionAllowedEditor(
                                                    editorInfo
                                            )
                    ) {

                        metaKeyManager
                                .enableShift();

                    } else {

                        metaKeyManager
                                .disableShift();
                    }

                    metaKeyManager.updateAlt();
                }

            }

        } else {

            metaKeyManager.updateShift();

            metaKeyManager.updateAlt();
        }
    }

    public InputMethodManager
    getInputMethodManager() {

        return inputMethodManager;
    }

    public PreferencesHolder
    getPreferencesHolder() {

        return preferencesHolder;
    }

    public MetaKeyManager
    getMetaKeyManager() {

        return metaKeyManager;
    }

    public SuggestionsManager
    getSuggestionsManager() {

        return suggestionsManager;
    }

    public KeyboardInputHandler
    getKeyboardInputHandler() {

        return keyboardInputHandler;
    }

    public boolean isShouldShowIme() {

        if (preferencesHolder
                .isShowPanelEnabled()) {

            return Settings.Secure.getInt(
                    getContentResolver(),
                    "show_ime_with_hard_keyboard",
                    0
            ) == 1;
        }

        return false;
    }
}
