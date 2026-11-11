// Add-on `sibling-actions` (config block `siblingActions`; SKILL section 2, K23 / critique rule CR-91; DESIGN G-17).
// User 2026-11-10, on "Apri il link": "Perche' i due tasti disallineati? E con tutto spazio perche' risicati a sinistra piccoli senza dire nulla cosi'?"
// Two rules on rest dumps, from clickable nodes (dp):
//   sibling-actions-width   clickable siblings stacked in a column (vertical gap <= gapDp, horizontal overlap >= overlapShare of the narrower) share width or start AND end lines:
//                           when their widths differ by more than tolDp the group is flagged (content-sized pills of different widths stacked).
//   sibling-actions-narrow  a stacked group of 2+ actions whose union is narrower than minShare of the content width, while the band to its right (up to the content right edge) holds nothing drawn.
// Content width: the widest node named by `contentRefIds` that contains the group's left edge, else the screen width minus marginDp on both sides.
// Only button-sized actions count: at least minWidthDp wide (a 48 dp icon target is not a pill) and at most maxHeightDp tall (a card or row is not a button).
// Config: { tolDp: 1, gapDp: 24, overlapShare: 0.5, minShare: 0.5, marginDp: 16, minDp: 24, minWidthDp: 64, maxHeightDp: 56, maxShare: 0.9, contentRefIds: ["setup-title"], ignoreIds: [], ignoreText: [] }
//   node sibling-actions.mjs --selftest
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { tolDp: 1, gapDp: 24, overlapShare: 0.5, minShare: 0.5, marginDp: 16, minDp: 24, minWidthDp: 64, maxHeightDp: 56, maxShare: 0.9, contentRefIds: ['setup-title'], ignoreIds: [], ignoreText: [] };
const lab = (n, all) => { const t = all.find((x) => x !== n && (x.text || '').trim() && x.l >= n.l - 1 && x.r <= n.r + 1 && x.t >= n.t - 1 && x.b <= n.b + 1); return ((n.text || (t && t.text) || n.id || 'action') + '').slice(0, 28); };

export function siblingActions(dump, userCfg = {}) {
  const C = { ...DEFAULTS, ...userCfg }, { nodes, W } = dump, out = [], t = C.tolDp;
  const drawn = (n) => n.r > n.l && n.b > n.t;
  let acts = nodes.filter((n) => n.clickable && drawn(n) && (n.r - n.l) >= C.minDp && (n.b - n.t) >= C.minDp && (n.r - n.l) >= C.minWidthDp && (n.b - n.t) <= C.maxHeightDp && (n.r - n.l) < W * C.maxShare && !C.ignoreIds.includes(n.id) && !C.ignoreText.some((x) => new RegExp(x).test(lab(n, nodes))));
  acts = acts.filter((a) => !acts.some((c) => c !== a && c.l >= a.l - 1 && c.r <= a.r + 1 && c.t >= a.t - 1 && c.b <= a.b + 1 && (c.r - c.l) * (c.b - c.t) < (a.r - a.l) * (a.b - a.t)));   // containers of other actions are not actions
  acts.sort((p, q) => p.t - q.t);
  const groups = [];
  for (const a of acts) {
    const g = groups.find((G) => G.some((b) => { const ov = Math.min(a.r, b.r) - Math.max(a.l, b.l), nar = Math.min(a.r - a.l, b.r - b.l); return ov >= C.overlapShare * nar && a.t - b.b <= C.gapDp && a.t >= b.b - 1; }));
    if (g) g.push(a); else groups.push([a]);
  }
  for (const g0 of groups) {
    const rowMate = (x) => g0.some((y) => y !== x && Math.min(x.b, y.b) - Math.max(x.t, y.t) > 0.5 * Math.min(x.b - x.t, y.b - y.t)), g = g0.filter((x) => !rowMate(x));   // actions side by side on one row are not a column: only the ones alone on their row are compared
    if (g.length < 2) continue;
    const names = g.map((n) => lab(n, nodes)).join(' / '), widths = g.map((n) => n.r - n.l), wMin = Math.min(...widths), wMax = Math.max(...widths);
    if (wMax - wMin > t) out.push({ rule: 'sibling-actions-width', el: names, value: wMin, expected: wMax, note: 'stacked actions of one group differ in width (' + widths.map((w) => Math.round(w)).join('/') + ' dp) and so cannot share start and end lines' });
    const L = Math.min(...g.map((n) => n.l)), R = Math.max(...g.map((n) => n.r)), T = Math.min(...g.map((n) => n.t)), B = Math.max(...g.map((n) => n.b));
    const refs = nodes.filter((n) => C.contentRefIds.includes(n.id) && n.l <= L + t && n.r > R);
    const cr = refs.length ? Math.max(...refs.map((n) => n.r)) : W - C.marginDp, cw = cr - L;
    const other = nodes.filter((n) => !g.includes(n) && drawn(n) && ((n.text || '').trim() || n.clickable) && !(n.l >= L - 1 && n.r <= R + 1) && n.l < cr && n.r > R + t && n.t < B && n.b > T && (n.r - n.l) < cw * C.maxShare);
    if (cw > 0 && (R - L) < C.minShare * cw && other.length === 0) out.push({ rule: 'sibling-actions-narrow', el: names, value: R - L, expected: C.minShare * cw, note: 'actions use ' + Math.round(100 * (R - L) / cw) + '% of the content width and the rest of the row is empty' });
  }
  return out;
}

export function fixtures() {
  const n = (id, text, l, t, r, b, x = {}) => ({ id, text, l, t, r, b, ...x }), title = n('setup-title', 'Apri il link', 16, 118, 760, 154);
  // Dp of the real fold dump output/consistency-2026-11-10g/fold-setup-eb-2.xml (px / 2.625): pills 119, 104 and 77 dp wide, stacked at the start edge of a 760 dp content width.
  const bad = { W: 829, H: 600, nodes: [title, n('', '', 16, 266, 135, 314, { clickable: true, enabled: false }), n('', 'Rimanda · 0:59', 30, 283, 121, 298), n('', '', 16, 322, 120, 370, { clickable: true }), n('', 'Incolla il link', 30, 339, 106, 354), n('', '', 16, 370, 93, 418, { clickable: true }), n('', 'Cambia e-mail', 16, 388, 93, 401)] };
  // The approved paste page (fold-setup-eb-2-paste.xml): a field with its button on one row, one action below.
  const good = { W: 829, H: 600, nodes: [n('setup-title', 'Incolla il link', 16, 118, 826, 154), n('eb_paste_field', '', 32, 289, 743, 337, { clickable: true }), n('eb_paste_button', '', 750, 289, 819, 337, { clickable: true }), n('', 'Incolla', 764, 306, 805, 321), n('', '', 16, 390, 168, 438, { clickable: true }), n('', 'Riprova automatico', 32, 407, 152, 422)] };
  return { bad, good, cfg: {} };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), b = siblingActions(f.bad, f.cfg), g = siblingActions(f.good, f.cfg);
  const pass = b.some((x) => x.rule === 'sibling-actions-width') && b.some((x) => x.rule === 'sibling-actions-narrow') && g.length === 0;
  console.log(pass ? 'sibling-actions selftest PASS (the Apri il link pills flagged for width and narrowness, the paste page clean)' : 'sibling-actions selftest FAIL ' + JSON.stringify(b.concat(g)));
  process.exit(pass ? 0 : 1);
}
