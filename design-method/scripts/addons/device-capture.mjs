// Registration of the device add-ons state-stability (K15), safe-area (K17) and action-outcome (K18) for uiautomator-format capture folders.
// The capture tool (capture-all.mjs, in-app walk) writes, next to the normal screens:
//   <screen>-state<K>-before.xml / -after.xml    one checkable node tapped (root attribute capture-tapped="<label>")
//   <screen>-action<K>-before.xml / -after.xml   one action control tapped (capture-tapped, capture-foreign="true" when another window took the focus)
//   root attributes insets="[l,t][r,b]" (system bars, px) and density="d" on every in-app dump
// deviceRows() pairs those files and runs the three add-ons; the pairs are NOT normal screens (PAIR_RE: the checkers exclude them from the other rules).
// Config block (project file, e.g. tools/device-addons.json): { stateStability: {...}, safeArea: {...}, actionOutcome: {...}, waitFallback: [{screen, wait, fallback}] (K19, CR-87), peekVisible: [{screen, dir?, viewport, neighbours, surface, minDp}] (K21, CR-01) } (each add-on's DEFAULTS).
import fs from 'node:fs';
import path from 'node:path';
import { stateStability } from './state-stability.mjs';
import { safeArea } from './safe-area.mjs';
import { actionOutcome } from './action-outcome.mjs';
import { waitFallback, matchesScreen } from './wait-fallback.mjs';
import { siblingActions } from './sibling-actions.mjs';
import { confirmRight } from './confirm-right.mjs';
import { workInProgress, matchesScreen as wipMatches } from './work-in-progress.mjs';
import { keyboardReturn, matchesScreen as kbdMatches } from './keyboard-return.mjs';
import { peekVisible, matchesScreen as peekMatches, rangeEndEdges } from './peek-visible.mjs';

export const PAIR_RE = /-(state|action)\d+-(before|after)$/;
const attr = (s, a) => { const m = new RegExp(` ${a}="([^"]*)"`).exec(s); return m ? m[1] : undefined; };
const unesc = (s) => (s || '').replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

/** One uiautomator-format dump in dp: nodes {id, text, l,t,r,b, clickable, checkable, checked, selected, enabled}, insets (dp) when recorded, W, H, tapped, foreign. */
export function readDump(file, density = 0) {
  const x = fs.readFileSync(file, 'utf8'), root = (/<hierarchy[^>]*>/.exec(x) || [''])[0];
  const d = +(attr(root, 'density') || density || 1), dp = (v) => v / d, nodes = [];
  for (const m of x.matchAll(/<node [^>]*>/g)) {
    const s = m[0], b = /bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/.exec(s); if (!b) continue;
    nodes.push({ id: attr(s, 'resource-id') || '', text: unesc(attr(s, 'text') || attr(s, 'content-desc') || ''), l: dp(+b[1]), t: dp(+b[2]), r: dp(+b[3]), b: dp(+b[4]),
      cls: attr(s, 'class') || '', pkg: attr(s, 'package') || '', clickable: attr(s, 'clickable') === 'true', scroll: attr(s, 'scrollable') === 'true', checkable: attr(s, 'checkable') === 'true', checked: attr(s, 'checked') === 'true', selected: attr(s, 'selected') === 'true', enabled: attr(s, 'enabled') !== 'false' });
  }
  const im = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(attr(root, 'insets') || '');
  const W = nodes.length ? Math.max(...nodes.map((n) => n.r)) : 0, H = nodes.length ? Math.max(...nodes.map((n) => n.b)) : 0;
  const imeAttr = /[(-?d+),(-?d+)][(-?d+),(-?d+)]/.exec(attr(root, 'ime') || ''), imeNode = nodes.filter((n) => /inputmethod|keyboard/i.test(n.pkg) && n.b > n.t).sort((p, q) => (q.b - q.t) - (p.b - p.t))[0];   // keyboard height (dp) for the keyboard-return add-on: root attribute ime, else the input-method window
  const ime = imeAttr ? (+imeAttr[4] - +imeAttr[2]) / d : imeNode ? imeNode.b - imeNode.t : 0;
  return { ime, nodes, W, H, insets: im ? { left: dp(+im[1]), top: dp(+im[2]), right: dp(+im[3]), bottom: dp(+im[4]) } : null, tapped: unesc(attr(root, 'capture-tapped') || ''), foreign: attr(root, 'capture-foreign') === 'true' };
}

/** Rows {rule, screen, el, value, expected, note} for every state pair, action pair and (rest) dump that carries insets. */
export function deviceRows(dir, files, cfg = {}, density = 0) {
  const kbdSeen = new Set(), matched = new Set(), peekMatched = new Set(), rows = [], peekInfo = [], stem = (f) => f.replace(/\.xml$/, ''), have = new Set(files.map(stem));
  const add = (screen, list) => list.forEach((r) => rows.push({ ...r, screen }));
  for (const f of files) {
    const n = stem(f), m = /^(.*-(state|action)\d+)-before$/.exec(n);
    if (m && have.has(m[1] + '-after')) {
      const A = readDump(path.join(dir, f), density), B = readDump(path.join(dir, m[1] + '-after.xml'), density);
      if (m[2] === 'state') add(m[1], stateStability(A, B, cfg.stateStability || {}));
      else add(m[1], actionOutcome(A, B, { text: B.tapped }, cfg.actionOutcome || {}));
      continue;
    }
    const km = /^(.*-kbd)-fd{2,}$/.exec(n);
    if (km) { if (!kbdSeen.has(km[1])) { kbdSeen.add(km[1]); const seq = files.filter((x) => x.startsWith(km[1] + '-f')).sort().map((x) => readDump(path.join(dir, x), density)); (cfg.keyboardReturn || []).forEach((e) => { if (kbdMatches(e, km[1])) add(km[1], keyboardReturn(seq, e)); }); } continue; }
    if (PAIR_RE.test(n) || /-f\d{2,}$/.test(n) || /-moving$|-hold-/.test(n)) continue;
    const D = readDump(path.join(dir, f), density);
    if (D.insets) add(n, safeArea(D, cfg.safeArea || {}));
    if (cfg.siblingActions) add(n, siblingActions(D, cfg.siblingActions));
    if (cfg.confirmRight) add(n, confirmRight(D, cfg.confirmRight));
    (cfg.workInProgress || []).forEach((e) => { if (wipMatches(e, n)) add(n, workInProgress(D, e)); });
    (cfg.waitFallback || []).forEach((e, i) => { if (matchesScreen(e, n)) { matched.add(i); add(n, waitFallback(D, e)); } });
    (cfg.peekVisible || []).forEach((e, i) => { if (peekMatches(e, n, dir)) { peekMatched.add(i); add(n, peekVisible(D, e, n, peekInfo)); } });
  }
  if (peekInfo.length) { console.log(`peek not verifiable: no neighbour page (${peekInfo.length} viewports, not a deviation):`); peekInfo.forEach((r) => console.log(`  ${r.screen} viewport ${r.viewport}`)); }
  (cfg.peekVisible || []).forEach((e, i) => { if (!peekMatched.has(i)) console.warn(`peek-visible: WARNING config entry ${i} (screen "${e.screen}") matches no dump: not checked`); });
  (cfg.waitFallback || []).forEach((e, i) => { if (!matched.has(i)) console.warn(`wait-fallback: WARNING config entry ${i} (screen "${e.screen}") matches no dump: not checked`); });
  return rows;
}

// Generic add-on hook for design-method/scripts/consistency-check.mjs (config.addons: ["device-capture"], config.deviceAddons = the block above).
export default {
  checks: { deviceCapture: [] },
  // Page-gutter rule (CR-62) asks for the expected edges of a block inside a period strip: the SAME range-end geometry as CR-89 (peek-visible.rangeEndEdges), never a second copy.
  // Uses the `ctx.cfg.deviceAddons.peekVisible` entries with endGutterDp that match the screen; `centredLeft` = where the base rule expects the block's left edge when the page is centred.
  // Returns {l, r} (page at a range end) or null (centred, or not inside a strip).
  blockEdges(ctx, s, b, centredLeft) {
    for (const e of ((ctx.cfg.deviceAddons || {}).peekVisible || [])) {
      if (e.endGutterDp === undefined || !peekMatches(e, s.name, ctx.dir || '')) continue;
      const vre = new RegExp(e.viewport);
      for (const v of s.nodes.filter((n) => vre.test((n.id || '').replace(/^.*:id\//, '')) && b.l >= n.l - 1 && b.r <= n.r + 1 && b.t >= n.t - 1 && b.b <= n.b + 1)) {
        const r = rangeEndEdges(s.nodes, e, v, centredLeft - v.l);
        if (r) return { l: r.l, r: r.r };
      }
    }
    return null;
  },
  finish(ctx, io) {
    const cfg = (ctx && ctx.cfg && ctx.cfg.deviceAddons) || {};
    for (const r of deviceRows(io.dir, io.allFiles, cfg)) io.push(r.rule, `${r.el}${r.note ? ' (' + r.note + ')' : ''}`, r.value, r.expected, r.screen, '');
  },
};
