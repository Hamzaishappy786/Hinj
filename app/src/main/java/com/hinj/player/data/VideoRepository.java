package com.hinj.player.data;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

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
 * Owns the "videos available to extract audio from" list, scanning
 * Music/Hinj the same way LibraryRepository watches it for songs. Kept as
 * a separate repository (rather than folded into LibraryRepository) since
 * videos and songs have different permissions, different metadata sources,
 * and different UI lifecycles (the Videos tab is opened far less often).
 */
public class VideoRepository {

    private static final long DEBOUNCE_MS = 400;

    private static volatile VideoRepository instance;

    public static VideoRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (VideoRepository.class) {
                if (instance == null) {
                    instance = new VideoRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<List<VideoItem>> videos = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> scanning = new MutableLiveData<>(false);

    private final List<FileObserver> fileObservers = new ArrayList<>();
    private final Runnable pendingScan = this::performScan;
    private boolean watching = false;

    private VideoRepository(Context appContext) {
        this.appContext = appContext;
    }

    public LiveData<List<VideoItem>> getVideos() {
        return videos;
    }

    public LiveData<Boolean> isScanning() {
        return scanning;
    }

    /** Call once READ_MEDIA_VIDEO is granted (e.g. first time the Videos tab opens). */
    public void startWatching() {
        if (watching) {
            requestRescan();
            return;
        }
        watching = true;
        registerFileObservers();
        requestRescan();
    }

    public void requestRescan() {
        mainHandler.removeCallbacks(pendingScan);
        mainHandler.postDelayed(pendingScan, DEBOUNCE_MS);
    }

    private void performScan() {
        scanning.postValue(true);
        executor.execute(() -> {
            List<VideoItem> result = VideoFileScanner.scan(appContext);
            videos.postValue(result);
            scanning.postValue(false);
        });
    }

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
}
