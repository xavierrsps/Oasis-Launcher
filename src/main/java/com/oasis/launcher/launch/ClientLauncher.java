package com.oasis.launcher.launch;

import com.oasis.launcher.util.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spawns the game client as a child Java process.
 *
 * <p>If a username/password are provided, they are written to the client's
 * standard input as two newline-terminated lines:
 *
 * <pre>
 *   {username}\n{password}\n
 * </pre>
 *
 * <p>The client-side AutoLoginPlugin reads from {@code System.in} on
 * startup; if it finds these two lines, it auto-fills the login screen.
 * If stdin is empty or the plugin isn't installed, the client behaves
 * exactly as before.
 *
 * <p>stdin is closed immediately after writing so the client doesn't
 * block on further reads.
 *
 * <p>Critical: we MUST launch the client with the same Java runtime that
 * the launcher itself is running on (i.e. the bundled jpackage runtime).
 * Otherwise testers with a system-wide JDK 11 / JDK 8 end up loading
 * mismatched native libraries (instrument.dll, etc.) and the client
 * crashes with errors like "Procedure entry point Canonicalize could not
 * be located in dynamic link library instrument.dll".
 *
 * <p>We additionally strip {@code JAVA_TOOL_OPTIONS}, {@code _JAVA_OPTIONS}
 * and similar env vars from the child process so they don't inject a
 * foreign {@code -javaagent} that would target the system JDK.
 */
public class ClientLauncher {

    private static final Logger logger = LogManager.getLogger(ClientLauncher.class);

    /** Default maximum heap in megabytes. */
    public static final int DEFAULT_HEAP_MB = 1024;

    /** Env vars that can inject foreign JVM args / agents. We strip these. */
    private static final String[] POISONED_ENV_VARS = {
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JAVA_OPTS",
            "JDK_JAVA_OPTIONS",
            "JAVA_AGENT",
            "JAVAAGENT"
    };

    /** Launches the game client without any auto-login credentials. */
    public void launch(int maxHeapMb) throws IOException {
        launch(maxHeapMb, null, null);
    }

    /**
     * Launches the game client with the given heap size and optional
     * auto-login credentials.
     *
     * <p>If {@code username} is null or blank, no credentials are written
     * to the client process.
     */
    public void launch(int maxHeapMb, String username, String password) throws IOException {
        // Dev convenience: -Doasis.client.jar=<path> overrides which jar we spawn.
        // Lets you point the launcher at game-client/build/libs/Oasis.jar without
        // copying it into the launcher's data dir.
        Path override = com.oasis.launcher.DevMode.clientJarOverride();
        Path jar = override != null ? override : Platform.clientJar();
        if (!Files.exists(jar)) {
            throw new IOException("Client jar not found at " + jar
                    + " \u2014 has the launcher downloaded it?");
        }

        String javaExe = findJavaExecutable();
        logger.info("Using Java executable: {}", javaExe);
        logger.info("Launcher process Java: {} ({}) at java.home={}",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.home"));

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-Xmx" + maxHeapMb + "m");
        cmd.add("-Xms" + Math.min(256, maxHeapMb) + "m");
        cmd.add("-Dsun.java2d.opengl=true");
        cmd.add("-Dsun.java2d.uiScale.enabled=false");
        // Tell the client where to find its cache/plugins/profiles. Without this
        // the client falls back to ~/.oasis (dev layout). With it, the client
        // uses the same OS-conventional path the launcher downloaded files into.
        //
        // Skip this in dev mode: the launcher hasn't downloaded any cache files
        // to <dataDir>/cache/ (we're using the locally-built jar), so we want
        // the client to keep using its existing ~/.oasis/cache/ that the
        // developer already has populated. Without this skip, the canvas would
        // render blank because the OSRS sprite cache lookup fails silently.
        if (!com.oasis.launcher.DevMode.isEnabled()) {
            cmd.add("-Doasis.dir=" + Platform.dataDir().toAbsolutePath());
        }
        // Signal to the client that the launcher is supplying credentials via stdin.
        if (username != null && !username.isBlank()) {
            cmd.add("-Doasis.autologin=stdin");
        }
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());

        logger.info("Launching client (autologin={}): {}",
                username != null && !username.isBlank(), maskCmd(cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Platform.dataDir().toFile());

        // Strip poisoned env vars that inject foreign JVM agents.
        // This is what fixes the "Canonicalize / instrument.dll" crash on
        // machines with an old system-wide JDK (e.g. JDK 11) installed.
        Map<String, String> env = pb.environment();
        for (String var : POISONED_ENV_VARS) {
            String existing = env.remove(var);
            if (existing != null) {
                logger.warn("Stripped env var {}=\"{}\" before launching client",
                        var, existing);
            }
        }
        // Also unset JAVA_HOME so the child process doesn't accidentally
        // pick up a system JDK via libraries that consult it.
        String oldJavaHome = env.remove("JAVA_HOME");
        if (oldJavaHome != null) {
            logger.info("Stripped JAVA_HOME (\"{}\") for client process", oldJavaHome);
        }

        // Inherit stdout/stderr so client logs show up in launcher console.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        // stdin is PIPE so we can write credentials before closing it.
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);

        Process process = pb.start();

        if (username != null && !username.isBlank()) {
            try (OutputStream stdin = process.getOutputStream();
                 Writer writer = new OutputStreamWriter(stdin, StandardCharsets.UTF_8)) {
                writer.write(username);
                writer.write('\n');
                writer.write(password != null ? password : "");
                writer.write('\n');
                writer.flush();
            } catch (IOException ioex) {
                logger.warn("Could not write credentials to client stdin: {}", ioex.getMessage());
            }
        } else {
            // Close stdin immediately so the client doesn't block waiting on it.
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {}
        }

        logger.info("Client process spawned (pid={})", process.pid());
    }

    /** Returns the command joined for logging. Password is never on cmd line - only via stdin. */
    private static String maskCmd(List<String> cmd) {
        return String.join(" ", cmd);
    }

    /**
     * Finds the Java executable to spawn the client with.
     *
     * Priority order:
     *   1. The launcher's OWN runtime (java.home) - this is the bundled
     *      jpackage JRE when running from an installed MSI. THIS IS THE
     *      CORRECT CHOICE and matches the runtime the client expects.
     *   2. Sibling "runtime" directory next to the installation - covers
     *      the case where java.home points somewhere weird.
     *   3. PATH lookup as absolute last resort (logged as a warning).
     */
    private String findJavaExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        String binary = windows ? "java.exe" : "java";

        // 1. The current JVM's runtime - this is the bundled jpackage JRE
        //    when the launcher was installed via the MSI.
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            Path candidate = Path.of(javaHome, "bin", binary);
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
            logger.warn("java.home points to '{}' but no {} found inside",
                    javaHome, binary);
        }

        // 2. Look for a "runtime" sibling directory. jpackage's image-mode
        //    layout puts the JRE here when not invoked via jpackage's own
        //    bootstrap.
        try {
            Path self = Path.of(System.getProperty("java.home", ""));
            Path runtimeSibling = self.getParent() == null ? null
                    : self.getParent().resolve("runtime").resolve("bin").resolve(binary);
            if (runtimeSibling != null && Files.exists(runtimeSibling)) {
                logger.info("Using sibling runtime directory: {}", runtimeSibling);
                return runtimeSibling.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {
        }

        // 3. Last resort: PATH lookup. Almost certainly the wrong JVM but
        //    better than refusing to launch.
        logger.warn("Falling back to '{}' on PATH \u2014 this may pick up the wrong "
                + "JDK and cause native library mismatches.", binary);
        return binary;
    }
}