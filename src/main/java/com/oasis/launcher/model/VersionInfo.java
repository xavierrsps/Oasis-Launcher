package com.oasis.launcher.model;

/**
 * Launcher self-update descriptor, read from {@code version.json} in the launcher-files repo.
 *
 * <pre>
 * { "version": "2.0.0",
 *   "url": "https://github.com/xavierrsps/Oasis-Launcher/releases/latest/download/OasisLauncherSetup.exe",
 *   "notes": "…" }
 * </pre>
 */
public class VersionInfo {
    public String version;
    public String url;
    public String notes;
}
