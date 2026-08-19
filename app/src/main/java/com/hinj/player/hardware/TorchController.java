package com.hinj.player.hardware;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

/**
 * Thin, defensive wrapper around CameraManager.setTorchMode(). Centralizes
 * every guard so callers (headset-disconnect handler, notification blink
 * rule) never have to think about missing hardware, another app holding the
 * camera, or forgetting to turn the torch back off.
 */
public class TorchController {

    private static final String TAG = "TorchController";
    private static final long AUTO_OFF_MS = 30_000; // safety: never leave the torch on unattended
    private static final long BLINK_ON_MS = 120;
    private static final long BLINK_OFF_MS = 120;

    private final CameraManager cameraManager;
    private final String torchCameraId;
    private final Handler handler;
    private final Runnable autoOff = () -> setTorch(false);

    private volatile boolean torchOn = false;

    public TorchController(Context context) {
        this.cameraManager = (CameraManager) context.getApplicationContext()
                .getSystemService(Context.CAMERA_SERVICE);
        this.torchCameraId = findTorchCameraId();

        HandlerThread thread = new HandlerThread("HinjTorchThread");
        thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public boolean isTorchAvailable() {
        return torchCameraId != null;
    }

    /** Turns the torch on with a hard 30s auto-off safety net, or off immediately. */
    public void setTorch(boolean on) {
        if (!isTorchAvailable() || cameraManager == null) return;
        handler.post(() -> {
            try {
                cameraManager.setTorchMode(torchCameraId, on);
                torchOn = on;
                handler.removeCallbacks(autoOff);
                if (on) {
                    handler.postDelayed(autoOff, AUTO_OFF_MS);
                }
            } catch (CameraAccessException e) {
                Log.w(TAG, "Torch unavailable (camera in use elsewhere?)", e);
            } catch (Exception e) {
                Log.w(TAG, "Failed to set torch mode", e);
            }
        });
    }

    /** Fire-and-forget rapid blink used by the notification "Rule B" trigger. */
    public void blink(int times) {
        if (!isTorchAvailable()) return;
        handler.post(() -> blinkStep(times));
    }

    private void blinkStep(int remaining) {
        if (remaining <= 0) {
            applyTorchImmediate(false);
            return;
        }
        applyTorchImmediate(true);
        handler.postDelayed(() -> {
            applyTorchImmediate(false);
            handler.postDelayed(() -> blinkStep(remaining - 1), BLINK_OFF_MS);
        }, BLINK_ON_MS);
    }

    private void applyTorchImmediate(boolean on) {
        try {
            cameraManager.setTorchMode(torchCameraId, on);
        } catch (Exception e) {
            Log.w(TAG, "Blink step failed", e);
        }
    }

    private String findTorchCameraId() {
        if (cameraManager == null) return null;
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean hasFlash = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                boolean isBack = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK;
                if (Boolean.TRUE.equals(hasFlash) && isBack) {
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "No torch-capable camera found", e);
        }
        return null;
    }
}
