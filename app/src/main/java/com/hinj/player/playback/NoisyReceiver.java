package com.hinj.player.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import com.hinj.player.hardware.TorchController;
import com.hinj.player.prefs.HinjPrefs;

/**
 * Handles ACTION_AUDIO_BECOMING_NOISY (headphones/Bluetooth route removed
 * mid-playback). This action is NOT exempt from Android 8's implicit
 * broadcast restrictions, so it must be registered with
 * Context.registerReceiver() at runtime — a <receiver> manifest entry would
 * silently never fire. HinjPlaybackService owns the register/unregister
 * lifecycle since it's the component that knows whether audio is playing.
 */
public class NoisyReceiver extends BroadcastReceiver {

    public interface PlaybackStateProvider {
        boolean isPlaying();
    }

    private final TorchController torchController;
    private final HinjPrefs prefs;
    private final PlaybackStateProvider stateProvider;

    public NoisyReceiver(Context context, TorchController torchController, PlaybackStateProvider stateProvider) {
        this.torchController = torchController;
        this.prefs = new HinjPrefs(context);
        this.stateProvider = stateProvider;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
        if (!prefs.isTorchOnUnplugEnabled()) return;
        if (!stateProvider.isPlaying()) return;

        torchController.setTorch(true); // auto-off handled internally after 30s
    }
}
