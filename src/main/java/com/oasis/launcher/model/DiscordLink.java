package com.oasis.launcher.model;

/**
 * A linked Discord identity, persisted locally once the user verifies an OTP code. Stored in
 * {@code discord-link.json} in the launcher data dir so the "Verified" state survives relaunch.
 */
public class DiscordLink {

    public String id;        // Discord user id
    public String username;  // display / global name
    public String avatar;    // avatar URL (optional)
    public long linkedAt;

    public DiscordLink() {
    }

    public DiscordLink(String id, String username, String avatar) {
        this.id = id;
        this.username = username;
        this.avatar = avatar;
        this.linkedAt = System.currentTimeMillis();
    }
}
