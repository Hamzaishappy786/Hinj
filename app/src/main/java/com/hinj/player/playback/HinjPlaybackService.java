package com.hinj.player.playback;

import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.hinj.player.hardware.TorchController;

/**
 * The single foreground service that owns playback end-to-end via
 * AndroidX Media3. Everything runs locally: ExoPlayer is fed content:// /
 * file:// URIs from the on-device library only — there is no network media
 * source anywhere in this class.
 *
 * Being a MediaSessionService with setHandleAudioBecomingNoisy(true) and a
 * proper foregroundServiceType gives us screen-lock and audio-focus
 * resilience for free; it also hosts the headset-disconnect torch trigger
 * and the notification duck/blink command bus subscription, since both need
 * direct access to the live ExoPlayer instance.
 */
public class HinjPlaybackService extends MediaSessionService implements PlaybackCommandBus.Listener {

    private static volatile int sAudioSessionId = 0;

    /** Used by NowPlayingSheet to attach a Visualizer to the correct audio session. */
    public static int getAudioSessionId() { return sAudioSessionId; }

    private ExoPlayer player;
    private MediaSession mediaSession;
    private TorchController torchController;
    private NoisyReceiver noisyReceiver;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private float preduckVolume = 1f;
    private final Runnable restoreVolume = this::restoreVolumeAfterDuck;

    @Override
    public void onCreate() {
        super.onCreate();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        sAudioSessionId = player.getAudioSessionId();
        mediaSession = new MediaSession.Builder(this, player).build();

        torchController = new TorchController(this);
        noisyReceiver = new NoisyReceiver(this, torchController, () -> player.isPlaying());
        // API 33+ requires an explicit export flag for context-registered receivers;
        // ContextCompat handles the pre-33 fallback automatically.
        ContextCompat.registerReceiver(this, noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        PlaybackCommandBus.getInstance().register(this);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        PlaybackCommandBus.getInstance().unregister(this);
        try {
            unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered — safe to ignore during teardown.
        }
        mainHandler.removeCallbacks(restoreVolume);

        sAudioSessionId = 0;
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Only stop when nothing is actively playing, so playback survives
        // the user swiping the app away from recents.
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    // ---- PlaybackCommandBus.Listener: notification interruption rules ----

    @Override
    public void onDuckRequested() {
        mainHandler.post(() -> {
            if (player == null) return;
            mainHandler.removeCallbacks(restoreVolume);
            preduckVolume = player.getVolume();
            player.setVolume(Math.min(preduckVolume, 0.2f));
            mainHandler.postDelayed(restoreVolume, 2000);
        });
    }

    @Override
    public void onBlinkRequested() {
        if (torchController != null) {
            torchController.blink(3);
        }
    }

    private void restoreVolumeAfterDuck() {
        if (player != null) {
            player.setVolume(preduckVolume);
        }
    }
}
