# Oasis — shared dev context

Xavier and Andrew both develop this project with Claude Code. To make cross-dev
handoffs seamless, **every Claude session on this repo is automatically shipped —
in full — to the shared Dev Town Board** the moment the session ends.

## Dev Town Board

- Web board: https://oasis.oasis-ps.com/board (**Docs** tab = session context feed;
  **Board** tab = live task claims, mirrored to Discord).
- Access is gated by Cloudflare Access — ask Xavier to add your email to the
  "Board team" policy if you can't reach it.

## How session sharing works (automatic)

- `.claude/settings.json` registers a `SessionEnd` hook that runs
  `scripts/ship-session.js`.
- That script converts the entire transcript (prompts, replies, reasoning, tool
  calls and results) to Markdown and POSTs it to the board's docs feed as a
  `session` doc. Screenshots/images are dropped; oversized tool output is trimmed.
- Nothing is curated or summarized — the goal is that the other dev misses
  **nothing**.

## One-time setup (each dev, once per machine)

The board ingest is protected by a shared secret that is **never committed**.
Enable shipping by putting the token in your home dir:

```
echo 'ASK_XAVIER_FOR_THE_TOKEN' > ~/.oasis-board-token
# optional, so docs are attributed to you instead of your OS username:
export OASIS_BOARD_AUTHOR='Andrew'
```

Without the token file the hook does nothing (no errors), so it's safe for anyone
who hasn't set it up. Verify your setup with:

```
OASIS_BOARD_DRYRUN=- node scripts/ship-session.js < /dev/null   # prints "no token" to ~/.oasis-board-ship.log if unset
```

## Shared memory

`docs/shared-memory/` is the hand-tended, git-committed knowledge base both devs
maintain (decisions, environment facts, gotchas). Read it at session start and
add to it when you learn something durable. See its `README.md` for the rules.

## Working rule

At the **start** of a work session: read `docs/shared-memory/`, then skim the
board's **Docs** tab (newest `session` docs) to pick up whatever the other dev
last did. Claim/adjust your task on the **Board** tab so the other dev sees
what you're on. When you learn something durable, write it to
`docs/shared-memory/`.
