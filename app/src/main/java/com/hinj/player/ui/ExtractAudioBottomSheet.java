package com.hinj.player.ui;

import android.Manifest;
import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.hinj.player.R;
import com.hinj.player.data.HinjStorage;
import com.hinj.player.extract.AudioExtractor;

import java.io.File;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets the user turn one video into a library track: edit the output title,
 * optionally delete the source video afterwards, watch progress, and land
 * back in the app with the new track already playable. Extraction itself
 * keeps running even if the user swipes the sheet away mid-progress isn't
 * allowed here on purpose — we lock the sheet open (non-cancelable) while
 * extracting so the user always sees how it finished.
 */
public class ExtractAudioBottomSheet extends BottomSheetDialogFragment {

    public interface Listener {
        /** Called once the .m4a is written and (if requested) the source video is gone. */
        void onExtractionComplete(File outputFile, String trackTitle);
    }

    private static final String ARG_SOURCE_KEY = "source_key";
    private static final String ARG_URI = "uri";
    private static final String ARG_NAME = "name";
    private static final String ARG_DURATION = "duration";
    private static final String ARG_DELETABLE = "deletable";

    public static ExtractAudioBottomSheet newInstance(com.hinj.player.data.VideoItem video) {
        Bundle args = new Bundle();
        args.putString(ARG_SOURCE_KEY, video.sourceKey);
        args.putString(ARG_URI, video.contentUri.toString());
        args.putString(ARG_NAME, video.displayName);
        args.putLong(ARG_DURATION, video.durationMs);
        args.putBoolean(ARG_DELETABLE, video.deletable);

        ExtractAudioBottomSheet sheet = new ExtractAudioBottomSheet();
        sheet.setArguments(args);
        return sheet;
    }

    private interface DeleteCallback {
        void onResult(boolean deleted);
    }

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final AudioExtractor extractor = new AudioExtractor();
    private boolean extracting = false;

    // State for the two consent round-trips a scoped-storage delete may need.
    private DeleteCallback pendingDeleteCallback;
    private Uri pendingDeleteMediaUri;
    private Uri pendingLegacyDeleteUri;
    private DeleteCallback pendingLegacyDeleteCallback;

    private final ActivityResultLauncher<IntentSenderRequest> deleteConsentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                DeleteCallback cb = pendingDeleteCallback;
                Uri mediaUri = pendingDeleteMediaUri;
                pendingDeleteCallback = null;
                pendingDeleteMediaUri = null;
                if (cb == null) return;

                boolean approved = result.getResultCode() == Activity.RESULT_OK;
                if (!approved) {
                    cb.onResult(false);
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // MediaStore.createDeleteRequest performs the deletion itself once approved.
                    cb.onResult(true);
                } else {
                    // API 29: consent just cleared the RecoverableSecurityException — retry now.
                    ioExecutor.execute(() -> {
                        boolean ok;
                        try {
                            requireContext().getContentResolver().delete(mediaUri, null, null);
                            ok = true;
                        } catch (Exception e) {
                            ok = false;
                        }
                        boolean finalOk = ok;
                        if (isAdded()) requireActivity().runOnUiThread(() -> cb.onResult(finalOk));
                    });
                }
            });

    private final ActivityResultLauncher<String> writePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                DeleteCallback cb = pendingLegacyDeleteCallback;
                Uri uri = pendingLegacyDeleteUri;
                pendingLegacyDeleteCallback = null;
                pendingLegacyDeleteUri = null;
                if (cb == null) return;
                if (Boolean.TRUE.equals(granted)) {
                    deleteLegacy(uri, cb);
                } else {
                    cb.onResult(false);
                }
            });

    private Uri sourceUri;
    private long durationMs;
    private boolean sourceDeletable;

    private TextView sheetVideoDuration;
    private TextInputEditText titleInput;
    private MaterialSwitch deleteOriginalSwitch;
    private TextView deleteOriginalLabel;
    private View progressGroup;
    private ProgressBar progressBar;
    private MaterialButton cancelButton;
    private MaterialButton extractButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_extract_audio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();

        sourceUri = Uri.parse(args.getString(ARG_URI));
        String displayName = args.getString(ARG_NAME, "Track");
        durationMs = args.getLong(ARG_DURATION, 0L);
        sourceDeletable = args.getBoolean(ARG_DELETABLE, false);

        android.widget.ImageView thumb = view.findViewById(R.id.sheetVideoThumb);
        sheetVideoDuration = view.findViewById(R.id.sheetVideoDuration);
        titleInput = view.findViewById(R.id.sheetTitleInput);
        deleteOriginalSwitch = view.findViewById(R.id.sheetDeleteOriginalSwitch);
        deleteOriginalLabel = view.findViewById(R.id.sheetDeleteOriginalLabel);
        progressGroup = view.findViewById(R.id.sheetProgressGroup);
        progressBar = view.findViewById(R.id.sheetProgressBar);
        cancelButton = view.findViewById(R.id.sheetCancelButton);
        extractButton = view.findViewById(R.id.sheetExtractButton);

        Glide.with(thumb.getContext()).load(sourceUri)
                .placeholder(R.drawable.ic_video)
                .error(R.drawable.ic_video)
                .centerCrop()
                .into(thumb);

        sheetVideoDuration.setText(formatDuration(durationMs));
        titleInput.setText(displayName);
        titleInput.setSelection(displayName.length());

        if (!sourceDeletable) {
            deleteOriginalSwitch.setEnabled(false);
            deleteOriginalSwitch.setChecked(false);
            deleteOriginalLabel.setAlpha(0.5f);
            deleteOriginalLabel.setText(R.string.extract_audio_delete_original_unavailable);
        }

        cancelButton.setOnClickListener(v -> {
            if (extracting) {
                extractor.cancel();
            } else {
                dismiss();
            }
        });

        extractButton.setOnClickListener(v -> startExtraction());
    }

    private void startExtraction() {
        String desiredTitle = titleInput.getText() != null ? titleInput.getText().toString().trim() : "";
        if (desiredTitle.isEmpty()) {
            titleInput.setError(getString(R.string.extract_audio_output_name));
            return;
        }

        setExtractingUi(true);

        ioExecutor.execute(() -> {
            HinjStorage.ensureDirectoryExists(requireContext().getApplicationContext());
            File outputFile = uniqueOutputFile(HinjStorage.musicDirFile(), sanitizeFileName(desiredTitle));

            requireActivity().runOnUiThread(() -> runExtractionOn(outputFile, desiredTitle));
        });
    }

    private void runExtractionOn(File outputFile, String trackTitle) {
        if (!isAdded()) return;

        extractor.extract(requireContext(), sourceUri, durationMs, outputFile, new AudioExtractor.Callback() {
            @Override
            public void onProgress(int percent) {
                if (!isAdded()) return;
                progressBar.setProgress(percent);
            }

            @Override
            public void onSuccess(File file) {
                if (!isAdded()) return;
                MediaScannerConnection.scanFile(requireContext().getApplicationContext(),
                        new String[]{file.getAbsolutePath()}, null, null);

                if (deleteOriginalSwitch.isChecked() && sourceDeletable) {
                    deleteOriginalVideo(sourceUri, deleted -> {
                        if (!deleted && isAdded()) {
                            Toast.makeText(requireContext(), R.string.extract_audio_delete_failed, Toast.LENGTH_SHORT).show();
                        }
                        finishWithSuccess(file, trackTitle);
                    });
                } else {
                    finishWithSuccess(file, trackTitle);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                setExtractingUi(false);
                titleInput.setError(null);
                com.google.android.material.snackbar.Snackbar
                        .make(requireView(), message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .show();
            }

            @Override
            public void onCancelled() {
                if (!isAdded()) return;
                setExtractingUi(false);
            }
        });
    }

    private void finishWithSuccess(File file, String trackTitle) {
        if (getActivity() instanceof Listener) {
            ((Listener) getActivity()).onExtractionComplete(file, trackTitle);
        }
        dismissAllowingStateLoss();
    }

    private void setExtractingUi(boolean isExtracting) {
        extracting = isExtracting;
        progressGroup.setVisibility(isExtracting ? View.VISIBLE : View.GONE);
        progressBar.setProgress(0);
        titleInput.setEnabled(!isExtracting);
        deleteOriginalSwitch.setEnabled(!isExtracting && sourceDeletable);
        extractButton.setEnabled(!isExtracting);
        extractButton.setAlpha(isExtracting ? 0.5f : 1f);
        setCancelable(!isExtracting);

        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog != null) {
            dialog.setCanceledOnTouchOutside(!isExtracting);
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setHideable(!isExtracting);
            if (isExtracting) {
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    @Override
    public void onDestroyView() {
        extractor.cancel();
        super.onDestroyView();
    }

    // ---- Delete-original-video, scoped-storage aware ----

    private void deleteOriginalVideo(Uri fileUri, DeleteCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            deleteLegacy(fileUri, callback);
        } else {
            deleteScoped(fileUri, callback);
        }
    }

    private void deleteLegacy(Uri fileUri, DeleteCallback callback) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLegacyDeleteUri = fileUri;
            pendingLegacyDeleteCallback = callback;
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        ioExecutor.execute(() -> {
            boolean ok = tryDirectDelete(fileUri);
            if (isAdded()) requireActivity().runOnUiThread(() -> callback.onResult(ok));
        });
    }

    private void deleteScoped(Uri fileUri, DeleteCallback callback) {
        ioExecutor.execute(() -> {
            if (tryDirectDelete(fileUri)) {
                if (isAdded()) requireActivity().runOnUiThread(() -> callback.onResult(true));
                return;
            }

            Uri mediaUri = resolveMediaStoreUri(requireContext(), fileUri);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (mediaUri == null) {
                    callback.onResult(false);
                    return;
                }
                requestScopedDelete(mediaUri, callback);
            });
        });
    }

    private void requestScopedDelete(Uri mediaUri, DeleteCallback callback) {
        try {
            IntentSender sender;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sender = MediaStore.createDeleteRequest(requireContext().getContentResolver(),
                        Collections.singletonList(mediaUri)).getIntentSender();
            } else {
                try {
                    requireContext().getContentResolver().delete(mediaUri, null, null);
                    callback.onResult(true);
                    return;
                } catch (RecoverableSecurityException rse) {
                    sender = rse.getUserAction().getActionIntent().getIntentSender();
                }
            }
            pendingDeleteCallback = callback;
            pendingDeleteMediaUri = mediaUri;
            deleteConsentLauncher.launch(new IntentSenderRequest.Builder(sender).build());
        } catch (Exception e) {
            callback.onResult(false);
        }
    }

    private boolean tryDirectDelete(Uri fileUri) {
        if (!"file".equals(fileUri.getScheme()) || fileUri.getPath() == null) return false;
        try {
            return new File(fileUri.getPath()).delete();
        } catch (SecurityException e) {
            return false;
        }
    }

    private static Uri resolveMediaStoreUri(Context context, Uri fileUri) {
        String path = fileUri.getPath();
        if (path == null) return null;
        String[] projection = {MediaStore.Video.Media._ID};
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
                MediaStore.Video.Media.DATA + "=?", new String[]{path}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String sanitizeFileName(String raw) {
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "Extracted Audio" : cleaned;
    }

    private static File uniqueOutputFile(File dir, String baseName) {
        File candidate = new File(dir, baseName + ".m4a");
        int suffix = 1;
        while (candidate.exists()) {
            candidate = new File(dir, baseName + " (" + suffix + ").m4a");
            suffix++;
        }
        return candidate;
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }
}
