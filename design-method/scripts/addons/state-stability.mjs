// Add-on `state-stability` (config block `stateStability`; SKILL section 2, K15 / critique rule CR-83). User 2026-11-09 ("selezionare cambia tutto allineamento"): selecting the chip "Studente" added a check,
// widened it and pushed its siblings to other rows. A state change (selected, checked, pressed, expanded label, loading) never changes an element's size or moves its siblings.
// Capture concept "states": for every checkable/selectable node of a dump the capture tool taps it and records the screen again; the pair is { before: dump, after: dump, tapped: id|text }.
//   node state-stability.mjs --selftest                 (must-flag fixture + clean fixture, exit 1 on failure)
//   import { stateStability } from './state-stability.mjs'  ->  stateStability(before, after, cfg) = [{ rule, el, value, expected, note }]   (dump = { nodes: [{ id, text, l, t, r, b, checkable?, selected?, vis? }], W, H } in dp)
// Config: { tolDp: 1, ignoreIds: [], ignoreText: [] } (ids end with * for a prefix). Loosen per element with a reason in the project, never globally.
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { tolDp: 1, ignoreIds: [], ignoreText: [] };
const idIn = (id, list) => list.some((g) => (g.endsWith('*') ? id.startsWith(g.slice(0, -1)) : id === g));
const key = (n) => (n.id || '') + '|' + (n.text || '').slice(0, 40);
const lab = (n) => ((n.text || n.id || 'node') + '').slice(0, 28);

export function stateStability(before, after, userCfg = {}) {
  const C = { ...DEFAULTS, ...userCfg }, out = [];
  const keep = (n) => n.r > n.l && n.b > n.t && !idIn(n.id || '', C.ignoreIds) && !C.ignoreText.some((x) => new RegExp(x).test(n.text || ''));
  const post = new Map(after.nodes.filter(keep).map((n) => [key(n), n]));
  for (const a of before.nodes.filter(keep)) {
    const b = post.get(key(a)); if (!b) continue;
    const dw = Math.abs((b.r - b.l) - (a.r - a.l)), dh = Math.abs((b.b - b.t) - (a.b - a.t)), dx = Math.abs(b.l - a.l), dy = Math.abs(b.t - a.t);
    if (dw > C.tolDp || dh > C.tolDp) out.push({ rule: 'state-size', el: lab(a), value: Math.max(dw, dh), expected: 0, note: `size ${Math.round(a.r - a.l)}x${Math.round(a.b - a.t)} -> ${Math.round(b.r - b.l)}x${Math.round(b.b - b.t)}` });
    else if (dx > C.tolDp || dy > C.tolDp) out.push({ rule: 'state-sibling-moved', el: lab(a), value: Math.max(dx, dy), expected: 0, note: `moved ${Math.round(dx)},${Math.round(dy)} dp after the tap` });
  }
  return out;
}

export function fixtures() {
  const chip = (text, l, t, r) => ({ id: 'chip', text, l, t, r, b: t + 48, checkable: true });
  const before = { W: 411, H: 891, nodes: [chip('Studente', 16, 300, 116), chip('Dipendente pubblico', 124, 300, 316), chip('Nessun reddito', 16, 356, 136)] };
  const reflow = { W: 411, H: 891, nodes: [chip('Studente', 16, 300, 150), chip('Dipendente pubblico', 16, 356, 208), chip('Nessun reddito', 16, 412, 136)] };
  return { before, reflow, stable: JSON.parse(JSON.stringify(before)) };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), bad = stateStability(f.before, f.reflow), ok = stateStability(f.before, f.stable);
  const pass = bad.length >= 3 && ok.length === 0;
  console.log(pass ? `state-stability selftest PASS (Studente reflow flagged ${bad.length}, stable clean)` : `state-stability selftest FAIL (flagged ${bad.length}, clean ${ok.length})`);
  process.exit(pass ? 0 : 1);
}
