package com.hinj.player.data;

import android.content.Context;
import android.database.ContentObserver;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the "current library" state and every source that can invalidate it:
 * a recursive FileObserver tree on Music/Hinj, a MediaStore ContentObserver,
 * and manual triggers (app resume, pull-to-refresh). All rescans are
 * debounced onto a single background executor so bursts of file events
 * collapse into one scan.
 */
public class LibraryRepository {

    private static final long DEBOUNCE_MS = 400;

    private static volatile LibraryRepository instance;

    public static LibraryRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (LibraryRepository.class) {
                if (instance == null) {
                    instance = new LibraryRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<List<Song>> library = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> scanning = new MutableLiveData<>(false);

    private final List<FileObserver> fileObservers = new ArrayList<>();
    private ContentObserver mediaStoreObserver;
    private final Runnable pendingScan = this::performScan;

    private LibraryRepository(Context appContext) {
        this.appContext = appContext;
    }

    public LiveData<List<Song>> getLibrary() {
        return library;
    }

    public LiveData<Boolean> isScanning() {
        return scanning;
    }

    /** Call once (e.g. from MainActivity.onCreate) after storage permission is granted. */
    public void startWatching() {
        registerFileObservers();
        registerContentObserver();
        requestRescan();
    }

    public void stopWatching() {
        for (FileObserver fo : fileObservers) fo.stopWatching();
        fileObservers.clear();
        if (mediaStoreObserver != null) {
            appContext.getContentResolver().unregisterContentObserver(mediaStoreObserver);
            mediaStoreObserver = null;
        }
    }

    /** Debounced rescan trigger — safe to call rapidly. */
    public void requestRescan() {
        mainHandler.removeCallbacks(pendingScan);
        mainHandler.postDelayed(pendingScan, DEBOUNCE_MS);
    }

    private void performScan() {
        scanning.postValue(true);
        executor.execute(() -> {
            HinjStorage.ensureDirectoryExists(appContext);
            List<Song> result = HinjFileScanner.scan(appContext);
            library.postValue(result);
            scanning.postValue(false);
        });
    }

    // ---- FileObserver: Android's isn't recursive, so we walk every subdir ----

    @SuppressWarnings("deprecation")
    private void registerFileObservers() {
        File root = HinjStorage.musicDirFile();
        Deque<File> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            if (dir == null || !dir.isDirectory()) continue;

            int mask = FileObserver.CREATE | FileObserver.DELETE
                    | FileObserver.MOVED_FROM | FileObserver.MOVED_TO
                    | FileObserver.CLOSE_WRITE;
            FileObserver observer = new FileObserver(dir.getAbsolutePath(), mask) {
                @Override
                public void onEvent(int event, String path) {
                    requestRescan();
                }
            };
            observer.startWatching();
            fileObservers.add(observer);

            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isDirectory()) queue.add(c);
                }
            }
        }
    }

    private void registerContentObserver() {
        mediaStoreObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                requestRescan();
            }
        };
        appContext.getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver);
    }
}
