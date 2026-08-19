package com.hinj.player.extract;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stream-copies (no re-encode) the audio track out of a local video into a
 * standalone .m4a file, via MediaExtractor + MediaMuxer. This only works
 * when the source audio codec is one MediaMuxer's MPEG_4 output accepts —
 * in practice AAC, which covers the vast majority of real-world MP4 videos.
 * Anything else (e.g. Vorbis/Opus in .webm/.mkv) surfaces a clear error
 * rather than silently failing or falling back to a full transcode, since
 * this app intentionally has no bundled audio encoder.
 *
 * One extraction runs at a time per instance; callers should keep a single
 * AudioExtractor around (e.g. one per bottom sheet) rather than share it.
 */
public class AudioExtractor {

    public interface Callback {
        /** 0-100, called on the main thread, throttled to whole-percent changes. */
        void onProgress(int percent);

        void onSuccess(File outputFile);

        /** Human-readable, safe to show directly in a Snackbar/dialog. */
        void onError(String message);

        void onCancelled();
    }

    private static final int BUFFER_SIZE = 1 << 20; // 1MB, comfortably larger than any single sample

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    /**
     * @param sourceUri     file:// (Hinj folder scan) or content:// (SAF import) — both are
     *                      handled transparently by MediaExtractor.setDataSource(Context, Uri, ...)
     * @param durationMsHint total duration from VideoItem, used for progress percentage; if <= 0
     *                      progress reporting is skipped but extraction still proceeds normally
     */
    public void extract(Context context, Uri sourceUri, long durationMsHint, File outputFile, Callback callback) {
        cancelled = false;
        executor.execute(() -> runExtraction(context.getApplicationContext(), sourceUri, durationMsHint, outputFile, callback));
    }

    public void cancel() {
        cancelled = true;
    }

    private void runExtraction(Context context, Uri sourceUri, long durationMsHint, File outputFile, Callback callback) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean success = false;
        try {
            extractor.setDataSource(context, sourceUri, null);

            int audioTrackIndex = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                    break;
                }
            }

            if (audioTrackIndex < 0) {
                postError(callback, "This video doesn't have an audio track.");
                return;
            }

            extractor.selectTrack(audioTrackIndex);

            try {
                muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (Exception e) {
                postError(callback, "Couldn't create the output file.");
                return;
            }

            int muxerTrackIndex;
            try {
                muxerTrackIndex = muxer.addTrack(audioFormat);
            } catch (IllegalArgumentException e) {
                String mime = audioFormat.getString(MediaFormat.KEY_MIME);
                postError(callback, "This video's audio format (" + mime
                        + ") isn't supported for extraction. MP4 videos with AAC audio work best.");
                return;
            }
            muxer.start();

            long totalDurationUs = durationMsHint > 0 ? durationMsHint * 1000L : 0L;
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int lastReportedPercent = -1;

            while (true) {
                if (cancelled) {
                    postCancelled(callback);
                    return;
                }

                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = extractor.getSampleFlags();

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);

                if (totalDurationUs > 0) {
                    int percent = (int) Math.max(0, Math.min(100,
                            (bufferInfo.presentationTimeUs * 100) / totalDurationUs));
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent;
                        postProgress(callback, percent);
                    }
                }

                extractor.advance();
            }

            success = true;
            postSuccess(callback, outputFile);
        } catch (Exception e) {
            postError(callback, "Extraction failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                    // stop() throws if start() never fully succeeded — harmless here.
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
            if (!success && outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    private void postProgress(Callback callback, int percent) {
        mainHandler.post(() -> callback.onProgress(percent));
    }

    private void postSuccess(Callback callback, File outputFile) {
        mainHandler.post(() -> callback.onSuccess(outputFile));
    }

    private void postError(Callback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    private void postCancelled(Callback callback) {
        mainHandler.post(callback::onCancelled);
    }
}
