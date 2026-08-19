package com.hinj.player.data;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recursively scans the Hinj folder for .mp3 and .m4a files (the latter
 * being what audio extracted from video lands as — see AudioExtractor) and
 * enriches each hit with metadata pulled from a single MediaStore.Audio
 * query, so we never run MediaMetadataRetriever per-file (slow on large
 * libraries).
 */
public class HinjFileScanner {

    private static final String TAG = "HinjFileScanner";

    private static final String[] PROJECTION = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
    };

    public static class MetaRow {
        long id;
        String title, artist, album;
        long durationMs;
        long albumId;
    }

    /** Blocking; call from a background thread (see LibraryRepository). */
    public static List<Song> scan(Context context) {
        List<File> audioFiles = walk(HinjStorage.musicDirFile());
        Map<String, MetaRow> metaByPath = queryMediaStoreMeta(context);

        List<Song> songs = new ArrayList<>(audioFiles.size());
        for (File f : audioFiles) {
            String path = f.getAbsolutePath();
            MetaRow meta = metaByPath.get(path);

            long id = meta != null ? meta.id : -1;
            String title = meta != null && notEmpty(meta.title) ? meta.title : stripExtension(f.getName());
            String artist = meta != null && notEmpty(meta.artist) ? meta.artist : "Unknown Artist";
            String album = meta != null && notEmpty(meta.album) ? meta.album : "Unknown Album";
            long duration = meta != null ? meta.durationMs : 0L;

            Uri playUri = id >= 0
                    ? ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    : Uri.fromFile(f);

            Uri artUri = null;
            // Priority 1: sibling .jpg file (YouTube downloads)
            String baseName = stripExtension(f.getName());
            File thumbFile = new File(f.getParentFile(), baseName + ".jpg");
            if (thumbFile.exists()) {
                artUri = Uri.fromFile(thumbFile);
            } else if (meta != null && meta.albumId >= 0) {
                // Priority 2: MediaStore album art
                artUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), meta.albumId);
            }

            songs.add(new Song(id, title, artist, album, duration, path, playUri, artUri));
        }
        return songs;
    }

    private static List<File> walk(File root) {
        List<File> out = new ArrayList<>();
        if (root == null || !root.exists() || !root.isDirectory()) return out;
        walkInto(root, out);
        return out;
    }

    private static void walkInto(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                walkInto(child, out);
            } else if (isSupportedAudio(child)) {
                out.add(child);
            }
        }
    }

    private static boolean isSupportedAudio(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        return name.endsWith(".mp3") || name.endsWith(".m4a");
    }

    private static Map<String, MetaRow> queryMediaStoreMeta(Context context) {
        Map<String, MetaRow> map = new HashMap<>();
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                MediaStore.Audio.Media.DATA + " LIKE ?",
                new String[]{HinjStorage.musicDirFile().getAbsolutePath() + "%"},
                null)) {
            if (cursor == null) return map;
            int idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int idxData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int idxTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int idxArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int idxAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int idxAlbumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

            while (cursor.moveToNext()) {
                MetaRow row = new MetaRow();
                row.id = cursor.getLong(idxId);
                row.title = cursor.getString(idxTitle);
                row.artist = cursor.getString(idxArtist);
                row.album = cursor.getString(idxAlbum);
                row.albumId = cursor.getLong(idxAlbumId);
                row.durationMs = cursor.getLong(idxDuration);
                String path = cursor.getString(idxData);
                if (path != null) {
                    map.put(path, row);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore metadata query failed; falling back to filenames only", e);
        }
        return map;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
