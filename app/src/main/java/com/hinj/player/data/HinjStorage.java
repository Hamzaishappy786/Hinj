package com.hinj.player.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.OutputStream;

/**
 * Locates (and if necessary creates) the public "Hinj" folder used for the
 * local library, following a three-tier fallback appropriate for scoped
 * storage on modern Android:
 *
 *   1. Direct java.io.File under Environment.DIRECTORY_MUSIC — works on most
 *      OEM builds and is what HinjFileScanner walks.
 *   2. A MediaStore insert with RELATIVE_PATH=Music/Hinj/, which forces the
 *      system to materialize the directory even when direct File writes are
 *      blocked.
 *   3. SAF (ACTION_OPEN_DOCUMENT_TREE) — the user picks/creates the folder
 *      once, we persist the returned URI permission for future launches.
 *
 * Tiers 2 and 3 exist for devices/policies where tier 1 silently no-ops.
 */
public class HinjStorage {

    private static final String TAG = "HinjStorage";
    private static final String FOLDER_NAME = "Hinj";
    private static final String PREFS = "hinj_storage_prefs";
    private static final String KEY_SAF_TREE_URI = "saf_tree_uri";

    public static File musicDirFile() {
        File musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        return new File(musicRoot, FOLDER_NAME);
    }

    /**
     * Attempts tier 1 (plain File.mkdirs) and tier 2 (MediaStore-forced
     * materialization) synchronously. Returns true if the folder exists on
     * disk afterwards. Call off the main thread.
     */
    public static boolean ensureDirectoryExists(Context context) {
        File dir = musicDirFile();
        if (dir.exists() && dir.isDirectory()) {
            return true;
        }

        // Tier 1: direct mkdirs
        if (dir.mkdirs()) {
            return true;
        }
        if (dir.exists()) {
            return true;
        }

        // Tier 2: MediaStore-forced materialization (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (materializeViaMediaStore(context)) {
                return dir.exists();
            }
        }

        Log.w(TAG, "Could not materialize Hinj folder via File or MediaStore; SAF fallback required.");
        return dir.exists();
    }

    /**
     * Writes (and immediately deletes) a placeholder .nomedia-adjacent file
     * through MediaStore purely to force the OS to create Music/Hinj/ on
     * disk. Cheap, synchronous, safe to call repeatedly.
     */
    private static boolean materializeViaMediaStore(Context context) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, ".hinj_marker");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/" + FOLDER_NAME + "/");

            Uri collection = MediaStore.Files.getContentUri("external");
            Uri item = context.getContentResolver().insert(collection, values);
            if (item == null) return false;

            try (OutputStream os = context.getContentResolver().openOutputStream(item)) {
                if (os != null) os.write(0);
            }
            // Clean up the marker; the directory itself persists.
            context.getContentResolver().delete(item, null, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "MediaStore materialization failed", e);
            return false;
        }
    }

    // ---- Tier 3: SAF ----

    public static void persistSafTreeUri(Context context, Uri treeUri) {
        context.getContentResolver().takePersistableUriPermission(treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        prefs(context).edit().putString(KEY_SAF_TREE_URI, treeUri.toString()).apply();
    }

    public static DocumentFile getSafTree(Context context) {
        String saved = prefs(context).getString(KEY_SAF_TREE_URI, null);
        if (saved == null) return null;
        return DocumentFile.fromTreeUri(context, Uri.parse(saved));
    }

    public static boolean hasSafFallback(Context context) {
        return prefs(context).getString(KEY_SAF_TREE_URI, null) != null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
