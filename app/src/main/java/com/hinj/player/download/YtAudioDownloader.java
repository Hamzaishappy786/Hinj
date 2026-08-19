package com.hinj.player.download;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;

import com.hinj.player.data.HinjStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads the audio file (and companion thumbnail) for a YouTube song.
 * Runs on a dedicated background thread; all callback methods are posted
 * to the main thread.
 */
public class YtAudioDownloader {

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(File audioFile, File thumbFile);
        void onError(String message);
        void onCancelled();
    }

    private volatile boolean cancelled = false;

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public void cancel() {
        cancelled = true;
    }

    public void download(Context ctx, YtSongInfo info, String userTitle,
                         String userArtist, Callback cb) {
        cancelled = false;
        executor.execute(() -> {
            File dir = HinjStorage.musicDirFile();
            dir.mkdirs();

            String base      = sanitize(userTitle);
            File   audioFile = uniqueFile(dir, base, "." + info.fileExtension);
            File   thumbFile = new File(dir, base + ".jpg");

            // 1. Download thumbnail (no progress needed — small file)
            if (info.thumbnailUrl != null && !info.thumbnailUrl.isEmpty()) {
                try {
                    downloadRaw(info.thumbnailUrl, thumbFile, null, -1);
                } catch (Exception e) {
                    // Non-fatal: missing thumbnail is acceptable
                    deleteQuietly(thumbFile);
                }
            }

            if (cancelled) {
                deleteQuietly(thumbFile);
                mainHandler.post(cb::onCancelled);
                return;
            }

            // 2. Download audio with progress reporting
            try {
                HttpURLConnection conn = openConnection(info.audioStreamUrl);
                long contentLength = conn.getContentLengthLong();

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(audioFile)) {

                    byte[] buf = new byte[8192];
                    long  downloaded = 0;
                    int   read;

                    while ((read = in.read(buf)) != -1) {
                        if (cancelled) {
                            out.close();
                            deleteQuietly(audioFile);
                            deleteQuietly(thumbFile);
                            mainHandler.post(cb::onCancelled);
                            return;
                        }
                        out.write(buf, 0, read);
                        downloaded += read;
                        if (contentLength > 0) {
                            int percent = (int) (downloaded * 100 / contentLength);
                            mainHandler.post(() -> cb.onProgress(percent));
                        }
                    }
                } finally {
                    conn.disconnect();
                }

                // 3. Trigger MediaStore scan
                MediaScannerConnection.scanFile(
                        ctx.getApplicationContext(),
                        new String[]{audioFile.getAbsolutePath()},
                        null, null);

                File finalAudio = audioFile;
                File finalThumb = thumbFile;
                mainHandler.post(() -> cb.onSuccess(finalAudio, finalThumb));

            } catch (Exception e) {
                deleteQuietly(audioFile);
                deleteQuietly(thumbFile);
                String msg = e.getMessage();
                mainHandler.post(() -> cb.onError(msg != null ? msg : "Download failed"));
            }
        });
    }

    // ---- helpers ----

    private static void downloadRaw(String urlStr, File dest, Callback cb, long ignored)
            throws IOException {
        HttpURLConnection conn = openConnection(urlStr);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.connect();
        return conn;
    }

    private static String sanitize(String raw) {
        String cleaned = raw.replaceAll("[/\\\\:*?\"<>|]", "").trim();
        if (cleaned.isEmpty()) cleaned = "download";
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100);
        return cleaned;
    }

    private static File uniqueFile(File dir, String base, String ext) {
        File candidate = new File(dir, base + ext);
        int suffix = 1;
        while (candidate.exists()) {
            candidate = new File(dir, base + " (" + suffix + ")" + ext);
            suffix++;
        }
        return candidate;
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) f.delete();
    }
}
