package com.oasis.launcher.update;

import com.oasis.launcher.util.Hashing;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * HTTP file downloader with progress callbacks and SHA-256 verification.
 *
 * <p>Files are downloaded to a {@code .part} sibling file first, then atomically
 * renamed on success — so a crashed download doesn't corrupt the existing file.
 */
public class Downloader {

    private static final Logger logger = LogManager.getLogger(Downloader.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Callback fired periodically during a download.
     */
    public interface ProgressCallback {
        /**
         * @param bytesDownloaded total bytes received so far
         * @param totalBytes      expected file size (or -1 if unknown)
         */
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    /**
     * Downloads {@code url} to {@code target}, verifies SHA-256 against
     * {@code expectedSha256}, and atomically replaces any existing file.
     *
     * <p>If the existing file already matches the expected hash, the download
     * is skipped — making partial updates fast.
     *
     * @throws IOException if download fails or hash mismatches
     */
    public void download(String url, Path target, String expectedSha256, ProgressCallback progress)
            throws IOException {

        // Skip if already up-to-date.
        if (Files.exists(target) && expectedSha256 != null) {
            String existing = Hashing.sha256(target);
            if (existing.equalsIgnoreCase(expectedSha256)) {
                logger.info("Skipping {} — already up to date", target.getFileName());
                if (progress != null) {
                    long size = Files.size(target);
                    progress.onProgress(size, size);
                }
                return;
            }
        }

        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");

        logger.info("Downloading {} → {}", url, target);

        URLConnection conn = URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "Oasis-Launcher/1.0");

        long total = conn.getContentLengthLong();
        long downloaded = 0;
        long lastReport = 0;

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(part)) {

            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                downloaded += n;
                // Throttle progress callbacks to ~30 fps to avoid UI thrash.
                long now = System.currentTimeMillis();
                if (progress != null && now - lastReport >= 33) {
                    progress.onProgress(downloaded, total);
                    lastReport = now;
                }
            }
        }
        if (progress != null) {
            progress.onProgress(downloaded, total);
        }

        // Verify hash before swapping in.
        if (expectedSha256 != null) {
            String actual = Hashing.sha256(part);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(part);
                throw new IOException("SHA-256 mismatch for " + target.getFileName()
                        + " — expected " + expectedSha256 + " but got " + actual);
            }
        }

        // Atomic swap.
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.info("Downloaded {} ({} bytes)", target.getFileName(), downloaded);
    }
}
