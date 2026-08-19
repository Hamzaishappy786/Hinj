package com.hinj.player.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.hinj.player.R;
import com.hinj.player.download.GithubActionsDownloader;
import com.hinj.player.download.YtMetaFetcher;
import com.hinj.player.download.YtSongInfo;

import java.io.File;

/**
 * Bottom-sheet: paste URL → preview metadata → trigger GitHub Actions download.
 */
public class YtDownloadSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onYtDownloadComplete(File audioFile, String title, String artist);
    }

    private enum State { INPUT, FETCHING, PREVIEW, DOWNLOADING }

    private final YtMetaFetcher          fetcher    = new YtMetaFetcher();
    private final GithubActionsDownloader downloader = new GithubActionsDownloader();

    private YtSongInfo currentInfo;
    private String     currentYtUrl;
    private String     currentTitle;
    private String     currentArtist;

    // INPUT group
    private View              groupInput;
    private TextInputEditText editUrl;
    private ProgressBar       fetchingProgress;
    private MaterialButton    btnGetInfo;

    // PREVIEW group
    private View              groupPreview;
    private ImageView         previewThumb;
    private TextInputEditText editTitle;
    private TextInputEditText editArtist;
    private TextView          previewDuration;
    private MaterialButton    btnDownload;
    private TextView          btnChangeLink;

    // DOWNLOADING group
    private View           groupDownloading;
    private ImageView      downloadingThumb;
    private TextView       downloadingTitle;
    private ProgressBar    downloadProgress;
    private TextView       progressLabel;
    private MaterialButton btnCancel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_yt_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        groupInput       = view.findViewById(R.id.groupInput);
        editUrl          = view.findViewById(R.id.editUrl);
        fetchingProgress = view.findViewById(R.id.fetchingProgress);
        btnGetInfo       = view.findViewById(R.id.btnGetInfo);

        groupPreview    = view.findViewById(R.id.groupPreview);
        previewThumb    = view.findViewById(R.id.previewThumb);
        editTitle       = view.findViewById(R.id.editTitle);
        editArtist      = view.findViewById(R.id.editArtist);
        previewDuration = view.findViewById(R.id.previewDuration);
        btnDownload     = view.findViewById(R.id.btnDownload);
        btnChangeLink   = view.findViewById(R.id.btnChangeLink);

        groupDownloading = view.findViewById(R.id.groupDownloading);
        downloadingThumb = view.findViewById(R.id.downloadingThumb);
        downloadingTitle = view.findViewById(R.id.downloadingTitle);
        downloadProgress = view.findViewById(R.id.downloadProgress);
        progressLabel    = view.findViewById(R.id.progressLabel);
        btnCancel        = view.findViewById(R.id.btnCancel);

        editUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                btnGetInfo.setEnabled(isValidYtUrl(s.toString()));
            }
        });

        btnGetInfo.setOnClickListener(v -> fetchMetadata());
        btnDownload.setOnClickListener(v -> startDownload());
        btnChangeLink.setOnClickListener(v -> showState(State.INPUT));
        btnCancel.setOnClickListener(v -> downloader.cancel());

        showState(State.INPUT);
    }

    @Override
    public void onDestroyView() {
        downloader.cancel();
        super.onDestroyView();
    }

    // ── metadata fetch ────────────────────────────────────────────────────────

    private void fetchMetadata() {
        String url = editUrl.getText() != null ? editUrl.getText().toString().trim() : "";
        if (url.isEmpty()) return;
        currentYtUrl = url;
        showState(State.FETCHING);

        fetcher.fetch(url, new YtMetaFetcher.Callback() {
            @Override
            public void onSuccess(YtSongInfo info) {
                if (!isAdded()) return;
                currentInfo = info;
                editTitle.setText(info.title);
                editArtist.setText(info.channelName != null ? info.channelName : "");
                previewDuration.setText("");

                if (info.thumbnailUrl != null && !info.thumbnailUrl.isEmpty()) {
                    Glide.with(previewThumb.getContext())
                            .load(info.thumbnailUrl)
                            .centerCrop()
                            .into(previewThumb);
                }
                showState(State.PREVIEW);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                showState(State.INPUT);
                showSnackbar(message);
            }
        });
    }

    // ── download ──────────────────────────────────────────────────────────────

    private void startDownload() {
        String title  = trimmedOr(editTitle,  currentInfo != null ? currentInfo.title       : "");
        String artist = trimmedOr(editArtist, currentInfo != null ? currentInfo.channelName : "");
        currentTitle  = title;
        currentArtist = artist;

        if (currentInfo == null || currentYtUrl == null) return;

        if (currentInfo.thumbnailUrl != null && !currentInfo.thumbnailUrl.isEmpty()) {
            Glide.with(downloadingThumb.getContext())
                    .load(currentInfo.thumbnailUrl)
                    .centerCrop()
                    .into(downloadingThumb);
        }
        downloadingTitle.setText(title);
        showState(State.DOWNLOADING);

        downloader.download(requireContext(), currentYtUrl, currentInfo, title, artist,
                new GithubActionsDownloader.Callback() {

            @Override
            public void onStatus(String message, int progress) {
                if (!isAdded()) return;
                if (progress < 0) {
                    downloadProgress.setIndeterminate(true);
                } else {
                    downloadProgress.setIndeterminate(false);
                    downloadProgress.setProgress(progress);
                }
                progressLabel.setText(message);
            }

            @Override
            public void onSuccess(File audioFile, File thumbFile) {
                if (!isAdded()) return;
                setLocked(false);
                if (getActivity() instanceof Listener) {
                    ((Listener) getActivity()).onYtDownloadComplete(
                            audioFile, currentTitle, currentArtist);
                }
                dismissAllowingStateLoss();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                setLocked(false);
                showState(State.PREVIEW);
                showSnackbar(message != null ? message : "Download failed");
            }

            @Override
            public void onCancelled() {
                if (!isAdded()) return;
                setLocked(false);
                showState(State.INPUT);
                if (editUrl != null) editUrl.setText("");
            }
        });
    }

    // ── state machine ─────────────────────────────────────────────────────────

    private void showState(State s) {
        groupInput.setVisibility(
                s == State.INPUT || s == State.FETCHING ? View.VISIBLE : View.GONE);
        fetchingProgress.setVisibility(s == State.FETCHING  ? View.VISIBLE : View.GONE);
        btnGetInfo.setVisibility(s == State.INPUT            ? View.VISIBLE : View.GONE);

        groupPreview.setVisibility(s == State.PREVIEW        ? View.VISIBLE : View.GONE);
        groupDownloading.setVisibility(s == State.DOWNLOADING ? View.VISIBLE : View.GONE);

        if (s == State.DOWNLOADING) {
            downloadProgress.setIndeterminate(true);
            progressLabel.setText("Starting…");
        }

        setLocked(s == State.DOWNLOADING);
    }

    private void setLocked(boolean locked) {
        setCancelable(!locked);
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog == null) return;
        dialog.setCanceledOnTouchOutside(!locked);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setHideable(!locked);
        if (locked) behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isValidYtUrl(String text) {
        if (text == null || text.isEmpty()) return false;
        return (text.contains("youtube.com") && text.contains("v="))
                || text.contains("youtu.be/")
                || text.contains("music.youtube.com");
    }

    private static String trimmedOr(TextInputEditText field, String fallback) {
        if (field == null || field.getText() == null) return fallback;
        String s = field.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private void showSnackbar(String message) {
        View root = getView();
        if (root != null) Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
    }
}
