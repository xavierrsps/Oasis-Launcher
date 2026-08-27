package com.oasis.launcher.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.oasis.launcher.model.Account;
import com.oasis.launcher.util.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persists the list of saved accounts to {@code accounts.json} in the
 * launcher data directory.
 *
 * <p>This file contains usernames only — passwords live in the OS-level
 * Credential Manager (Windows) / Keychain (macOS / Linux) and are looked
 * up via {@link CredentialStore}.
 */
public class AccountStore {

    private static final Logger logger = LogManager.getLogger(AccountStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ACCOUNT_LIST_TYPE = new TypeToken<List<Account>>(){}.getType();

    private final Path file;
    private List<Account> accounts = new ArrayList<>();

    public AccountStore() {
        this.file = Platform.dataDir().resolve("accounts.json");
        load();
    }

    /** Returns accounts sorted by lastUsed descending (most recent first). */
    public List<Account> list() {
        List<Account> sorted = new ArrayList<>(accounts);
        sorted.sort(Comparator.comparingLong((Account a) -> a.lastUsed).reversed());
        return sorted;
    }

    /** Finds an account by exact username match. */
    public Account find(String username) {
        for (Account a : accounts) {
            if (a.username.equalsIgnoreCase(username)) return a;
        }
        return null;
    }

    /** Adds or updates an account (replaces on duplicate username). */
    public void save(Account account) {
        if (account == null || account.username == null || account.username.isBlank()) {
            throw new IllegalArgumentException("Account username is required");
        }
        accounts.removeIf(a -> a.username.equalsIgnoreCase(account.username));
        accounts.add(account);
        persist();
    }

    /** Removes an account by username. Returns true if removed. */
    public boolean remove(String username) {
        boolean removed = accounts.removeIf(a -> a.username.equalsIgnoreCase(username));
        if (removed) persist();
        return removed;
    }

    /** Updates the lastUsed timestamp for the given account. */
    public void markUsed(String username) {
        Account a = find(username);
        if (a != null) {
            a.lastUsed = System.currentTimeMillis();
            persist();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            accounts = new ArrayList<>();
            return;
        }
        try {
            String json = Files.readString(file);
            List<Account> loaded = GSON.fromJson(json, ACCOUNT_LIST_TYPE);
            accounts = loaded != null ? loaded : new ArrayList<>();
        } catch (Exception e) {
            logger.warn("Could not read accounts.json, starting fresh: {}", e.getMessage());
            accounts = new ArrayList<>();
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(accounts));
        } catch (IOException e) {
            logger.error("Could not write accounts.json", e);
        }
    }
}
