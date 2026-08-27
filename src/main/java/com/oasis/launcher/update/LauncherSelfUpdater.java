package com.oasis.launcher.update;

import com.oasis.launcher.model.Manifest;
import com.oasis.launcher.util.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Detects and applies launcher self-updates.
 *
 * <p>The flow:
 * <ol>
 *   <li>Compare the running launcher version against the manifest's
 *       {@code launcherVersion}.</li>
 *   <li>If newer, find the matching {@link Manifest.FileType#LAUNCHER}
 *       entry in the manifest's file list.</li>
 *   <li>Download the new installer to a temp directory, verify SHA-256.</li>
 *   <li>Spawn {@code msiexec.exe} via {@code cmd /c start} so it detaches
 *       from the launcher and gets its own UAC elevation. Then exit the
 *       current launcher so the file can be replaced.</li>
 * </ol>
 *
 * <p>The MSI installer is responsible for replacing the old launcher and
 * (optionally) restarting it via shortcut after install.
 */
public class LauncherSelfUpdater {

    private static final Logger logger = LogManager.getLogger(LauncherSelfUpdater.class);

    /**
     * Version of the running launcher. Read dynamically from the JAR's
     * MANIFEST.MF (set automatically by Gradle from {@code project.version}).
     * Falls back to a hardcoded value only if the manifest entry is missing
     * (e.g. when running from IDE / gradle run without packaging).
     */
    public static final String CURRENT_VERSION = detectVersion();

    private static String detectVersion() {
        // 1. Implementation-Version from MANIFEST.MF (set by Gradle).
        String v = LauncherSelfUpdater.class.getPackage().getImplementationVersion();
        if (v != null && !v.isBlank()) {
            return v;
        }
        // 2. System property fallback - jpackage sets jpackage.app-version.
        v = System.getProperty("jpackage.app-version");
        if (v != null && !v.isBlank()) {
            return v;
        }
        // 3. Last-resort hardcoded fallback (only hit during 'gradle run').
        return "1.0.1";
    }

    private final Downloader downloader = new Downloader();

    /**
     * Returns the LAUNCHER file entry from the manifest if its version is
     * newer than {@link #CURRENT_VERSION}, otherwise empty.
     */
    public Optional<Manifest.ManifestFile> checkForUpdate(Manifest manifest) {
        if (manifest == null || manifest.launcherVersion == null) {
            return Optional.empty();
        }
        logger.info("Comparing manifest launcherVersion={} against current={}",
                manifest.launcherVersion, CURRENT_VERSION);
        if (!isNewer(manifest.launcherVersion, CURRENT_VERSION)) {
            logger.info("Launcher is up to date (v{})", CURRENT_VERSION);
            return Optional.empty();
        }
        // Find a LAUNCHER-type file in the manifest.
        for (Manifest.ManifestFile file : manifest.files) {
            if (file.type == Manifest.FileType.LAUNCHER) {
                logger.info("Launcher update available: v{} \u2192 v{}",
                        CURRENT_VERSION, manifest.launcherVersion);
                return Optional.of(file);
            }
        }
        logger.warn("Manifest says launcher v{} is available but no LAUNCHER " +
                "file entry was found in files[]", manifest.launcherVersion);
        return Optional.empty();
    }

    /**
     * Downloads the launcher installer and spawns msiexec via cmd /c start
     * so it detaches from the launcher process and can request UAC elevation
     * independently. Calls {@link System#exit(int)} so the running launcher
     * releases its file locks.
     *
     * <p>This method DOES NOT return on success - the JVM is terminated.
     * On failure, throws an exception and the launcher continues normally.
     */
    public void applyUpdate(Manifest.ManifestFile file, Downloader.ProgressCallback progress)
            throws IOException {
        Path installerPath = Paths.get(System.getProperty("java.io.tmpdir"),
                "launcher-update.msi");

        logger.info("Downloading launcher update to {}", installerPath);
        downloader.download(file.url, installerPath, file.sha256, progress);

        if (!Files.exists(installerPath) || Files.size(installerPath) == 0) {
            throw new IOException("Downloaded MSI is missing or empty: " + installerPath);
        }

        // Use cmd /c start to spawn msiexec DETACHED from the launcher process.
        // Without this, the msiexec child gets killed when the launcher exits,
        // or worse: it tries to install but can't get UAC elevation because
        // it inherited the launcher's non-elevated token.
        //
        // /qb = basic UI (progress only, no questions)
        // /norestart = don't reboot Windows
        // REINSTALLMODE=amus = force re-install all files, overwrite version
        //   (a = all files, m = machine config, u = user config, s = shortcuts)
        ProcessBuilder pb = new ProcessBuilder(
                "cmd.exe", "/c", "start", "\"Oasis Launcher Update\"",
                "msiexec.exe",
                "/i", installerPath.toAbsolutePath().toString(),
                "/qb",
                "/norestart",
                "REINSTALLMODE=amus"
        );
        logger.info("Spawning installer: {}", String.join(" ", pb.command()));
        Process p = pb.start();

        // Give msiexec a moment to start up and grab UAC focus before we exit.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        logger.info("Exiting launcher (pid={}) so MSI can replace files...",
                ProcessHandle.current().pid());
        System.exit(0);
    }

    /**
     * Returns true if {@code candidate} is a strictly higher semantic
     * version than {@code current}. Handles "1.0.0" / "1.0.1" / "2.0.0" etc.
     * Non-numeric segments are compared lexicographically.
     */
    static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) return false;
        if (current == null || current.isBlank()) return true;

        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            String aPart = i < a.length ? a[i] : "0";
            String bPart = i < b.length ? b[i] : "0";
            int cmp = compareSegment(aPart, bPart);
            if (cmp != 0) return cmp > 0;
        }
        return false;  // exactly equal
    }

    private static int compareSegment(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}