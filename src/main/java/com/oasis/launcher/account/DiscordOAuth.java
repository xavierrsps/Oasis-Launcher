package com.oasis.launcher.account;

import com.google.gson.Gson;
import com.oasis.launcher.model.DiscordLink;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Drives the browser-based "Log in with Discord" (OAuth2) flow, launcher side.
 *
 * <p>The launcher generates a random {@code state}, opens the browser at the bot's {@code oauthStartUrl}
 * (which redirects to Discord's consent screen), then polls {@code oauthPollUrl} until the bot's callback
 * has exchanged the authorization code and parked the identity under that {@code state}. The client secret
 * and the OAuth token never touch the launcher — the exchange happens server-side. Mirrors
 * {@link DiscordVerifier}: nothing here throws on a plain failure.
 */
public class DiscordOAuth {

    private static final Logger logger = LogManager.getLogger(DiscordOAuth.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Gson gson = new Gson();
    private final SecureRandom rng = new SecureRandom();

    public enum PollStatus { PENDING, LINKED, EXPIRED, ERROR }

    public static final class PollResult {
        public final PollStatus status;
        public final DiscordLink link;   // non-null only when status == LINKED

        private PollResult(PollStatus status, DiscordLink link) {
            this.status = status;
            this.link = link;
        }

        static PollResult of(PollStatus s) {
            return new PollResult(s, null);
        }

        static PollResult linked(DiscordLink l) {
            return new PollResult(PollStatus.LINKED, l);
        }
    }

    private static final class Resp {
        boolean ok;
        String status;
        String id;
        String username;
        String avatar;
    }

    /** A fresh, unguessable state token (48 hex chars) that ties the browser handshake to this launcher. */
    public String newState() {
        byte[] b = new byte[24];
        rng.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The browser URL that kicks off consent, with {@code state} appended. */
    public String startUrl(String oauthStartUrl, String state) {
        String sep = oauthStartUrl.contains("?") ? "&" : "?";
        return oauthStartUrl + sep + "state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    /** One poll of the bot's poll endpoint. Never throws — transport failures come back as {@link PollStatus#ERROR}. */
    public PollResult poll(String oauthPollUrl, String state) {
        String sep = oauthPollUrl.contains("?") ? "&" : "?";
        String url = oauthPollUrl + sep + "state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Oasis-Launcher/2.0")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return PollResult.of(PollStatus.ERROR);
        } catch (Exception ex) {
            logger.debug("OAuth poll transport error: {}", ex.toString());
            return PollResult.of(PollStatus.ERROR);
        }
        if (resp.statusCode() / 100 != 2) {
            logger.debug("OAuth poll HTTP {}", resp.statusCode());
            return PollResult.of(PollStatus.ERROR);
        }
        Resp r;
        try {
            r = gson.fromJson(resp.body(), Resp.class);
        } catch (Exception parse) {
            return PollResult.of(PollStatus.ERROR);
        }
        if (r == null) {
            return PollResult.of(PollStatus.ERROR);
        }
        if (r.ok && r.id != null && !r.id.isBlank()) {
            String name = r.username != null ? r.username : "Discord user";
            return PollResult.linked(new DiscordLink(r.id, name, r.avatar));
        }
        if ("expired".equals(r.status)) {
            return PollResult.of(PollStatus.EXPIRED);
        }
        return PollResult.of(PollStatus.PENDING);
    }
}
