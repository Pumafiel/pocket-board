package com.sinux.pocketboard.input.handler;

import android.view.KeyEvent;

/**
 * Detects a second short press of the same physical key.
 *
 * This controller deliberately handles ONLY double press.
 * Long press is handled independently by KeyboardInputHandler.
 *
 * Examples:
 *
 * N       -> first press
 * N N     -> second press / multipress
 *
 * Shift state is NOT stored here because the Shift state of the
 * second physical press must be evaluated by KeyboardInputHandler.
 */
public final class MultipressController {

    private static final long MULTIPRESS_TIMEOUT = 750L;

    private int lastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long lastKeyDownTime = 0L;
    private boolean waitingForSecondPress = false;

    public void reset() {
        lastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        lastKeyDownTime = 0L;
        waitingForSecondPress = false;
    }

    /**
     * Processes an ACTION_DOWN event.
     *
     * @return true when this is a second short press of the same key.
     */
    public boolean process(KeyEvent event) {
        if (event == null) {
            return false;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        /*
         * Repeat events belong to long-press handling.
         * They must never be interpreted as multipress.
         */
        if (event.getRepeatCount() != 0) {
            return false;
        }

        int keyCode = event.getKeyCode();
        long eventTime = event.getEventTime();

        boolean isSecondPress =
                waitingForSecondPress
                        && lastKeyCode == keyCode
                        && eventTime - lastKeyDownTime
                        <= MULTIPRESS_TIMEOUT;

        if (isSecondPress) {
            /*
             * The double press has been consumed.
             *
             * A third press starts a completely new sequence.
             * Therefore:
             *
             * N N     -> multipress
             * N N N   -> another first press after the pair
             */
            reset();
            return true;
        }

        /*
         * Start waiting for a possible second press.
         */
        lastKeyCode = keyCode;
        lastKeyDownTime = eventTime;
        waitingForSecondPress = true;

        return false;
    }

    /**
     * Called when a long press is detected.
     *
     * Long press cancels any pending double-press sequence.
     */
    public void markLongPress() {
        reset();
    }
}
