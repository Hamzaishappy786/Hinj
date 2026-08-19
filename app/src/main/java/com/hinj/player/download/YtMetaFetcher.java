package com.hinj.player.download;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches YouTube video metadata via the official YouTube oEmbed API.
 * This only retrieves title, channel, and thumbnail — not a stream URL.
 * Audio download is handled separately by GithubActionsDownloader.
 */
public class YtMetaFetcher {

    public interface Callback {
        void onSuccess(YtSongInfo info);
        void onError(String message);
    }

    private static final Pattern RE_WATCH = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern RE_SHORT  = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");
    private static final Pattern RE_EMBED  = Pattern.compile("youtube\\.com/(?:embed|shorts)/([A-Za-z0-9_-]{11})");

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public void fetch(String youtubeUrl, Callback callback) {
        executor.execute(() -> {
            try {
                String videoId = extractVideoId(youtubeUrl);
                if (videoId == null) {
                    postError(callback, "Not a valid YouTube URL");
                    return;
                }
                YtSongInfo info = fetchOembed(videoId);
                mainHandler.post(() -> callback.onSuccess(info));
            } catch (Exception e) {
                String msg = e.getMessage();
                postError(callback, msg != null ? msg : "Failed to get video info");
            }
        });
    }

    private YtSongInfo fetchOembed(String videoId) throws Exception {
        String ytUrl  = "https://www.youtube.com/watch?v=" + videoId;
        String apiUrl = "https://www.youtube.com/oembed?url="
                + URLEncoder.encode(ytUrl, "UTF-8") + "&format=json";

        HttpURLConnection conn = openGet(apiUrl);
        int    code = conn.getResponseCode();
        String body = readBody(conn);
        conn.disconnect();

        if (code == 401 || code == 403) throw new IOException("Video is private or age-restricted");
        if (code == 404) throw new IOException("Video not found or unavailable");
        if (code != 200) throw new IOException("YouTube returned HTTP " + code);

        JSONObject json    = new JSONObject(body);
        String     title   = json.optString("title", "Unknown");
        String     channel = json.optString("author_name", "");
        // Use a higher-res thumbnail from YouTube CDN regardless of what oEmbed returns
        String thumbUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        return new YtSongInfo(title, channel, thumbUrl, "", "mp3", 0L);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private HttpURLConnection openGet(String urlStr) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Hinj/1.0)");
        return c;
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        int         code = conn.getResponseCode();
        InputStream raw  = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (raw == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = raw.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString("UTF-8");
    }

    private void postError(Callback callback, String msg) {
        mainHandler.post(() -> callback.onError(msg));
    }

    public static String extractVideoId(String url) {
        if (url == null || url.isEmpty()) return null;
        for (Pattern p : new Pattern[]{RE_WATCH, RE_SHORT, RE_EMBED}) {
            Matcher m = p.matcher(url);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
