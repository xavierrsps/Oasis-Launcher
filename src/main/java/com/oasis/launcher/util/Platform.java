package com.oasis.launcher.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves platform-specific data directories.
 *
 * <ul>
 *   <li>Windows: {@code %APPDATA%\Oasis}</li>
 *   <li>macOS: {@code ~/Library/Application Support/Oasis}</li>
 *   <li>Linux: {@code ~/.oasis} (or {@code $XDG_DATA_HOME/oasis} if set)</li>
 * </ul>
 */
public final class Platform {

    public static final String APP_NAME = "Oasis";

    private Platform() {}

    /** Returns the data directory for the current OS, creating it if needed. */
    public static Path dataDir() {
        Path dir = computeDataDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create data directory: " + dir, e);
        }
        return dir;
    }

    private static Path computeDataDir() {
        String os = System.getProperty("os.name").toLowerCase();
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, APP_NAME);
            }
            return Paths.get(home, "AppData", "Roaming", APP_NAME);
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", APP_NAME);
        }
        // Linux / Unix
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg, APP_NAME.toLowerCase());
        }
        return Paths.get(home, "." + APP_NAME.toLowerCase());
    }

    /** Path to the local client.jar file. */
    public static Path clientJar() {
        return dataDir().resolve("Oasis.jar");
    }

    /** Path to the cache directory. */
    public static Path cacheDir() {
        return dataDir().resolve("cache");
    }

    /** Path to the version.local state file. */
    public static Path stateFile() {
        return dataDir().resolve("version.local");
    }

    /** Path to the logs directory. */
    public static Path logsDir() {
        return dataDir().resolve("logs");
    }
}
