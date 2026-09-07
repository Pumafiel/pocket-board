package com.sinux.pocketboard.input.handler;

import android.view.KeyEvent;

/**
 * Detects consecutive short presses of the same physical key.
 *
 * Examples:
 *
 * N       -> first press
 * N N     -> second press
 * N N N   -> third press
 * N N N N -> fourth press
 *
 * The controller does NOT decide which character must be produced.
 * KeyboardInputHandler uses the press count to select:
 *
 *   value
 *   Add[0]
 *   Add[1]
 *   Add[2]
 *   ...
 *
 * Long press is handled independently by KeyboardInputHandler.
 *
 * Shift and Alt state are intentionally not stored here.
 */
public final class MultipressController {

    private static final long MULTIPRESS_TIMEOUT = 750L;

    private int lastKeyCode =
            KeyEvent.KEYCODE_UNKNOWN;

    private long lastKeyDownTime = 0L;

    private int pressCount = 0;

    public void reset() {

        lastKeyCode =
                KeyEvent.KEYCODE_UNKNOWN;

        lastKeyDownTime = 0L;

        pressCount = 0;
    }

    /**
     * Processes an ACTION_DOWN event.
     *
     * @return true when this press continues a multipress
     *         sequence (second press or later).
     */
    public boolean process(
            KeyEvent event) {

        if (event == null) {
            return false;
        }

        if (event.getAction()
                != KeyEvent.ACTION_DOWN) {

            return false;
        }

        /*
         * Repeat events belong to long-press handling.
         * They must never be interpreted as multipress.
         */
        if (event.getRepeatCount() != 0) {
            return false;
        }

        int keyCode =
                event.getKeyCode();

        long eventTime =
                event.getEventTime();

        boolean sameKey =
                lastKeyCode == keyCode;

        boolean withinTimeout =
                eventTime - lastKeyDownTime
                        <= MULTIPRESS_TIMEOUT;

        /*
         * Continue the existing sequence.
         */
        if (pressCount > 0
                && sameKey
                && withinTimeout) {

            pressCount++;

            lastKeyDownTime =
                    eventTime;

            return true;
        }

        /*
         * This is the first press of a new sequence.
         */
        lastKeyCode =
                keyCode;

        lastKeyDownTime =
                eventTime;

        pressCount = 1;

        return false;
    }

    /**
     * Returns the number of consecutive short presses
     * in the current sequence.
     *
     * Examples:
     *
     *   first press  -> 1
     *   second press -> 2
     *   third press  -> 3
     */
    public int getPressCount() {
        return pressCount;
    }

    /**
     * Returns the zero-based index of the additional value.
     *
     * Examples:
     *
     *   first press  -> 0 (normal value)
     *   second press -> 0 (Add[0])
     *   third press  -> 1 (Add[1])
     *   fourth press -> 2 (Add[2])
     */
    public int getAdditionalValueIndex() {

        if (pressCount <= 1) {
            return -1;
        }

        return pressCount - 2;
    }

    /**
     * Called when a long press is detected.
     *
     * Long press cancels any pending multipress sequence.
     */
    public void markLongPress() {
        reset();
    }
}
