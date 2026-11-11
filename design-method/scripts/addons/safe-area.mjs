// Add-on `safe-area` (config block `safeArea`; SKILL section 2, K17 / critique rule CR-85). User 2026-11-09 ("Posta si compenetra con barra superiore"): a page title was drawn under the status bar, over the clock.
// No visible text or control may intersect the status-bar or navigation-bar insets. Insets come from the dump: `dump.insets = { top, bottom, left?, right? }` in dp (the in-app dump writes the root WindowInsets
// as attributes, or the capture tool records `dumpsys window` / the system window nodes). The system's own nodes (package systemui, ids starting "status_bar"/"navigation_bar") are not content.
//   node safe-area.mjs --selftest
// Config: { tolDp: 1, ignoreIds: [], ignoreText: [], bottomIds: [] } (bottomIds: ids allowed to sit in the bottom inset, e.g. a dock that draws behind a transparent nav bar by design, with the user's decision).
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { tolDp: 1, ignoreIds: [], ignoreText: [], bottomIds: [] };
const idIn = (id, list) => list.some((g) => (g.endsWith('*') ? id.startsWith(g.slice(0, -1)) : id === g));
const lab = (n) => ((n.text || n.id || 'node') + '').slice(0, 28);

export function safeArea(dump, userCfg = {}) {
  const C = { ...DEFAULTS, ...userCfg }, out = [], ins = dump.insets || {}, H = dump.H, W = dump.W;
  if (!(ins.top > 0) && !(ins.bottom > 0)) return [{ rule: 'safe-area-unknown', el: 'dump', value: 0, expected: 1, note: 'no insets recorded: capture them (dump.insets), the rule cannot run' }];
  const content = dump.nodes.filter((n) => n.r > n.l && n.b > n.t && ((n.text || '').trim() || n.clickable) && !/^status_bar|^navigation_bar|systemui/i.test((n.id || '') + (n.pkg || '')) &&
    !idIn(n.id || '', C.ignoreIds) && !C.ignoreText.some((x) => new RegExp(x).test(n.text || '')) && n.vis !== 0);
  // 2026-11-10: content inside a scroll container may pass under a transparent navigation bar mid-scroll (wrong by construction); the bottom rule runs on non-scrolling chrome only.
  // The scroll-END position (last content must clear the bar) needs a dump taken scrolled to the end: not captured yet.
  const scrollers = dump.nodes.filter((x) => x.scroll && x.r > x.l && x.b > x.t);
  const inScroll = (n) => scrollers.some((x) => x !== n && n.l >= x.l - C.tolDp && n.r <= x.r + C.tolDp && n.t >= x.t - C.tolDp && n.t < x.b)   // starts inside the viewport (its bottom may be clipped by it);
  for (const n of content) {
    if (ins.top > 0 && n.t < ins.top - C.tolDp && n.b > C.tolDp) out.push({ rule: 'safe-area-top', el: lab(n), value: ins.top - n.t, expected: 0, note: `top edge ${Math.round(n.t)} dp is inside the ${Math.round(ins.top)} dp status-bar inset` });
    if (ins.bottom > 0 && n.b > H - ins.bottom + C.tolDp && n.t < H - C.tolDp && !idIn(n.id || '', C.bottomIds) && !inScroll(n)) out.push({ rule: 'safe-area-bottom', el: lab(n), value: n.b - (H - ins.bottom), expected: 0, note: `bottom edge ${Math.round(n.b)} dp is inside the ${Math.round(ins.bottom)} dp navigation-bar inset` });
    if (ins.left > 0 && n.l < ins.left - C.tolDp) out.push({ rule: 'safe-area-left', el: lab(n), value: ins.left - n.l, expected: 0, note: 'inside the left cutout/bar inset' });
    if (ins.right > 0 && W && n.r > W - ins.right + C.tolDp) out.push({ rule: 'safe-area-right', el: lab(n), value: n.r - (W - ins.right), expected: 0, note: 'inside the right cutout/bar inset' });
  }
  return out;
}

export function fixtures() {
  const base = { W: 411, H: 891, insets: { top: 40, bottom: 24 } };
  return {
    posta: { ...base, nodes: [{ id: 'page-title', text: 'Posta', l: 16, t: 12, r: 120, b: 44, vis: 1 }, { id: 'btn', text: 'Collega Gmail', l: 16, t: 200, r: 200, b: 248, clickable: true, vis: 1 }] },
    clean: { ...base, nodes: [{ id: 'page-title', text: 'Posta', l: 16, t: 96, r: 120, b: 128, vis: 1 }, { id: 'btn', text: 'Collega Gmail', l: 16, t: 200, r: 200, b: 248, clickable: true, vis: 1 }] },
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), bad = safeArea(f.posta), ok = safeArea(f.clean);
  const pass = bad.some((d) => d.rule === 'safe-area-top') && ok.length === 0;
  console.log(pass ? 'safe-area selftest PASS (title in the status-bar band flagged, clean page clean)' : `safe-area selftest FAIL (${JSON.stringify(bad)} / ${JSON.stringify(ok)})`);
  process.exit(pass ? 0 : 1);
}
