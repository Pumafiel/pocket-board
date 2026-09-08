package com.sinux.pocketboard.utils;

import android.content.Context;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

public class InputUtils {

    private InputUtils() {
        // Utility class.
    }

    public static InputMethodInfo getInputMethodInfo(
            Context context,
            InputMethodManager inputMethodManager) {

        if (context == null || inputMethodManager == null) {
            return null;
        }

        for (InputMethodInfo imi :
                inputMethodManager.getInputMethodList()) {

            if (imi.getPackageName()
                    .equals(context.getPackageName())) {

                return imi;
            }
        }

        return null;
    }

    /**
     * Returns true when the current editor expects numeric input.
     *
     * WebView/Chromium converts HTML:
     *
     *     inputmode="numeric"
     *
     * into EditorInfo.TYPE_CLASS_NUMBER.
     *
     * Android also uses TYPE_CLASS_PHONE for telephone
     * fields and TYPE_CLASS_DATETIME for date/time fields.
     *
     * These must all use the numeric physical keyboard mapping.
     */
    public static boolean isNumericEditor(
            EditorInfo editorInfo) {

        if (editorInfo == null) {
            return false;
        }

        int inputClass =
                editorInfo.inputType
                        & InputType.TYPE_MASK_CLASS;

        switch (inputClass) {

            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
            case InputType.TYPE_CLASS_PHONE:
                return true;

            default:
                return false;
        }
    }

    public static boolean isSuggestionAllowedEditor(
            EditorInfo editorInfo) {

        if (editorInfo == null) {
            return false;
        }

        if (editorInfo.inputType == EditorInfo.TYPE_NULL) {
            return false;
        }

        int variation =
                editorInfo.inputType
                        & InputType.TYPE_MASK_VARIATION;

        switch (variation) {

            case InputType.TYPE_TEXT_VARIATION_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_URI:
                return false;

            default:
                /*
                 * We intentionally keep suggestions enabled for
                 * normal text editors.
                 *
                 * Numeric/phone editors are handled separately by
                 * KeyboardInputHandler and composing is disabled
                 * there.
                 */
                return true;
        }
    }

    public static KeyEvent translateKeyEvent(
            KeyEvent originalEvent,
            int translatedKeyCode,
            int action,
            int metaState) {

        if (originalEvent == null) {
            return null;
        }

        return new KeyEvent(
                originalEvent.getDownTime(),
                originalEvent.getEventTime(),
                action,
                translatedKeyCode,
                originalEvent.getRepeatCount(),
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                translatedKeyCode,
                0,
                InputDevice.SOURCE_KEYBOARD
        );
    }

    public static KeyEvent createKeyEvent(
            long eventTime,
            int keyCode,
            int action,
            int repeatCount,
            int metaState) {

        return new KeyEvent(
                eventTime,
                eventTime,
                action,
                keyCode,
                repeatCount,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                keyCode,
                0,
                InputDevice.SOURCE_KEYBOARD
        );
    }
}
