#!/usr/bin/env python3
"""
Oasis launcher — Discord → Recent Updates sync.

Pulls the latest posts from a Discord channel per game base (e.g. #updates for Oasis3,
#announcements for OasisOS) and rewrites the launcher's per-base feed files
(updates-oasis3.json / updates-oasisos.json), then commits + pushes them so the launcher
shows them on next open. No launcher release needed — it reads these files live.

Design notes
------------
* Stdlib only (urllib/json/subprocess) — just needs Python 3.9+. No pip installs.
* Uses the Discord REST API with a BOT token. The bot must be in your server, be able to
  View Channel + Read Message History on each channel, and have the **Message Content**
  privileged intent enabled in the Developer Portal (required to read post text).
* The token is NEVER committed: put it in the env var OASIS_DISCORD_TOKEN, or in
  tools/discord_sync.json (which is git-ignored). tools/discord_sync.example.json is the
  template to copy.
* Message → card mapping:
    - "[Badge]" at the very start of a post  → the card's corner badge (e.g. "[Announcement]").
    - first line                             → card title
    - the rest                               → card body
    - author name + avatar                   → card footer
    - first image attachment/embed           → card image (re-hosted into the repo so it
                                               doesn't expire like raw Discord links do)
    - jump-to-post link                      → clicking the card opens Discord
* Each run REBUILDS each feed from the current channel messages (idempotent); nothing is
  appended blindly.

Usage
-----
    python tools/discord_sync.py                 # one sync pass, commit + push
    python tools/discord_sync.py --once --no-push # build files locally, don't push
    python tools/discord_sync.py --watch          # keep syncing on an interval
    python tools/discord_sync.py --interval 180 --watch
"""

import argparse
import datetime as _dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://discord.com/api/v10"
TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.dirname(TOOLS_DIR)                       # tools/.. == repo root
CONFIG_PATH = os.path.join(TOOLS_DIR, "discord_sync.json")
MEDIA_DIRNAME = "updates-media"

BADGE_RE = re.compile(r"^\s*\[([^\]\n]{1,24})\]\s*")


# ── config ──────────────────────────────────────────────────────────────────

def load_config():
    if not os.path.exists(CONFIG_PATH):
        die(f"No config at {CONFIG_PATH}\n"
            f"Copy tools/discord_sync.example.json to tools/discord_sync.json and fill it in.")
    with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
        cfg = json.load(fh)
    # token: env var wins over the file so you can keep the file token-free
    token = os.environ.get("OASIS_DISCORD_TOKEN") or cfg.get("token") or ""
    token = token.strip()
    if not token or token.startswith("PASTE_"):
        die("No bot token. Set OASIS_DISCORD_TOKEN, or put \"token\" in tools/discord_sync.json.")
    cfg["token"] = token
    cfg.setdefault("repo", "xavierrsps/Oasis-Launcher")
    cfg.setdefault("branch", "main")
    cfg.setdefault("limit", 8)
    cfg.setdefault("rehost_images", True)
    cfg.setdefault("push", True)
    if not cfg.get("bases"):
        die("config has no \"bases\" — map each base id to a Discord channel_id.")
    return cfg


# ── Discord REST ────────────────────────────────────────────────────────────

def discord_get(path, token, raw=False):
    url = path if path.startswith("http") else API + path
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bot {token}",
        "User-Agent": "OasisLauncherSync/1.0 (+https://github.com/xavierrsps/Oasis-Launcher)",
    })
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = resp.read()
                return data if raw else json.loads(data.decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 429:  # rate limited — honour retry_after
                retry = 2.0
                try:
                    retry = float(json.loads(e.read().decode("utf-8")).get("retry_after", 2.0))
                except Exception:
                    pass
                time.sleep(min(retry + 0.25, 10))
                continue
            body = ""
            try:
                body = e.read().decode("utf-8")
            except Exception:
                pass
            raise RuntimeError(f"Discord API {e.code} for {url}: {body[:200]}")
        except urllib.error.URLError as e:
            time.sleep(1.5)
            if attempt == 5:
                raise RuntimeError(f"Network error for {url}: {e}")
    raise RuntimeError(f"Gave up after retries for {url}")


def channel_guild_id(channel_id, token):
    try:
        return discord_get(f"/channels/{channel_id}", token).get("guild_id")
    except Exception:
        return None


# ── message → update card ───────────────────────────────────────────────────

def fmt_date(ts):
    try:
        dt = _dt.datetime.fromisoformat(ts.replace("Z", "+00:00"))
        return dt.strftime("%d %b").lstrip("0")   # "28 Aug", "5 Aug"
    except Exception:
        return ""


def split_title_body(text):
    text = (text or "").strip()
    if not text:
        return None, ""
    parts = text.split("\n", 1)
    first = parts[0].strip()
    rest = parts[1].strip() if len(parts) > 1 else ""
    if len(first) <= 100:
        return first, rest
    cut = first[:90].rsplit(" ", 1)[0]
    return cut + "…", text


def pick_image_url(msg):
    for att in msg.get("attachments", []) or []:
        if str(att.get("content_type", "")).startswith("image/") and att.get("url"):
            return att["url"], att.get("filename", "image.png")
    for e in msg.get("embeds", []) or []:
        for key in ("image", "thumbnail"):
            u = (e.get(key) or {}).get("url")
            if u:
                return u, "embed.png"
    return None, None


def message_to_update(msg, guild_id, base_id, cfg, token):
    content = msg.get("content", "") or ""

    badge = None
    m = BADGE_RE.match(content)
    if m:
        badge = m.group(1).strip()
        content = content[m.end():]

    # Prefer an embed's title/description if the post is an embed.
    embeds = msg.get("embeds", []) or []
    title, body = None, content.strip()
    if embeds:
        e = embeds[0]
        if e.get("title"):
            title = e["title"].strip()
        if e.get("description"):
            body = (e["description"].strip() if not body else body + "\n\n" + e["description"].strip())
    if not title:
        title, body = split_title_body(body)

    if not title and not pick_image_url(msg)[0]:
        return None  # nothing renderable (system message, sticker-only, etc.)

    author = msg.get("author", {}) or {}
    name = author.get("global_name") or author.get("username") or "Oasis"
    avatar = None
    if author.get("avatar") and author.get("id"):
        avatar = f"https://cdn.discordapp.com/avatars/{author['id']}/{author['avatar']}.png?size=64"

    update = {
        "title": title or name,
        "date": fmt_date(msg.get("timestamp", "")),
        "body": body.strip(),
        "author": name,
    }
    if badge:
        update["badge"] = badge
    if avatar:
        update["avatar"] = avatar
    if guild_id:
        update["link"] = f"https://discord.com/channels/{guild_id}/{msg.get('channel_id', '')}/{msg.get('id', '')}"

    img_url, filename = pick_image_url(msg)
    if img_url:
        if cfg.get("rehost_images", True):
            hosted = rehost_image(img_url, base_id, msg.get("id", "img"), filename, cfg, token)
            if hosted:
                update["image"] = hosted
        else:
            update["image"] = img_url
    return update


# ── image re-hosting (Discord attachment URLs expire; commit a copy) ─────────

_media_touched = {}  # base_id -> list of relative paths written this run


def rehost_image(url, base_id, msg_id, filename, cfg, token):
    ext = os.path.splitext(filename or "")[1].lower()
    if ext not in (".png", ".jpg", ".jpeg", ".gif", ".webp"):
        ext = ".png"
    rel = f"{MEDIA_DIRNAME}/{base_id}/{msg_id}{ext}"
    dest = os.path.join(REPO_DIR, rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        data = discord_get(url, token, raw=True)
        with open(dest, "wb") as fh:
            fh.write(data)
    except Exception as ex:
        print(f"   ! could not fetch image {url[:60]}…: {ex}")
        return None
    _media_touched.setdefault(base_id, []).append(rel)
    repo = cfg.get("repo", "xavierrsps/Oasis-Launcher")
    branch = cfg.get("branch", "main")
    return f"https://raw.githubusercontent.com/{repo}/{branch}/{rel}"


def prune_stale_media(base_id):
    """Remove images for this base that weren't written this run (posts edited/deleted)."""
    base_dir = os.path.join(REPO_DIR, MEDIA_DIRNAME, base_id)
    if not os.path.isdir(base_dir):
        return
    keep = set(os.path.normpath(os.path.join(REPO_DIR, p)) for p in _media_touched.get(base_id, []))
    for fn in os.listdir(base_dir):
        full = os.path.normpath(os.path.join(base_dir, fn))
        if full not in keep:
            try:
                os.remove(full)
            except OSError:
                pass


# ── per-base sync ───────────────────────────────────────────────────────────

def existing_status(base_id):
    path = os.path.join(REPO_DIR, f"updates-{base_id}.json")
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as fh:
                return json.load(fh).get("status")
        except Exception:
            return None
    return None


def sync_base(base_id, base_cfg, cfg):
    channel_id = str(base_cfg.get("channel_id", "")).strip()
    if not channel_id or channel_id.startswith("PASTE_"):
        print(f" • {base_id}: no channel_id set — skipping.")
        return None
    token = cfg["token"]
    print(f" • {base_id}: reading channel {channel_id} …")
    guild_id = channel_guild_id(channel_id, token)
    msgs = discord_get(f"/channels/{channel_id}/messages?limit={int(cfg.get('limit', 8))}", token)

    updates = []
    for msg in msgs:  # Discord returns newest-first, which is the order we want
        if msg.get("type", 0) not in (0, 19, 20):  # default / reply / chat-command replies
            continue
        u = message_to_update(msg, guild_id, base_id, cfg, token)
        if u:
            updates.append(u)

    prune_stale_media(base_id)

    status = base_cfg.get("status") or existing_status(base_id)
    feed = {}
    if status:
        feed["status"] = status
    feed["updates"] = updates

    out_path = os.path.join(REPO_DIR, f"updates-{base_id}.json")
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(feed, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print(f"   ✓ wrote {os.path.basename(out_path)} ({len(updates)} update(s))")
    return f"updates-{base_id}.json"


# ── git ─────────────────────────────────────────────────────────────────────

def git(*args, check=True):
    return subprocess.run(["git", "-C", REPO_DIR, *args],
                          capture_output=True, text=True, check=check)


def commit_and_push(push):
    # Stage the per-base feed files + any re-hosted media.
    feeds = [f for f in os.listdir(REPO_DIR) if f.startswith("updates-") and f.endswith(".json")]
    if feeds:
        git("add", "--", *feeds, check=False)
    if os.path.isdir(os.path.join(REPO_DIR, MEDIA_DIRNAME)):
        git("add", "--", MEDIA_DIRNAME, check=False)

    staged = git("diff", "--cached", "--quiet", check=False)
    if staged.returncode == 0:
        print(" = no changes since last sync.")
        return False
    git("commit", "-m", "discord-sync: refresh launcher update feeds")
    print(" ✓ committed feed changes")
    if push:
        r = git("push", check=False)
        if r.returncode != 0:
            print(f" ! git push failed:\n{r.stderr.strip()}\n   (commit is local — push manually.)")
            return False
        print(" ✓ pushed to remote")
    return True


# ── main ────────────────────────────────────────────────────────────────────

def die(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def run_once(cfg):
    print("Oasis Discord sync —", _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    changed_any = False
    for base_id, base_cfg in cfg["bases"].items():
        try:
            if sync_base(base_id, base_cfg, cfg):
                changed_any = True
        except Exception as ex:
            print(f"   ! {base_id} failed: {ex}")
    if changed_any:
        commit_and_push(cfg.get("push", True))
    else:
        print(" = nothing to write.")


def main():
    ap = argparse.ArgumentParser(description="Sync Discord posts into the Oasis launcher update feeds.")
    ap.add_argument("--once", action="store_true", help="run a single sync pass (default)")
    ap.add_argument("--watch", action="store_true", help="keep running, syncing on an interval")
    ap.add_argument("--interval", type=int, default=300, help="seconds between passes in --watch mode")
    ap.add_argument("--no-push", action="store_true", help="write + commit but don't git push")
    args = ap.parse_args()

    cfg = load_config()
    if args.no_push:
        cfg["push"] = False

    if args.watch:
        print(f"Watching every {args.interval}s. Ctrl+C to stop.")
        while True:
            try:
                run_once(cfg)
            except Exception as ex:
                print(f" ! pass failed: {ex}")
            time.sleep(max(30, args.interval))
    else:
        run_once(cfg)


if __name__ == "__main__":
    main()
