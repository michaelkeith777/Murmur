package com.zelvari.murmur;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/**
 * One-tap dictation. Android only lets the active keyboard or an accessibility service
 * change keyboards, so this service exists for exactly that: when its button is tapped it
 * switches to Murmur (which starts listening) and Murmur hands back when it's done.
 * It never inspects screen content.
 */
public class MurmurAccessService extends AccessibilityService {
    private static MurmurAccessService instance;

    private final AccessibilityButtonController.AccessibilityButtonCallback buttonCallback =
            new AccessibilityButtonController.AccessibilityButtonCallback() {
                @Override
                public void onClicked(AccessibilityButtonController controller) {
                    toggle();
                }
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        getAccessibilityButtonController().registerAccessibilityButtonCallback(buttonCallback);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: this service only reacts to its own button.
    }

    @Override
    public void onInterrupt() {
    }

    /** Starts (or stops) one-tap dictation. Returns false if the service is switched off. */
    static boolean trigger() {
        MurmurAccessService s = instance;
        if (s == null) return false;
        s.toggle();
        return true;
    }

    static boolean isEnabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(new android.content.ComponentName(c, MurmurAccessService.class).flattenToString());
    }

    private void toggle() {
        String mine = murmurKeyboardId();
        if (mine == null) {
            Toast.makeText(this, "Turn on the Murmur keyboard in the Murmur app first", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "One-tap dictation needs Android 11 or newer", Toast.LENGTH_LONG).show();
            return;
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (mine.equals(current)) {
            // Already on Murmur: a second tap goes back to the regular keyboard.
            String previous = Prefs.get(this, Prefs.PREVIOUS_IME, "");
            if (!previous.isEmpty()) getSoftKeyboardController().switchToInputMethod(previous);
            return;
        }
        Prefs.sp(this).edit()
                .putString(Prefs.PREVIOUS_IME, current == null ? "" : current)
                .putLong(Prefs.ONE_SHOT_AT, System.currentTimeMillis())
                .apply();
        if (!getSoftKeyboardController().switchToInputMethod(mine)) {
            Prefs.sp(this).edit().remove(Prefs.ONE_SHOT_AT).apply();
            Toast.makeText(this, "Couldn't switch to the Murmur keyboard", Toast.LENGTH_SHORT).show();
        }
    }

    private String murmurKeyboardId() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return null;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) return info.getId();
        }
        return null;
    }
}
