package com.sinux.pocketboard.input.handler;

import android.view.KeyEvent;

/**
 * Detects consecutive short presses of the same physical key.
 *
 * Multipress is independent from long press.
 *
 * First press:
 *     N -> n
 *
 * Second press:
 *     N N -> ñ
 *
 * Shift affects the result of the multipress:
 *     Shift + N N -> Ñ
 *
 * Alt is NOT converted into the multipress character.
 * Alt selects the normal alternative value, which is the same
 * value used by long press.
 */
public final class MultipressController {

    private static final long MULTIPRESS_TIMEOUT = 750L;

    private int lastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long lastKeyTime = 0L;
    private boolean active = false;

    public void reset() {
        lastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        lastKeyTime = 0L;
        active = false;
    }

    /**
     * Registers a normal ACTION_DOWN event.
     *
     * @return true when this event is a second consecutive
     *         press of the same key inside the multipress window.
     */
    public boolean process(KeyEvent event) {
        if (event == null ||
                event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        /*
         * Android repeat events are generated while holding
         * the physical key. They belong to long-press handling,
         * never to multipress.
         */
        if (event.getRepeatCount() != 0) {
            return false;
        }

        int keyCode = event.getKeyCode();
        long eventTime = event.getEventTime();

        boolean sameKey =
                active &&
                lastKeyCode == keyCode;

        boolean withinTimeout =
                sameKey &&
                eventTime - lastKeyTime <= MULTIPRESS_TIMEOUT;

        boolean multipress =
                withinTimeout;

        if (multipress) {
            /*
             * Consume this sequence.
             *
             * After the second press we reset the sequence so
             * a third press is NOT interpreted as another
             * multipress level.
             */
            reset();
        } else {
            lastKeyCode = keyCode;
            lastKeyTime = eventTime;
            active = true;
        }

        return multipress;
    }

    /**
     * Called when a long press has been detected.
     *
     * The current multipress sequence must be discarded.
     */
    public void markLongPress() {
        reset();
    }
}
