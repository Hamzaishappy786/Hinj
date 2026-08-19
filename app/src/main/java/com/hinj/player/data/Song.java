package com.hinj.player.data;

import android.net.Uri;

import java.util.Objects;

/**
 * Immutable representation of a single local .mp3 track.
 * Everything here is sourced from disk / MediaStore — no remote fields exist.
 */
public class Song {

    public final long mediaStoreId;   // -1 if not indexed by MediaStore yet
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final String filePath;     // absolute path on disk, used as the stable key
    public final Uri contentUri;      // playable URI (content:// when available, else file://)
    public final Uri albumArtUri;     // nullable

    public Song(long mediaStoreId, String title, String artist, String album,
                long durationMs, String filePath, Uri contentUri, Uri albumArtUri) {
        this.mediaStoreId = mediaStoreId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.filePath = filePath;
        this.contentUri = contentUri;
        this.albumArtUri = albumArtUri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Song)) return false;
        Song song = (Song) o;
        return filePath.equals(song.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath);
    }
}
