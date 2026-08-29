package com.oasis.launcher.model;

/**
 * Discord integration config, fetched from {@code discord.json} in the launcher-files repo so the
 * endpoint/invite can change (e.g. when the VPS domain is set) without shipping a new launcher.
 *
 * <pre>
 * {
 *   "verifyUrl":     "https://your-domain/verify",        // launcher POSTs {"code":"123456"} here
 *   "inviteUrl":     "https://discord.gg/xxxxxxx",          // opened by the Discord button
 *   "codeUrl":       "https://discord.com/users/<botId>",   // optional: where "Get a code" sends the user
 *   "oauthStartUrl": "https://your-domain/oauth/start",     // optional: enables browser "Log in with Discord"
 *   "oauthPollUrl":  "https://your-domain/oauth/poll"       // optional: launcher polls this for the identity
 * }
 * </pre>
 *
 * <p>When both {@code oauthStartUrl} and {@code oauthPollUrl} are set, the launcher uses the smoother
 * browser OAuth flow; otherwise it falls back to the OTP code entry.
 */
public class DiscordConfig {
    public String verifyUrl;
    public String inviteUrl;
    /** Optional deep link to where the user gets a code (e.g. the bot's DM). Falls back to {@link #inviteUrl}. */
    public String codeUrl;
    /** Optional: bot endpoint that redirects to Discord consent. Set with {@link #oauthPollUrl} to enable OAuth. */
    public String oauthStartUrl;
    /** Optional: bot endpoint the launcher polls for the linked identity after browser consent. */
    public String oauthPollUrl;

    /** True when the browser OAuth login is configured (both endpoints present). */
    public boolean oauthEnabled() {
        return oauthStartUrl != null && !oauthStartUrl.isBlank()
                && oauthPollUrl != null && !oauthPollUrl.isBlank();
    }
}
