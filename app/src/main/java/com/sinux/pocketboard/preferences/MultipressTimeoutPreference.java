package com.sinux.pocketboard.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.sinux.pocketboard.R;

/**
 * Same role as a SeekBarPreference (a bounded integer the user drags to
 * set), but also exposes the current value as an editable number so it
 * can be typed in directly -- useful here since "Multipress speed" is a
 * millisecond value the user may want to nudge precisely rather than
 * hunt for on a slider.
 *
 * The slider and the text box always agree: dragging one updates the
 * other, and only a fully valid, in-range value from either gets
 * persisted.
 */
public class MultipressTimeoutPreference extends Preference {

    private final int minValue;
    private final int maxValue;
    private final int defaultValue;

    private int currentValue;

    private SeekBar seekBar;
    private EditText editText;

    // Guards against the SeekBar listener and the EditText watcher
    // re-triggering each other while one of them is applying the
    // other's new value.
    private boolean updatingViews;

    public MultipressTimeoutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        minValue = context.getResources().getInteger(R.integer.multipress_timeout_min_value);
        maxValue = context.getResources().getInteger(R.integer.multipress_timeout_max_value);
        defaultValue = context.getResources().getInteger(R.integer.multipress_timeout_default_value);

        setLayoutResource(R.layout.preference_multipress_timeout);
    }

    public MultipressTimeoutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MultipressTimeoutPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultipressTimeoutPreference(Context context) {
        this(context, null);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInteger(index, defaultValue);
    }

    @Override
    protected void onSetInitialValue(Object defaultValueObj) {
        int fallback = defaultValueObj instanceof Integer ? (Integer) defaultValueObj : defaultValue;
        currentValue = clamp(getPersistedInt(fallback));
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        seekBar = (SeekBar) holder.findViewById(R.id.multipress_timeout_seekbar);
        editText = (EditText) holder.findViewById(R.id.multipress_timeout_edittext);

        if (seekBar == null || editText == null) {
            return;
        }

        seekBar.setMax(maxValue - minValue);

        // Remove any watcher/listener left over from a recycled view
        // before re-wiring, so updateViews() below doesn't trigger a
        // spurious persist on an unrelated preference instance.
        seekBar.setOnSeekBarChangeListener(null);
        editText.removeTextChangedListener(editTextWatcher);

        updateViews();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingViews) {
                    return;
                }

                applyValue(minValue + progress, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // No-op.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistAndNotify();
            }
        });

        editText.addTextChangedListener(editTextWatcher);

        editText.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                commitEditTextValue();
            }
        });

        editText.setOnEditorActionListener((view, actionId, event) -> {
            commitEditTextValue();
            return false;
        });
    }

    private final TextWatcher editTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // No-op.
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // No-op.
        }

        @Override
        public void afterTextChanged(Editable editable) {
            if (updatingViews) {
                return;
            }

            // Live-update the slider as the user types, but only
            // persist a complete, in-range value -- an out-of-range
            // or partial number (or one the user is mid-edit on,
            // e.g. about to type a second digit) is left for
            // commitEditTextValue() to clamp once they're done.
            Integer typed = parse(editable.toString());

            if (typed != null && typed >= minValue && typed <= maxValue) {
                updatingViews = true;
                seekBar.setProgress(typed - minValue);
                updatingViews = false;
            }
        }
    };

    private void commitEditTextValue() {
        Integer typed = parse(editText.getText().toString());

        applyValue(typed != null ? typed : currentValue, true);
    }

    /**
     * @param fromEditText when true, also rewrites the text box itself
     *                     (e.g. after clamping an out-of-range value);
     *                     when false, the user is actively typing and
     *                     the box is left alone so the cursor isn't
     *                     disturbed.
     */
    private void applyValue(int value, boolean fromEditText) {
        int clamped = clamp(value);

        currentValue = clamped;

        updatingViews = true;
        seekBar.setProgress(clamped - minValue);

        if (fromEditText) {
            String text = String.valueOf(clamped);

            if (!text.contentEquals(editText.getText())) {
                editText.setText(text);
                editText.setSelection(text.length());
            }
        }

        updatingViews = false;

        persistAndNotify();
    }

    private void persistAndNotify() {
        if (callChangeListener(currentValue)) {
            persistInt(currentValue);
        }
    }

    private void updateViews() {
        updatingViews = true;

        seekBar.setProgress(currentValue - minValue);

        String text = String.valueOf(currentValue);

        if (!text.contentEquals(editText.getText())) {
            editText.setText(text);
        }

        updatingViews = false;
    }

    @Nullable
    private static Integer parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int clamp(int value) {
        return Math.max(minValue, Math.min(maxValue, value));
    }
}
