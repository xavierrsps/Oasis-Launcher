package com.oasis.launcher.update;

import com.google.gson.Gson;
import com.oasis.launcher.model.Manifest;
import com.oasis.launcher.model.NewsFeed;
import com.oasis.launcher.model.ServerStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches launcher metadata (manifest, news, server status) over HTTPS.
 *
 * <p>All fetches use a short timeout so a hung server doesn't lock up the UI.
 */
public class ManifestFetcher {

    private static final Logger logger = LogManager.getLogger(ManifestFetcher.class);

    /** Owner-controlled repo hosting manifest.json / news.json / status.json + the Release binaries.
     *  Format: "GitHubUser/repo-name". This is the launcher's trust root — it decides which client to
     *  download and where it points, so keep it in a repo only the owner controls. */
    private static final String LAUNCHER_FILES_REPO = "xavierrsps/Oasis-Launcher";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/" + LAUNCHER_FILES_REPO + "/main/";

    public static final String MANIFEST_URL = RAW_BASE + "manifest.json";
    public static final String NEWS_URL     = RAW_BASE + "news.json";
    public static final String STATUS_URL   = RAW_BASE + "status.json";

    private final HttpClient http;
    private final Gson gson = new Gson();

    public ManifestFetcher() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Manifest fetchManifest() throws IOException, InterruptedException {
        String json = fetch(MANIFEST_URL);
        return gson.fromJson(json, Manifest.class);
    }

    public NewsFeed fetchNews() throws IOException, InterruptedException {
        String json = fetch(NEWS_URL);
        return gson.fromJson(json, NewsFeed.class);
    }

    public ServerStatus fetchStatus() throws IOException, InterruptedException {
        String json = fetch(STATUS_URL);
        return gson.fromJson(json, ServerStatus.class);
    }

    private String fetch(String url) throws IOException, InterruptedException {
        logger.info("Fetching {}", url);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Oasis-Launcher/1.0")
                .GET()
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }
}
