package com.hinj.player.notif;

import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.hinj.player.playback.PlaybackCommandBus;
import com.hinj.player.prefs.HinjPrefs;

/**
 * Fires the two user-configurable interruption rules whenever a new
 * notification (not Hinj's own playback notification) arrives:
 *   Rule A — duck ExoPlayer volume for 2s, then restore.
 *   Rule B — briefly blink the camera LED without touching playback.
 * Both are dispatched through PlaybackCommandBus so this class never needs
 * a reference to the playback service directly.
 */
public class HinjNotificationListener extends NotificationListenerService {

    private HinjPrefs prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new HinjPrefs(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (getPackageName().equals(sbn.getPackageName())) return; // ignore our own notification
        if (!sbn.isClearable() && isOngoingSystemNoise(sbn)) return;

        if (prefs == null) prefs = new HinjPrefs(this);

        if (prefs.isNotifDuckEnabled()) {
            PlaybackCommandBus.getInstance().requestDuck();
        }
        if (prefs.isNotifBlinkEnabled()) {
            PlaybackCommandBus.getInstance().requestBlink();
        }
    }

    private boolean isOngoingSystemNoise(StatusBarNotification sbn) {
        // Ongoing, non-clearable notifications (foreground service icons,
        // download progress, etc.) are usually not something the user wants
        // ducking/blinking for on every update.
        return (sbn.getNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
    }

    /** Convenience for SettingsActivity to check/deep-link into system settings. */
    public static boolean isEnabled(android.content.Context context) {
        String pkgName = context.getPackageName();
        String flat = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(pkgName);
    }
}
