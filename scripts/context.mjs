// How big this Claude Code conversation is (the session budget, CLAUDE.md → Working rules; copied from the
// DHARMA Initiative project's tools/context.mjs, 2026-10-18).
// Reads the hook / status line JSON on stdin, finds the last reply's token count at the end of the transcript.
//   --line  the status line: "Context 212k · fresh session soon"
//   --hook  for UserPromptSubmit: prints a note for Claude only when the conversation is over budget
//   --tool  for PostToolUse: the same note once per session, so long stretches of work see it too
import { openSync, readSync, fstatSync, closeSync, existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BUDGET = 250_000, SOON = 180_000;
let input = '';
for await (const c of process.stdin) input += c;
let size = 0, session = '';
try {
  const { transcript_path: p, session_id } = JSON.parse(input);
  session = session_id || '';
  const fd = openSync(p, 'r'), len = fstatSync(fd).size, n = Math.min(len, 4_000_000), buf = Buffer.alloc(n);
  readSync(fd, buf, 0, n, len - n); closeSync(fd);
  const lines = buf.toString('utf8').split('\n');
  for (let i = lines.length - 1; i >= 0 && !size; i--) {
    if (!lines[i].includes('"usage"')) continue;
    try {
      const e = JSON.parse(lines[i]), u = e.message?.usage;
      if (e.type === 'assistant' && u && !e.isSidechain) size = (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0) + (u.output_tokens || 0);
    } catch { /* a line cut at the start of the window */ }
  }
} catch { /* no transcript yet */ }
const k = `${Math.round(size / 1000)}k`;
const note = `Session budget: this conversation is ${k} tokens (budget ${BUDGET / 1000}k). Finish the item in hand, run /wrap-up (rewrite HANDOFF.md), and give the user the one-line prompt for a fresh session (CLAUDE.md → Working rules → Session budget).`;
if (process.argv.includes('--hook')) {
  if (size > BUDGET) console.log(note);
} else if (process.argv.includes('--tool')) {
  const mark = join(tmpdir(), `gitdashboard-budget-${session.replace(/[^\w-]/g, '')}`);
  if (size > BUDGET && session && !existsSync(mark)) {
    writeFileSync(mark, k);
    console.log(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: note } }));
  }
} else {
  console.log(`Context ${k}${size > BUDGET ? ' · over budget: start a fresh session' : size > SOON ? ' · fresh session soon' : ''}`);
}
