package com.hinj.player.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.hinj.player.R;
import com.hinj.player.playback.HinjPlaybackService;
import com.hinj.player.playback.PlayerConnection;

import java.util.Locale;

/**
 * Manages the full-screen "Now Playing" overlay that slides up from the
 * sticky mini-bar. Handles:
 *  • slide-up / swipe-down animation
 *  • audio-reactive WaveCircleView via android.media.audiofx.Visualizer
 *  • seekbar + time labels with 200ms polling
 *  • shuffle / repeat button state
 */
public class NowPlayingSheet {

    private final AppCompatActivity activity;
    private final FrameLayout container;
    private final PlayerConnection playerConnection;
    private final ActivityResultLauncher<String> audioPermLauncher;

    private WaveCircleView waveCircleView;
    private TextView nowTitle;
    private TextView nowArtist;
    private SeekBar nowSeekBar;
    private TextView nowTimeElapsed;
    private TextView nowTimeTotal;
    private ImageButton nowPlayPause;
    private ImageButton nowShuffle;
    private ImageButton nowRepeat;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean visible = false;
    private boolean seekBarDragging = false;
    private long cachedDurationMs = 0L;

    @Nullable private Visualizer visualizer;

    // Swipe-to-dismiss state
    private float swipeStartX, swipeStartY;
    private boolean isSwiping = false;
    @Nullable private VelocityTracker velocityTracker;

    // Glide target — reusing the same instance cancels any in-flight load
    private final CustomTarget<Bitmap> artTarget = new CustomTarget<Bitmap>() {
        @Override
        public void onResourceReady(@NonNull Bitmap bmp, @Nullable Transition<? super Bitmap> t) {
            waveCircleView.setArtBitmap(bmp);
        }
        @Override
        public void onLoadCleared(@Nullable Drawable placeholder) {
            waveCircleView.setArtBitmap(null);
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 200L);
        }
    };

    public NowPlayingSheet(AppCompatActivity activity,
                           FrameLayout container,
                           PlayerConnection playerConnection,
                           ActivityResultLauncher<String> audioPermLauncher) {
        this.activity = activity;
        this.container = container;
        this.playerConnection = playerConnection;
        this.audioPermLauncher = audioPermLauncher;
        inflate();
    }

    private void inflate() {
        LayoutInflater.from(activity).inflate(R.layout.sheet_now_playing, container, true);

        waveCircleView  = container.findViewById(R.id.nowWaveView);
        nowTitle        = container.findViewById(R.id.nowTitle);
        nowArtist       = container.findViewById(R.id.nowArtist);
        nowSeekBar      = container.findViewById(R.id.nowSeekBar);
        nowTimeElapsed  = container.findViewById(R.id.nowTimeElapsed);
        nowTimeTotal    = container.findViewById(R.id.nowTimeTotal);
        nowPlayPause    = container.findViewById(R.id.nowPlayPause);
        nowShuffle      = container.findViewById(R.id.nowShuffle);
        nowRepeat       = container.findViewById(R.id.nowRepeat);

        container.setVisibility(View.GONE);

        container.findViewById(R.id.btnCloseSheet).setOnClickListener(v -> hide());
        nowPlayPause.setOnClickListener(v -> playerConnection.togglePlayPause());
        container.findViewById(R.id.nowPrevious).setOnClickListener(v -> playerConnection.previous());
        container.findViewById(R.id.nowNext).setOnClickListener(v -> playerConnection.next());

        nowShuffle.setOnClickListener(v -> {
            playerConnection.toggleShuffle();
            updateShuffleRepeatTints();
        });
        nowRepeat.setOnClickListener(v -> {
            playerConnection.toggleRepeat();
            updateShuffleRepeatTints();
        });

        nowSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && cachedDurationMs > 0) {
                    long pos = (long)((double) progress / sb.getMax() * cachedDurationMs);
                    nowTimeElapsed.setText(fmt(pos));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                seekBarDragging = true;
                stopTicker();
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                MediaController ctrl = playerConnection.getController();
                if (ctrl != null && cachedDurationMs > 0) {
                    long pos = (long)((double) sb.getProgress() / sb.getMax() * cachedDurationMs);
                    playerConnection.seekTo(pos);
                }
                seekBarDragging = false;
                startTicker();
            }
        });

        setupSwipeGesture(container.findViewById(R.id.sheetSwipeZone));
    }

    private void setupSwipeGesture(View swipeZone) {
        swipeZone.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    swipeStartX = event.getRawX();
                    swipeStartY = event.getRawY();
                    isSwiping = false;
                    if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                    else velocityTracker.clear();
                    velocityTracker.addMovement(event);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (velocityTracker != null) velocityTracker.addMovement(event);
                    float dx = event.getRawX() - swipeStartX;
                    float dy = event.getRawY() - swipeStartY;
                    if (!isSwiping && (Math.abs(dy) > 8 || Math.abs(dx) > 8)) {
                        isSwiping = Math.abs(dy) > Math.abs(dx);
                        if (!isSwiping) return false;
                    }
                    if (isSwiping && dy > 0) container.setTranslationY(dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float translation = container.getTranslationY();
                    float vy = 0f;
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000);
                        vy = velocityTracker.getYVelocity();
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    isSwiping = false;
                    if (vy > 800f || translation > container.getHeight() * 0.28f) {
                        hide();
                    } else {
                        container.animate().translationY(0f).setDuration(220).start();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    // ---- Public API ----

    public void show() {
        visible = true;
        onPlaybackStateChanged(); // sync UI immediately before animating in
        container.setVisibility(View.VISIBLE);
        // Post so the view has a measured height before we start the animation
        container.post(() -> {
            container.setTranslationY(container.getHeight());
            container.animate()
                    .translationY(0f)
                    .setDuration(370)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        });
        startTicker();
        tryStartVisualizer();
    }

    public void hide() {
        visible = false;
        stopTicker();
        stopVisualizer();
        container.animate()
                .translationY(container.getHeight())
                .setDuration(300)
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    container.setVisibility(View.GONE);
                    container.setTranslationY(0f);
                })
                .start();
    }

    public boolean isVisible() { return visible; }

    /** Returns true if the sheet consumed the back press (i.e. it was open). */
    public boolean handleBackPress() {
        if (visible) { hide(); return true; }
        return false;
    }

    public void onActivityPause() {
        if (visible) { stopTicker(); stopVisualizer(); }
    }

    public void onActivityResume() {
        if (visible) { startTicker(); tryStartVisualizer(); }
    }

    /** Called by MainActivity after the RECORD_AUDIO permission result arrives. */
    public void onPermissionResult(boolean granted) {
        if (granted && visible) tryStartVisualizer();
    }

    /** Called whenever playback state changes so the sheet stays in sync. */
    public void onPlaybackStateChanged() {
        if (!visible) return;
        MediaController ctrl = playerConnection.getController();
        if (ctrl == null) return;

        MediaMetadata meta = ctrl.getMediaMetadata();
        nowTitle.setText(meta.title != null ? meta.title : "");
        nowTitle.setSelected(true); // activates marquee scrolling
        nowArtist.setText(meta.artist != null ? meta.artist : "");

        nowPlayPause.setImageResource(ctrl.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        Glide.with(activity)
                .asBitmap()
                .load(meta.artworkUri)
                .into(artTarget);

        if (!ctrl.isPlaying()) waveCircleView.setAudioLevel(0f);
        updateShuffleRepeatTints();
    }

    // ---- Progress ticker ----

    private void startTicker() {
        handler.removeCallbacks(progressTicker);
        handler.post(progressTicker);
    }

    private void stopTicker() {
        handler.removeCallbacks(progressTicker);
    }

    private void updateProgress() {
        if (seekBarDragging) return;
        MediaController ctrl = playerConnection.getController();
        if (ctrl == null) return;
        long pos = ctrl.getCurrentPosition();
        long dur = ctrl.getDuration();
        if (dur <= 0) return;
        cachedDurationMs = dur;
        nowSeekBar.setProgress((int)((double) pos / dur * nowSeekBar.getMax()));
        nowTimeElapsed.setText(fmt(pos));
        nowTimeTotal.setText(fmt(dur));
    }

    // ---- Visualizer ----

    private void tryStartVisualizer() {
        stopVisualizer();
        boolean hasAudioPerm = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!hasAudioPerm) {
            // Request; if granted, onPermissionResult() will call us back
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startVisualizer();
    }

    private void startVisualizer() {
        try {
            int sessionId = HinjPlaybackService.getAudioSessionId();
            visualizer = new Visualizer(sessionId);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[0]); // smallest → fastest
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) {
                    float rms = rmsNormalized(waveform);
                    // Dispatch to main thread; WaveCircleView's phase animator already
                    // calls invalidate() every frame, so we just update the target level.
                    waveCircleView.post(() -> waveCircleView.setAudioLevel(rms));
                }
                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {}
            }, Visualizer.getMaxCaptureRate() / 2, /* waveform */ true, /* fft */ false);
            visualizer.setEnabled(true);
        } catch (Exception e) {
            // Visualizer unavailable (permission race, no audio session yet, etc.)
            // Waves still animate using the phase-only animation — just not audio-reactive.
            visualizer = null;
        }
    }

    private void stopVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Exception ignored) {}
            visualizer = null;
        }
        waveCircleView.setAudioLevel(0f);
    }

    /** Converts a PCM waveform byte[] (unsigned 0-255, silence=128) to 0..1 RMS. */
    private float rmsNormalized(byte[] waveform) {
        long sum = 0;
        for (byte b : waveform) {
            int v = (b & 0xFF) - 128;
            sum += (long) v * v;
        }
        float rms = (float) Math.sqrt((double) sum / waveform.length);
        // 64/128 maps to ~1.0 so moderate speech/music already hits max
        return Math.min(1f, rms / 55f);
    }

    // ---- Shuffle / Repeat tints ----

    private void updateShuffleRepeatTints() {
        int green = ContextCompat.getColor(activity, R.color.hinj_green);
        int grey  = ContextCompat.getColor(activity, R.color.hinj_grey);
        nowShuffle.setColorFilter(playerConnection.isShuffleEnabled() ? green : grey);
        nowRepeat.setColorFilter(
                playerConnection.getRepeatMode() != Player.REPEAT_MODE_OFF ? green : grey);
    }

    // ---- Utilities ----

    private String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }
}
