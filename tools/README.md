# Discord → launcher updates sync

`discord_sync.py` pulls the latest posts from a Discord channel **per game base** and rewrites the
launcher's feed files (`updates-oasis3.json`, `updates-oasisos.json`) at the repo root, then commits
and pushes them. The launcher reads those files live, so **new posts show up on the next launcher open —
no launcher release needed.**

One channel maps to one base: e.g. your `#updates` channel → Oasis3, your `#announcements` channel →
OasisOS. Post in Discord, run the sync (or leave it watching), and the cards fill themselves.

## One-time setup (≈5 minutes, needs your Discord account)

1. **Create a bot** at <https://discord.com/developers/applications> → *New Application* → *Bot* → *Add Bot*.
2. On the **Bot** page, enable the **Message Content Intent** (under *Privileged Gateway Intents*).
   This is required to read post text. *Reset Token* → copy the token (you'll only see it once).
3. **Invite the bot** to your server: *OAuth2 → URL Generator* → scope `bot`, permissions
   *View Channel* + *Read Message History* → open the generated URL → add it to your server.
4. **Get the channel IDs**: in Discord, *User Settings → Advanced → Developer Mode* ON, then right-click
   each channel → *Copy Channel ID*.
5. **Configure**: copy `discord_sync.example.json` to `discord_sync.json` and paste the two channel IDs
   (Oasis3 + OasisOS). For the token, either paste it into `discord_sync.json` **or** — recommended —
   leave it out and set an environment variable so it's never in a file:
   - PowerShell (this session): `$env:OASIS_DISCORD_TOKEN = "your-token"`
   - Permanent: `setx OASIS_DISCORD_TOKEN "your-token"` (reopen the terminal after).

`discord_sync.json` is git-ignored, so your token is never committed.

## Running it

From the repo root:

```bash
python tools/discord_sync.py                 # one pass: rebuild feeds, commit + push
python tools/discord_sync.py --no-push        # build + commit locally, don't push
python tools/discord_sync.py --watch          # keep syncing (default every 5 min)
python tools/discord_sync.py --watch --interval 180
```

On Windows you can also just double-click **`sync.bat`**.

To auto-sync in the background, run `--watch`, or point Windows Task Scheduler at
`python tools\discord_sync.py` on a schedule.

## How a Discord post becomes a card

| Discord post | Card |
|---|---|
| First line | Title |
| The rest of the message | Body |
| `[Announcement]` at the very start | Corner badge ("ANNOUNCEMENT") — the tag is removed from the title |
| Who posted (name + avatar) | Card footer |
| First image attachment/embed | Card image (copied into `updates-media/` so it doesn't expire) |
| — | Clicking the card opens the original post in Discord |

Each run **rebuilds** each feed from the channel's current messages (newest first, up to `limit`), so
editing or deleting a post in Discord is reflected on the next sync. The per-base `status` line comes
from `discord_sync.json` (falls back to whatever's already in the feed file).

Forum-style channels (threads) aren't handled yet — this reads standard text/announcement channels.

---

# Discord account linking (OTP) — `oasis_link_bot.py`

Powers the launcher's **Log in with Discord** button. Flow:

1. In the launcher, **Log in with Discord** → an OTP box opens.
2. In Discord, the user runs **/link** (or DMs the bot the word `link`) → the bot **DMs a 6-digit code**.
3. They type the code in the launcher → the launcher calls the bot's **/verify** endpoint → linked.
4. A green **✓ Verified** shows and the button becomes a small Discord square that opens Discord.

## Verify contract (language-agnostic)

The launcher only needs this one endpoint — implement it in `oasis_link_bot.py`, in your existing bot,
or in any language:

```
POST <verifyUrl>            Content-Type: application/json
    body:    {"code": "123456"}
    200 OK:  {"ok": true, "id": "<discordUserId>", "username": "<name>", "avatar": "<url or null>"}
    else:    {"ok": false, "error": "..."}          (or any non-2xx)
```
Codes are single-use and short-lived (default 10 min). `oasis_link_bot.py` is a complete reference: a
`/link` command that DMs the code + this endpoint, sharing one in-memory code table.

## Running the reference bot

```bash
pip install discord.py
$env:DISCORD_TOKEN = "your-bot-token"
python tools/oasis_link_bot.py
```

The `/link` slash command needs no privileged intent; the DM-`link` trigger needs the **Message Content**
intent. On your **VPS**, run it behind TLS (e.g. an nginx reverse proxy terminating HTTPS on your domain),
then set the repo's **`discord.json`**:

```json
{ "verifyUrl": "https://your-domain/verify", "inviteUrl": "https://discord.gg/your-invite" }
```

The launcher reads `discord.json` live, so pointing it at your VPS is a repo edit — **no launcher
release needed.** Until `verifyUrl` is set, the launcher shows the button + OTP box but reports that
linking isn't switched on yet.

