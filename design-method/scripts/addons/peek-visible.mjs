// Add-on `peek-visible` (config block `peekVisible`; SKILL section 2, K21 / critique rule CR-01). User 2026-11-08/09: "Il mese precedente si dovrebbe vedere un po' di più, a mala pena si capisce che si può fare swipe";
// "si deve vedere ancora più". Reserved space with nothing drawn does not meet an affordance rule: a neighbour page whose slot is wide enough in geometry but holds no drawn surface shows a blank indent.
// Range ends: an entry with `endGutterDp` is the other check (see rangeEnds): on a side with no drawn neighbour the page surface must reach the content margin.
// Config: [{ screen: <glob on the dump name>, dir?: <regex on the dump folder, slashes normalised to "/">, within?: <regex on id: only viewports lying inside such a node are checked (a strip whose pages are not drawn tiles of their own is out of scope)>, viewport: <regex on id>, neighbours: <regex on id>, surface: <regex on id>,
//            minDp: [{ screen: <glob>, dp: <number> }, ...] (first matching glob; none matching = entry not applied to that dump), tolDp?: 1 }].
// For every viewport and neighbour node that has a visible slot (its box cut by the viewport, wider than 0), the surface nodes (cut the same way) must cover at least `dp` of that slot, measured from the
// viewport's inner edge to the slot's far edge. No slot (no period on that side) is not checked. A config entry whose screen glob matches no dump is a warning (unmatched), never a pass.
//   node peek-visible.mjs --selftest
import { fileURLToPath } from 'node:url';

const globRe = (g) => new RegExp('^' + g.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$');

const bare = (id) => (id || '').replace(/^.*:id\//, '');
const BS = String.fromCharCode(92);
export const matchesScreen = (entry, name, dir = '') => globRe(entry.screen).test(name) && (!entry.dir || new RegExp(entry.dir).test(String(dir).split(BS).join('/')));

/**
 * Range ends (K21, user 2026-11-10 on Spese: "Qua perché così stretto?", then "va bene modifica schermata"). Entry shape: { screen, dir?, viewport, neighbours, current, surface, endGutterDp, endTolDp?, within? }.
 * The viewport overhangs the content column by `endGutterDp` (the caller's page margin); on a side where no neighbour page is drawn (no neighbour node with a visible slot on that side of the
 * current page) the current page's surface must reach the content margin (viewport edge -/+ endGutterDp), within `endTolDp` (default 1). Both sides empty = a single page: margin to margin.
 */
export function rangeEndSides(nodes, entry, v) {
  const nre = new RegExp(entry.neighbours), cre = new RegExp(entry.current), sre = new RegExp(entry.surface);
  const cut = (n) => ({ l: Math.max(n.l, v.l), r: Math.min(n.r, v.r), t: Math.max(n.t, v.t), b: Math.min(n.b, v.b) });
  const cur = nodes.filter((n) => cre.test(bare(n.id)) && (n.t + n.b) / 2 > v.t && (n.t + n.b) / 2 < v.b && (n.l + n.r) / 2 > v.l && (n.l + n.r) / 2 < v.r)[0];
  if (!cur) return null;
  const slots = nodes.filter((x) => nre.test(bare(x.id))).map((x) => ({ x, s: cut(x) })).filter(({ s }) => s.r - s.l > 0 && s.b - s.t > 0 && s.t < cur.b && s.b > cur.t);
  const left = slots.some(({ x }) => x.r <= cur.l + 1), right = slots.some(({ x }) => x.l >= cur.r - 1);
  const sf = nodes.filter((x) => sre.test(bare(x.id))).map(cut).filter((x) => x.r > x.l && x.b > x.t && x.t < cur.b && x.b > cur.t && x.l < cur.r && x.r > cur.l)
    .sort((a, b) => (Math.min(b.r, cur.r) - Math.max(b.l, cur.l)) - (Math.min(a.r, cur.r) - Math.max(a.l, cur.l)))[0];
  return { cur, left, right, sf };
}

/**
 * THE range-end geometry (one source of truth, used by rangeEnds below and by the page-gutter rule, CR-62). `inset` = how far a CENTRED page sits from the viewport edge (neighbour peek + gap).
 * Both neighbours: centred, returns null. One neighbour only: the page keeps its centred width and rests on the margin (viewport edge -/+ endGutterDp) of the empty side. None: margin to margin.
 */
export function rangeEndEdges(nodes, entry, v, inset) {
  const sd = rangeEndSides(nodes, entry, v), g = entry.endGutterDp;
  if (!sd || (sd.left && sd.right)) return null;
  const w = v.r - v.l - 2 * inset;
  if (!sd.left && !sd.right) return { l: v.l + g, r: v.r - g, ...sd };
  return sd.left ? { l: v.r - g - w, r: v.r - g, ...sd } : { l: v.l + g, r: v.l + g + w, ...sd };
}

export function rangeEnds(dump, entry, name) {
  const tol = entry.endTolDp ?? 1, g = entry.endGutterDp, vre = new RegExp(entry.viewport);
  const nodes = dump.nodes || [], rows = [];
  const wre = entry.within ? new RegExp(entry.within) : null, hosts = wre ? nodes.filter((n) => wre.test(bare(n.id))) : [];
  const inside = (v) => !wre || hosts.some((h) => v.l >= h.l - 1 && v.r <= h.r + 1 && v.t >= h.t - 1 && v.b <= h.b + 1);
  for (const v of nodes.filter((n) => vre.test(bare(n.id)) && inside(n))) {
    const sd = rangeEndSides(nodes, entry, v);
    if (!sd || !sd.sf) continue;
    const { left, right, sf } = sd;
    const check = (side, gap) => { if (Math.abs(gap) > tol) rows.push({ rule: 'G-11 range ends: the page surface stops short of the content margin on a side with no neighbour', el: `${side} edge ${gap > 0 ? 'inside' : 'outside'} the margin by ${Math.abs(gap).toFixed(1)} dp`, value: Math.abs(gap), expected: 0, note: name }); };
    if (!left) check('left', sf.l - (v.l + g));
    if (!right) check('right', (v.r - g) - sf.r);
  }
  return rows;
}

/** Rows for one dump (nodes in dp) against one config entry. `name` is the dump name, used to pick the minimum. */
export function peekVisible(dump, entry, name, info = []) {
  if (entry.endGutterDp !== undefined) return rangeEnds(dump, entry, name);
  const need = (entry.minDp || []).find((m) => globRe(m.screen).test(name));
  if (!need) return [];
  const tol = entry.tolDp ?? 1, vre = new RegExp(entry.viewport), nre = new RegExp(entry.neighbours), sre = new RegExp(entry.surface), nodes = dump.nodes || [];
  const rows = [];
  const wre = entry.within ? new RegExp(entry.within) : null, hosts = wre ? nodes.filter((n) => wre.test(bare(n.id))) : [];
  const inside = (v) => !wre || hosts.some((h) => v.l >= h.l - 1 && v.r <= h.r + 1 && v.t >= h.t - 1 && v.b <= h.b + 1);
  for (const v of nodes.filter((n) => vre.test(bare(n.id)) && inside(n))) {
    const cut = (n) => ({ l: Math.max(n.l, v.l), r: Math.min(n.r, v.r), t: Math.max(n.t, v.t), b: Math.min(n.b, v.b) });
    // A viewport with no neighbour page at all (one window of data): the rule cannot check it. Listed in `info`, never counted as a deviation or a pass.
    const seen = nodes.filter((x) => nre.test(bare(x.id))).map(cut).some((s) => s.r - s.l > 0 && s.b - s.t > 0);
    if (!seen) info.push({ rule: 'peek not verifiable: no neighbour page', screen: name, viewport: `[${v.l},${v.t}][${v.r},${v.b}]` });
    for (const n of nodes.filter((x) => nre.test(bare(x.id)))) {
      const s = cut(n);
      if (!(s.r - s.l > 0 && s.b - s.t > 0)) continue;                      // not in this viewport, or no period on that side
      const drawn = nodes.filter((x) => sre.test(bare(x.id))).map(cut).filter((x) => x.r > x.l && x.b > x.t && x.t < s.b && x.b > s.t)
        .map((x) => Math.max(0, Math.min(x.r, s.r) - Math.max(x.l, s.l)));
      const w = drawn.length ? Math.max(...drawn) : 0;
      if (w < need.dp - tol) rows.push({ rule: 'CR-01, no drawn peek', el: `${bare(n.id)} slot ${(s.r - s.l).toFixed(1)} dp, drawn surface ${w.toFixed(1)} dp`, value: w, expected: need.dp, note: 'reserved space with nothing drawn does not meet the peek rule' });
    }
  }
  return rows;
}

export function fixtures() {
  const node = (id, l, t, r, b) => ({ id, text: '', l, t, r, b, enabled: true, clickable: false });
  const base = [node('period-viewport', 16, 400, 404, 500), node('period-page-prev', -300, 400, 56, 500), node('period-page-cur', 77, 400, 343, 500)];
  return {
    entry: { screen: '*pilastri', viewport: '^period-viewport$', neighbours: '^period-page-(prev|next)$', surface: '^period-page-surface$', minDp: [{ screen: 'fold-*', dp: 40 }, { screen: 'phone-*', dp: 28 }] },
    blank: { W: 412, H: 900, nodes: base },
    narrow: { W: 412, H: 900, nodes: [...base, node('period-page-surface', 16, 400, 40, 500)] },
    ok: { W: 412, H: 900, nodes: [...base, node('period-page-surface', 16, 400, 56, 500), node('period-page-surface', 77, 400, 343, 500)] },
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const node = (id, l, t, r, b) => ({ id, text: '', l, t, r, b, enabled: true, clickable: false });
  const f = fixtures(), a = peekVisible(f.blank, f.entry, 'fold-top-pilastri'), b = peekVisible(f.narrow, f.entry, 'fold-top-pilastri'), c = peekVisible(f.ok, f.entry, 'fold-top-pilastri'), d = peekVisible(f.ok, f.entry, 'phone-top-pilastri');
  const inf = []; peekVisible({ W: 412, H: 900, nodes: [node('period-viewport', 16, 400, 404, 500), node('period-page-cur', 77, 400, 343, 500)] }, f.entry, 'fold-top-spese', inf);
  const inf2 = []; peekVisible(f.ok, f.entry, 'fold-top-pilastri', inf2);
  const pass = inf.length === 1 && inf[0].viewport === '[16,400][404,500]' && inf2.length === 0 && a.length === 1 && b.length === 1 && c.length === 0 && d.length === 0 && peekVisible(f.blank, f.entry, 'fold-top-spese').length === 1 && matchesScreen(f.entry, 'fold-top-pilastri') && !matchesScreen(f.entry, 'fold-top-spese')
    && matchesScreen({ ...f.entry, dir: 'CR-01/peek' }, 'fold-top-pilastri', ['x','fixtures','cr','CR-01','peek-no-surface-bad'].join(BS)) && !matchesScreen({ ...f.entry, dir: 'CR-01/peek' }, 'fold-top-pilastri', 'x/fixtures/w10');
  const ee = { screen: '*', viewport: '^period-viewport$', neighbours: '^period-page-(prev|next)$', current: '^period-page-cur$', surface: '^period-page-surface$', endGutterDp: 16 };
  const strip = (curL, curR, prevR) => ({ W: 360, H: 700, nodes: [node('period-viewport', 0, 100, 360, 200), node('period-page-prev', -300, 100, prevR, 200), node('period-page-cur', curL, 100, curR, 200), node('period-page-surface', curL, 100, curR, 200)] });
  const endsBad = rangeEnds(strip(36, 324, 28), ee, 'phone-x'), endsGood = rangeEnds(strip(56, 344, 48), ee, 'phone-x');
  const endsMid = rangeEnds({ W: 360, H: 700, nodes: [node('period-viewport', 0, 100, 360, 200), node('period-page-prev', -300, 100, 28, 200), node('period-page-next', 332, 100, 700, 200), node('period-page-cur', 36, 100, 324, 200), node('period-page-surface', 36, 100, 324, 200)] }, ee, 'phone-x');
  const endsSingle = rangeEnds({ W: 360, H: 700, nodes: [node('period-viewport', 0, 100, 360, 200), node('period-page-cur', 36, 100, 324, 200), node('period-page-surface', 36, 100, 324, 200)] }, ee, 'phone-x');
  const vp = node('period-viewport', 0, 100, 360, 200), eg = (nodes) => rangeEndEdges(nodes, ee, vp, 36);
  const gPrev = eg([vp, node('period-page-prev', -300, 100, 48, 200), node('period-page-cur', 56, 100, 344, 200), node('period-page-surface', 56, 100, 344, 200)]);
  const gNext = eg([vp, node('period-page-next', 312, 100, 700, 200), node('period-page-cur', 16, 100, 304, 200), node('period-page-surface', 16, 100, 304, 200)]);
  const gOne = eg([vp, node('period-page-cur', 16, 100, 344, 200), node('period-page-surface', 16, 100, 344, 200)]);
  const gMid = eg([vp, node('period-page-prev', -300, 100, 28, 200), node('period-page-next', 332, 100, 700, 200), node('period-page-cur', 36, 100, 324, 200), node('period-page-surface', 36, 100, 324, 200)]);
  const edgesOk = gPrev.l === 56 && gPrev.r === 344 && gNext.l === 16 && gNext.r === 304 && gOne.l === 16 && gOne.r === 344 && gMid === null;
  if (!edgesOk) { console.log('peek-visible selftest FAIL (range-end edges)'); process.exit(1); }
  const endsOk = endsBad.length === 1 && endsGood.length === 0 && endsMid.length === 0 && endsSingle.length === 2;
  if (!endsOk) { console.log('peek-visible selftest FAIL (range ends)'); process.exit(1); }
  console.log(pass ? 'peek-visible selftest PASS (blank slot flagged, 24 dp tile flagged, 40 dp tile clean, cover minimum 28 dp; range ends: short page flagged, margin page clean, both-sides peek clean, single page both edges)' : 'peek-visible selftest FAIL');
  process.exit(pass ? 0 : 1);
}
