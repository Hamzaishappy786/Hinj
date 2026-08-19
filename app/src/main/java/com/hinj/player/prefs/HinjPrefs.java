package com.hinj.player.prefs;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single typed access point for every user-facing toggle in Hinj.
 * Nothing here ever touches the network — local SharedPreferences only.
 */
public class HinjPrefs {

    private static final String FILE = "hinj_prefs";

    private static final String KEY_NIGHT_MODE = "night_mode"; // true = dark, false = light
    private static final String KEY_TORCH_ON_UNPLUG = "torch_on_unplug";
    private static final String KEY_NOTIF_DUCK = "notif_duck";
    private static final String KEY_NOTIF_BLINK = "notif_blink";
    private static final String KEY_FIRST_RUN     = "first_run_done";
    private static final String KEY_GH_TOKEN      = "gh_token";
    private static final String KEY_GH_OWNER      = "gh_owner";
    private static final String KEY_GH_REPO       = "gh_repo";
    private static final String KEY_GH_BRANCH     = "gh_branch";

    private final SharedPreferences sp;

    public HinjPrefs(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean isDarkMode() {
        return sp.getBoolean(KEY_NIGHT_MODE, true); // dark by default
    }

    public void setDarkMode(boolean dark) {
        sp.edit().putBoolean(KEY_NIGHT_MODE, dark).apply();
    }

    /** Off by default: flashing the torch on unplug is aggressive behavior. */
    public boolean isTorchOnUnplugEnabled() {
        return sp.getBoolean(KEY_TORCH_ON_UNPLUG, false);
    }

    public void setTorchOnUnplugEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_TORCH_ON_UNPLUG, enabled).apply();
    }

    public boolean isNotifDuckEnabled() {
        return sp.getBoolean(KEY_NOTIF_DUCK, false);
    }

    public void setNotifDuckEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_NOTIF_DUCK, enabled).apply();
    }

    public boolean isNotifBlinkEnabled() {
        return sp.getBoolean(KEY_NOTIF_BLINK, false);
    }

    public void setNotifBlinkEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_NOTIF_BLINK, enabled).apply();
    }

    public boolean isFirstRunDone() {
        return sp.getBoolean(KEY_FIRST_RUN, false);
    }

    public void markFirstRunDone() {
        sp.edit().putBoolean(KEY_FIRST_RUN, true).apply();
    }

    public String getGithubToken()  { return sp.getString(KEY_GH_TOKEN, ""); }
    public String getGithubOwner()  { return sp.getString(KEY_GH_OWNER, ""); }
    public String getGithubRepo()   { return sp.getString(KEY_GH_REPO,  ""); }
    public String getGithubBranch() { return sp.getString(KEY_GH_BRANCH, "main"); }

    public void setGithubToken(String v)  { sp.edit().putString(KEY_GH_TOKEN, v).apply(); }
    public void setGithubOwner(String v)  { sp.edit().putString(KEY_GH_OWNER, v).apply(); }
    public void setGithubRepo(String v)   { sp.edit().putString(KEY_GH_REPO,  v).apply(); }
    public void setGithubBranch(String v) { sp.edit().putString(KEY_GH_BRANCH, v).apply(); }
}
