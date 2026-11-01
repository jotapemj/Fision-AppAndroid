package com.jotapeveloper.fision;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.jotapeveloper.fision.brand.FisionSplashView;

/**
 * Animated splash (nucleus, neutron impact, fission, wordmark), then the song
 * list with a fade. The system splash of Android 12+ is plain black with no
 * icon (SplashTheme), so it flows straight into this animation.
 */
public class SplashScreenActivity extends AppCompatActivity {

    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A launcher tap can land on top of a live task whose base intent is
        // not the launcher one (opened from an installer, a store or a shell):
        // the task is already showing, so this one steps aside.
        Intent in = getIntent();
        if (!isTaskRoot() && in != null && Intent.ACTION_MAIN.equals(in.getAction())
                && in.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        FisionSplashView splash = new FisionSplashView(this);
        splash.setOnFinished(this::openMain);
        setContentView(splash);
    }

    private void openMain() {
        if (launched || isFinishing()) return;
        launched = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
