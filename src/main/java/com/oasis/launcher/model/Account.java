package com.oasis.launcher.model;

/**
 * Represents one saved game account in the launcher.
 *
 * <p>Only the username is stored in this object. The password lives in
 * Windows Credential Manager (Linux/macOS use system keychain equivalents)
 * and is looked up at PLAY-click time by {@link com.oasis.launcher.account.CredentialStore}.
 */
public class Account {

    /** Display name for the account, also used as Credential Manager key. */
    public String username;

    /** Optional player-defined display label (e.g. "My Iron"). Falls back to username. */
    public String displayName;

    /** Whether to remember the password for this account. */
    public boolean rememberPassword;

    /** Timestamp of last PLAY click — used to sort accounts by recency. */
    public long lastUsed;

    public Account() {}

    public Account(String username, boolean rememberPassword) {
        this.username = username;
        this.displayName = username;
        this.rememberPassword = rememberPassword;
        this.lastUsed = System.currentTimeMillis();
    }

    public String label() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        return username;
    }
}
