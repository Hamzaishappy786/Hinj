package com.hinj.player.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.hinj.player.R;

/**
 * System SplashScreen (API 31+ automatic, backported via the compat lib for
 * older versions) hands off to this Activity, which plays a short branded
 * entrance animation before moving into MainActivity.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long TRANSITION_DELAY_MS = 1400;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        playEntranceAnimation();

        new android.os.Handler(getMainLooper()).postDelayed(this::goToMain, TRANSITION_DELAY_MS);
    }

    private void playEntranceAnimation() {
        View logoCard = findViewById(R.id.splashLogoCard);
        View title = findViewById(R.id.splashTitle);
        View tagline = findViewById(R.id.splashTagline);

        logoCard.setScaleX(0.3f);
        logoCard.setScaleY(0.3f);
        logoCard.setAlpha(0f);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(logoCard, View.SCALE_X, 0.3f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(logoCard, View.SCALE_Y, 0.3f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(logoCard, View.ALPHA, 0f, 1f);
        AnimatorSet logoSet = new AnimatorSet();
        logoSet.playTogether(scaleX, scaleY, alpha);
        logoSet.setDuration(650);
        logoSet.setInterpolator(new OvershootInterpolator(1.6f));

        ObjectAnimator titleAlpha = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f);
        titleAlpha.setDuration(400);
        titleAlpha.setStartDelay(450);

        ObjectAnimator taglineAlpha = ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f);
        taglineAlpha.setDuration(400);
        taglineAlpha.setStartDelay(600);

        AnimatorSet full = new AnimatorSet();
        full.playTogether(logoSet, titleAlpha, taglineAlpha);
        full.start();
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
