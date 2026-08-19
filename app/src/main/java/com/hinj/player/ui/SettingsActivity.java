package com.hinj.player.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.hinj.player.R;
import com.hinj.player.notif.HinjNotificationListener;
import com.hinj.player.prefs.HinjPrefs;

public class SettingsActivity extends AppCompatActivity {

    private HinjPrefs prefs;
    private TextView notifAccessStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new HinjPrefs(this);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        MaterialSwitch switchTheme = findViewById(R.id.switchTheme);
        MaterialSwitch switchTorchUnplug = findViewById(R.id.switchTorchUnplug);
        MaterialSwitch switchNotifDuck = findViewById(R.id.switchNotifDuck);
        MaterialSwitch switchNotifBlink = findViewById(R.id.switchNotifBlink);
        notifAccessStatus = findViewById(R.id.notifAccessStatus);

        switchTheme.setChecked(prefs.isDarkMode());
        switchTorchUnplug.setChecked(prefs.isTorchOnUnplugEnabled());
        switchNotifDuck.setChecked(prefs.isNotifDuckEnabled());
        switchNotifBlink.setChecked(prefs.isNotifBlinkEnabled());

        switchTheme.setOnCheckedChangeListener((btn, checked) -> {
            prefs.setDarkMode(checked);
            AppCompatDelegate.setDefaultNightMode(checked
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
        });

        switchTorchUnplug.setOnCheckedChangeListener((btn, checked) ->
                prefs.setTorchOnUnplugEnabled(checked));

        switchNotifDuck.setOnCheckedChangeListener((btn, checked) ->
                prefs.setNotifDuckEnabled(checked));

        switchNotifBlink.setOnCheckedChangeListener((btn, checked) ->
                prefs.setNotifBlinkEnabled(checked));

        findViewById(R.id.rowNotifAccess).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        // GitHub Downloads settings
        TextInputEditText editGhOwner  = findViewById(R.id.editGhOwner);
        TextInputEditText editGhRepo   = findViewById(R.id.editGhRepo);
        TextInputEditText editGhToken  = findViewById(R.id.editGhToken);
        TextInputEditText editGhBranch = findViewById(R.id.editGhBranch);

        editGhOwner.setText(prefs.getGithubOwner());
        editGhRepo.setText(prefs.getGithubRepo());
        editGhToken.setText(prefs.getGithubToken());
        editGhBranch.setText(prefs.getGithubBranch());

        View.OnFocusChangeListener saveGh = (v, hasFocus) -> {
            if (!hasFocus) {
                prefs.setGithubOwner(text(editGhOwner));
                prefs.setGithubRepo(text(editGhRepo));
                prefs.setGithubToken(text(editGhToken));
                String branch = text(editGhBranch);
                prefs.setGithubBranch(branch.isEmpty() ? "main" : branch);
            }
        };
        editGhOwner.setOnFocusChangeListener(saveGh);
        editGhRepo.setOnFocusChangeListener(saveGh);
        editGhToken.setOnFocusChangeListener(saveGh);
        editGhBranch.setOnFocusChangeListener(saveGh);
    }

    private static String text(TextInputEditText e) {
        return e.getText() != null ? e.getText().toString().trim() : "";
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean granted = HinjNotificationListener.isEnabled(this);
        notifAccessStatus.setText(granted
                ? R.string.settings_notif_access_granted
                : R.string.settings_notif_access_not_granted);
    }
}
