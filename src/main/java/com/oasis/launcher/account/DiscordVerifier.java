package com.oasis.launcher.account;

import com.google.gson.Gson;
import com.oasis.launcher.model.DiscordLink;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Verifies an OTP code against the bot's verify endpoint.
 *
 * <p>Contract — the launcher sends {@code POST <verifyUrl>} with JSON body {@code {"code":"123456"}}
 * and expects {@code {"ok":true,"id":"<discordId>","username":"<name>","avatar":"<url>"}} on success
 * (2xx). Anything else is classified into a {@link VerifyOutcome} carrying a user-facing message; the
 * underlying reason (exception type + message + stack, or the HTTP status) is written to the log so a
 * failing link can actually be diagnosed instead of collapsing to a single "couldn't reach" string.
 */
public class DiscordVerifier {

    private static final Logger logger = LogManager.getLogger(DiscordVerifier.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Gson gson = new Gson();

    /** What happened on a verify attempt. Only {@link #LINKED} is success. */
    public enum Status { LINKED, INVALID_CODE, NOT_CONFIGURED, RATE_LIMITED, SERVICE_ERROR, UNREACHABLE }

    /** Result of {@link #verify}: a status, a message safe to show the user, and (for LINKED) the identity. */
    public static final class VerifyOutcome {
        public final Status status;
        public final DiscordLink link;     // non-null only when status == LINKED
        public final String userMessage;   // explanatory text for the UI (null for LINKED)

        private VerifyOutcome(Status status, DiscordLink link, String userMessage) {
            this.status = status;
            this.link = link;
            this.userMessage = userMessage;
        }

        static VerifyOutcome linked(DiscordLink l) {
            return new VerifyOutcome(Status.LINKED, l, null);
        }

        static VerifyOutcome of(Status status, String userMessage) {
            return new VerifyOutcome(status, null, userMessage);
        }

        public boolean ok() {
            return status == Status.LINKED;
        }
    }

    private static final class Resp {
        boolean ok;
        String id;
        String discord_id;
        String username;
        String global_name;
        String avatar;
        String error;
    }

    /** Never throws — every failure mode comes back as a {@link VerifyOutcome} with an explanatory message. */
    public VerifyOutcome verify(String verifyUrl, String code) {
        if (verifyUrl == null || verifyUrl.isBlank()) {
            return VerifyOutcome.of(Status.NOT_CONFIGURED,
                    "Discord linking isn't switched on yet — check back soon.");
        }
        String body = gson.toJson(Map.of("code", code == null ? "" : code.trim()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(verifyUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Oasis-Launcher/2.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Discord verify interrupted while calling {}", verifyUrl, ie);
            return VerifyOutcome.of(Status.UNREACHABLE, "The verify request was interrupted. Try again.");
        } catch (Exception ex) {
            // This is the "why it isn't working" — full type + message + stack trace go to launcher.log.
            logger.warn("Discord verify could not reach {} — {}: {}",
                    verifyUrl, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return VerifyOutcome.of(Status.UNREACHABLE, unreachableMessage(ex));
        }

        int sc = resp.statusCode();
        if (sc == 429) {
            logger.warn("Discord verify rate-limited (HTTP 429) at {}", verifyUrl);
            return VerifyOutcome.of(Status.RATE_LIMITED, "Too many tries. Wait a minute, then verify again.");
        }
        if (sc / 100 != 2) {
            logger.warn("Discord verify service returned HTTP {} at {} — body: {}",
                    sc, verifyUrl, snippet(resp.body()));
            return VerifyOutcome.of(Status.SERVICE_ERROR,
                    "The verify service is having trouble (HTTP " + sc + "). Try again shortly.");
        }

        Resp r;
        try {
            r = gson.fromJson(resp.body(), Resp.class);
        } catch (Exception parse) {
            logger.warn("Discord verify returned an unparseable body at {} — {}", verifyUrl, snippet(resp.body()));
            return VerifyOutcome.of(Status.SERVICE_ERROR,
                    "The verify service sent something unexpected. Try again shortly.");
        }
        if (r == null || !r.ok) {
            return VerifyOutcome.of(Status.INVALID_CODE,
                    "That code didn't match. Grab a fresh one from the bot and try again.");
        }
        String id = r.id != null ? r.id : r.discord_id;
        if (id == null || id.isBlank()) {
            logger.warn("Discord verify returned ok:true but no id at {}", verifyUrl);
            return VerifyOutcome.of(Status.INVALID_CODE,
                    "That code didn't match. Grab a fresh one from the bot and try again.");
        }
        String name = r.username != null ? r.username : r.global_name;
        return VerifyOutcome.linked(new DiscordLink(id, name != null ? name : "Discord user", r.avatar));
    }

    /** Turns a transport-level exception into a specific, human message (the log has the full detail). */
    private static String unreachableMessage(Throwable ex) {
        if (ex instanceof HttpTimeoutException) {
            return "The verify service didn't respond in time. It may be waking up — try again in a few seconds.";
        }
        if (ex instanceof UnknownHostException) {
            return "Couldn't find the verify server (DNS). Check your internet connection.";
        }
        if (ex instanceof ConnectException) {
            return "Couldn't connect to the verify service — it may be offline, or a firewall is blocking the launcher.";
        }
        if (ex instanceof SSLException) {
            return "Secure (TLS) connection to the verify service failed.";
        }
        return "Couldn't reach the verify service (" + ex.getClass().getSimpleName() + "). Try again in a moment.";
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
