package com.sinux.pocketboard.input.handler;

import android.view.KeyEvent;

import java.util.function.LongSupplier;

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

    /*
     * Fallback used only if no supplier is given. The real value
     * normally comes from PreferencesHolder.getMultipressTimeout()
     * (the "Multipress speed" setting), read fresh on every press so
     * changing it takes effect immediately, no IME restart needed.
     */
    private static final long DEFAULT_MULTIPRESS_TIMEOUT = 750L;

    private final LongSupplier timeoutMsSupplier;

    private int lastKeyCode =
            KeyEvent.KEYCODE_UNKNOWN;

    private long lastKeyDownTime = 0L;

    private int pressCount = 0;

    public MultipressController() {
        this(() -> DEFAULT_MULTIPRESS_TIMEOUT);
    }

    public MultipressController(
            LongSupplier timeoutMsSupplier) {

        this.timeoutMsSupplier =
                timeoutMsSupplier != null
                        ? timeoutMsSupplier
                        : () -> DEFAULT_MULTIPRESS_TIMEOUT;
    }

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
                        <= timeoutMsSupplier.getAsLong();

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
