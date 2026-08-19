package com.hinj.player.data;

import android.net.Uri;

import java.util.Objects;

/**
 * A video the user could extract audio from. sourceKey is the stable
 * identity for diffing/list purposes: the absolute file path for videos
 * found by scanning Music/Hinj, or the content Uri string for videos
 * brought in through the system file picker (which may not resolve to a
 * plain filesystem path at all).
 */
public class VideoItem {

    public final String sourceKey;
    public final Uri contentUri;
    public final String displayName;   // filename without extension, used as the default extraction title
    public final long durationMs;
    public final boolean deletable;    // false for SAF-picked videos we don't own / can't reliably delete

    public VideoItem(String sourceKey, Uri contentUri, String displayName, long durationMs, boolean deletable) {
        this.sourceKey = sourceKey;
        this.contentUri = contentUri;
        this.displayName = displayName;
        this.durationMs = durationMs;
        this.deletable = deletable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoItem)) return false;
        VideoItem that = (VideoItem) o;
        return sourceKey.equals(that.sourceKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceKey);
    }
}
