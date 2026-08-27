package com.oasis.launcher.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oasis.launcher.model.LocalState;
import com.oasis.launcher.model.Manifest;
import com.oasis.launcher.util.Hashing;
import com.oasis.launcher.util.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * High-level orchestrator: fetches manifest, diffs against local state,
 * downloads new/changed files, persists state.
 *
 * <p>Designed to be called from a background thread. UI updates happen via
 * the {@link ProgressListener} callback.
 */
public class UpdateManager {

    private static final Logger logger = LogManager.getLogger(UpdateManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ManifestFetcher fetcher = new ManifestFetcher();
    private final Downloader downloader = new Downloader();

    /**
     * Callback for the UI to display update progress.
     */
    public interface ProgressListener {
        /** Status text for the user, e.g. "Downloading Oasis.jar (1/3)". */
        void status(String message);

        /** Bytes for the *current* file. */
        void fileProgress(long downloaded, long total);

        /** Overall progress as a 0.0..1.0 fraction. */
        void overallProgress(double fraction);
    }

    /**
     * Runs the full update flow.
     *
     * @return the loaded manifest, in case callers want to display version info
     * @throws Exception on any failure (network, hash mismatch, etc.)
     */
    public Manifest update(ProgressListener listener) throws Exception {
        listener.status("Checking for updates…");
        Manifest manifest = fetcher.fetchManifest();
        LocalState state = loadState();

        // Build the list of files that actually need downloading.
        List<Manifest.ManifestFile> todo = new ArrayList<>();
        for (Manifest.ManifestFile file : manifest.files) {
            Path target = resolveTarget(file);
            String localHash = state.getHash(file.name);
            // CACHE_ZIP is intentionally deleted after extraction, so a
            // missing zip on disk does NOT imply the cache is missing —
            // trust the recorded hash. For everything else, missing-on-disk
            // forces a redownload.
            boolean missingOnDisk = file.type != Manifest.FileType.CACHE_ZIP
                    && !Files.exists(target);
            if (missingOnDisk
                    || localHash == null
                    || !localHash.equalsIgnoreCase(file.sha256)) {
                todo.add(file);
            }
        }

        if (todo.isEmpty()) {
            listener.status("Up to date");
            listener.overallProgress(1.0);
            return manifest;
        }

        long totalBytes = 0;
        for (Manifest.ManifestFile file : todo) {
            totalBytes += file.size;
        }
        long completedBytes = 0;

        for (int i = 0; i < todo.size(); i++) {
            Manifest.ManifestFile file = todo.get(i);
            listener.status(String.format("Downloading %s (%d/%d)",
                    file.name, i + 1, todo.size()));
            Path target = resolveTarget(file);

            final long baseBytes = completedBytes;
            final long grandTotal = totalBytes;
            downloader.download(file.url, target, file.sha256, (down, t) -> {
                listener.fileProgress(down, t);
                listener.overallProgress(Math.min(1.0,
                        (baseBytes + down) / (double) grandTotal));
            });

            // Cache zips: download → verify → extract into cache/, then drop archive.
            if (file.type == Manifest.FileType.CACHE_ZIP) {
                listener.status("Extracting " + file.name + "…");
                extractCacheZip(target);
            }

            completedBytes += file.size;
            state.setHash(file.name, file.sha256);
            saveState(state);  // checkpoint after each file
        }

        state.clientVersion = manifest.clientVersion;
        state.cacheVersion = manifest.cacheVersion;
        state.launcherVersion = manifest.launcherVersion;
        saveState(state);

        listener.status("Update complete");
        listener.overallProgress(1.0);
        return manifest;
    }

    /** Resolves the on-disk target path for a manifest entry based on its type. */
    private Path resolveTarget(Manifest.ManifestFile file) {
        return switch (file.type) {
            case CLIENT -> Platform.clientJar();
            case CACHE -> Platform.cacheDir().resolve(file.name);
            case CACHE_ZIP -> Platform.cacheDir().resolve(file.name);
            case LAUNCHER -> Platform.dataDir().resolve("launcher-update.jar");
            case RESOURCE -> Platform.dataDir().resolve(file.name);
        };
    }

    /**
     * Extracts a CACHE_ZIP file into the cache directory then deletes the zip.
     * Skip-files-that-exist behavior keeps repeated runs cheap.
     */
    private void extractCacheZip(Path zipPath) throws IOException {
        Path cacheDir = Platform.cacheDir();
        Files.createDirectories(cacheDir);
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(zipPath)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path target = cacheDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(cacheDir)) {
                    // zip-slip defence
                    logger.warn("Skipping suspicious zip entry outside cache dir: {}", entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (java.io.OutputStream out = new java.io.BufferedOutputStream(
                            Files.newOutputStream(target))) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = zin.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
            }
        }
        Files.deleteIfExists(zipPath);
        logger.info("Extracted cache zip to {} and removed source archive.", cacheDir);
    }

    private LocalState loadState() {
        Path stateFile = Platform.stateFile();
        if (!Files.exists(stateFile)) {
            return new LocalState();
        }
        try {
            String json = Files.readString(stateFile);
            LocalState state = GSON.fromJson(json, LocalState.class);
            return state != null ? state : new LocalState();
        } catch (Exception e) {
            logger.warn("Could not read version.local, starting fresh: {}", e.getMessage());
            return new LocalState();
        }
    }

    private void saveState(LocalState state) throws IOException {
        Path stateFile = Platform.stateFile();
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, GSON.toJson(state));
    }
}
