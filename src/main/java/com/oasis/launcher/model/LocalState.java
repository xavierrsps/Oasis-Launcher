package com.oasis.launcher.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the locally installed versions and file hashes.
 *
 * <p>Stored as JSON in the Oasis data directory as {@code version.local}.
 *
 * <p>The {@code fileHashes} map keys are filenames (relative paths) and
 * values are SHA-256 hashes. This lets the launcher detect partial updates
 * and resume on interrupted downloads.
 */
public class LocalState {

    public String launcherVersion = "1.0.0";
    public String clientVersion = "";
    public String cacheVersion = "";
    public Map<String, String> fileHashes = new HashMap<>();

    /** Returns the SHA-256 hash for a local file, or null if unknown. */
    public String getHash(String fileName) {
        return fileHashes.get(fileName);
    }

    /** Stores the SHA-256 hash for a downloaded file. */
    public void setHash(String fileName, String hash) {
        fileHashes.put(fileName, hash);
    }
}
