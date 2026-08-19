package com.hinj.player.playback;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Player.RepeatMode;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;

/**
 * Wraps MediaController connection setup/teardown so Activities don't repeat
 * the ListenableFuture boilerplate. Exposes a small, UI-friendly surface for
 * the sticky bottom bar and the song list.
 */
public class PlayerConnection {

    public interface Callback {
        void onConnected(MediaController controller);
        void onPlaybackStateChanged();
    }

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onIsPlayingChanged(boolean isPlaying) { callback.onPlaybackStateChanged(); }
        @Override public void onMediaItemTransition(MediaItem mediaItem, int reason) { callback.onPlaybackStateChanged(); }
        @Override public void onPlaybackStateChanged(int playbackState) { callback.onPlaybackStateChanged(); }
        @Override public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) { callback.onPlaybackStateChanged(); }
        @Override public void onRepeatModeChanged(int repeatMode) { callback.onPlaybackStateChanged(); }
    };

    public PlayerConnection(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void connect() {
        SessionToken token = new SessionToken(context,
                new ComponentName(context, HinjPlaybackService.class));
        controllerFuture = new MediaController.Builder(context, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                callback.onConnected(controller);
            } catch (Exception ignored) {
                // Connection failed/cancelled — UI simply stays in its default state.
            }
        }, MoreExecutors.directExecutor());
    }

    public void disconnect() {
        if (controller != null) {
            controller.removeListener(playerListener);
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        controller = null;
    }

    public MediaController getController() {
        return controller;
    }

    public void playQueue(List<MediaItem> items, int startIndex) {
        if (controller == null) return;
        controller.setMediaItems(items, startIndex, 0L);
        controller.prepare();
        controller.play();
    }

    public void togglePlayPause() {
        if (controller == null) return;
        if (controller.isPlaying()) {
            controller.pause();
        } else {
            controller.play();
        }
    }

    public void next() {
        if (controller != null) controller.seekToNext();
    }

    public void previous() {
        if (controller != null) controller.seekToPrevious();
    }

    public void seekTo(long positionMs) {
        if (controller != null) controller.seekTo(positionMs);
    }

    public void toggleShuffle() {
        if (controller == null) return;
        controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
    }

    public void toggleRepeat() {
        if (controller == null) return;
        int next = controller.getRepeatMode() == Player.REPEAT_MODE_OFF
                ? Player.REPEAT_MODE_ALL
                : Player.REPEAT_MODE_OFF;
        controller.setRepeatMode(next);
    }

    public boolean isShuffleEnabled() {
        return controller != null && controller.getShuffleModeEnabled();
    }

    public int getRepeatMode() {
        return controller != null ? controller.getRepeatMode() : Player.REPEAT_MODE_OFF;
    }
}
