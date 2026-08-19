package com.hinj.player.playback;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import com.hinj.player.data.Song;

import java.util.ArrayList;
import java.util.List;

/** Maps local Song models to Media3 MediaItems for playback + notification metadata. */
public class MediaItemFactory {

    public static MediaItem from(Song song) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setArtworkUri(song.albumArtUri)
                .build();

        return new MediaItem.Builder()
                .setUri(song.contentUri)
                .setMediaId(song.filePath)
                .setMediaMetadata(metadata)
                .build();
    }

    public static List<MediaItem> from(List<Song> songs) {
        List<MediaItem> items = new ArrayList<>(songs.size());
        for (Song s : songs) items.add(from(s));
        return items;
    }
}
