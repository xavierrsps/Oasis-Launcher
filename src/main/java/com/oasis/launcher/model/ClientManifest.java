package com.oasis.launcher.model;

/**
 * Descriptor for the game client jar, read from {@code client-manifest.json}
 * in the launcher-files repo. Consumed by the Play flow to download-then-launch
 * the client (hash-verified) into {@code <dataDir>/Oasis.jar}.
 *
 * <pre>
 * { "version": "240",
 *   "filename": "Oasis.jar",
 *   "url": "https://github.com/xavierrsps/Oasis-Launcher/releases/latest/download/Oasis.jar",
 *   "sha256": "…",
 *   "size": 12345678 }
 * </pre>
 */
public class ClientManifest {
    public String version;
    public String filename;
    public String url;
    public String sha256;
    public long size;
}
