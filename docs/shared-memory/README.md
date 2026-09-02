# Shared memory

Durable, curated knowledge that **both** Xavier and Andrew maintain and that
Claude should treat as project memory for this repo. Unlike the auto-shipped
session docs on the board (raw, complete, per-session), this folder is the
hand-tended layer: decisions, gotchas, environment facts, and conventions worth
keeping. It's committed to git, so it's the same for both devs on every clone.

## How Claude should use this

- **Read** everything here at the start of a work session — it's authoritative
  project context (same role as `~/.claude` memory, but shared and versioned).
- **Write** here whenever you learn something durable that the other dev would
  want: an architectural decision, a non-obvious environment fact, a
  reproducible fix, a "don't do X because Y" lesson.
- One topic per file, named for the topic (e.g. `bot-deployment.md`,
  `cloudflare-access.md`). Keep entries short and current; prune what's stale.

## What does NOT belong here

- Secrets/tokens (this repo is public via raw.githubusercontent).
- Full session transcripts — those ship automatically to the board's Docs tab.
- Anything derivable by just reading the code.
