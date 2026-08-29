#!/usr/bin/env python3
"""
Oasis launcher — Discord OTP link bot (reference implementation).

The launcher's "Log in with Discord" flow supports two paths, both served here:
  1. **OTP codes** — a **/link** slash command (also works by DMing the bot the word "link") that DMs
     the user a one-time 6-digit code, plus **POST /verify** to exchange the code for the identity.
  2. **OAuth2** (the smoother "Log in with Discord") — **GET /oauth/start**, **GET /oauth/callback**,
     **GET /oauth/poll**. The launcher opens the browser at /oauth/start, the user authorises on
     Discord, and the launcher polls /oauth/poll for the identity. Requires the three OAuth env vars
     below; if they're unset the OAuth routes return 503 and the launcher just uses OTP codes.

Run this as-is (it's a complete little bot), or lift these handlers into your existing Oasis bot — the
launcher only cares about the HTTP contracts below. If your bot isn't Python, implement the same
contracts in whatever it's written in.

Verify contract (what the launcher expects)
-------------------------------------------
    POST <verifyUrl>            Content-Type: application/json
        body:    {"code": "123456"}
        200 OK:  {"ok": true, "id": "<discordUserId>", "username": "<name>", "avatar": "<url or null>"}
        else:    {"ok": false, "error": "..."}          (or any non-2xx)
    Codes are single-use and expire (default 10 min).

Setup
-----
    pip install discord.py            # brings in aiohttp too
    # In the Discord Developer Portal: the /link slash command needs no privileged intent;
    # the DM-"link" trigger needs the Message Content intent.
    # Env vars:
    #   DISCORD_TOKEN          your bot token                       (required)
    #   VERIFY_HOST            bind host   (default 0.0.0.0)
    #   VERIFY_PORT            bind port   (default 8787)
    #   CODE_TTL              OTP seconds  (default 600)
    #   --- OAuth2 (optional; enables the browser "Log in with Discord") ---
    #   DISCORD_CLIENT_ID      application/client id (usually the bot's snowflake)
    #   DISCORD_CLIENT_SECRET  from Developer Portal → OAuth2
    #   OAUTH_REDIRECT_URI     e.g. https://oasis.oasis-ps.com/api/oauth/callback
    #                          (add this EXACT string under OAuth2 → Redirects in the portal)
    #   OAUTH_TTL              seconds a pending/handshake state lives (default 600)
    python tools/oasis_link_bot.py

On your VPS, put this behind TLS (e.g. an nginx reverse proxy or Cloudflare tunnel terminating https),
then set discord.json -> "verifyUrl" to https://<domain>/verify and "inviteUrl" to your invite. For
OAuth also set "oauthStartUrl" to https://<domain>/oauth/start and "oauthPollUrl" to
https://<domain>/oauth/poll, and register https://<domain>/oauth/callback as a redirect in the portal.
"""

import asyncio
import os
import secrets
import time
import urllib.parse

import aiohttp
import discord
from aiohttp import web
from discord import app_commands

TOKEN = os.environ.get("DISCORD_TOKEN", "").strip()
HOST = os.environ.get("VERIFY_HOST", "0.0.0.0")
PORT = int(os.environ.get("VERIFY_PORT", "8787"))
TTL = int(os.environ.get("CODE_TTL", "600"))

# OAuth2 "Log in with Discord" (optional — the launcher falls back to OTP codes when unset).
# CLIENT_ID is your application id (for most bots, the same snowflake as the bot user id).
CLIENT_ID = os.environ.get("DISCORD_CLIENT_ID", "").strip()
CLIENT_SECRET = os.environ.get("DISCORD_CLIENT_SECRET", "").strip()
# Must EXACTLY match a redirect URI registered in the Developer Portal, e.g.
#   https://oasis.oasis-ps.com/api/oauth/callback
OAUTH_REDIRECT_URI = os.environ.get("OAUTH_REDIRECT_URI", "").strip()
OAUTH_TTL = int(os.environ.get("OAUTH_TTL", "600"))
OAUTH_SCOPE = "identify"

# code -> {"id", "username", "avatar", "exp"}
CODES = {}
# state -> {"status": "pending"|"done", "id", "username", "avatar", "exp"} for the OAuth login flow
OAUTH = {}
# per-ip throttle for /verify: ip -> [timestamps]
_HITS = {}


def _purge(now):
    for k in [k for k, v in CODES.items() if v["exp"] < now]:
        CODES.pop(k, None)


def new_code():
    for _ in range(20):
        c = f"{secrets.randbelow(1_000_000):06d}"
        if c not in CODES:
            return c
    return f"{secrets.randbelow(1_000_000):06d}"


def issue_code(user):
    now = time.time()
    _purge(now)
    # one pending code per user
    for k in [k for k, v in CODES.items() if v["id"] == str(user.id)]:
        CODES.pop(k, None)
    code = new_code()
    avatar = None
    da = getattr(user, "display_avatar", None)
    if da is not None:
        avatar = str(da.url)
    CODES[code] = {
        "id": str(user.id),
        "username": getattr(user, "global_name", None) or user.name,
        "avatar": avatar,
        "exp": now + TTL,
    }
    return code


def code_message(code):
    return (f"Your Oasis launcher code is **{code}** — enter it in the launcher within "
            f"{TTL // 60} minutes. If that wasn't you, ignore this.")


intents = discord.Intents.default()
intents.message_content = True   # only needed for the DM-"link" trigger; harmless if not approved
client = discord.Client(intents=intents)
tree = app_commands.CommandTree(client)


@tree.command(name="link", description="Get a one-time code to link your account in the Oasis launcher.")
async def link_cmd(interaction: discord.Interaction):
    code = issue_code(interaction.user)
    try:
        await interaction.user.send(code_message(code))
        await interaction.response.send_message("Sent you a DM with your code. 📬", ephemeral=True)
    except discord.Forbidden:
        await interaction.response.send_message(
            f"I couldn't DM you (your DMs are closed). Your code is **{code}**.", ephemeral=True)


@client.event
async def on_ready():
    try:
        await tree.sync()
    except Exception as e:
        print("slash-command sync failed:", e)
    print(f"[oasis-link] online as {client.user} — verify endpoint on {HOST}:{PORT}")


@client.event
async def on_message(msg):
    if msg.author.bot:
        return
    if isinstance(msg.channel, discord.DMChannel) and msg.content.strip().lower().lstrip("/!") == "link":
        await msg.channel.send(code_message(issue_code(msg.author)))


# ── HTTP verify endpoint ─────────────────────────────────────────────────────

async def handle_verify(request):
    now = time.time()
    ip = request.headers.get("X-Forwarded-For", request.remote or "?").split(",")[0].strip()
    hits = [t for t in _HITS.get(ip, []) if now - t < 60]
    if len(hits) >= 20:
        return web.json_response({"ok": False, "error": "rate limited"}, status=429)
    hits.append(now)
    _HITS[ip] = hits

    try:
        data = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "bad request"}, status=400)

    code = str(data.get("code", "")).strip()
    _purge(now)
    entry = CODES.get(code)
    if not entry or entry["exp"] < now:
        CODES.pop(code, None)
        return web.json_response({"ok": False, "error": "invalid or expired"})
    CODES.pop(code, None)  # single use
    return web.json_response({
        "ok": True,
        "id": entry["id"],
        "username": entry["username"],
        "avatar": entry["avatar"],
    })


async def handle_health(_request):
    return web.json_response({"ok": True, "service": "oasis-link",
                              "pending": len(CODES), "oauth_pending": len(OAUTH),
                              "oauth": bool(CLIENT_ID and CLIENT_SECRET and OAUTH_REDIRECT_URI)})


# ── OAuth2 "Log in with Discord" ─────────────────────────────────────────────
#
# Flow: the launcher generates a random `state`, opens the browser at /oauth/start,
# the user authorises on Discord, Discord redirects to /oauth/callback (we exchange
# the code for the identity, server-side, using the client secret), and the launcher
# polls /oauth/poll?state=... until it gets the linked identity back.

def _purge_oauth(now):
    for k in [k for k, v in OAUTH.items() if v["exp"] < now]:
        OAUTH.pop(k, None)


def _oauth_configured():
    return bool(CLIENT_ID and CLIENT_SECRET and OAUTH_REDIRECT_URI)


def _oauth_page(title, body):
    return ("<!doctype html><html><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>Oasis · Discord</title><style>"
            "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            "background:#181109;color:#f1e5cb;font-family:system-ui,Segoe UI,Roboto,sans-serif}"
            ".card{max-width:420px;padding:40px 34px;text-align:center;background:#2b2113;"
            "border:1px solid rgba(244,181,63,.28);border-radius:16px}"
            "h1{margin:0 0 10px;font-size:22px;color:#f4b53f}p{margin:0;color:#bcaa86;line-height:1.5}"
            "</style></head><body><div class='card'><h1>" + title + "</h1><p>" + body + "</p></div></body></html>")


async def handle_oauth_start(request):
    if not _oauth_configured():
        return web.Response(status=503, text="OAuth is not configured on this server.")
    state = request.query.get("state", "").strip()
    if not (16 <= len(state) <= 128) or not all(c in "0123456789abcdefABCDEF" for c in state):
        return web.Response(status=400, text="bad state")
    now = time.time()
    _purge_oauth(now)
    OAUTH[state] = {"status": "pending", "exp": now + OAUTH_TTL}
    params = {
        "client_id": CLIENT_ID,
        "redirect_uri": OAUTH_REDIRECT_URI,
        "response_type": "code",
        "scope": OAUTH_SCOPE,
        "state": state,
    }
    raise web.HTTPFound("https://discord.com/oauth2/authorize?" + urllib.parse.urlencode(params))


async def handle_oauth_callback(request):
    now = time.time()
    _purge_oauth(now)
    if request.query.get("error"):
        return web.Response(content_type="text/html", status=200,
                            text=_oauth_page("Link cancelled", "You can close this tab and return to the launcher."))
    state = request.query.get("state", "").strip()
    code = request.query.get("code", "").strip()
    entry = OAUTH.get(state)
    if not entry or entry["exp"] < now:
        return web.Response(content_type="text/html", status=200,
                            text=_oauth_page("Link expired", "That attempt timed out — start again from the launcher."))
    if not code:
        return web.Response(content_type="text/html", status=200,
                            text=_oauth_page("Link failed", "No authorization code was returned. Try again."))
    data = {
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": OAUTH_REDIRECT_URI,
    }
    try:
        async with aiohttp.ClientSession() as sess:
            async with sess.post("https://discord.com/api/oauth2/token", data=data) as tr:
                if tr.status != 200:
                    print("[oauth] token exchange failed", tr.status, (await tr.text())[:200])
                    return web.Response(content_type="text/html", status=200,
                                        text=_oauth_page("Link failed", "Couldn't complete the link. Try again from the launcher."))
                access_token = (await tr.json()).get("access_token")
            async with sess.get("https://discord.com/api/users/@me",
                                headers={"Authorization": "Bearer " + str(access_token)}) as ur:
                if ur.status != 200:
                    return web.Response(content_type="text/html", status=200,
                                        text=_oauth_page("Link failed", "Couldn't read your Discord profile. Try again."))
                u = await ur.json()
    except Exception as e:  # noqa: BLE001 — surface any transport error as a friendly page
        print("[oauth] callback error:", repr(e))
        return web.Response(content_type="text/html", status=200,
                            text=_oauth_page("Link failed", "Something went wrong talking to Discord. Try again."))

    uid = u.get("id")
    avatar = None
    if uid and u.get("avatar"):
        ext = "gif" if str(u["avatar"]).startswith("a_") else "png"
        avatar = "https://cdn.discordapp.com/avatars/%s/%s.%s?size=128" % (uid, u["avatar"], ext)
    OAUTH[state] = {
        "status": "done",
        "id": uid,
        "username": u.get("global_name") or u.get("username"),
        "avatar": avatar,
        "exp": now + OAUTH_TTL,
    }
    return web.Response(content_type="text/html", status=200,
                        text=_oauth_page("You're linked! ✅", "Head back to the Oasis launcher — it finishes up on its own."))


async def handle_oauth_poll(request):
    now = time.time()
    _purge_oauth(now)
    state = request.query.get("state", "").strip()
    entry = OAUTH.get(state)
    if not entry or entry["exp"] < now:
        return web.json_response({"ok": False, "status": "expired"})
    if entry.get("status") != "done":
        return web.json_response({"ok": False, "status": "pending"})
    OAUTH.pop(state, None)  # single use
    return web.json_response({"ok": True, "id": entry["id"],
                              "username": entry["username"], "avatar": entry["avatar"]})


async def start_http():
    app = web.Application()
    app.router.add_post("/verify", handle_verify)
    app.router.add_get("/health", handle_health)
    app.router.add_get("/oauth/start", handle_oauth_start)
    app.router.add_get("/oauth/callback", handle_oauth_callback)
    app.router.add_get("/oauth/poll", handle_oauth_poll)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, HOST, PORT).start()


async def main():
    if not TOKEN:
        raise SystemExit("Set DISCORD_TOKEN (your bot token) before running.")
    await start_http()
    await client.start(TOKEN)


if __name__ == "__main__":
    asyncio.run(main())
