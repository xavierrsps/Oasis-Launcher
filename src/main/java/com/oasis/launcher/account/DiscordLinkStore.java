package com.oasis.launcher.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oasis.launcher.model.DiscordLink;
import com.oasis.launcher.util.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Persists the linked Discord identity to {@code discord-link.json} in the launcher data dir. */
public class DiscordLinkStore {

    private static final Logger logger = LogManager.getLogger(DiscordLinkStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public DiscordLinkStore() {
        this.file = Platform.dataDir().resolve("discord-link.json");
    }

    public Optional<DiscordLink> get() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            DiscordLink link = GSON.fromJson(Files.readString(file), DiscordLink.class);
            return (link != null && link.id != null) ? Optional.of(link) : Optional.empty();
        } catch (Exception e) {
            logger.warn("Could not read discord-link.json: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void save(DiscordLink link) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(link));
        } catch (Exception e) {
            logger.error("Could not write discord-link.json", e);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }
}
