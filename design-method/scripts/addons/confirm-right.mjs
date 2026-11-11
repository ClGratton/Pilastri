// Add-on `confirm-right` (config block `confirmRight`; SKILL section 2, K30 / critique rule CR-96; DESIGN G-17(d)).
// User 2026-11-10 22:40: "Darò che maggior parte delle persone usa mano destra avanti stara a destra (metti in skill che tasto conferma sta a destra)".
// Rule on rest dumps (dp): in a row of sibling actions the action that confirms or goes forward is the rightmost; secondary and escape actions sit to its left.
// Roles come from tags (resource-id / testTag), never from the text of one screen: config `confirmIds` / `secondaryIds` are regexes on the node id.
// A row = button-sized clickables whose vertical bands overlap by more than rowShare of the shorter one. Flagged: a confirm action whose right edge is left of the right edge of a
// secondary sibling of its row (confirm-right). Rows without a tagged confirm are not judged (the tag is how a screen declares its confirming action).
// Config: { confirmIds: ["^action-confirm"], secondaryIds: ["^action-secondary"], rowShare: 0.5, minWidthDp: 48, maxHeightDp: 64, tolDp: 1 }
//   node confirm-right.mjs --selftest
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { confirmIds: ['^action-confirm'], secondaryIds: ['^action-secondary'], rowShare: 0.5, minWidthDp: 48, maxHeightDp: 64, tolDp: 1 };
const lab = (n, all) => { const t = all.find((x) => x !== n && (x.text || '').trim() && x.l >= n.l - 1 && x.r <= n.r + 1 && x.t >= n.t - 1 && x.b <= n.b + 1); return ((n.id || '') + (n.text || (t && t.text) ? ' "' + (n.text || t.text) + '"' : '')).slice(0, 40); };

export function confirmRight(dump, userCfg = {}) {
  const C = { ...DEFAULTS, ...userCfg }, { nodes } = dump, out = [], res = (a) => a.map((x) => new RegExp(x));
  const conf = res(C.confirmIds), sec = res(C.secondaryIds), isC = (n) => conf.some((r) => r.test(n.id || '')), isS = (n) => sec.some((r) => r.test(n.id || ''));
  const acts = nodes.filter((n) => (n.clickable || isC(n) || isS(n)) && n.r > n.l && n.b > n.t && (n.r - n.l) >= C.minWidthDp && (n.b - n.t) <= C.maxHeightDp);
  for (const c of acts.filter(isC)) {
    for (const s of acts.filter((x) => x !== c && isS(x))) {
      const ov = Math.min(c.b, s.b) - Math.max(c.t, s.t);
      if (ov <= C.rowShare * Math.min(c.b - c.t, s.b - s.t)) continue;
      if (c.r < s.r - C.tolDp) out.push({ rule: 'confirm-right', el: lab(c, nodes) + ' left of ' + lab(s, nodes), value: c.r, expected: s.r, note: 'the confirming action must be the rightmost of its row (confirm ends at ' + Math.round(c.r) + ' dp, secondary at ' + Math.round(s.r) + ' dp)' });
    }
  }
  return out;
}

export function fixtures() {
  const n = (id, text, l, t, r, b, x = {}) => ({ id, text, l, t, r, b, ...x });
  // Cover width 360 dp: the "Approva in banca" page, bottom row of two sibling actions.
  const bad = { W: 360, H: 740, nodes: [n('setup-title', 'Approva in banca', 16, 118, 344, 154), n('action-confirm-retry', '', 16, 640, 216, 688, { clickable: true }), n('', 'Riprova', 90, 655, 140, 672), n('action-secondary-open', '', 228, 640, 344, 688, { clickable: true }), n('', 'Apri la pagina', 236, 655, 336, 672)] };
  const good = { W: 360, H: 740, nodes: [n('setup-title', 'Approva in banca', 16, 118, 344, 154), n('action-secondary-open', '', 16, 640, 132, 688, { clickable: true }), n('', 'Apri la pagina', 24, 655, 124, 672), n('action-confirm-retry', '', 144, 640, 344, 688, { clickable: true }), n('', 'Riprova', 218, 655, 268, 672)] };
  return { bad, good, cfg: {} };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), b = confirmRight(f.bad, f.cfg), g = confirmRight(f.good, f.cfg);
  const pass = b.length === 1 && b[0].rule === 'confirm-right' && g.length === 0;
  console.log(pass ? 'confirm-right selftest PASS (Riprova left of Apri la pagina flagged, the swapped row clean)' : 'confirm-right selftest FAIL ' + JSON.stringify(b.concat(g)));
  process.exit(pass ? 0 : 1);
}
