package com.oasis.launcher.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oasis.launcher.util.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Launcher-level acceptance of the Oasis Terms of Service, Rules and Privacy Policy, persisted to
 * {@code terms-acceptance.json} in the launcher data dir. The user must accept once to use the
 * launcher at all; acceptance is keyed by a terms version, so bumping the version re-prompts.
 *
 * <p>Mirrors the small-JSON-store pattern used by {@link DiscordLinkStore}.
 */
public class AcceptanceStore {

    private static final Logger logger = LogManager.getLogger(AcceptanceStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public AcceptanceStore() {
        this.file = Platform.dataDir().resolve("terms-acceptance.json");
    }

    /** JSON shape persisted on disk. */
    private static final class Stored {
        String version;
        String acceptedAt;
    }

    /** True if the current user has accepted the given terms version on this launcher. */
    public boolean hasAccepted(String version) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            Stored s = GSON.fromJson(Files.readString(file), Stored.class);
            return s != null && version != null && version.equals(s.version);
        } catch (Exception e) {
            logger.warn("Could not read terms-acceptance.json: {}", e.getMessage());
            return false;
        }
    }

    /** Records acceptance of the given terms version, stamped with the current time. */
    public void accept(String version) {
        Stored s = new Stored();
        s.version = version;
        s.acceptedAt = Instant.now().toString();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(s));
        } catch (Exception e) {
            logger.error("Could not write terms-acceptance.json", e);
        }
    }

    /** Clears any recorded acceptance (re-prompts on next launch). */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }
}
