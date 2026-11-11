// Add-on `action-outcome` (config block `actionOutcome`; SKILL section 2, K18 / critique rule CR-86). User 2026-11-09 (button "Collega Gmail" pressed, no confirmation anywhere): an action that gives no visible result is a defect.
// Capture concept "actions": the capture tool taps every action control of a screen (config `actions`: [{ screen, text|id }]) and records the screen after the result settles (a few seconds). A result that lives in
// another app (a system sheet) is recorded as `after.foreign: true` and is excused; the return to the app is then captured as a second "after".
// Pair = { before: dump, after: dump, tapped: node }. Deviation when nothing changed: same node texts, same bounds, same checked/selected/enabled flags.
//   node action-outcome.mjs --selftest
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { ignoreText: [], minChangedNodes: 1 };
const sig = (d) => d.nodes.filter((n) => n.r > n.l && n.b > n.t).map((n) => [n.id || '', (n.text || '').trim(), Math.round(n.l), Math.round(n.t), Math.round(n.r), Math.round(n.b), n.selected ? 1 : 0, n.checkable ? 1 : 0, n.enabled === false ? 0 : 1].join('|'));

export function actionOutcome(before, after, tapped, userCfg = {}) {
  const C = { ...DEFAULTS, ...userCfg }, label = ((tapped.text || tapped.id || 'action') + '').slice(0, 28);
  if (C.ignoreText.some((x) => new RegExp(x).test(tapped.text || ''))) return [];
  if (after.foreign) return [];
  const a = new Set(sig(before)), b = new Set(sig(after));
  let changed = 0; for (const s of b) if (!a.has(s)) changed++; for (const s of a) if (!b.has(s)) changed++;
  return changed >= C.minChangedNodes ? [] : [{ rule: 'action-no-outcome', el: label, value: 0, expected: C.minChangedNodes, note: 'the screen did not change after the tap: no progress, done or failed state (every action confirms its result)' }];
}

export function fixtures() {
  const node = (id, text, l, t, r, b, extra = {}) => ({ id, text, l, t, r, b, ...extra });
  const before = { nodes: [node('title', 'Posta', 16, 96, 120, 128), node('btn', 'Collega Gmail', 16, 200, 200, 248, { clickable: true })] };
  return { before, silent: JSON.parse(JSON.stringify(before)), confirmed: { nodes: [node('title', 'Posta', 16, 96, 120, 128), node('status', 'Configurazione Google mancante', 16, 200, 300, 248)] }, tapped: before.nodes[1] };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), bad = actionOutcome(f.before, f.silent, f.tapped), ok = actionOutcome(f.before, f.confirmed, f.tapped);
  const pass = bad.length === 1 && ok.length === 0;
  console.log(pass ? 'action-outcome selftest PASS (silent tap flagged, confirmed tap clean)' : 'action-outcome selftest FAIL');
  process.exit(pass ? 0 : 1);
}
