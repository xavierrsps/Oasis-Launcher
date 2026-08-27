package com.oasis.launcher.model;

import java.util.List;

/**
 * Represents the manifest.json hosted on the launcher server (GitHub Releases).
 *
 * <p>Example:
 * <pre>
 * {
 *   "launcherVersion": "1.0.0",
 *   "clientVersion": "1.2.3",
 *   "cacheVersion": "2024.01.15",
 *   "files": [
 *     {
 *       "name": "Oasis.jar",
 *       "type": "CLIENT",
 *       "url": "https://github.com/.../Oasis.jar",
 *       "sha256": "abc...",
 *       "size": 8421376
 *     },
 *     {
 *       "name": "main_file_cache.dat2",
 *       "type": "CACHE",
 *       "url": "...",
 *       "sha256": "...",
 *       "size": 209715200
 *     }
 *   ]
 * }
 * </pre>
 */
public class Manifest {

    public String launcherVersion;
    public String clientVersion;
    public String cacheVersion;
    public List<ManifestFile> files;

    public static class ManifestFile {
        public String name;
        public FileType type;
        public String url;
        public String sha256;
        public long size;
    }

    public enum FileType {
        /** The launcher itself (for self-update). */
        LAUNCHER,
        /** The main client JAR. */
        CLIENT,
        /** Cache file (placed in cache/ subdirectory). */
        CACHE,
        /**
         * Compressed cache bundle. Downloaded into cache/ subdirectory and
         * extracted there. The zip itself is deleted after successful extract.
         * Used to ship the multi-hundred-MB OSRS cache as a single download.
         */
        CACHE_ZIP,
        /** Any other resource (config, sprites, etc). */
        RESOURCE
    }
}
