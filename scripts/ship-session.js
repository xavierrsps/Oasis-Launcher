#!/usr/bin/env node
/*
 * ship-session.js — Dev Town Board session shipper.
 *
 * Runs from the Claude Code `SessionEnd` hook (see .claude/settings.json).
 * Reads the just-finished session's transcript, converts the FULL conversation
 * to readable Markdown, and POSTs it to the shared board's docs feed so the
 * other dev can pick up with complete context.
 *
 * The board endpoint is public-but-token-guarded. The token is a SECRET and is
 * NEVER committed: this script reads it from $OASIS_BOARD_TOKEN or from the file
 * ~/.oasis-board-token. If neither is present the script exits quietly, so the
 * hook is harmless for anyone who has not opted in.
 *
 * One-time setup per dev (see CLAUDE.md):
 *   echo 'THE_TOKEN' > ~/.oasis-board-token        # or set OASIS_BOARD_TOKEN
 *   (optional) export OASIS_BOARD_AUTHOR='Andrew'   # defaults to OS username
 *
 * Everything below is best-effort: any failure is logged to
 * ~/.oasis-board-ship.log and the process still exits 0.
 */

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const BOARD_URL = process.env.OASIS_BOARD_URL || 'https://oasis.oasis-ps.com/api/docs/ingest';
const PER_BLOCK_CAP = parseInt(process.env.OASIS_BOARD_BLOCK_CAP || '6000', 10); // chars per tool block
const BODY_CAP = parseInt(process.env.OASIS_BOARD_BODY_CAP || '7000000', 10);    // ~7MB, backend caps at 8MB
const LOG = path.join(os.homedir(), '.oasis-board-ship.log');

function log(msg) {
  try { fs.appendFileSync(LOG, `[${new Date().toISOString()}] ${msg}\n`); } catch (_) {}
}

function readToken() {
  if (process.env.OASIS_BOARD_TOKEN && process.env.OASIS_BOARD_TOKEN.trim()) {
    return process.env.OASIS_BOARD_TOKEN.trim();
  }
  try {
    const f = path.join(os.homedir(), '.oasis-board-token');
    const t = fs.readFileSync(f, 'utf8').trim();
    return t || null;
  } catch (_) { return null; }
}

function readStdin() {
  try { return fs.readFileSync(0, 'utf8'); } catch (_) { return ''; }
}

function truncate(s, cap) {
  if (typeof s !== 'string') s = String(s);
  if (s.length <= cap) return s;
  return s.slice(0, cap) + `\n…[truncated ${s.length - cap} chars]`;
}

// Render a tool_result whose content may be a string or an array of sub-blocks.
function renderToolResultContent(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return JSON.stringify(content);
  const parts = [];
  for (const b of content) {
    if (!b || typeof b !== 'object') { parts.push(String(b)); continue; }
    if (b.type === 'text') parts.push(b.text || '');
    else if (b.type === 'image') parts.push('[image omitted]');
    else if (b.type === 'tool_reference') parts.push(`tool: ${b.tool_name}`);
    else parts.push(JSON.stringify(b));
  }
  return parts.join('\n');
}

function fence(s) {
  // Pick a fence long enough not to collide with backticks inside the payload.
  let ticks = '```';
  while (s.includes(ticks)) ticks += '`';
  return `${ticks}\n${s}\n${ticks}`;
}

function main() {
  const token = readToken();
  if (!token) { log('no token; skipping (set OASIS_BOARD_TOKEN or ~/.oasis-board-token to enable)'); process.exit(0); }

  let hook = {};
  const raw = readStdin();
  if (raw) { try { hook = JSON.parse(raw); } catch (e) { log('bad hook json: ' + e.message); } }

  const transcriptPath = hook.transcript_path;
  if (!transcriptPath || !fs.existsSync(transcriptPath)) {
    log('no transcript_path (' + transcriptPath + '); skipping'); process.exit(0);
  }

  const lines = fs.readFileSync(transcriptPath, 'utf8').split(/\r?\n/).filter(Boolean);
  const entries = [];
  for (const l of lines) { try { entries.push(JSON.parse(l)); } catch (_) {} }

  // Metadata: last-seen git branch/cwd, first real user prompt for the title.
  let branch = hook.gitBranch || '';
  let cwd = hook.cwd || '';
  let firstPrompt = '';
  const md = [];

  for (const e of entries) {
    if (e.gitBranch) branch = e.gitBranch;
    if (e.cwd) cwd = e.cwd;
    if (e.type !== 'user' && e.type !== 'assistant') continue;
    const msg = e.message || {};
    const role = msg.role || e.type;
    let content = msg.content;
    if (typeof content === 'string') content = [{ type: 'text', text: content }];
    if (!Array.isArray(content)) continue;

    const side = e.isSidechain ? ' _(subagent)_' : '';
    const blocks = [];
    for (const b of content) {
      if (!b || typeof b !== 'object') continue;
      if (b.type === 'text' && b.text && b.text.trim()) {
        if (!firstPrompt && role === 'user') firstPrompt = b.text.trim();
        blocks.push(b.text);
      } else if (b.type === 'thinking' && b.thinking && b.thinking.trim()) {
        blocks.push('> 🧠 _thinking_\n>\n' + b.thinking.split('\n').map(x => '> ' + x).join('\n'));
      } else if (b.type === 'tool_use') {
        const input = truncate(JSON.stringify(b.input || {}, null, 2), PER_BLOCK_CAP);
        blocks.push(`🔧 **${b.name}**\n` + fence(input));
      } else if (b.type === 'tool_result') {
        const body = truncate(renderToolResultContent(b.content), PER_BLOCK_CAP);
        blocks.push('↳ _result_\n' + fence(body));
      }
    }
    if (!blocks.length) continue;

    const icon = role === 'assistant' ? '🤖 Assistant' : '👤 User';
    md.push(`\n### ${icon}${side}\n`);
    md.push(blocks.join('\n\n'));
  }

  const author = (process.env.OASIS_BOARD_AUTHOR || os.userInfo().username || 'unknown').trim();
  const when = new Date();
  const dateStr = when.toISOString().slice(0, 16).replace('T', ' ');
  const promptLine = firstPrompt.replace(/\s+/g, ' ').slice(0, 80);
  const title = `${author} · ${dateStr}${promptLine ? ' · ' + promptLine : ''}`;

  const header =
    `# Claude session — ${author}\n\n` +
    `- **when:** ${when.toISOString()}\n` +
    `- **cwd:** ${cwd}\n` +
    `- **branch:** ${branch || '(unknown)'}\n` +
    `- **session:** ${hook.session_id || '(unknown)'}\n` +
    (hook.reason ? `- **end reason:** ${hook.reason}\n` : '') +
    `\n---\n`;

  let body = header + md.join('\n');
  if (body.length > BODY_CAP) body = body.slice(0, BODY_CAP) + '\n\n…[session truncated to fit board limit]';

  if (!body.trim() || md.length === 0) { log('empty session; skipping'); process.exit(0); }

  const payload = {
    title,
    author,
    kind: 'session',
    task: '',
    branch,
    session_id: hook.session_id || '',
    body,
    meta: { cwd, version: hook.version || '', reason: hook.reason || '' },
  };

  if (process.env.OASIS_BOARD_DRYRUN) {
    const out = process.env.OASIS_BOARD_DRYRUN;
    if (out === '1' || out === '-') { process.stdout.write(body); }
    else { fs.writeFileSync(out, body); log(`dryrun wrote ${body.length}B to ${out}`); }
    process.exit(0);
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  fetch(BOARD_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Board-Token': token },
    body: JSON.stringify(payload),
    signal: controller.signal,
  })
    .then(async (r) => {
      const t = await r.text().catch(() => '');
      if (r.ok) log(`shipped '${title}' (${body.length}B) -> ${t.slice(0, 120)}`);
      else log(`ship failed ${r.status}: ${t.slice(0, 200)}`);
    })
    .catch((e) => log('ship error: ' + (e && e.message)))
    .finally(() => { clearTimeout(timer); process.exit(0); });
}

try { main(); } catch (e) { log('fatal: ' + (e && e.stack || e)); process.exit(0); }
