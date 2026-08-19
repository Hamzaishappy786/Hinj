package com.hinj.player.ui;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.hinj.player.R;
import com.hinj.player.data.LibraryRepository;
import com.hinj.player.data.Song;
import com.hinj.player.data.VideoFileScanner;
import com.hinj.player.data.VideoItem;
import com.hinj.player.data.VideoRepository;
import com.hinj.player.playback.MediaItemFactory;
import com.hinj.player.playback.PlayerConnection;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements PlayerConnection.Callback, ExtractAudioBottomSheet.Listener,
        YtDownloadSheet.Listener {

    private View rootView;
    private TabLayout tabLayout;

    private RecyclerView songList;
    private SwipeRefreshLayout swipeRefresh;
    private View emptyState;

    private RecyclerView videoList;
    private SwipeRefreshLayout videoSwipeRefresh;
    private View emptyVideoState;
    private View fabImportVideo;

    private View playerBar;
    private ImageButton barPlayPause;
    private ImageView barArt;
    private TextView barTitle;
    private TextView barSubtitle;

    private SongAdapter adapter;
    private VideoAdapter videoAdapter;
    private LibraryRepository libraryRepository;
    private VideoRepository videoRepository;
    private PlayerConnection playerConnection;
    private NowPlayingSheet nowPlayingSheet;
    private List<Song> currentSongs = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // ---- Activity result launchers (must be registered before onCreate completes) ----

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean audioGranted = Boolean.TRUE.equals(result.getOrDefault(audioPermission(), false));
                if (audioGranted) startLibrary();
                else Toast.makeText(this, R.string.permission_rationale_body, Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String> videoPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) startVideoWatching();
                else Toast.makeText(this, R.string.permission_rationale_video_body, Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String[]> videoImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importVideo(uri);
            });

    // RECORD_AUDIO is requested lazily by NowPlayingSheet when the user opens it.
    private final ActivityResultLauncher<String> audioPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (nowPlayingSheet != null)
                    nowPlayingSheet.onPermissionResult(Boolean.TRUE.equals(granted));
            });

    // ---- Lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create PlayerConnection early so NowPlayingSheet can hold a reference to it
        playerConnection = new PlayerConnection(this, this);

        bindViews();
        setupList();
        setupVideoList();
        setupTabs();
        setupPlayerBar();

        libraryRepository = LibraryRepository.getInstance(this);
        videoRepository   = VideoRepository.getInstance(this);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnDownloadYt).setOnClickListener(v ->
                new YtDownloadSheet().show(getSupportFragmentManager(), "yt_download"));
        fabImportVideo.setOnClickListener(v -> videoImportLauncher.launch(new String[]{"video/*"}));

        ensurePermissionsThenStart();
    }

    @Override
    protected void onPause() {
        if (nowPlayingSheet != null) nowPlayingSheet.onActivityPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nowPlayingSheet != null) nowPlayingSheet.onActivityResume();
    }

    @Override
    public void onBackPressed() {
        if (nowPlayingSheet != null && nowPlayingSheet.handleBackPress()) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        playerConnection.disconnect();
        super.onDestroy();
    }

    // ---- View setup ----

    private void bindViews() {
        rootView   = findViewById(android.R.id.content);
        tabLayout  = findViewById(R.id.tabLayout);

        songList      = findViewById(R.id.songList);
        swipeRefresh  = findViewById(R.id.swipeRefresh);
        emptyState    = findViewById(R.id.emptyState);

        videoList         = findViewById(R.id.videoList);
        videoSwipeRefresh = findViewById(R.id.videoSwipeRefresh);
        emptyVideoState   = findViewById(R.id.emptyVideoState);
        fabImportVideo    = findViewById(R.id.fabImportVideo);

        // Inflate sticky mini player bar
        ViewGroup container = findViewById(R.id.playerBarContainer);
        View bar = getLayoutInflater().inflate(R.layout.view_player_bar, container, true);
        playerBar    = bar.findViewById(R.id.playerBarRoot);
        barPlayPause = bar.findViewById(R.id.barPlayPause);
        barArt       = bar.findViewById(R.id.barArt);
        barTitle     = bar.findViewById(R.id.barTitle);
        barSubtitle  = bar.findViewById(R.id.barSubtitle);

        bar.findViewById(R.id.barNext).setOnClickListener(v -> playerConnection.next());
        bar.findViewById(R.id.barPrevious).setOnClickListener(v -> playerConnection.previous());
        barPlayPause.setOnClickListener(v -> playerConnection.togglePlayPause());

        // Inflate now-playing full-screen sheet into its overlay container
        FrameLayout sheetContainer = findViewById(R.id.nowPlayingSheet);
        nowPlayingSheet = new NowPlayingSheet(this, sheetContainer, playerConnection, audioPermLauncher);

        // Tapping the mini bar opens the full sheet
        playerBar.setOnClickListener(v -> nowPlayingSheet.show());
    }

    private void setupList() {
        adapter = new SongAdapter(this::onSongClicked);
        songList.setLayoutManager(new LinearLayoutManager(this));
        songList.setAdapter(adapter);
        swipeRefresh.setOnRefreshListener(() -> libraryRepository.requestRescan());
    }

    private void setupVideoList() {
        videoAdapter = new VideoAdapter(this::onExtractClicked);
        videoList.setLayoutManager(new LinearLayoutManager(this));
        videoList.setAdapter(videoAdapter);
        videoSwipeRefresh.setOnRefreshListener(() -> videoRepository.requestRescan());
    }

    private void setupTabs() {
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showTab(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showTab(int position) {
        boolean songsSelected = position == 0;
        swipeRefresh.setVisibility(songsSelected ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(songsSelected && currentSongs.isEmpty() ? View.VISIBLE : View.GONE);
        videoSwipeRefresh.setVisibility(songsSelected ? View.GONE : View.VISIBLE);
        fabImportVideo.setVisibility(songsSelected ? View.GONE : View.VISIBLE);
        emptyVideoState.setVisibility(
                songsSelected || !videoAdapter.getCurrentList().isEmpty() ? View.GONE : View.VISIBLE);
        if (!songsSelected) ensureVideoPermissionThenWatch();
    }

    private void setupPlayerBar() {
        playerBar.setVisibility(View.GONE);
    }

    // ---- Song / video interaction ----

    private void onSongClicked(Song song, int position) {
        if (position < 0) return;
        playerConnection.playQueue(MediaItemFactory.from(currentSongs), position);
        adapter.setPlayingPath(song.filePath);
    }

    private void onExtractClicked(VideoItem video) {
        ExtractAudioBottomSheet.newInstance(video).show(getSupportFragmentManager(), "extract_audio");
    }

    private void importVideo(Uri uri) {
        ioExecutor.execute(() -> {
            String displayName = queryDisplayName(getContentResolver(), uri);
            VideoItem item = VideoFileScanner.toVideoItem(this, uri, displayName);
            runOnUiThread(() -> {
                if (item != null) onExtractClicked(item);
                else Toast.makeText(this, R.string.import_video_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) {
                    int dot = name.lastIndexOf('.');
                    return dot > 0 ? name.substring(0, dot) : name;
                }
            }
        } catch (Exception ignored) {}
        return "Imported video";
    }

    // ---- YtDownloadSheet.Listener ----

    @Override
    public void onYtDownloadComplete(File audioFile, String title, String artist) {
        libraryRepository.requestRescan();
        Snackbar.make(rootView, title + " added", Snackbar.LENGTH_LONG)
                .setAction(R.string.extract_audio_play_now, v -> {
                    MediaItem item = new MediaItem.Builder()
                            .setUri(Uri.fromFile(audioFile))
                            .setMediaId(audioFile.getAbsolutePath())
                            .setMediaMetadata(new MediaMetadata.Builder()
                                    .setTitle(title).setArtist(artist).build())
                            .build();
                    playerConnection.playQueue(Collections.singletonList(item), 0);
                }).show();
    }

    // ---- ExtractAudioBottomSheet.Listener ----

    @Override
    public void onExtractionComplete(File outputFile, String trackTitle) {
        libraryRepository.requestRescan();
        Snackbar.make(rootView, R.string.extract_audio_success, Snackbar.LENGTH_LONG)
                .setAction(R.string.extract_audio_play_now, v -> playExtractedFile(outputFile, trackTitle))
                .show();
    }

    private void playExtractedFile(File outputFile, String trackTitle) {
        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.fromFile(outputFile))
                .setMediaId(outputFile.getAbsolutePath())
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(trackTitle).build())
                .build();
        playerConnection.playQueue(Collections.singletonList(item), 0);
    }

    // ---- Permissions ----

    private static String audioPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private static String videoPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void ensurePermissionsThenStart() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, audioPermission()) != PackageManager.PERMISSION_GRANTED)
            needed.add(audioPermission());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);

        if (needed.isEmpty()) startLibrary();
        else permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void ensureVideoPermissionThenWatch() {
        if (ContextCompat.checkSelfPermission(this, videoPermission()) == PackageManager.PERMISSION_GRANTED)
            startVideoWatching();
        else
            videoPermissionLauncher.launch(videoPermission());
    }

    private void startLibrary() {
        libraryRepository.getLibrary().observe(this, songs -> {
            currentSongs = songs;
            adapter.submitList(new ArrayList<>(songs));
            boolean songsTabVisible = tabLayout.getSelectedTabPosition() == 0;
            emptyState.setVisibility(songsTabVisible && songs.isEmpty() ? View.VISIBLE : View.GONE);
        });
        libraryRepository.isScanning().observe(this, scanning ->
                swipeRefresh.setRefreshing(Boolean.TRUE.equals(scanning)));
        libraryRepository.startWatching();
        playerConnection.connect();
    }

    private void startVideoWatching() {
        videoRepository.getVideos().observe(this, videos -> {
            videoAdapter.submitList(new ArrayList<>(videos));
            boolean videosTabVisible = tabLayout.getSelectedTabPosition() == 1;
            emptyVideoState.setVisibility(videosTabVisible && videos.isEmpty() ? View.VISIBLE : View.GONE);
        });
        videoRepository.isScanning().observe(this, scanning ->
                videoSwipeRefresh.setRefreshing(Boolean.TRUE.equals(scanning)));
        videoRepository.startWatching();
    }

    // ---- PlayerConnection.Callback ----

    @Override
    public void onConnected(MediaController controller) {
        onPlaybackStateChanged();
    }

    @Override
    public void onPlaybackStateChanged() {
        MediaController controller = playerConnection.getController();
        if (controller == null || controller.getMediaItemCount() == 0) {
            playerBar.setVisibility(View.GONE);
            return;
        }
        playerBar.setVisibility(View.VISIBLE);

        MediaMetadata meta = controller.getMediaMetadata();
        barTitle.setText(meta.title != null ? meta.title : "");
        barSubtitle.setText(meta.artist != null ? meta.artist : "");

        Glide.with(barArt.getContext())
                .load(meta.artworkUri)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .centerCrop()
                .into(barArt);

        barPlayPause.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        if (controller.getCurrentMediaItem() != null)
            adapter.setPlayingPath(controller.getCurrentMediaItem().mediaId);

        // Keep the full sheet in sync if it's open
        if (nowPlayingSheet != null) nowPlayingSheet.onPlaybackStateChanged();
    }
}
