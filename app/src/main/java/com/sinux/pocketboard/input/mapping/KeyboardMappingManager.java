package com.sinux.pocketboard.input.mapping;

import android.content.Context;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import androidx.annotation.NonNull;
import androidx.collection.LruCache;

import com.sinux.pocketboard.R;
import com.sinux.pocketboard.utils.ToastMessageUtils;

public class KeyboardMappingManager {

    /*
     * One slot per language PocketBoard ships (es-AR, en-US, de-DE),
     * plus the numeric mapping that switchToNumericKeyboardMapping()
     * also stores here. At 2 (the previous value), cycling through
     * all 3 languages evicted and re-parsed the XML on every single
     * switch instead of ever reusing a cached mapping.
     */
    private static final int KEYBOARD_MAPPING_CACHE_DEPTH = 4;

    private static final String INPUT_METHOD_KEYBOARD_MAPPING =
            "KeyboardMapping";

    private static final String TITAN_MODEL_NAME =
            "Titan";

    private static final String TITAN_SLIM_MODEL_NAME =
            "Titan Slim";

    private static final String TITAN_DEVICE_NAME =
            "Titan_Slim";

    /*
     * InputMethodManager.getCurrentInputMethodSubtype() can return
     * null -- no subtype has been explicitly selected yet, which
     * happens right after the IME is first enabled, and is common on
     * custom ROMs/GSIs. Without a fallback, the constructor would
     * NPE on inputMethodSubtype.getExtraValueOf(...) below and crash
     * PocketBoardIME.onCreate(), taking the whole keyboard down.
     * This matches the first <subtype> in method.xml.
     */
    private static final String DEFAULT_KEYBOARD_MAPPING_FILE =
            "en_us";

    private final Context context;
    private final LruCache<String, KeyboardMapping> keyboardMappings;

    private KeyboardMapping currentMapping;

    public KeyboardMappingManager(
            Context context,
            InputMethodManager inputMethodManager) {

        this.context = context;

        keyboardMappings =
                new LruCache<>(
                        KEYBOARD_MAPPING_CACHE_DEPTH
                );

        switchToKeyboardMapping(
                inputMethodManager
                        .getCurrentInputMethodSubtype()
        );
    }

    public KeyboardMapping getCurrentMapping() {
        return currentMapping;
    }

    public void switchToNumericKeyboardMapping() {
        setCurrentKeyboardMapping("numeric");
    }

    public void switchToKeyboardMapping(
            InputMethodSubtype inputMethodSubtype) {

        if (inputMethodSubtype == null) {

            setCurrentKeyboardMapping(
                    DEFAULT_KEYBOARD_MAPPING_FILE
            );

            return;
        }

        setCurrentKeyboardMapping(
                inputMethodSubtype
                        .getExtraValueOf(
                                INPUT_METHOD_KEYBOARD_MAPPING
                        )
        );
    }

    private void setCurrentKeyboardMapping(
            String keyMappingFile) {

        if (TextUtils.isEmpty(keyMappingFile)) {
            return;
        }

        KeyboardMapping cachedMapping =
                keyboardMappings.get(
                        keyMappingFile
                );

        if (cachedMapping != null) {

            currentMapping =
                    cachedMapping;

            return;
        }

        try {

            KeyboardMapping mapping =
                    loadKeyboardMapping(
                            keyMappingFile
                    );

            keyboardMappings.put(
                    keyMappingFile,
                    mapping
            );

            currentMapping =
                    mapping;

        } catch (Exception e) {

            toastError();
        }
    }

    private void toastError() {

        ToastMessageUtils.showMessage(
                context,
                R.string.keyboard_mapping_load_failed
        );
    }

    /**
     * Returns the model suffix used by the keyboard mapping
     * resource files.
     *
     * Titan Slim devices use the same Titan-specific mapping
     * files as the Titan family:
     *
     * keyboard_mapping_en_us_titan.xml
     * keyboard_mapping_es_ar_titan.xml
     * keyboard_mapping_de_de_titan.xml
     *
     * Other devices use the normal mapping files.
     */
    private String getDeviceModelString() {

        String model =
                android.os.Build.MODEL;

        String device =
                android.os.Build.DEVICE;

        if (TITAN_SLIM_MODEL_NAME.equalsIgnoreCase(model)) {
            return "_titan";
        }

        if (TITAN_DEVICE_NAME.equalsIgnoreCase(device)) {
            return "_titan";
        }

        if (TITAN_MODEL_NAME.equalsIgnoreCase(model)) {
            return "_titan";
        }

        return "";
    }

    @NonNull
    private KeyboardMapping loadKeyboardMapping(
            String keyMappingFile)
            throws Exception {

        KeyboardMappingParser parser =
                new KeyboardMappingParser(
                        context,
                        keyMappingFile,
                        getDeviceModelString()
                );

        return parser.parseMapping();
    }
}
