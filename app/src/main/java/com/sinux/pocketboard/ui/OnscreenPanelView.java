package com.sinux.pocketboard.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * Plain container for the on-screen panel (suggestions bar and
 * buttons).
 *
 * This used to also implement a "virtual touchpad" (swipe along the
 * bar to move the cursor), removed because Titan Slim's stock firmware
 * already provides its own touchpad through Intelligent Assistant, and
 * the two conflicted. The class itself stays only because input.xml
 * and InputView refer to it.
 */
public class OnscreenPanelView extends LinearLayout {

    public OnscreenPanelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Kept so InputView's call site is unchanged; there is nothing to
     * do here anymore (it used to start the "try the touchpad" hint
     * animation).
     */
    public void onStartInputView(boolean isVisible) {
        // Intentionally empty.
    }
}
