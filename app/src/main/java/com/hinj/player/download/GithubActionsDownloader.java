package com.hinj.player.download;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;

import com.hinj.player.data.HinjStorage;
import com.hinj.player.prefs.HinjPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Triggers a GitHub Actions workflow that downloads audio from YouTube via
 * Puppeteer/y2mate.gs, then retrieves the resulting artifact.
 *
 * Flow: trigger dispatch → poll until complete → download artifact zip →
 * extract mp3 → save to Music/Hinj → download thumbnail.
 */
public class GithubActionsDownloader {

    private static final String API           = "https://api.github.com";
    private static final String WORKFLOW_FILE = "download_yt.yml";
    private static final int    POLL_MS       = 5_000;
    private static final int    TIMEOUT_MS    = 600_000; // 10 minutes

    public interface Callback {
        /** Called on main thread. progress = -1 means indeterminate. */
        void onStatus(String message, int progress);
        void onSuccess(File audioFile, File thumbFile);
        void onError(String message);
        void onCancelled();
    }

    private volatile boolean      cancelled = false;
    private final ExecutorService executor  = Executors.newSingleThreadExecutor();
    private final Handler         main      = new Handler(Looper.getMainLooper());

    public void cancel() { cancelled = true; }

    public void download(Context ctx, String ytUrl, YtSongInfo info,
                         String title, String artist, Callback cb) {
        cancelled = false;
        executor.execute(() -> {
            try {
                HinjPrefs prefs  = new HinjPrefs(ctx);
                String    token  = prefs.getGithubToken();
                String    owner  = prefs.getGithubOwner();
                String    repo   = prefs.getGithubRepo();
                String    branch = prefs.getGithubBranch();

                if (token.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
                    postError(cb, "GitHub not configured — open Settings → GitHub Downloads");
                    return;
                }

                // Record a timestamp slightly before triggering so we can identify the run
                long beforeMs = System.currentTimeMillis() - 5_000L;

                postStatus(cb, "Queuing on GitHub Actions…", -1);
                triggerWorkflow(owner, repo, branch, token, ytUrl, sanitize(title));
                if (cancelled) { postCancelled(cb); return; }

                postStatus(cb, "Waiting for job to start…", -1);
                Thread.sleep(12_000); // GitHub takes ~10s to queue
                if (cancelled) { postCancelled(cb); return; }

                long runId = findRunId(owner, repo, token, beforeMs);
                postStatus(cb, "Running on GitHub Actions…", -1);

                pollUntilComplete(owner, repo, token, runId, cb);
                if (cancelled) { postCancelled(cb); return; }

                postStatus(cb, "Downloading audio to device…", -1);
                File zipFile = downloadArtifactZip(owner, repo, token, runId);
                if (cancelled) { zipFile.delete(); postCancelled(cb); return; }

                File dir      = HinjStorage.musicDirFile();
                dir.mkdirs();
                String base      = sanitize(title);
                File   audioFile = uniqueFile(dir, base, ".mp3");
                extractAudio(zipFile, audioFile);
                zipFile.delete();
                if (cancelled) { audioFile.delete(); postCancelled(cb); return; }

                File thumbFile = downloadThumb(info.thumbnailUrl, dir, base);

                MediaScannerConnection.scanFile(ctx,
                        new String[]{audioFile.getAbsolutePath()}, null, null);

                File fAudio = audioFile, fThumb = thumbFile;
                main.post(() -> cb.onSuccess(fAudio, fThumb));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                postCancelled(cb);
            } catch (Exception e) {
                String msg = e.getMessage();
                postError(cb, msg != null ? msg : "Download failed");
            }
        });
    }

    // ── GitHub API ────────────────────────────────────────────────────────────

    private void triggerWorkflow(String owner, String repo, String branch,
                                  String token, String ytUrl, String outputName)
            throws Exception {
        String url = API + "/repos/" + owner + "/" + repo
                + "/actions/workflows/" + WORKFLOW_FILE + "/dispatches";

        JSONObject inputs = new JSONObject();
        inputs.put("youtube_url", ytUrl);
        inputs.put("output_name", outputName);

        JSONObject body = new JSONObject();
        body.put("ref", branch.isEmpty() ? "main" : branch);
        body.put("inputs", inputs);

        byte[]           payload = body.toString().getBytes("UTF-8");
        HttpURLConnection conn   = openJson(url, "POST", token);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(payload.length);
        conn.getOutputStream().write(payload);

        int code = conn.getResponseCode();
        conn.disconnect();

        if (code != 204) {
            throw new IOException("GitHub rejected the workflow trigger (HTTP " + code
                    + "). Check: (1) token has 'workflow' scope, "
                    + "(2) " + WORKFLOW_FILE + " exists in " + owner + "/" + repo
                    + ", (3) branch '" + branch + "' is correct.");
        }
    }

    private long findRunId(String owner, String repo, String token, long afterMs)
            throws Exception {
        String url = API + "/repos/" + owner + "/" + repo
                + "/actions/workflows/" + WORKFLOW_FILE
                + "/runs?event=workflow_dispatch&per_page=5";

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        for (int attempt = 0; attempt < 12; attempt++) {
            if (cancelled) throw new InterruptedException("Cancelled");

            HttpURLConnection conn = openJson(url, "GET", token);
            int    code = conn.getResponseCode();
            String body = readBody(conn);
            conn.disconnect();

            if (code == 200) {
                JSONArray runs = new JSONObject(body).optJSONArray("workflow_runs");
                if (runs != null) {
                    for (int i = 0; i < runs.length(); i++) {
                        JSONObject run       = runs.getJSONObject(i);
                        String     createdAt = run.optString("created_at", "");
                        if (!createdAt.isEmpty()) {
                            long runMs = sdf.parse(createdAt).getTime();
                            if (runMs >= afterMs) return run.getLong("id");
                        }
                    }
                }
            }
            Thread.sleep(5_000);
        }
        throw new IOException("Timed out waiting for the GitHub Actions run to appear in the API");
    }

    private void pollUntilComplete(String owner, String repo, String token,
                                    long runId, Callback cb) throws Exception {
        String url      = API + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId;
        long   deadline = System.currentTimeMillis() + TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (cancelled) throw new InterruptedException("Cancelled");
            Thread.sleep(POLL_MS);
            if (cancelled) throw new InterruptedException("Cancelled");

            HttpURLConnection conn = openJson(url, "GET", token);
            int    code = conn.getResponseCode();
            String body = readBody(conn);
            conn.disconnect();

            if (code != 200) continue;

            JSONObject run        = new JSONObject(body);
            String     status     = run.optString("status", "");
            String     conclusion = run.optString("conclusion", "null");

            if ("completed".equals(status)) {
                if (!"success".equals(conclusion)) {
                    throw new IOException("GitHub Actions job ended with '" + conclusion
                            + "'. Check the Actions tab in your repo for logs.");
                }
                return;
            }

            postStatus(cb, "queued".equals(status)
                    ? "Queued on GitHub Actions…"
                    : "Running on GitHub Actions…", -1);
        }
        throw new IOException("Timed out (10 min) waiting for GitHub Actions to finish");
    }

    private File downloadArtifactZip(String owner, String repo, String token, long runId)
            throws Exception {
        // List artifacts for the run
        String listUrl = API + "/repos/" + owner + "/" + repo
                + "/actions/runs/" + runId + "/artifacts";
        HttpURLConnection conn = openJson(listUrl, "GET", token);
        String body = readBody(conn);
        conn.disconnect();

        JSONArray artifacts = new JSONObject(body).optJSONArray("artifacts");
        if (artifacts == null || artifacts.length() == 0) {
            throw new IOException("No artifacts found — the workflow may have failed to produce audio");
        }
        long artifactId = artifacts.getJSONObject(0).getLong("id");

        // GitHub returns a 302 redirect to a temporary S3 URL for the zip
        String            zipApiUrl = API + "/repos/" + owner + "/" + repo
                + "/actions/artifacts/" + artifactId + "/zip";
        HttpURLConnection dlConn = openJson(zipApiUrl, "GET", token);
        dlConn.setInstanceFollowRedirects(false);
        int    code     = dlConn.getResponseCode();
        String location = dlConn.getHeaderField("Location");
        dlConn.disconnect();

        if ((code == 301 || code == 302 || code == 307) && location != null) {
            // Follow redirect to S3 without the GitHub auth header
            dlConn = (HttpURLConnection) new URL(location).openConnection();
            dlConn.setConnectTimeout(30_000);
            dlConn.setReadTimeout(120_000);
            code = dlConn.getResponseCode();
        }

        if (code != 200) throw new IOException("Failed to download artifact: HTTP " + code);

        File tmpDir = HinjStorage.musicDirFile().getParentFile();
        if (tmpDir == null) throw new IOException("Could not determine storage directory");
        tmpDir.mkdirs();
        File zipFile = new File(tmpDir, "hinj_dl_tmp.zip");

        try (InputStream in = dlConn.getInputStream();
             FileOutputStream out = new FileOutputStream(zipFile)) {
            byte[] buf = new byte[32_768];
            int    n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        dlConn.disconnect();
        return zipFile;
    }

    private void extractAudio(File zipFile, File dest) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() &&
                        (name.endsWith(".mp3") || name.endsWith(".m4a")
                                || name.endsWith(".webm") || name.endsWith(".ogg"))) {
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        byte[] buf = new byte[32_768];
                        int    n;
                        while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    zis.closeEntry();
                    return;
                }
                zis.closeEntry();
            }
        }
        throw new IOException("No audio file found inside the downloaded artifact ZIP");
    }

    private File downloadThumb(String thumbUrl, File dir, String base) {
        if (thumbUrl == null || thumbUrl.isEmpty()) return null;
        File thumbFile = new File(dir, base + ".jpg");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(thumbUrl).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(thumbFile)) {
                    byte[] buf = new byte[8192];
                    int    n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            thumbFile.delete();
            return null;
        }
        return thumbFile;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private HttpURLConnection openJson(String urlStr, String method, String token)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return c;
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        int         code = conn.getResponseCode();
        InputStream raw  = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (raw == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int    n;
        while ((n = raw.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString("UTF-8");
    }

    // ── Filename helpers ──────────────────────────────────────────────────────

    static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        int  i = 1;
        while (f.exists()) f = new File(dir, base + " (" + i++ + ")" + ext);
        return f;
    }

    // ── Callback helpers ──────────────────────────────────────────────────────

    private void postStatus(Callback cb, String msg, int progress) {
        main.post(() -> cb.onStatus(msg, progress));
    }

    private void postError(Callback cb, String msg) {
        main.post(() -> cb.onError(msg));
    }

    private void postCancelled(Callback cb) {
        main.post(cb::onCancelled);
    }
}
