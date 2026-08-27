package com.oasis.launcher.update;

import com.oasis.launcher.model.VersionInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Detects and applies launcher self-updates from {@code version.json}.
 *
 * <p>Flow: compare the running version against {@code version.json}'s {@code version}; if newer,
 * download its {@code url} (the installer) to a temp file and spawn {@code msiexec} detached (so it
 * gets its own UAC elevation), then exit so the file can be replaced. The installer restarts the app.
 */
public class LauncherSelfUpdater {

    private static final Logger logger = LogManager.getLogger(LauncherSelfUpdater.class);

    /** Running launcher version — from the JAR manifest (Gradle sets it from project.version), or the
     *  jpackage app-version, falling back to a constant only during {@code gradle run}. */
    public static final String CURRENT_VERSION = detectVersion();

    private static String detectVersion() {
        String v = LauncherSelfUpdater.class.getPackage().getImplementationVersion();
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty("jpackage.app-version");
        if (v != null && !v.isBlank()) return v;
        return "2.0.0";
    }

    private final Downloader downloader = new Downloader();

    /** True if {@code version.json} advertises a version newer than the running launcher. */
    public boolean hasUpdate(VersionInfo info) {
        if (info == null || info.version == null || info.url == null) {
            return false;
        }
        logger.info("Comparing version.json {} against current {}", info.version, CURRENT_VERSION);
        return isNewer(info.version, CURRENT_VERSION);
    }

    /**
     * Downloads the installer and spawns msiexec detached, then exits. Does not return on success.
     * (The installer path is chosen by extension; {@code version.json} carries no hash, so the file is
     * trusted by virtue of coming from the owner-controlled repo/Releases.)
     */
    public void applyUpdate(VersionInfo info, Downloader.ProgressCallback progress) throws IOException {
        boolean msi = info.url.toLowerCase().endsWith(".msi");
        Path installer = Paths.get(System.getProperty("java.io.tmpdir"),
                msi ? "oasis-launcher-update.msi" : "oasis-launcher-update.exe");

        logger.info("Downloading launcher update to {}", installer);
        downloader.download(info.url, installer, null, progress);
        if (!Files.exists(installer) || Files.size(installer) == 0) {
            throw new IOException("Downloaded installer is missing or empty: " + installer);
        }

        // Relaunch target: the app exe sits next to the bundled runtime (java.home/../Oasis.exe).
        String relaunch = "";
        try {
            Path exe = Paths.get(System.getProperty("java.home")).getParent().resolve("Oasis.exe");
            relaunch = "start \"\" \"" + exe.toAbsolutePath() + "\"";
        } catch (Exception ignored) {
        }

        // A tiny script waits for THIS process to exit (so files aren't locked), installs the update
        // silently (per-user, no UAC), then relaunches the app. Spawned detached so it outlives us.
        String install = msi
                ? "msiexec /i \"" + installer.toAbsolutePath() + "\" /qn /norestart REINSTALLMODE=amus"
                : "\"" + installer.toAbsolutePath() + "\" /qn";
        Path bat = Paths.get(System.getProperty("java.io.tmpdir"), "oasis-update.bat");
        Files.writeString(bat, "@echo off\r\n"
                + "timeout /t 2 /nobreak >nul\r\n"
                + install + "\r\n"
                + relaunch + "\r\n"
                + "del \"%~f0\"\r\n");

        logger.info("Spawning silent updater: {}", bat);
        new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", bat.toAbsolutePath().toString()).start();

        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        logger.info("Exiting launcher so the installer can replace files…");
        System.exit(0);
    }

    /** True if {@code candidate} is a strictly higher dotted version than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) return false;
        if (current == null || current.isBlank()) return true;
        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int cmp = seg(i < a.length ? a[i] : "0", i < b.length ? b[i] : "0");
            if (cmp != 0) return cmp > 0;
        }
        return false;
    }

    private static int seg(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
