#!/usr/bin/env python3
"""
Oasis launcher — Discord OTP link bot (reference implementation).

The launcher's "Log in with Discord" flow needs two things, both here:
  1. A **/link** slash command (also works by DMing the bot the word "link") that DMs the user a
     one-time 6-digit code.
  2. An HTTP endpoint **POST /verify** that the launcher calls to exchange a code for the Discord
     identity.

Run this as-is (it's a complete little bot), or lift these two handlers into your existing Oasis bot —
the launcher only cares about the /verify contract below. If your bot isn't Python, implement the same
contract in whatever it's written in.

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
    #   DISCORD_TOKEN   your bot token         (required)
    #   VERIFY_HOST     bind host  (default 0.0.0.0)
    #   VERIFY_PORT     bind port  (default 8787)
    #   CODE_TTL        seconds    (default 600)
    python tools/oasis_link_bot.py

On your VPS, put this behind TLS (e.g. an nginx reverse proxy terminating https), then set
discord.json -> "verifyUrl" to  https://<your-domain>/verify  and "inviteUrl" to your invite.
"""

import asyncio
import os
import secrets
import time

import discord
from aiohttp import web
from discord import app_commands

TOKEN = os.environ.get("DISCORD_TOKEN", "").strip()
HOST = os.environ.get("VERIFY_HOST", "0.0.0.0")
PORT = int(os.environ.get("VERIFY_PORT", "8787"))
TTL = int(os.environ.get("CODE_TTL", "600"))

# code -> {"id", "username", "avatar", "exp"}
CODES = {}
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
    return web.json_response({"ok": True, "service": "oasis-link", "pending": len(CODES)})


async def start_http():
    app = web.Application()
    app.router.add_post("/verify", handle_verify)
    app.router.add_get("/health", handle_health)
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
