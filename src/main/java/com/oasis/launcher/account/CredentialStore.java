package com.oasis.launcher.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based encrypted credential store.
 * Stores passwords in %APPDATA%/Oasis/creds.dat using XOR obfuscation
 * with a machine-derived key.
 */
public class CredentialStore {

    private static final String STORE_FILENAME = "creds.dat";
    private static final ReentrantLock LOCK = new ReentrantLock();

    private final Path storeFile;
    private final byte[] key;

    public CredentialStore() {
        this.storeFile = resolveDataDir().resolve(STORE_FILENAME);
        this.key = deriveKey();
    }

    /** Save credentials. Returns true on success. */
    public boolean save(String username, String password) {
        LOCK.lock();
        try {
            Map<String, String> all = readAll();
            all.put(username.toLowerCase(), password);
            writeAll(all);
            System.out.println("[CredentialStore] Saved credentials for '" + username
                    + "' (pwd-len=" + password.length() + ")");
            return true;
        } catch (Exception e) {
            System.err.println("[CredentialStore] Failed to save credentials for '" + username + "': " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            LOCK.unlock();
        }
    }

    /** Load credentials. Returns Optional.empty() if not found. */
    public Optional<String> load(String username) {
        LOCK.lock();
        try {
            Map<String, String> all = readAll();
            String pwd = all.get(username.toLowerCase());
            if (pwd == null) {
                System.out.println("[CredentialStore] No credentials found for '" + username + "'");
                return Optional.empty();
            }
            System.out.println("[CredentialStore] Loaded credentials for '" + username
                    + "' (pwd-len=" + pwd.length() + ")");
            return Optional.of(pwd);
        } catch (Exception e) {
            System.err.println("[CredentialStore] Failed to load credentials for '" + username + "': " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
        } finally {
            LOCK.unlock();
        }
    }

    /** Delete credentials for a user. Returns true if something was deleted. */
    public boolean delete(String username) {
        LOCK.lock();
        try {
            Map<String, String> all = readAll();
            if (all.remove(username.toLowerCase()) != null) {
                writeAll(all);
                System.out.println("[CredentialStore] Deleted credentials for '" + username + "'");
                return true;
            }
            return false;
        } catch (Exception e) {
            System.err.println("[CredentialStore] Failed to delete credentials for '" + username + "': " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            LOCK.unlock();
        }
    }

    // ---- Internal ----

    private Map<String, String> readAll() {
        Map<String, String> result = new HashMap<>();
        if (!Files.exists(storeFile)) {
            return result;
        }
        try {
            byte[] obfuscated = Files.readAllBytes(storeFile);
            if (obfuscated.length == 0) return result;
            byte[] decoded = Base64.getDecoder().decode(obfuscated);
            xor(decoded);
            String content = new String(decoded, StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (line.isEmpty()) continue;
                int sep = line.indexOf('=');
                if (sep <= 0) continue;
                result.put(line.substring(0, sep), line.substring(sep + 1));
            }
        } catch (Exception e) {
            System.err.println("[CredentialStore] Failed to read store, starting fresh: " + e.getMessage());
        }
        return result;
    }

    private void writeAll(Map<String, String> all) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        byte[] plain = sb.toString().getBytes(StandardCharsets.UTF_8);
        xor(plain);
        byte[] encoded = Base64.getEncoder().encode(plain);

        Files.createDirectories(storeFile.getParent());
        Files.write(storeFile, encoded,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private void xor(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= key[i % key.length];
        }
    }

    private byte[] deriveKey() {
        String seed = System.getProperty("user.name", "user") + ":"
                + System.getenv().getOrDefault("COMPUTERNAME", "machine") + ":oasis-v1";
        byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (seedBytes[i % seedBytes.length] ^ (i * 31));
        }
        return k;
    }

    /** Resolve %APPDATA%/Oasis directory (Windows) or ~/.oasis (other). */
    private Path resolveDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            return Paths.get(appData, "Oasis");
        }
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".oasis");
    }
}