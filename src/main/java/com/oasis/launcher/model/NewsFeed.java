package com.oasis.launcher.model;

import java.util.List;

/**
 * The "Recent Updates" feed, read from {@code updates.json} in the launcher-files repo. Each entry maps
 * 1:1 to a Discord post in {@code #updates}/{@code #announcements} (a sync bot writes this file), and is
 * also hand-editable.
 *
 * <pre>
 * { "updates": [ {
 *     "title":  "Oasis 2.0 is live",
 *     "date":   "2026-08-27",
 *     "body":   "the message text…",
 *     "author": "Xavier",
 *     "avatar": "https://cdn.discordapp.com/avatars/…png",   // optional
 *     "image":  "https://…png",                               // optional attachment/embed
 *     "link":   "https://discord.com/channels/…"              // optional jump-to-post
 * } ] }
 * </pre>
 */
public class NewsFeed {

    /** Optional per-base current-status line (e.g. "In production, working on rev949."). */
    public String status;

    public List<Update> updates;

    public static class Update {
        public String title;
        public String date;
        public String body;
        public String author;
        public String avatar;
        public String image;
        public String link;
        /** Optional corner tag, e.g. "ANNOUNCEMENT" / "PATCH". */
        public String badge;
    }
}
