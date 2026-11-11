// Add-on `aligned-to-nothing` (config `addons: ["aligned-to-nothing"]`, config block `alignedToNothing`; SKILL section 2, K14).
// User 2026-11-09 ("deve flaggare cose non allineate a nulla, sia skill che script"). Two rules on rest dumps (not moving/hold/animation frames):
//   aligned-to-nothing  every drawn LEAF element (visible text, or a small described control/icon) must have at least one of its lines (left, right, centre-x, top, bottom, centre-y) coincide, within tolDp,
//                       with the SAME line of another leaf or of a drawn container (config anchorIds, e.g. card, chip, dock). An element aligned to nothing is a deviation. A dump has no text baseline, so the
//                       text box centre/edges stand in for it. Legitimate lone elements (a chart point, a centred empty-state) are loosened per element with `ignoreIds` / `ignoreText` and a reason in the project.
//   header-centre       a trailing header control must share the title's vertical centre: for each {a, b} pair of ids in `centrePairs` (default page-trailing vs page-title, page-trailing vs period-title),
//                       when both are visible in the same pane/column, |centre-y a - centre-y b| <= tolDp. The first node of each id per side (left/right half of a wide screen) is compared.
// Config `alignedToNothing`: { tolDp: 1, anchorIds: ["card"], ignoreIds: [], ignoreText: [], centrePairs: [["page-trailing","page-title"]], controlMaxDp: 56, minDp: 6 }; ids end with * for a prefix.
import fs from 'node:fs';
import path from 'node:path';

export const DEFAULTS = { tolDp: 1, anchorIds: ['card'], ignoreIds: [], ignoreText: [], centrePairs: [['page-trailing', 'page-title']], controlMaxDp: 56, minDp: 6 };
const idIn = (id, list) => list.some((g) => (g.endsWith('*') ? id.startsWith(g.slice(0, -1)) : id === g));
const lab = (n) => ((n.text || n.id || 'node') + '').slice(0, 28);
export const REST_ONLY = (name) => !/-f[0-9]{2,}$/.test(name) && !name.endsWith('-moving') && !name.includes('-hold-');

/** dump = { nodes, W, H }; returns [{ rule, el, value, expected, note }] (value in dp). */
export function alignedToNothing(dump, userCfg = {}) {
  const C = { ...DEFAULTS, ...userCfg }, t = C.tolDp, out = [], { nodes, W, H } = dump;
  const fold = W > 600;   // two panes side by side
  const inScreen = (n) => n.r > n.l && n.b > n.t && n.r > 0 && n.l < W && n.b > 0 && n.t < H;
  const isLeaf = (n) => inScreen(n) && !idIn(n.id, C.ignoreIds) && !C.ignoreText.some((x) => new RegExp(x).test(n.text || '')) &&
    ((n.vis > 0 && (n.text || '').trim().length > 0) || ((n.text || '').trim().length > 0 && (n.r - n.l) <= C.controlMaxDp && (n.b - n.t) <= C.controlMaxDp && (n.clickable || !n.vis)));
  const leaves = nodes.filter((n) => isLeaf(n) && (n.r - n.l) >= C.minDp && (n.b - n.t) >= C.minDp);
  const anchors = nodes.filter((n) => inScreen(n) && idIn(n.id, C.anchorIds));
  const lines = (n) => [['l', n.l], ['r', n.r], ['cx', (n.l + n.r) / 2], ['t', n.t], ['b', n.b], ['cy', (n.t + n.b) / 2]];
  const same = (a, b) => a.l === b.l && a.t === b.t && a.r === b.r && a.b === b.b;
  // Drawn controls (clickable boxes: fields, buttons) are alignment lines too: a field and the button inside it share their top and centre.
  const controls = nodes.filter((n) => inScreen(n) && n.clickable && (n.r - n.l) >= C.minDp && (n.b - n.t) >= C.minDp);
  const others = leaves.concat(anchors, controls.filter((c) => !anchors.includes(c) && !leaves.includes(c)));
  const inside = (o, n) => o.l <= n.l && o.r >= n.r && o.t <= n.t && o.b >= n.b && !same(o, n);
  const shares = (n, skip) => { const mine = lines(n); return others.some((o) => o !== n && !same(o, n) && !skip(o) && lines(o).some(([k, v], i) => Math.abs(v - mine[i][1]) <= t)); };
  for (const n of leaves) {
    // A label inside its own clickable container (the "Incolla" text in its button) is judged by that container too: the person sees the
    // button, not the text box; the container's own lines must be shared with something that is not the label or its ancestors.
    const box = controls.filter((c) => inside(c, n)).sort((a, b) => (a.r - a.l) * (a.b - a.t) - (b.r - b.l) * (b.b - b.t))[0];
    const hit = shares(n, (o) => o.clickable && inside(o, n)) || (box ? shares(box, (o) => o === n || inside(o, box) || inside(box, o)) : false);
    if (!hit) out.push({ rule: 'aligned to nothing: an element has no edge or centre line shared with any other element (K14)', el: lab(n), value: Math.round(n.l * 10) / 10, expected: 0, note: n.id || '' });
  }
  const side = (n) => (fold ? ((n.l + n.r) / 2 < W / 2 ? 'L' : 'R') : 'P');
  for (const [ia, ib] of C.centrePairs) for (const P of fold ? ['L', 'R'] : ['P']) {
    const a = nodes.find((n) => idIn(n.id, [ia]) && inScreen(n) && side(n) === P), b = nodes.find((n) => idIn(n.id, [ib]) && inScreen(n) && side(n) === P);
    if (!a || !b) continue;
    const d = Math.abs((a.t + a.b) / 2 - (b.t + b.b) / 2);
    if (d > t) out.push({ rule: `header-centre: ${ia} is centred on the ${ib} (K14)`, el: lab(a), value: Math.round(d * 10) / 10, expected: 0, note: fold ? (P === 'L' ? 'left pane' : 'right pane') : '' });
  }
  return out;
}

export default {
  name: 'aligned-to-nothing',
  checks: { alignedToNothing: [] },
  roles: { alignedToNothing: [] },
  finish(c, X) {
    if (!c.on('alignedToNothing')) return;
    const cfg = { ...DEFAULTS, ...(c.cfg.alignedToNothing || {}) };
    for (const f of X.allFiles) {
      const name = X.stem(f); if (!REST_ONLY(name)) continue;
      const nodes = X.parse(path.join(X.dir, f)), W = Math.max(0, ...nodes.map((n) => n.r)), H = Math.max(0, ...nodes.map((n) => n.b));
      for (const d of alignedToNothing({ nodes, W, H }, cfg)) X.push(d.rule, d.el, d.value, d.expected, name, name.split(X.NM.layoutSeparator)[0]);
    }
  },
};

// ---- self-test fixtures (synthetic dumps, 360 x 640 px, density 1). Shared by design-method/scripts/selftest.mjs and tools/consistency-check.mjs --self-test.
// Must-flag: ant-header-bad (trailing control centre 8 dp below the title centre: the user's "?" case), ant-lone-bad (a text with no line shared with anything).
// Must-pass: ant-good (header centred, every text shares a line, a declared lone chart point is ignored per element).
export const ANT_CONFIG = { tolDp: 1, anchorIds: ['card'], ignoreIds: ['chart-point'] };
export function antFixtures(root) {
  const nd = (id, text, [l, t, r, b], f = {}) => `<node resource-id="app:id/${id}" text="${text}" clickable="${!!f.click}" scrollable="false" bounds="[${l},${t}][${r},${b}]" />`;
  const write = (d, name, nodes) => { fs.mkdirSync(path.join(root, d), { recursive: true }); fs.writeFileSync(path.join(root, d, name + '.xml'), `<hierarchy>${nodes.join('')}</hierarchy>`); };
  const rows = [nd('row-a', 'Affitto', [16, 100, 120, 120]), nd('row-a-val', '650 euro', [250, 100, 344, 120]), nd('row-b', 'Cibo', [16, 140, 120, 160]), nd('row-b-val', '210 euro', [250, 140, 344, 160])];
  write('ant-header-bad', 'narrow-top-a', [nd('page-title', 'Spese', [16, 10, 200, 42]), nd('page-trailing', '?', [296, 18, 344, 66], { click: true }), ...rows]);
  write('ant-lone-bad', 'narrow-top-a', [nd('page-title', 'Spese', [16, 10, 200, 42]), ...rows, nd('stray', 'Nota', [53, 203, 131, 217])]);
  write('ant-good', 'narrow-top-a', [nd('page-title', 'Spese', [16, 10, 200, 42]), nd('page-trailing', '?', [296, 2, 344, 50], { click: true }), ...rows, nd('chart-point', 'Punto', [53, 303, 61, 311])]);
}
