// Add-on `visual-integrity` (config `addons: ["visual-integrity"]`, config block `visualIntegrity`): wrong edges, compenetrations, hidden content (SKILL section 2, K13).
// A uiautomator dump gives only the VISIBLE (already clipped) bounds, so a glyph cut by an invisible clip edge looks like a slightly smaller box. The in-app dump therefore also writes, per node,
// `layout-bounds` (the node's own unclipped box), optionally `alpha`, `scroll-end="true"` on a scroller at its end, and lists texts that are not visible to the user as flat `hidden-node`s.
// Rules, run on every dump at rest AND on every animation frame (`<screen>-f00.xml`, `<screen>-sw-f03.xml` ...; frames are read by this add-on only, no other rule sees them):
//   clip-edge  a content node (visible text) whose layout box is larger than its visible box by more than tolDp on a side, where the clipping edge is neither the screen edge, nor the edge of a drawn
//              container (config boundaryIds), nor a scroller's own edge (top/bottom, scrollClip), nor inside a declared pager/offscreen page (allowClipIds). The cut is then invisible: a wrong edge.
//   overlap    two unrelated leaf elements (visible text, content-description icon, small control) whose visible boxes intersect by more than tolDp on both axes; neither contains the other. A text
//              against a text-less control counts only on the control's own row (its touch box reaches into the next row). Generalises CR-69 / CR-74 / CR-79, which stay as fixtures.
//   hidden     (rest dumps only) a node with text or description that is zero-size, alpha 0, or sits with its centre inside the window yet is not visible to the user, unless declared (allowHiddenIds)
//              or inside a declared page; and, on a scroller at its end (`scroll-end="true"` or a dump named `*-end`), text under a fixed overlay (floatingIds, e.g. the dock): last content must be reachable.
// Config `visualIntegrity`: { tolDp: 1, boundaryIds: [], allowClipIds: [], allowHiddenIds: [], overlapIgnoreIds: [], nonDrawingIds: [], floatingIds: [], scrollClip: true, controlMaxDp: 56, frames: true }; ids end with * for a prefix.
import fs from 'node:fs';
import path from 'node:path';

export const DEFAULTS = { tolDp: 1, boundaryIds: [], allowClipIds: [], allowHiddenIds: [], overlapIgnoreIds: [], floatingIds: [], scrollClip: true, controlMaxDp: 56, frames: true };
export const FRAME_RE = /-f[0-9]{2,}$/;   // `<screen>-f00`, `<screen>-sw-f12`

const idIn = (id, list) => list.some((g) => (g.endsWith('*') ? id.startsWith(g.slice(0, -1)) : id === g));
const lbox = (n) => n.lb || n;   // layout box (dp); a dump without layout-bounds is read as "not clipped"
const within = (a, c, t) => a.l >= c.l - t && a.t >= c.t - t && a.r <= c.r + t && a.b <= c.b + t;
const lab = (n) => ((n.text || n.id || 'node') + '').slice(0, 28);

/** dump = { nodes, hidden, W, H, name }; returns [{ rule, el, value, expected }] (value in dp). */
export function visualIntegrity(dump, userCfg = {}) {
  const C = { ...DEFAULTS, ...userCfg }, t = C.tolDp, out = [];
  const { nodes, hidden = [], W, H } = dump, frame = dump.frame ?? FRAME_RE.test(dump.name || '');
  const add = (rule, el, value, expected = 0) => out.push({ rule, el, value: Math.round(value * 10) / 10, expected });
  const allowed = nodes.concat(hidden).filter((n) => idIn(n.id, C.allowClipIds));
  const inAllowed = (b) => allowed.some((a) => within(b, lbox(a), t));
  const boundaries = nodes.filter((n) => idIn(n.id, C.boundaryIds));
  const scrollers = nodes.filter((n) => n.scroll);

  // ---- clip-edge
  for (const n of nodes) {
    if (!n.vis || !n.text.trim() || !n.lb) continue;
    const L = n.lb;
    const sides = [['left', L.l, n.l, 'l', 'h'], ['right', L.r, n.r, 'r', 'h'], ['top', L.t, n.t, 't', 'v'], ['bottom', L.b, n.b, 'b', 'v']];
    for (const [side, outer, inner, key, axis] of sides) {
      const over = side === 'left' || side === 'top' ? inner - outer : outer - inner;   // how much of the layout box is cut on that side
      if (over <= t) continue;
      const edge = n[key];   // where the cut is: the visible edge on that side
      if (edge <= t || edge >= (axis === 'h' ? W : H) - t) continue;   // the screen edge is a visible boundary
      if (inAllowed(L)) continue;
      const along = (b) => (axis === 'h' ? Math.min(b.b, n.b) - Math.max(b.t, n.t) > t : Math.min(b.r, n.r) - Math.max(b.l, n.l) > t);
      if (boundaries.some((b) => b !== n && Math.abs(b[key] - edge) <= t && along(b))) continue;   // a drawn container edge: the cut is visible
      if (C.scrollClip && axis === 'v' && scrollers.some((s) => s !== n && Math.abs(s[key] - edge) <= t && Math.min(s.r, n.r) - Math.max(s.l, n.l) > t)) continue;   // a list scrolling under its viewport edge
      add(`clip-edge: text is cut on its ${side} by an edge nothing draws${frame ? ' (animation frame)' : ''}`, lab(n), over, 0);
    }
  }

  // ---- overlap
  // Leaf candidates: a text, or a small text-less control. A tall clickable node with text is a merged card (its text is its children's), a container, not a leaf.
  const floats = nodes.filter((n) => idIn(n.id, C.floatingIds));
  // Only DRAWN elements: a node with a text or a content description (a labelled icon button). A transparent touch target (clickable, no text, no description) draws nothing and is never an overlap; config nonDrawingIds lists more.
  const cand = nodes.filter((n) => !idIn(n.id, C.overlapIgnoreIds) && !idIn(n.id, C.nonDrawingIds || []) && n.r - n.l > 0 && n.b - n.t > 0 && n.text.trim() && !(n.clickable && n.vis && n.b - n.t > C.controlMaxDp) && !(n.clickable && !n.vis && (n.r - n.l > C.controlMaxDp || n.b - n.t > C.controlMaxDp)));   // a clickable description-only node bigger than a control is a transparent tap overlay (a whole title or row), not a drawn icon
  const leaf = cand.filter((n) => !cand.some((m) => m !== n && within(m, n, 0.5) && !within(n, m, 0.5)));
  for (let i = 0; i < leaf.length; i++) for (let j = i + 1; j < leaf.length; j++) {
    const a = leaf[i], b = leaf[j];
    if (within(a, b, 0.5) || within(b, a, 0.5)) continue;   // one inside the other: one control seen twice (own label, inline icon)
    if (floats.some((f) => within(a, f, 0.5)) !== floats.some((f) => within(b, f, 0.5))) continue;   // content scrolling under a fixed overlay (dock, island): the scroll-end rule owns that
    if (a.text && b.text && (a.text.includes(b.text) || b.text.includes(a.text))) continue;
    const ix = Math.min(a.r, b.r, W) - Math.max(a.l, b.l, 0), iy = Math.min(a.b, b.b) - Math.max(a.t, b.t);
    if (ix <= t || iy <= t) continue;
    const ctl = (n) => !n.vis && n.r - n.l <= C.controlMaxDp && n.b - n.t <= C.controlMaxDp;   // a labelled icon: its 48 dp touch box is bigger than what is drawn
    if ((ctl(a) || ctl(b)) && Math.abs((a.t + a.b) / 2 - (b.t + b.b) / 2) > 0.25 * Math.min(a.b - a.t, b.b - b.t)) continue;   // touch box reaching into the next row
    add(`overlap: two elements draw over each other${frame ? ' (animation frame)' : ''}`, lab(a) + ' / ' + lab(b), Math.min(ix, iy), 0);
  }

  // ---- hidden (rest only)
  if (!frame) {
    for (const n of nodes) {
      if (!n.text.trim() || idIn(n.id, C.allowHiddenIds)) continue;
      if (n.alpha !== undefined && n.alpha < 0.01) add('hidden: text with alpha 0', lab(n), 0, 1);
    }
    for (const n of hidden) {
      if (!n.text.trim() || idIn(n.id, C.allowHiddenIds)) continue;
      const L = lbox(n), cx = (L.l + L.r) / 2, cy = (L.t + L.b) / 2;
      if (inAllowed(L)) continue;
      const inWin = cx >= 0 && cx <= W && cy >= 0 && cy <= H;
      if (L.r - L.l <= 0.5 || L.b - L.t <= 0.5) { if (inWin) add('hidden: text with zero size', lab(n), 0, 1); continue; }
      if (inWin) add('hidden: text inside the window that no one can see (clipped away)', lab(n), Math.min(L.r - L.l, L.b - L.t), 0);
    }
    if (C.floatingIds.length && (nodes.some((n) => n.scrollEnd) || /-end$/.test(dump.name || ''))) {
      const fl = nodes.filter((n) => idIn(n.id, C.floatingIds));
      for (const n of nodes) {
        if (!n.vis || !n.text.trim() || fl.some((f) => within(n, f, 0.5))) continue;
        for (const f of fl) { const ix = Math.min(n.r, f.r) - Math.max(n.l, f.l), iy = Math.min(n.b, f.b) - Math.max(n.t, f.t); if (ix > t && iy > t) add(`hidden: content stays under the fixed ${f.id} at the end of the scroll`, lab(n), iy, 0); }
      }
    }
  }
  return out;
}

/** Add-on contract of the generic checker: one `finish` pass over every dump (rest dumps and frames), one row per rule+element (the frames of one screen are merged). */
export default {
  name: 'visual-integrity',
  checks: { visualIntegrity: [] },
  roles: { visualIntegrity: [] },
  finish(c, X) {
    if (!c.on('visualIntegrity')) return;
    const cfg = { ...DEFAULTS, ...(c.cfg.visualIntegrity || {}) };
    const merged = new Map();
    for (const f of X.allFiles) {
      const name = X.stem(f), frame = FRAME_RE.test(name), mid = name.endsWith(X.NM.movingMarker) || name.includes(X.NM.holdMarker); if (frame && !cfg.frames) continue;
      const nodes = X.parse(path.join(X.dir, f)), hidden = X.parseHidden ? X.parseHidden(path.join(X.dir, f)) : [];
      const W = Math.max(0, ...nodes.map((n) => n.r)), H = Math.max(0, ...nodes.map((n) => n.b));
      const base = name.replace(/-f[0-9]{2,}$/, '');
      for (const d of visualIntegrity({ nodes, hidden, W, H, name, frame: frame || mid }, cfg)) {
        const key = base + '|' + d.rule + '|' + d.el, m = merged.get(key);
        if (m) { m.n++; if (d.value > m.value) m.value = d.value; } else merged.set(key, { ...d, name, base, layout: base.split(X.NM.layoutSeparator)[0], n: 1, frame: frame || mid });
      }
    }
    for (const m of merged.values()) X.push(m.rule + (m.frame && m.n > 1 ? ` [${m.n} frames, first ${m.name}]` : ''), m.el, m.value, m.expected, m.frame ? m.base : m.name, m.layout);
  },
};

// ---- self-test fixtures (synthetic dumps, 360 x 640 px, density 1). Shared by design-method/scripts/selftest.mjs and tools/consistency-check.mjs --self-test.
// Must-flag: vi-clip-rest (first glyph cut by an invisible edge), vi-clip-wobble (the same cut only in the frames of a settling spring), vi-overlap, vi-hidden, vi-end-bad.
// Must-pass: vi-peek-good (declared neighbour page), vi-scroll-good (list under its viewport edge, text cut by a drawn card edge, last content above the dock).
export const VI_CONFIG = { tolDp: 1, boundaryIds: ['card'], allowClipIds: ['period-page-prev', 'period-page-next'], allowHiddenIds: ['period-page-*'], floatingIds: ['dock'] };
export function viFixtures(root) {
  const nd = (id, text, [l, t, r, b], lb, f = {}, tag = 'node') => `<${tag} resource-id="app:id/${id}" text="${text}" clickable="${!!f.click}" scrollable="${!!f.scroll}" bounds="[${l},${t}][${r},${b}]"` +
    `${lb ? ` layout-bounds="[${lb[0]},${lb[1]}][${lb[2]},${lb[3]}]"` : ''}${f.alpha !== undefined ? ` alpha="${f.alpha}"` : ''}${f.end ? ' scroll-end="true"' : ''} />`;
  const write = (d, name, nodes) => { fs.mkdirSync(path.join(root, d), { recursive: true }); fs.writeFileSync(path.join(root, d, name + '.xml'), `<hierarchy>${nodes.join('')}</hierarchy>`); };
  const base = [nd('page-content', '', [0, 0, 360, 640], [0, 0, 360, 640])];
  const title = (cut) => nd('period-title', '9 ott – 8 nov', [14 + cut, 100, 200, 130], [14, 100, 200, 130]);
  write('vi-clip-rest', 'narrow-clip', [...base, title(16), nd('card', '', [14, 200, 346, 300], [14, 200, 346, 300])]);
  write('vi-clip-wobble', 'narrow-clip', [...base, title(0)]);   // rest is clean ...
  for (const [k, cut] of [[0, 0], [1, 7], [2, 3], [3, 0]]) write('vi-clip-wobble', `narrow-clip-f0${k}`, [...base, title(cut)]);   // ... the spring overshoot cuts it in frames 1 and 2
  write('vi-overlap', 'narrow-ov', [...base, nd('stat-label', 'Versato tuo', [20, 100, 140, 120], [20, 100, 140, 120]), nd('stat-value', '150 euro', [120, 100, 220, 120], [120, 100, 220, 120])]);
  write('vi-hidden', 'narrow-hid', [...base, nd('alpha-zero', 'Invisible', [20, 300, 120, 320], [20, 300, 120, 320], { alpha: 0 }),
    nd('budget', 'Budget', [20, 200, 20, 200], [20, 200, 120, 220], {}, 'hidden-node'), nd('zero', 'Zero', [30, 250, 30, 250], [30, 250, 30, 250], {}, 'hidden-node')]);
  write('vi-peek-good', 'narrow-peek', [...base, nd('period-page-next', '', [320, 100, 344, 300], [320, 100, 640, 300]), nd('neighbour-title', 'Nov', [320, 120, 344, 150], [320, 120, 400, 150]),
    nd('neighbour-off', 'Dic', [0, 0, 0, 0], [500, 120, 560, 150], {}, 'hidden-node'), nd('period-page-next', 'x', [0, 0, 0, 0], [350, 120, 360, 150], {}, 'hidden-node')]);
  const scene = (lastTop) => [...base, nd('list', '', [0, 60, 360, 600], [0, 0, 360, 900], { scroll: true, end: true }), nd('row-top', 'Row cut under the top edge', [20, 60, 300, 90], [20, 40, 300, 90]),
    nd('card', '', [20, 200, 340, 300], [20, 200, 340, 300]), nd('card-text', 'Text cut by a drawn card edge', [30, 210, 340, 250], [30, 210, 420, 250]),
    nd('last-row', 'Last row', [20, lastTop, 200, lastTop + 40], [20, lastTop, 200, lastTop + 40]), nd('dock', '', [60, 560, 300, 620], [60, 560, 300, 620])];
  write('vi-scroll-good', 'narrow-end', scene(480));
  write('vi-end-bad', 'narrow-end', scene(570));
}
