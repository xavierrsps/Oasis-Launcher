package com.oasis.launcher.update;

import com.google.gson.Gson;
import com.oasis.launcher.model.DiscordConfig;
import com.oasis.launcher.model.NewsFeed;
import com.oasis.launcher.model.ServerStatus;
import com.oasis.launcher.model.VersionInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches launcher metadata over HTTPS from the owner-controlled launcher-files repo.
 *
 * <p>Schema (three small JSON files at the repo root, all human-editable):
 * <ul>
 *   <li>{@code version.json} — launcher self-update ({@link VersionInfo})</li>
 *   <li>{@code updates.json} — the "Recent Updates" feed ({@link NewsFeed})</li>
 *   <li>{@code status.json}  — live server status ({@link ServerStatus}); optional</li>
 * </ul>
 */
public class ManifestFetcher {

    private static final Logger logger = LogManager.getLogger(ManifestFetcher.class);

    /** Owner-controlled repo hosting version.json / updates.json / status.json + the Release binaries.
     *  This is the launcher's trust root — it decides which build to self-update to. */
    private static final String LAUNCHER_FILES_REPO = "xavierrsps/Oasis-Launcher";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/" + LAUNCHER_FILES_REPO + "/main/";

    public static final String VERSION_URL = RAW_BASE + "version.json";
    public static final String NEWS_URL    = RAW_BASE + "updates.json";
    public static final String STATUS_URL  = RAW_BASE + "status.json";
    public static final String DISCORD_URL = RAW_BASE + "discord.json";

    private final HttpClient http;
    private final Gson gson = new Gson();

    public ManifestFetcher() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public VersionInfo fetchVersionInfo() throws IOException, InterruptedException {
        return gson.fromJson(fetch(VERSION_URL), VersionInfo.class);
    }

    public NewsFeed fetchNews() throws IOException, InterruptedException {
        return gson.fromJson(fetch(NEWS_URL), NewsFeed.class);
    }

    /**
     * Per-game-base "Recent Updates" feed, from {@code updates-<baseId>.json} (e.g. {@code updates-oasis3.json}).
     * Each base gets its own file so a Discord sync bot can point one channel at each. The feed may also carry a
     * {@code "status"} string — the base's current-status line shown in the launcher.
     */
    public NewsFeed fetchNews(String baseId) throws IOException, InterruptedException {
        return gson.fromJson(fetch(RAW_BASE + "updates-" + baseId + ".json"), NewsFeed.class);
    }

    public ServerStatus fetchStatus() throws IOException, InterruptedException {
        return gson.fromJson(fetch(STATUS_URL), ServerStatus.class);
    }

    /** Discord integration config (verify + invite URLs). */
    public DiscordConfig fetchDiscordConfig() throws IOException, InterruptedException {
        return gson.fromJson(fetch(DISCORD_URL), DiscordConfig.class);
    }

    private String fetch(String url) throws IOException, InterruptedException {
        logger.info("Fetching {}", url);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Oasis-Launcher/2.0")
                .GET()
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }
}
