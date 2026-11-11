// Reads Claude Code subagent transcripts (JSONL with `"type":"tool_use","id":"..."` entries); another host passes its own call pattern with --pattern.
// Exits (waking the main session) as soon as a running subagent crosses a tool-call step. Costs no model tokens.
// STARTING POINT: adapt the transcript directory to the host (SKILL.md "Adapting the tools"); run it in the background when an agent starts.
// Usage: node agent-budget-watch.mjs <subagentTranscriptDir> [--steps 60,90,120,150] [--hours 4] [--active-min 15] [--state file.json] [--pattern <regex matching one tool call, the unique id in capture group 0>]
// State file: each step fires once per agent. Counts UNIQUE tool_use ids (a transcript can record each call twice).
import fs from 'node:fs';
import path from 'node:path';

const a = process.argv.slice(2);
const opt = (n, d) => { const i = a.indexOf('--' + n); return i >= 0 ? a[i + 1] : d; };
const dir = a.find((x, i) => !x.startsWith('--') && (i === 0 || !a[i - 1].startsWith('--')));
if (!dir || !fs.existsSync(dir)) { console.error('usage: agent-budget-watch.mjs <subagentTranscriptDir> [--steps 60,90,120,150] [--hours 4]'); process.exit(2); }
const STEPS = opt('steps', '60,90,120,150').split(',').map(Number);
const ACTIVE_MIN = Number(opt('active-min', 15));
const deadline = Date.now() + Number(opt('hours', 4)) * 3600e3;
const stateFile = opt('state', path.join(dir, '..', 'agent-budget-watch.json'));
const load = () => { try { return JSON.parse(fs.readFileSync(stateFile, 'utf8')); } catch { return {}; } };
const PATTERN = new RegExp(opt('pattern', '"type":"tool_use","id":"[^"]+"'), 'g');
const countCalls = (p) => new Set(fs.readFileSync(p, 'utf8').match(PATTERN) || []).size;

for (;;) {
  const seen = load();
  for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.jsonl'))) {
    const p = path.join(dir, f);
    if (Date.now() - fs.statSync(p).mtimeMs > ACTIVE_MIN * 60e3) continue;   // only agents still writing
    const n = countCalls(p);
    const crossed = STEPS.filter((s) => n >= s).pop();
    if (crossed && (seen[f] || 0) < crossed) {
      seen[f] = crossed;
      fs.writeFileSync(stateFile, JSON.stringify(seen));
      console.log(`${f}: ${n} tool calls, crossed ${crossed}`);
      process.exit(0);
    }
  }
  if (Date.now() > deadline) { console.log('watch deadline reached, no agent crossed a step'); process.exit(0); }
  await new Promise((r) => setTimeout(r, 60e3));
}
