package com.hinj.player.data;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recursively scans the Hinj folder for video containers that
 * MediaExtractor/MediaMuxer can realistically stream-copy audio out of.
 * Duration comes from MediaMetadataRetriever per file; video counts are
 * small relative to song libraries so this per-file cost is acceptable.
 */
public class VideoFileScanner {

    private static final String TAG = "VideoFileScanner";

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "webm", "3gp", "mov"));

    /** Blocking; call from a background thread. */
    public static List<VideoItem> scan(Context context) {
        List<File> files = new ArrayList<>();
        walk(HinjStorage.musicDirFile(), files);

        List<VideoItem> items = new ArrayList<>(files.size());
        for (File f : files) {
            VideoItem item = toVideoItem(context, f);
            if (item != null) items.add(item);
        }
        return items;
    }

    private static void walk(File dir, List<File> out) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                walk(child, out);
            } else if (isVideo(child)) {
                out.add(child);
            }
        }
    }

    private static boolean isVideo(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** Shared with the SAF-import path so both sources build identical VideoItems. */
    public static VideoItem toVideoItem(Context context, File file) {
        return buildItem(context, file.getAbsolutePath(), Uri.fromFile(file), stripExtension(file.getName()), true);
    }

    public static VideoItem toVideoItem(Context context, Uri contentUri, String displayName) {
        return buildItem(context, contentUri.toString(), contentUri, displayName, false);
    }

    private static VideoItem buildItem(Context context, String sourceKey, Uri uri, String displayName, boolean deletable) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0L;
            return new VideoItem(sourceKey, uri, displayName, durationMs, deletable);
        } catch (Exception e) {
            Log.w(TAG, "Could not read metadata for " + sourceKey, e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
