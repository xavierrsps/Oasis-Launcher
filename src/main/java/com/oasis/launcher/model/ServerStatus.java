package com.oasis.launcher.model;

/**
 * Represents the server status fetched from status.json.
 *
 * <pre>
 * {
 *   "online": true,
 *   "playerCount": 47,
 *   "uptime": "3d 14h",
 *   "worldStatus": "Stable"
 * }
 * </pre>
 */
public class ServerStatus {
    public boolean online;
    public int playerCount;
    public String uptime;
    public String worldStatus;
}
