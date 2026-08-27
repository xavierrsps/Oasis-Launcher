package com.oasis.launcher.account;

import com.google.gson.Gson;
import com.oasis.launcher.model.DiscordLink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies an OTP code against the bot's verify endpoint.
 *
 * <p>Contract — the launcher sends {@code POST <verifyUrl>} with JSON body {@code {"code":"123456"}}
 * and expects {@code {"ok":true,"id":"<discordId>","username":"<name>","avatar":"<url>"}} on success
 * (2xx). Anything else — non-2xx, {@code ok:false}, or a missing id — is treated as "not verified".
 */
public class DiscordVerifier {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Gson gson = new Gson();

    private static final class Resp {
        boolean ok;
        String id;
        String discord_id;
        String username;
        String global_name;
        String avatar;
        String error;
    }

    /** Returns the linked identity if the code is valid, or empty if not (never throws on a plain rejection). */
    public Optional<DiscordLink> verify(String verifyUrl, String code) throws Exception {
        if (verifyUrl == null || verifyUrl.isBlank()) {
            throw new IllegalStateException("Discord verification isn't set up yet.");
        }
        String body = gson.toJson(Map.of("code", code == null ? "" : code.trim()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(verifyUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Oasis-Launcher/2.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            return Optional.empty();
        }
        Resp r = gson.fromJson(resp.body(), Resp.class);
        if (r == null || !r.ok) {
            return Optional.empty();
        }
        String id = r.id != null ? r.id : r.discord_id;
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String name = r.username != null ? r.username : r.global_name;
        return Optional.of(new DiscordLink(id, name != null ? name : "Discord user", r.avatar));
    }
}
