package com.zelvari.murmur;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible shortcut: fires one-tap dictation and closes immediately, so the app you were
 * typing in comes straight back with Murmur listening. Meant for the side key or an edge panel.
 */
public class DictateActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!MurmurAccessService.trigger()) {
            Toast.makeText(this, "Turn on the one-tap button in Murmur first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}
