package com.oasis.launcher.model;

/**
 * Discord integration config, fetched from {@code discord.json} in the launcher-files repo so the
 * endpoint/invite can change (e.g. when the VPS domain is set) without shipping a new launcher.
 *
 * <pre>
 * {
 *   "verifyUrl": "https://your-domain/verify",   // launcher POSTs {"code":"123456"} here
 *   "inviteUrl": "https://discord.gg/xxxxxxx"      // opened by the Discord button
 * }
 * </pre>
 */
public class DiscordConfig {
    public String verifyUrl;
    public String inviteUrl;
}
