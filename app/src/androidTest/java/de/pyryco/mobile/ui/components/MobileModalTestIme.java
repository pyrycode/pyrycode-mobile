package de.pyryco.mobile.ui.components;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

/** A real test-only IME for ATD, using only framework classes in its separate APK process. */
public class MobileModalTestIme extends InputMethodService {
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    @Override
    public View onCreateInputView() {
        FrameLayout keyboard = new FrameLayout(this);
        int height = Math.round(240 * getResources().getDisplayMetrics().density);
        keyboard.setMinimumHeight(height);
        Button key = new Button(this);
        key.setText("Keyboard entry");
        key.setOnClickListener(view -> {
            if (getCurrentInputConnection() != null) {
                getCurrentInputConnection().commitText("Keyboard entry", 1);
            }
        });
        keyboard.addView(key);
        return keyboard;
    }
}
