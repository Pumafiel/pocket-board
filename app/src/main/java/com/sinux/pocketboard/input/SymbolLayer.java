package com.sinux.pocketboard.input;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * What SYM types. Fixed and the same for every language, unlike the
 * old SymPad: this is deliberately not configurable, since the whole
 * point of removing SymPad was one predictable symbol layer instead
 * of a per-key-customizable grid.
 *
 * Covers digits and the punctuation the ALT layer (see the
 * keyboard_mapping_*.xml files) does not already have, so the two
 * modifiers complement each other rather than duplicating one
 * another:
 *   ALT already has:  0-9 ( ) - _ / : @ * # + " ' ! . , ?
 *   SYM adds:         ; $ % & = [ ] { } ~ < > | \ ^ °
 *
 * Only produces characters for the plain QWERTY letter keys; any
 * other key (Backspace, Enter, Space, arrows...) is left for
 * PocketBoardIME to handle normally, SYM held or not.
 */
public final class SymbolLayer {

    private static final Map<Integer, String> KEY_CODE_TO_SYMBOL =
            new HashMap<>();

    static {
        // Row 1: digits, same layout as ALT's own scattered digits
        // but laid out as one clean row for SYM.
        put(KeyEvent.KEYCODE_Q, "1");
        put(KeyEvent.KEYCODE_W, "2");
        put(KeyEvent.KEYCODE_E, "3");
        put(KeyEvent.KEYCODE_R, "4");
        put(KeyEvent.KEYCODE_T, "5");
        put(KeyEvent.KEYCODE_Y, "6");
        put(KeyEvent.KEYCODE_U, "7");
        put(KeyEvent.KEYCODE_I, "8");
        put(KeyEvent.KEYCODE_O, "9");
        put(KeyEvent.KEYCODE_P, "0");

        // Row 2.
        put(KeyEvent.KEYCODE_A, ";");
        put(KeyEvent.KEYCODE_S, "$");
        put(KeyEvent.KEYCODE_D, "%");
        put(KeyEvent.KEYCODE_F, "&");
        put(KeyEvent.KEYCODE_G, "=");
        put(KeyEvent.KEYCODE_H, "[");
        put(KeyEvent.KEYCODE_J, "]");
        put(KeyEvent.KEYCODE_K, "{");
        put(KeyEvent.KEYCODE_L, "}");

        // Row 3.
        put(KeyEvent.KEYCODE_Z, "~");
        put(KeyEvent.KEYCODE_X, "<");
        put(KeyEvent.KEYCODE_C, ">");
        put(KeyEvent.KEYCODE_V, "|");
        put(KeyEvent.KEYCODE_B, "\\");
        put(KeyEvent.KEYCODE_N, "^");
        put(KeyEvent.KEYCODE_M, "°");
    }

    private SymbolLayer() {
    }

    private static void put(int keyCode, String symbol) {
        KEY_CODE_TO_SYMBOL.put(keyCode, symbol);
    }

    /**
     * @return the symbol for {@code keyCode}, or null if SYM doesn't
     * map that key (so the caller should let it behave normally).
     */
    @Nullable
    public static String getSymbol(int keyCode) {
        return KEY_CODE_TO_SYMBOL.get(keyCode);
    }
}
