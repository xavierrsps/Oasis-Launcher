package com.oasis.launcher;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Developer-mode escape hatch for the launcher.
 *
 * <p>Production: the launcher always fetches the published manifest from GitHub,
 * diffs hashes, and overwrites any local files that don't match. That's the
 * right behaviour for players but useless for iterating on the client locally —
 * every {@code ./gradlew run} would clobber your freshly built jar with the
 * GitHub release version.
 *
 * <p>Dev mode opts out of the update check entirely. Activated via either:
 * <ul>
 *   <li>JVM sysprop {@code -Doasis.launcher.dev=true}</li>
 *   <li>Env var {@code OASIS_LAUNCHER_DEV=1} (any non-empty value works)</li>
 * </ul>
 *
 * <p>Additionally, {@code -Doasis.client.jar=<absolute path>} lets you point
 * the launcher at a specific client jar (typically {@code game-client/build/
 * libs/Oasis.jar}) without copying it into the launcher's data dir. Works in
 * both dev and prod modes — set it once in your IDE run-config and forget.
 */
public final class DevMode {

    private static final Logger logger = LogManager.getLogger(DevMode.class);

    public static final String SYSPROP_DEV = "oasis.launcher.dev";
    public static final String ENV_DEV = "OASIS_LAUNCHER_DEV";
    public static final String SYSPROP_CLIENT_JAR = "oasis.client.jar";

    private DevMode() {}

    /** True if the launcher should skip update checks and launch whatever's local. */
    public static boolean isEnabled() {
        String prop = System.getProperty(SYSPROP_DEV);
        if (prop != null && (prop.equalsIgnoreCase("true") || prop.equals("1"))) {
            return true;
        }
        String env = System.getenv(ENV_DEV);
        return env != null && !env.isBlank() && !env.equals("0") && !env.equalsIgnoreCase("false");
    }

    /**
     * Returns an explicit override for the client jar path if the user provided
     * one via {@code -Doasis.client.jar=<path>}, otherwise null. Callers should
     * fall back to {@link com.oasis.launcher.util.Platform#clientJar()} when
     * this returns null.
     */
    public static Path clientJarOverride() {
        String prop = System.getProperty(SYSPROP_CLIENT_JAR);
        if (prop == null || prop.isBlank()) return null;
        return Path.of(prop);
    }

    /** Logs the current dev-mode state — called once at startup. */
    public static void logState() {
        if (isEnabled()) {
            logger.warn("[DEV MODE] Update checks disabled (oasis.launcher.dev / OASIS_LAUNCHER_DEV is set).");
        }
        Path override = clientJarOverride();
        if (override != null) {
            logger.info("[DEV MODE] Client jar override: {}", override);
        }
    }
}
