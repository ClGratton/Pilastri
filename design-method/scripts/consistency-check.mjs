#!/usr/bin/env node
// Generic consistency check (SKILL.md section 2 criteria H/C/K/M, section 6 tools). The BASE holds generic algorithms only: geometry from tagged nodes, majority vs token rules,
// one-per-screen duplicates, singleton / ratio / height / alignment mechanisms. Everything else is data or an add-on chosen by config:
//   config.adapter   dump parser in adapters/ (uiautomator = Android XML, json-nodes = any platform's node export)
//   config.addons    add-on files in addons/ (frame-dock-island, pager-peek, pager-period, hierarchy-opinions): chrome, pager and board-specific rules of one design
//   config.locale    key(s) of locales.json: stop words and unit signs for the duplicate check; config.dateLocale: months and separators for the dateRanges add-on
//   config.ruleIds   {ruleKey: "ID"}: the project's own rule ids, printed after the neutral rule text (the base never knows an id)
//   config.naming    dump file-name conventions (empty defaults: no top pages, no setup, no font-scale dumps); config.heuristics: every magic number, see RULES.md
// See RULES.md for rule -> criterion -> config keys, and consistency.config.example.json for a minimal config.
// Usage: node consistency-check.mjs <dumpDir> --scale <px per dp> [--config f] [--out REPORT.md] [--exceptions f] [--strict]
//        node consistency-check.mjs --rest a.xml --moving b.xml --scale N --config f        (pair mode)
//        node consistency-check.mjs --self-test                                              (synthetic good/bad fixtures for every rule type)
// --scale converts dump pixels to the layout unit (Android: dpi / 160; --density and --dpi are accepted aliases). Exit 1 when any deviation is not covered by an exception (or, with
// --strict, when a check was skipped), 2 on usage or config errors. The report header line is `Dumps: N (dir), scale S, density S, tolerance ...` (install-gate parses `scale`).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : d; };
const here = path.dirname(fileURLToPath(import.meta.url));
const readJson = (f, d) => (f && fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : d);

if (args.includes('--self-test')) { const { selfTest } = await import(pathToFileURL(path.join(here, 'selftest.mjs')).href); await selfTest(here, fileURLToPath(import.meta.url)); process.exit(); }

const dir = args.find((a, i) => !a.startsWith('--') && (i === 0 || !args[i - 1].startsWith('--')));
const pairArg = opt('rest') && opt('moving') ? [[opt('rest'), opt('moving')]] : [];
if (!dir && !pairArg.length) { console.error('usage: consistency-check.mjs <dumpDir> --scale <px per dp> [--config f] [--out f] [--exceptions f] [--strict] | --self-test'); process.exit(2); }
const scale = parseFloat(opt('scale', opt('density', '0'))) || (parseFloat(opt('dpi', '0')) / 160);
if (!scale) { console.error('--scale (or --density, --dpi) required'); process.exit(2); }
const cfgFile = opt('config', path.join(here, 'consistency.config.json'));
const cfg = readJson(cfgFile, null);
if (!cfg) { console.error('config missing: pass --config or create consistency.config.json next to the script (start from consistency.config.example.json)'); process.exit(2); }
const exceptions = readJson(opt('exceptions', path.join(here, 'consistency-exceptions.json')), []);

const TOL = cfg.tolerance ?? 1;
const T = cfg.tags || {};                          // role -> tag (resource-id); a role without a tag disables the rules that need it (listed under "Checks skipped")
const TK = cfg.tokens || {};                       // the project's token values (dp); every enabled check lists what it needs, see NEED
const NM = { layoutSeparator: '-', topMarker: null, setupMarker: null, fontScaleMarker: null, fontScaleLabel: 'fs', restMarker: '-rest', movingMarker: '-moving', holdMarker: '-hold-', ...(cfg.naming || {}) };
// Heuristics: every magic number of the base, documented in RULES.md. Override per project; defaults are the values the rules were calibrated with.
const H = { scrollViewportMinH: 0.4, peekMinRatio: 0.4, sideInsetFull: 0.98, rightInsetReach: 30, primaryTopTol: 4, blockMinRatio: 0.4, labelMinWidth: 16, clipCharWidth: 0.28, clipMaxLineH: 28, clipMinChars: 4, clipGuardMaxW: 0.8, gearTol: 2, ...(cfg.heuristics || {}) };
const WIDE = cfg.wideLayouts ?? [], NARROW = cfg.narrowLayouts ?? [];
const LL = { wide: 'wide', narrow: 'narrow', ...(cfg.layoutLabels || {}) }; LL.wideLong = LL.wideLong || LL.wide;
const CK = cfg.checks || {}, on = (k) => CK[k] !== undefined && CK[k] !== false;
const RID = cfg.ruleIds || {}, rid = (k) => (RID[k] ? ` (${RID[k]})` : '');
const EX = { cardInset: ['container'], cardBody: ['container', 'cardBody', 'cardBodyRows', 'cardHeader', 'pageContent'], overlap: ['pageContent'], ...(cfg.excludeRoles || {}) };
const BLOCK = cfg.blockRoles || ['container'], CLIPB = cfg.clipBoxRoles || ['pill', 'chip', 'container'], CLIPBOX = cfg.clipRoles || ['pageContent'], FLOAT = cfg.floating || [];

// ---- adapter (platform dump format) and add-ons
const adapterName = cfg.adapter || 'uiautomator';
const adapter = (await import(pathToFileURL(path.join(here, 'adapters', adapterName + '.mjs')).href).catch(() => { console.error(`config error: adapter "${adapterName}" not found in adapters/`); process.exit(2); })).default;
const EXT = cfg.dumpExtension || adapter.ext;
const addons = [];
for (const name of cfg.addons || []) addons.push((await import(pathToFileURL(path.join(here, 'addons', name + '.mjs')).href).catch(() => { console.error(`config error: add-on "${name}" not found in addons/`); process.exit(2); })).default);

// ---- checks known to the base: tokens each needs, roles of which at least one must appear in the dumps (else "nothing was checked")
const BASE = { cardInset: { needs: ['cardInset'], roles: ['cardTitle', 'container'] }, cardBody: { needs: ['cardInset', 'headerRowH'], roles: ['cardBody', 'cardBodyRows'] }, clip: { roles: ['pill', 'chip', 'container', 'actionRow'] }, overlap: {},
  pageHeader: { roles: ['pageTitle', 'pageSubtitle'] }, labels: { roles: ['sectionLabel'] }, cardEdges: { roles: ['container'] }, chipHeight: { roles: ['chip'] }, firstContent: { roles: ['pageContent'] },
  touch: { needs: ['minTouch'] }, copy: { needs: ['maxCopy'] }, gutterGap: { needs: ['gutter', 'gap'], roles: ['container'] }, duplicate: {}, dateRanges: {}, currencySplit: {}, coMotion: {} };
const KNOWN = { ...BASE };
for (const a of addons) for (const [k, needs] of Object.entries(a.checks || {})) KNOWN[k] = { needs, roles: (a.roles || {})[k] || [] };
for (const k of Object.keys(CK)) {
  if (k.startsWith('_')) continue;
  if (!KNOWN[k]) { console.error(`config error: unknown check "${k}" (a base check, or a check of an add-on that is not listed in config.addons)`); process.exit(2); }
  if (on(k)) for (const t of KNOWN[k].needs || []) if (TK[t] === undefined) { console.error(`config error: check "${k}" needs tokens.${t}`); process.exit(2); }
}
const GENERIC = ['singleton', 'heightRatio', 'heightEquals', 'forbidInside', 'alignRules'];   // generic mechanisms: arrays of rule objects in the config, each names its role(s)

const ids = (...ks) => ks.map((k) => T[k]).filter(Boolean);
const is = (n, ...ks) => ids(...ks).includes(n.id);
const hasPrefix = (n, k) => !!T[k] && n.id.startsWith(T[k]);
const inRoles = (n, list) => list.some((k) => is(n, k) || (k.endsWith('Prefix') && hasPrefix(n, k)));
const dp = (px) => px / scale;
const r1 = (v) => Math.round(v * 10) / 10;
const match = (n, tag) => (tag.endsWith('*') ? n.id.startsWith(tag.slice(0, -1)) : n.id === tag);

// ---- locales (data): date months/separators for the dateRanges add-on; stop words and unit signs for the duplicate check (config.locale, one key or a list)
const LOCALES = readJson(path.join(here, 'locales.json'), {});
const DATE_ON = on('dateRanges');   // add-on: a date locale is read only when it is enabled
const loc = !DATE_ON ? null : typeof cfg.dateLocale === 'string' ? LOCALES[cfg.dateLocale] : cfg.dateLocale;
if (on('duplicate') && DATE_ON && !loc) console.error('warning: checks.dateRanges is on but config.dateLocale is unset or unknown: the date part of the duplicate check is off');
const MON = (loc && loc.months) || {};
const MRE = Object.keys(MON).sort((a, b) => b.length - a.length).join('|');
const FULL = (loc && loc.full ? loc.full : Object.keys(MON).filter((k) => k.length > 4)).join('|');
const SEP = '(?:' + ((loc && loc.separators) || ['–', '—', '-']).join('|') + ')';
const MD = loc && loc.order === 'md';
const BARE = loc ? new RegExp(MD ? String.raw`^[‹›\s]*(?:(?:${MRE})\s*)?\d{1,2}(?:\s*${SEP}\s*(?:(?:${MRE})\s*)?\d{1,2})?[‹›\s]*$` : String.raw`^[‹›\s]*\d{1,2}(\s*(${MRE}))?(\s*${SEP}\s*\d{1,2}\s*(${MRE}))?[‹›\s]*$`) : null;
const LSETS = [].concat(cfg.locale || []).map((k) => LOCALES[k] || {});
const UNIT_STOP = new Set(LSETS.flatMap((l) => l.stopWords || []).map((w) => w.toLowerCase()));
const UNIT_EXTRA = LSETS.flatMap((l) => l.unitSigns || []).map((u) => u.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
// a value token = digits plus their attached unit: a currency sign, %, a configured unit sign, or a word of 2+ letters; no language involved
const TOKEN = new RegExp(String.raw`(?<![\d.,])[−–+$£-]*(\d[\d.,:/]*)(?:\s*(\p{Sc}|%|${[...UNIT_EXTRA, String.raw`\p{L}{2,}`].join('|')}))?`, 'giu');
const globRe = (g) => new RegExp('^' + g.split('*').map((x) => x.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$');
const dupHits = new Map();   // filter -> { n: elements silenced, screens }; printed in the report so a filter can never hide a row silently
const dupFile = opt('duplicate-filters', cfg.duplicateFilters ? path.resolve(path.dirname(cfgFile), cfg.duplicateFilters) : path.join(here, 'duplicate-filters.json'));
// Scope by config (never by filters): config.duplicateScreens = ["glob", ...] limits the duplicate check to those screens (e.g. single-screen layouts; the same text in
// two panes of a wide layout is not a repetition). config.duplicateScope = path of a JSON {screens:[...]} (one source shared with the project's tools). Absent = every screen.
const scopeFile = opt('duplicate-scope', cfg.duplicateScope ? path.resolve(path.dirname(cfgFile), cfg.duplicateScope) : null);
const dupScreens = cfg.duplicateScreens || (scopeFile && fs.existsSync(scopeFile) ? JSON.parse(fs.readFileSync(scopeFile, 'utf8')).screens : null);
const dupScoped = (name) => !dupScreens || dupScreens.some((g) => globRe(g).test(name));
let dupSkipped = 0;
const dupFilters = fs.existsSync(dupFile) ? JSON.parse(fs.readFileSync(dupFile, 'utf8')).filter((f) => f && f.screen) : [];

// Unicode-space normalisation: U+00A0, U+202F, U+2007 are read as U+0020 by every text match (duplicate filters and comparison, value tokens, exception matching); `raw` keeps the
// text as exported, for the currencySplit rule only. Not a loosening: a figure joined to its symbol by a no-break space compares and filters like one joined by a plain space.
const uniSpace = (t) => t.replace(/[   ]/g, ' ');
const parse = (file) => adapter.parse(file, dp).filter((n) => n.r - n.l > 0.5 && n.b - n.t > 0.5).map((n) => ({ ...n, raw: n.text, text: uniSpace(n.text) }));
const allFiles = dir ? fs.readdirSync(dir).filter((f) => f.endsWith(EXT)).sort() : [];
const stem = (f) => f.slice(0, -EXT.length);
const FRAME = /-f[0-9]{2,}$/;   // animation frames (<screen>-f00, <screen>-sw-f03): read by the visual-integrity add-on only
const files = allFiles.filter((f) => !/-(state|action)\d+-(before|after)$/.test(stem(f)) && !stem(f).endsWith(NM.movingMarker) && !stem(f).includes(NM.holdMarker) && !FRAME.test(stem(f)));   // held-finger dumps (x-hold-rest/short/long) are mid-drag, read by an add-on finish hook
if (!files.length && !pairArg.length && !allFiles.some((f) => stem(f).includes(NM.holdMarker))) { console.error(`no ${EXT} dumps in ${dir}`); process.exit(2); }

// A pane keeps the previous page composed underneath the new one. Dumps list nodes in draw order, so every node preceding a LATER page-content of another
// title, inside that page's pane and bounds, belongs to the covered layer and is not visible.
function visibleNodes(all) {
  if (!T.pageContent) return all;
  const pcs = all.map((n, i) => [n, i]).filter(([n]) => n.id === T.pageContent);
  if (pcs.length < 2) return all;
  const covered = new Set();
  for (const [pc, i] of pcs) for (const [later, j] of pcs) {
    if (j <= i || later.text === pc.text || !later.text.trim()) continue;
    const cxL = (later.l + later.r) / 2;
    if (Math.abs(later.l - pc.l) >= 2 || !(cxL > pc.l && cxL < pc.r + 1)) continue;
    for (let k = i; k < j; k++) { const n = all[k]; const cx = (n.l + n.r) / 2; if (!hasPrefix(n, 'panePrefix') && cx >= later.l && cx <= later.r + 1) covered.add(k); }
  }
  return all.filter((_, k) => !covered.has(k));
}
const seenRoles = new Set();   // roles whose tag appears in at least one dump: an enabled check whose roles never appear checked nothing
const markSeen = (nodes) => { for (const [k, tag] of Object.entries(T)) if (!seenRoles.has(k) && nodes.some((n) => (tag.endsWith('*') || k.endsWith('Prefix') ? n.id.startsWith(tag.replace(/\*$/, '')) : n.id === tag))) seenRoles.add(k); };
const screens = files.map((f) => {
  const name = stem(f);
  const nodes = visibleNodes(parse(path.join(dir, f))); markSeen(nodes);
  const W = Math.max(...nodes.map((n) => n.r), 0), Hh = Math.max(...nodes.map((n) => n.b), 0);
  // The scroll viewport (a vertical scrollable spanning a pane) ends above the screen bottom (system bar, floating overlay): cards cut there are clipped, not short.
  const sc = nodes.filter((n) => n.scroll && n.b - n.t >= Hh * H.scrollViewportMinH);
  const V = Math.min(Hh, ...sc.map((n) => n.b));
  const VT = Math.max(0, Math.min(...sc.map((n) => n.t), Hh));
  const layout = name.split(NM.layoutSeparator)[0], top = !!NM.topMarker && name.includes(NM.topMarker);
  return { name, layout, wide: WIDE.includes(layout), narrow: NARROW.includes(layout), top, base: top ? name.split(NM.topMarker)[1] : '', fs: NM.fontScaleMarker && name.includes(NM.fontScaleMarker) ? NM.fontScaleLabel : '', scale: NM.fontScaleMarker && name.includes(NM.fontScaleMarker) ? (NM.fontScaleValue ?? 2) : 1, nodes, W, H: Hh, V, VT };
});

const inside = (a, c) => a.l >= c.l - 0.5 && a.t >= c.t - 0.5 && a.r <= c.r + 0.5 && a.b <= c.b + 0.5;
const area = (n) => (n.r - n.l) * (n.b - n.t);
const label = (n) => (n.text || '').slice(0, 28);
// Per-line heights above a font scale (config heuristics.wrapAboveScale, default 1.3; roles config.wrapRoles, default pageSubtitle / pageTrailing / chip): a text role the spec lets wrap has no
// comparable box height, so its height is divided by its line count = round(height / smallest single-line height of that role at that layout and font scale). Roles NOT listed (page title,
// period title) stay one line (K2) and keep the box-height comparison. Applies only to dumps whose name carries naming.fontScaleMarker and whose naming.fontScaleValue (default 2) exceeds the threshold.
const WRAPR = (cfg.wrapRoles ?? ['pageSubtitle', 'pageTrailing', 'chip']).filter((k) => T[k]), WRAPA = cfg.heuristics?.wrapAboveScale ?? 1.3, oneLine = new Map();
for (const s of screens) if (s.scale > WRAPA) for (const n of s.nodes) if (n.vis && WRAPR.some((k) => is(n, k))) { const k = [s.layout, s.fs, n.id].join('|'); oneLine.set(k, Math.min(oneLine.get(k) ?? Infinity, n.b - n.t)); }
const perLine = (s, n) => { const h = n.b - n.t, one = oneLine.get([s.layout, s.fs, n.id].join('|')); return s.scale > WRAPA && one && WRAPR.some((k) => is(n, k)) ? h / Math.max(1, Math.round(h / one)) : h; };
const M = [], direct = [];   // measurements (majority), deviations against a token / structural rule
const tok = (s, rule, n, value, expected, note = '') => { if (Math.abs(value - expected) > TOL) direct.push({ rule, layout: s.layout, screen: s.name, el: label(n) || n.id, value: r1(value), expected: r1(expected), group: 'token', note }); };
const add = (s, rule, n, value, group = '') => M.push({ rule, layout: s.layout, screen: s.name, el: label(n), value: r1(value), group, fs: s.fs });
const DS = (s, rule, el, value, expected, note = '') => direct.push({ rule, layout: s.layout, screen: s.name, el, value: r1(value), expected: r1(expected), group: 'direct', note });
const D = (s, rule, n, value, expected, note = '') => DS(s, rule, (n && (label(n) || n.id)) || '-', value, expected, note);
// Context handed to add-ons.
const ctx = { dir, cfg, T, TK, H, TOL, LL, is, hasPrefix, inRoles, inside, area, label, r1, on, rid, add, tok, D, DS, perLine };
// Inset adjusters: a block inside a viewport of role R is narrowed per side by dp(s) (config insetAdjust [{role, perSideDp: n | {wide, narrow}}], or an add-on's own).
const ADJ = [...(cfg.insetAdjust || []).map((a) => ({ role: a.role, dp: (s) => (typeof a.perSideDp === 'number' ? a.perSideDp : s.wide ? a.perSideDp.wide : a.perSideDp.narrow) })), ...addons.flatMap((a) => (a.adjusters ? a.adjusters(ctx) : []))];
const adjustOf = (s, by, n) => Math.max(0, ...ADJ.map((a) => (by(T[a.role]).some((v) => inside(n, v)) ? a.dp(s) : 0)));

for (const s of screens) {
  const by = (id) => (id ? s.nodes.filter((n) => match(n, id)) : []);
  const allCards = by(T.container), widest = Math.max(0, ...allCards.map((c) => c.r - c.l));
  const cards = allCards.filter((c) => c.r - c.l >= widest * H.peekMinRatio);   // clipped neighbours of a swipe strip are not measured
  const enclosing = (n) => cards.filter((c) => c !== n && inside(n, c)).sort((a, b) => area(a) - area(b))[0];
  const pane = (n) => (s.wide ? ((n.l + n.r) / 2 < s.W / 2 ? 'L' : 'R') : 'P');
  const floating = s.nodes.filter((n) => inRoles(n, FLOAT));
  // cut by the viewport, or by a floating overlay drawn over its bottom edge (a card passing UNDER the overlay loses its lower rows from the dump)
  const clipped = (c) => c.b >= s.V - 0.5 || floating.some((x) => c.b >= x.t - 1 && c.t < x.t && c.l < x.r && c.r > x.l);
  const scrollTop = Math.max(0, ...s.nodes.filter((n) => n.scroll && n.b - n.t >= s.H * H.scrollViewportMinH).map((n) => n.t));
  // the card under a label = the nearest one that overlaps it horizontally (a full-width label is in neither pane: two half-width cards share its row, the left one names it)
  const below = (n) => cards.filter((c) => c.t >= n.b - 1 && Math.min(c.r, n.r) - Math.max(c.l, n.l) > 1 && !enclosing(c)).sort((a, b) => a.t - b.t || a.l - b.l)[0];
  const h = { by, pane, cards, allCards, enclosing, below, scrollTop, clipped };

  if (on('cardInset')) {
    // card-title: inset from its card (top, left), height; header row: title centre vs card top, trailing centre vs title centre
    const titles = by(T.cardTitle), trails = by(T.cardTrailing);
    for (const t of titles) {
      const c = enclosing(t); if (!c || clipped(c) || (scrollTop > 0 && c.t <= scrollTop + 0.5)) continue;
      add(s, `${T.cardTitle} top inset`, t, t.t - c.t);
      add(s, `${T.cardTitle} left inset`, t, t.l - c.l);
      add(s, `${T.cardTitle} height`, t, t.b - t.t);
      add(s, 'card-header: title centre below card top', t, (t.t + t.b) / 2 - c.t);
      const tr = trails.find((x) => enclosing(x) === c && Math.abs((x.t + x.b) / 2 - (t.t + t.b) / 2) < 40);
      if (tr) add(s, 'card-header: trailing centre minus title centre', tr, ((tr.t + tr.b) / 2) - ((t.t + t.b) / 2), 'zero');
    }
    // card inner padding: last visible content (texted nodes, charts, icon discs; not the card's own boxes) vs the card, against the token
    for (const c of cards) {
      const ch = c.b - c.t, rowFull = (n) => (n.r - n.l) >= (c.r - c.l) * 0.9;
      const raw = s.nodes.filter((n) => n !== c && !inRoles(n, EX.cardInset) && inside(n, c) && !floating.some((x) => inside(n, x)) &&
        (is(n, 'chart', 'iconDisc') || n.text.trim() !== '') && !(n.vis === 0 && !is(n, 'chart') && rowFull(n)) && (is(n, 'chart', 'iconDisc') || (n.b - n.t) < ch * 0.9) &&
        !(n.clickable && rowFull(n)) &&   // a full-width clickable row is a container, not content
        !s.nodes.some((x) => x.clickable && x !== n && rowFull(x) && (x.b - x.t) < ch * 0.9 && inside(x, c) && Math.abs(x.l - n.l) < 1 && Math.abs(x.r - n.r) < 1 && Math.abs(x.t - n.t) < 1 && Math.abs(x.b - n.b) < 1));   // merged label of a clickable row
      // Measure the OUTERMOST content child: text inside a chip is represented by the chip box; text inside a full-width clickable row only counts for left/right.
      const boxes = s.nodes.filter((n) => is(n, 'chip', 'pill') && n !== c && inside(n, c));
      const rowTargets = s.nodes.filter((n) => n.clickable && !is(n, 'pill', 'chip') && rowFull(n) && (n.b - n.t) < ch * 0.9 && inside(n, c));   // a full-width button is content for the bottom inset, not a list row
      const outer = (k) => boxes.filter((x) => inside(k, x)).sort((a, b) => area(b) - area(a))[0] || k;
      const kids = raw.map(outer);
      if (!kids.length) continue;
      const bottomKids = raw.filter((k) => !rowTargets.some((r) => inside(k, r))).map(outer);
      const narrow = kids.filter((k) => (k.r - k.l) < (c.r - c.l) * H.sideInsetFull);   // a full-width node (rule, bar) says nothing about the side inset
      const side = narrow.length ? narrow : kids;
      // Right inset: only content that spans the card (chart, trailing text, or ending within rightInsetReach dp of the card edge); short left-aligned text says nothing about it.
      const wide = side.filter((k) => is(k, 'chart', 'cardTrailing') || (c.r - k.r) < H.rightInsetReach);
      const sideKids = side.filter((k) => !(clipped(c) && k.b >= s.V - 0.5));   // nodes cut by the viewport do not measure the left inset
      const bot = bottomKids.length ? Math.max(...bottomKids.map((k) => k.b)) : null, lft = sideKids.length ? Math.min(...sideKids.map((k) => k.l)) : null, rgt = Math.max(...wide.map((k) => k.r));
      if (!clipped(c) && bot !== null) tok(s, 'card-inset bottom (last content to card bottom)', bottomKids.find((k) => k.b === bot), c.b - bot, TK.cardInset);
      if (lft !== null) tok(s, 'card-inset left (content to card)', sideKids.find((k) => k.l === lft), lft - c.l, TK.cardInset);
      if (wide.length) tok(s, 'card-inset right (content to card)', wide.find((k) => k.r === rgt), c.r - rgt, TK.cardInset);
    }
  }

  if (on('cardBody')) {
    // card-body cardInset from its card on all four sides; first / last visible content touches body top / bottom / start, end when it spans. A titled card's first
    // content is the fixed header row, not the title's line box; a dump without the header tag gets one synthesised from the card-title (centre +- headerRowH/2).
    // ROW cards (cardBodyRows) are measured by header and end only. A card cut at the scroll top or the bottom (viewport, overlay) is not measurable on that side.
    const hasHeaderTag = by(T.cardHeader).length > 0, HH = TK.headerRowH / 2, I = TK.cardInset, R = rid('cardBody');
    for (const b of [...by(T.cardBody), ...by(T.cardBodyRows)]) {
      const c = allCards.filter((x) => x !== b && inside(b, x)).sort((p, q) => area(p) - area(q))[0]; if (!c) continue;
      if (floating.some((x) => b.t >= x.t - 0.5 && b.l < x.r && b.r > x.l)) continue;   // below the overlay: only a clipped remainder is visible
      const sliver = b.b - b.t < 12, rows = b.id === T.cardBodyRows, cutB = sliver || clipped(c), cutT = sliver || (c.t <= scrollTop + 0.5 && scrollTop > 0);   // shorter than any text line: a cut card
      tok(s, `card-body: inset left is ${I} dp${R}`, b, b.l - c.l, I); tok(s, `card-body: inset right is ${I} dp${R}`, b, c.r - b.r, I);
      if (!cutT) tok(s, `card-body: inset top is ${I} dp${R}`, b, b.t - c.t, I);
      if (!cutB) tok(s, `card-body: inset bottom is ${I} dp${R}`, b, c.b - b.b, I);
      let headers = by(T.cardHeader).filter((n) => inside(n, b));
      if (!hasHeaderTag) headers = by(T.cardTitle).filter((n) => inside(n, b)).map((t) => ({ id: T.cardHeader, text: t.text, vis: 1, l: b.l, r: b.r, t: (t.t + t.b) / 2 - HH, b: (t.t + t.b) / 2 + HH, synthesised: true }));
      const bxs = s.nodes.filter((n) => is(n, 'chip', 'pill') && inside(n, b));
      const inHeader = (n) => headers.some((hd) => n.l >= hd.l - 0.5 && n.r <= hd.r + 0.5 && n.t >= hd.t - 0.5 && n.b <= hd.b + 0.5);
      const raw = s.nodes.filter((n) => n !== b && !inRoles(n, EX.cardBody) && inside(n, b) && !floating.some((x) => inside(n, x)) && !inHeader(n) &&
        (is(n, 'chart', 'iconDisc', 'pill', 'chip') || n.text.trim() !== '') && (is(n, 'chart') || (n.b - n.t) < (b.b - b.t) * 0.9));
      const body = [...new Set(raw.map((k) => bxs.filter((x) => inside(k, x)).sort((p, q) => area(q) - area(p))[0] || k))];
      const kids = [...headers, ...(rows ? [] : body)];
      if (!kids.length) continue;
      const touch = (what, v, k) => tok(s, `card-body: first / last content touches the body ${what}${R}`, k, v, 0, k.synthesised ? 'synthesised header' : '');
      const top = kids.reduce((p, q) => (q.t < p.t ? q : p)); if (!cutT) touch('top', top.t - b.t, top);
      if (!cutB && !rows) { const bt = kids.reduce((p, q) => (q.b > p.b ? q : p)); touch('bottom', b.b - bt.b, bt); }
      const sk = kids.filter((k) => !(cutB && k.b >= s.V - 0.5)); if (sk.length && !rows) { const st = sk.reduce((p, q) => (q.l < p.l ? q : p)); touch('start', st.l - b.l, st); }
      const sp = [...headers, ...body.filter((k) => is(k, 'chart', 'cardTrailing') || (b.r - k.r) < H.rightInsetReach)]; if (sp.length) { const en = sp.reduce((p, q) => (q.r > p.r ? q : p)); touch('end', b.r - en.r, en); }
    }
  }

  if (on('clip')) {
    // Dump counterpart of text overflow: a dump has no text layout, so no overflow flag. (a) geometric: a texted node whose centre is inside a pill, chip or card but whose box leaves it;
    // (b) HEURISTIC: a single-line texted node in a pill / chip / clickable narrower than clipCharWidth line-heights per character is truncated or squeezed.
    const guards = s.nodes.filter((n) => inRoles(n, CLIPB.filter((k) => k !== 'container')) || n.clickable);
    const boxesOf = s.nodes.filter((x) => inRoles(x, CLIPB));
    for (const n of s.nodes) {
      if (!n.vis || inRoles(n, CLIPB)) continue;
      for (const bx of boxesOf) {
        const cx = (n.l + n.r) / 2, cy = (n.t + n.b) / 2;
        if (cx > bx.l && cx < bx.r && cy > bx.t && cy < bx.b && (n.l < bx.l - TOL || n.r > bx.r + TOL)) DS(s, `clip: text box wider than its ${bx.id}${rid('clip')}`, label(n), n.r - n.l, bx.r - bx.l);
      }
      const hn = n.b - n.t, w = n.r - n.l;
      if (hn <= H.clipMaxLineH && n.text.length >= H.clipMinChars && w < n.text.length * H.clipCharWidth * hn && guards.some((g) => g !== n && inside(n, g) && (g.r - g.l) < s.W * H.clipGuardMaxW)) DS(s, `clip: text narrower than its label needs (heuristic${RID.clip ? ', ' + RID.clip : ''})`, label(n), w, n.text.length * H.clipCharWidth * hn, 'heuristic');
    }
  }
  if (on('overlap')) {
    // two visible texts must not overlap (bounds intersect by more than 1 dp in both axes, clamped to the window; one text containing the other is one label seen twice).
    const tx = s.nodes.filter((n) => n.vis && n.text.trim() && !inRoles(n, EX.overlap));
    for (let i = 0; i < tx.length; i++) for (let j = i + 1; j < tx.length; j++) {
      const a = tx[i], b = tx[j]; if (a.text.includes(b.text) || b.text.includes(a.text)) continue;
      const ix = Math.min(a.r, b.r, s.W) - Math.max(a.l, b.l, 0), iy = Math.min(a.b, b.b) - Math.max(a.t, b.t);
      if (ix > TOL && iy > TOL) DS(s, `clip: texts overlap${rid('overlap')}`, label(a) + ' / ' + label(b), ix, 0);
    }
  }

  if (on('pageHeader')) {
    // The setup title is its own role, measured among the setup screens only
    for (const n of by(T.setupTitle)) { add(s, `page-header ${T.setupTitle} y`, n, n.t, 'setup'); add(s, `page-header ${T.setupTitle} height`, n, n.b - n.t, 'setup'); }
    // A page header whose top sits ON the top of its page-content is cut by the window (the page was scrolled before the dump): its box is not the line box. One CAPTURE row, no measurements.
    const hdrCut = (n) => by(T.pageContent).some((p) => (n.l + n.r) / 2 >= p.l && (n.l + n.r) / 2 <= p.r && n.t <= p.t + 0.5);
    const cutHdr = [T.pageTitle, T.pageSubtitle].flatMap(by).filter(hdrCut);
    if (cutHdr.length) DS(s, 'capture: page header cut by the window top (page not at scroll 0)', label(cutHdr[0]), cutHdr[0].b - cutHdr[0].t, 0, 'capture');
    for (const id of [T.pageTitle, T.pageSubtitle].filter(Boolean)) for (const n of by(id).filter(() => !cutHdr.length)) {
      const g = s.top ? 'top' + pane(n) : NM.setupMarker && s.name.includes(NM.setupMarker) ? 'setup' : 'any';   // on two panes each pane carries its own header; a setup flow has its own group
      add(s, `page-header ${id} y`, n, n.t, g);
      add(s, `page-header ${id} height`, n, perLine(s, n), 'any');   // majority: line boxes in dumps are not the token line heights
      if (s.top) add(s, `page-header ${id} x (top-level pages)`, n, n.l, g);
    }
  }
  if (on('labels')) for (const n of by(T.sectionLabel)) { const c = below(n); if (c) add(s, 'label section left minus card left', n, n.l - c.l, 'zero'); }
  if (on('cardEdges')) for (const c of cards) if (!enclosing(c)) add(s, 'card-edge right (per pane)', c, c.r + adjustOf(s, by, c), s.name + pane(c));   // a card in an adjusted block is narrowed per side; add it back so it compares with the plain cards of its pane
  if (on('chipHeight')) {
    const pcTop = Math.max(0, ...by(T.pageContent).map((p) => p.t), s.VT);
    const floatBox = s.nodes.filter((n) => inRoles(n, FLOAT));   // a chip whose top sits on the bottom edge of a floating overlay is the part left below it (bounds clipped): cut, not measurable
    for (const n of by(T.chip)) if (n.b < s.V - 0.5 && n.t > pcTop + 0.5 && !floatBox.some((x) => Math.abs(n.t - x.b) <= 1 && n.l < x.r && n.r > x.l)) add(s, 'chip height', n, n.b - n.t);   // cut by the viewport bottom or scrolled under the window top: not measurable
  }
  checkDirect(s, by, pane);
  for (const a of addons) if (a.screen) a.screen(ctx, s, h);
  if (on('duplicate')) checkDup(s);
  if (on('currencySplit')) checkCurrencySplit(s);

  // Public custom majority measurements (config.measure[]; `rules[]` is the old name): inset, size, position, alignBelow on any tag.
  const below2 = (n) => cards.filter((c) => c.t >= n.b - 1 && pane(c) === pane(n) && !enclosing(c)).sort((a, b) => a.t - b.t)[0];
  for (const R of cfg.measure || cfg.rules || []) {
    const name = R.name || `${R.type} ${R.tag || ''} ${R.side || R.dim || R.axis || R.edge || ''}`.trim();
    if (R.type === 'inset') for (const n of by(R.tag)) { const c = enclosing(n); if (!c) continue; add(s, name, n, R.side === 'top' ? n.t - c.t : R.side === 'left' ? n.l - c.l : R.side === 'right' ? c.r - n.r : c.b - n.b); }
    if (R.type === 'size') for (const n of by(R.tag)) add(s, name, n, R.dim === 'width' ? n.r - n.l : n.b - n.t);
    if (R.type === 'position') for (const n of by(R.tag)) { if (R.scope === 'top' && !s.top) continue; add(s, name, n, R.axis === 'x' ? n.l : n.t, R.scope === 'top' ? 'top' : 'any'); }
    if (R.type === 'alignBelow') for (const n of by(R.tag)) { const c = below2(n); if (!c) continue; add(s, name, n, R.edge === 'right' ? n.r - c.r : n.l - c.l, 'zero'); }
  }
  genericRules(s, by);
}

// Direct rules (no majority): first content, touch targets, copy length, gutter and gap.
function checkDirect(s, by, pane) {
  const W = s.W, half = W / 2, floating = s.nodes.filter((n) => inRoles(n, FLOAT));
  if (s.top && on('firstContent')) {
    const pc = by(T.pageContent);
    if (!pc.length) D(s, `page-header: tag ${T.pageContent} missing (first content top)${rid('firstContent')}`, null, -1, 0, 'request tag');
    else add(s, 'page-header content top' + rid('firstContent'), pc[0], pc[0].t, 'top');
  }
  if (on('touch')) {
    // merged clickable nodes (a dump is already merged), deduped by bounds + label. minTouch is the platform minimum (Android/Material 48 dp, iOS 44 pt, WCAG 2.2 web 24 px).
    const seen = new Set(), MT = TK.minTouch;
    for (const n of s.nodes) if (n.clickable) {
      const k = [r1(n.l), r1(n.t), r1(n.r), r1(n.b), n.text].join('|'); if (seen.has(k)) continue; seen.add(k);
      const w = n.r - n.l, hn = n.b - n.t;
      if (n.b >= s.V - 0.5 && hn < MT) continue;   // cut by the scroll viewport bottom: the visible part says nothing about the target
      if (n.t <= s.VT + 0.5 && hn < MT) continue;   // cut by the window / scroll viewport top (scrolled under it)
      // trimmed by a floating overlay drawn over it: the visible part says nothing about the target either
      if ((w < MT - TOL || hn < MT - TOL) && floating.some((x) => n.l < x.r + 0.5 && n.r > x.l - 0.5 && n.t < x.b + 0.5 && n.b > x.t - 0.5 && (Math.abs(n.b - x.t) < 1 || Math.abs(n.t - x.b) < 1 || Math.abs(n.r - x.l) < 1 || Math.abs(n.l - x.r) < 1))) continue;
      if (w >= 1 && hn >= 1 && (w < MT - TOL || hn < MT - TOL)) D(s, `touch: clickable at least ${MT} x ${MT} dp${rid('touch')}`, n, Math.min(w, hn), MT);
    }
  }
  // Visible copy only: a node without `text` carries an accessibility description (chart summaries), which is read aloud, not drawn.
  if (on('copy')) for (const n of s.nodes) if (n.vis && n.text.length > TK.maxCopy) D(s, `copy: text longer than ${TK.maxCopy} chars on a working screen${rid('copy')}`, n, n.text.length, TK.maxCopy);
  if (on('gutterGap')) {
    const isBlock = (n) => inRoles(n, BLOCK), R = rid('gutterGap');
    const all = s.nodes.filter(isBlock), widest = Math.max(0, ...all.map((n) => n.r - n.l));
    const top = all.filter((n) => n.r - n.l >= widest * H.blockMinRatio).filter((c) => !all.some((o) => o !== c && inside(c, o) && area(o) > area(c)));
    for (const b of top) {
      const cx = (b.l + b.r) / 2;
      if (s.wide && !s.top && cx >= half) continue;
      const nar = adjustOf(s, by, b);   // only that exact edge is accepted inside an adjusted viewport
      const le = (s.wide && s.top && cx >= half ? half + TK.gutter : TK.gutter) + nar;
      // Range ends (user 2026-11-10 "va bene modifica schermata"): an add-on may supply the page's edges at a range end (blockEdges, the geometry of rangeEndEdges, the same source as the range-end rule).
      // A page still centred at a range end is that rule's finding, not reported twice here; any other offset is a deviation.
      const re = addons.map((a) => (a.blockEdges ? a.blockEdges(ctx, s, b, le) : null)).find(Boolean);
      const lOk = (x) => Math.abs(b.l - x) <= TOL, rOk = (x) => Math.abs(b.r - x) <= TOL;
      if (!(lOk(le) || (re && lOk(re.l)))) D(s, `gutter: block left edge on the ${TK.gutter} dp gutter${R}`, b, b.l, re ? re.l : le);
      if (s.narrow && !(Math.abs((W - b.r) - (TK.gutter + nar)) <= TOL || (re && rOk(re.r)))) D(s, `gutter: block right edge on the ${TK.gutter} dp gutter${R}`, b, W - b.r, re ? W - re.r : TK.gutter + nar);
    }
    for (const p of [0, 1]) {
      const col = top.filter((b) => (s.wide && (b.l + b.r) / 2 >= half ? 1 : 0) === p).sort((a, b) => a.t - b.t);
      for (let i = 1; i < col.length; i++) {
        const a = col[i - 1], b = col[i];
        if (Math.min(a.r, b.r) - Math.max(a.l, b.l) < Math.min(a.r - a.l, b.r - b.l) * 0.5) continue;
        if (s.nodes.some((n) => !isBlock(n) && n.text.trim() && n.t >= a.b - 0.5 && n.b <= b.t + 0.5 && n.r - n.l > 1 && Math.min(n.r, a.r) - Math.max(n.l, a.l) > 1)) continue;   // a label between the blocks
        const gap = b.t - a.b;
        if (Math.abs(gap - TK.gap) > TOL) D(s, `gap: ${TK.gap} dp between stacked blocks${R}`, b, gap, TK.gap);
      }
    }
  }
}

// Generic mechanisms (criteria H1, H2, C1, C5, K6 in their simplest form): arrays of rule objects in the config, each naming its roles. Optional `id` prints the project's rule id.
//   singleton    {role, scope: "screen"|"pane", min?: 0, max?: 1, containedIn?: role}   at most max / at least min nodes of role per scope (per container of role `containedIn` when given)
//   heightRatio  {role, over, min, scope?}   height of the first `role` node (same pane on wide layouts) at least min x the height of every `over` node
//   heightEquals {role, dp}                  every node of role is dp high (+-tolerance)
//   forbidInside {role, child: "clickable"}  no clickable node strictly inside a node of role
//   alignRules   {role, layouts?: "narrow"|"wide"|"all", exactly?: n, centreX?: true, fromRightEdge?: dp}   tagged element centred on the screen or at a distance from the right edge
// Each rule applies to every dump unless `topOnly: true` (top-level pages only).
function genericRules(s, by) {
  const half = s.W / 2, rl = (e) => (e.id ? ` (${e.id})` : ''), hh = (n) => n.b - n.t, hasLayout = (e) => !e.layouts || e.layouts === 'all' || (e.layouts === 'narrow' ? s.narrow : s.wide);
  const skip = (e) => (e.topOnly && !s.top) || !hasLayout(e);
  for (const e of cfg.singleton || []) {
    if (skip(e) || !T[e.role]) continue;
    const max = e.max ?? 1, min = e.min ?? 0, nodes = by(T[e.role]);
    const scopes = e.containedIn ? by(T[e.containedIn]).map((c) => nodes.filter((n) => inside(n, c))) : e.scope === 'pane' && s.wide ? [nodes.filter((n) => (n.l + n.r) / 2 < half), nodes.filter((n) => (n.l + n.r) / 2 >= half)] : [nodes];
    scopes.forEach((g, i) => { if (g.length > max || g.length < min) D(s, `singleton: ${min === max ? 'exactly' : min ? `${min} to` : 'at most'} ${max} ${T[e.role]} per ${e.containedIn ? T[e.containedIn] : e.scope || 'screen'}${rl(e)}${scopes.length > 1 ? (i === 0 ? ' (first)' : ' (second)') : ''}`, g[0] || null, g.length, g.length < min ? min : max); });
  }
  for (const e of cfg.heightRatio || []) {
    if (skip(e) || !T[e.role] || !T[e.over]) continue;
    for (const n of by(T[e.over])) { const f = by(T[e.role]).find((p) => !s.wide || ((p.l + p.r) / 2 < half) === ((n.l + n.r) / 2 < half)); if (f && !(Math.abs(f.l - n.l) < 1 && Math.abs(f.t - n.t) < 1 && Math.abs(f.b - n.b) < 1) && hh(f) < e.min * hh(n)) D(s, `ratio: ${T[e.role]} height >= ${e.min} x ${T[e.over]}${rl(e)}`, n, hh(f) / hh(n), e.min, 'line boxes'); }
  }
  for (const e of cfg.heightEquals || []) {
    if (skip(e) || !T[e.role]) continue;
    for (const n of by(T[e.role])) if (Math.abs(hh(n) - e.dp) > TOL) D(s, `height: ${T[e.role]} is ${e.dp} dp${rl(e)}`, n, hh(n), e.dp);
  }
  for (const e of cfg.forbidInside || []) {
    if (skip(e) || !T[e.role]) continue;
    for (const r of by(T[e.role])) { const inner = s.nodes.filter((n) => n.clickable && n !== r && inside(n, r) && !(Math.abs(n.l - r.l) < 1 && Math.abs(n.r - r.r) < 1)); if (inner.length) D(s, `forbid: no ${e.child || 'clickable'} inside the ${T[e.role]}${rl(e)}`, inner[0], inner.length, 0); }
  }
  for (const e of cfg.alignRules || []) {
    if (skip(e) || !T[e.role]) continue;
    const ns = by(T[e.role]);
    if (e.exactly !== undefined && ns.length !== e.exactly) { D(s, `align: exactly ${e.exactly} ${T[e.role]}${rl(e)}`, null, ns.length, e.exactly); continue; }
    for (const n of ns) {
      if (e.centreX && Math.abs((n.l + n.r) / 2 - s.W / 2) > TOL) D(s, `align: ${T[e.role]} centre x equals screen centre${rl(e)}`, n, (n.l + n.r) / 2, s.W / 2);
      if (e.fromRightEdge !== undefined && Math.abs((s.W - n.r) - e.fromRightEdge) > TOL) D(s, `align: ${T[e.role]} ${e.fromRightEdge} dp from the right edge${rl(e)}`, n, s.W - n.r, e.fromRightEdge);
    }
  }
}

// BASE value tokens: every token with a digit plus its attached unit ("429 EUR", "17%", "12 months", "9 Sep", "0/4"); a plain integer without a unit is too ambiguous and
// is skipped. No language, no date logic: the same token in two nodes is a repetition. Stop words (a unit that is a preposition) come from config.locale.
function factsOf(text) {
  const keys = new Set();
  for (const m of text.toLowerCase().matchAll(TOKEN)) {
    const d = m[1].replace(/[.,:/]+$/, '').replace(/[,.]00$/, ''); let u = (m[2] || '').toLowerCase(); if (UNIT_STOP.has(u)) u = '';
    if (!u && /^\d+$/.test(d)) continue;
    keys.add('V:' + d + u);
  }
  return keys;
}
// ADD-ON dateRanges (checks.dateRanges, locale from config.dateLocale / locales.json): a date range and its own endpoints, or a date inside a sentence and the same date
// written bare, are ONE fact. Keys: R range, E endpoint of a range, D date in a sentence, M month name, MY month+year. Off by default; the base check never reads a locale.
function dateKeys(text, isBare = false) {
  let t = text.toLowerCase(); const keys = new Set();
  if (!MRE) return keys;
  t = t.replace(new RegExp(String.raw`(${MRE})\s*(\d{4})\s*${SEP}\s*(${MRE})\s*(\d{4})`, 'g'), (_, m1, y1, m2, y2) => { keys.add(`R:${MON[m1]}/${y1}-${MON[m2]}/${y2}`); keys.add(`MY:${MON[m1]}/${y1}`); keys.add(`MY:${MON[m2]}/${y2}`); return ' '; });
  if (MD) {
    t = t.replace(new RegExp(String.raw`\b(${MRE})\s*(\d{1,2})\s*${SEP}\s*(?:(${MRE})\s*)?(\d{1,2})\b`, 'g'), (_, m1, d1, m2, d2) => { const a = `${+d1}/${MON[m1]}`, b = `${+d2}/${MON[m2 || m1]}`; keys.add(`R:${a}-${b}`); keys.add('E:' + a); keys.add('E:' + b); return ' '; });
    t = t.replace(new RegExp(String.raw`\b(${MRE})\s+(\d{1,2})\b`, 'g'), (_, m, d) => { keys.add(`${isBare ? 'E' : 'D'}:${+d}/${MON[m]}`); return ' '; });
  } else {
    t = t.replace(new RegExp(String.raw`(\d{1,2})(?:\s*(${MRE})\b)?\s*${SEP}\s*(\d{1,2})\s*(${MRE})\b`, 'g'), (_, d1, m1, d2, m2) => { const a = `${+d1}/${MON[m1 || m2]}`, b = `${+d2}/${MON[m2]}`; keys.add(`R:${a}-${b}`); keys.add('E:' + a); keys.add('E:' + b); return ' '; });
    t = t.replace(new RegExp(String.raw`(\d{1,2})\s+(${MRE})\b`, 'g'), (_, d, m) => { keys.add(`${isBare ? 'E' : 'D'}:${+d}/${MON[m]}`); return ' '; });
  }
  if (FULL) for (const m of t.matchAll(new RegExp(String.raw`\b(${FULL})\b`, 'g'))) keys.add('M:' + MON[m[1]]);
  return keys;
}
// CLASS RULE (2026-11-10, user: "judge by meaning, not by text: two figures that coincide only in the synthetic data are different facts"): identical texts in SIBLING ITEMS of one repeated
// component are each item's own attribute, not a repetition of one fact. Two nodes with the same normalised text are siblings when they sit on one line of a repeated layout (same top and bottom,
// disjoint columns: items in a row; or same left and right, disjoint rows: items in a list) AND each has an owner context (the other texts sharing its column, or its row band, outside its own
// line) that differs between the two (different pillar, different ledger row). Identical contexts (the same row twice), no context (two stray labels), a different alignment (a header and a card)
// stay flagged. The twins are compared with each other no more; each is still compared with everything else on the screen. Returns Map node -> class id.
function siblingClasses(nodes) {
  const norm = (n) => n.text.trim().toLowerCase().replace(/\s+/g, ' '), ov = (a0, a1, b0, b1) => Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
  const owners = (a, horiz) => {
    const set = new Set();
    for (const m of nodes) {
      if (m === a || !m.text.trim() || norm(m) === norm(a)) continue;
      if (m.l <= a.l + 1 && m.r >= a.r - 1 && m.t <= a.t + 1 && m.b >= a.b - 1) continue;   // a container of the node is not its owner
      const along = horiz ? ov(a.l, a.r, m.l, m.r) >= 0.5 * Math.min(a.r - a.l, m.r - m.l) : ov(a.t, a.b, m.t, m.b) >= 0.5 * Math.min(a.b - a.t, m.b - m.t);
      const apart = horiz ? ov(a.t, a.b, m.t, m.b) < 0.5 * (a.b - a.t) : ov(a.l, a.r, m.l, m.r) < 0.5 * (a.r - a.l);
      if (along && apart) set.add(norm(m));
    }
    return [...set].sort().join('\u0001');
  };
  const byText = new Map(); for (const n of nodes) { const k = norm(n); if (!byText.has(k)) byText.set(k, []); byText.get(k).push(n); }
  const par = new Map(), find = (x) => { while (par.get(x) !== x) { par.set(x, par.get(par.get(x))); x = par.get(x); } return x; }, out = new Map();
  for (const g of byText.values()) {
    if (g.length < 2) continue;
    for (const n of g) par.set(n, n);
    for (let i = 0; i < g.length; i++) for (let j = i + 1; j < g.length; j++) {
      const a = g[i], b = g[j];
      const row = Math.abs(a.t - b.t) <= 1 && Math.abs(a.b - b.b) <= 1 && ov(a.l, a.r, b.l, b.r) === 0, list = Math.abs(a.l - b.l) <= 1 && Math.abs(a.r - b.r) <= 1 && ov(a.t, a.b, b.t, b.b) === 0;
      if (!row && !list) continue;
      const oa = owners(a, row), ob = owners(b, row);
      if (oa && ob && oa !== ob) par.set(find(a), find(b));
    }
    for (const n of g) if (find(n) !== n || g.some((m) => m !== n && find(m) === find(n))) out.set(n, find(n));
  }
  return out;
}
// Duplicates (generalised 2026-11-09, user: "cerchi duplicati in una schermata, lo flagga, poi l'agente mette un filtro solo per quell'elemento"): scope is ONE screen (one
// dump). Every duplicate is flagged: (a) text nodes with identical normalised text (whitespace and case only); (b) any value token found in more than one node. There are NO
// built-in exclusions and no fact types. The only way to silence a repetition is a filter naming ONE element (screen glob + resource-id + text pattern + reason) in the
// duplicate-filters file, added after looking at the flagged element and judging its repetition legitimate.
// currencySplit (K10, STRUCTURE): a figure and its currency symbol are never split. A visible text (the text attribute, not the accessibility description) in which a digit and a currency symbol
// (config currencySymbols, default € $ £; either order) are joined by a breakable U+0020 space may wrap between them and leave the symbol alone on a line; the joiner must be U+00A0 / U+202F.
function checkCurrencySplit(s) {
  const syms = (cfg.currencySymbols ?? ['€', '$', '£']).join(''); if (!syms) return;
  const cs = [...syms].map((x) => '\\' + x).join(''), re = new RegExp('\\d +[' + cs + ']|[' + cs + '] +\\d'), seen = new Set();
  for (const n of s.nodes) {
    const k = [n.l, n.t, n.r, n.b, n.raw].join('|');
    if (!n.vis || seen.has(k) || !re.test(n.raw)) continue; seen.add(k);
    D(s, `text: a figure and its currency symbol are never split by a breakable space${rid('currencySplit')}`, n, 1, 0, 'use U+00A0 between figure and symbol');
  }
}
function checkDup(s) {
  if (!dupScoped(s.name)) { dupSkipped++; return; }
  const flt = dupFilters.filter((f) => globRe(f.screen).test(s.name));
  const filtered = (n) => { const f = flt.find((f) => (!f.id || f.id === n.id) && new RegExp(f.text || '.', 'i').test(n.text)); if (!f) return false; const hh = dupHits.get(f) || { n: 0, screens: new Set() }; hh.n++; hh.screens.add(s.name); dupHits.set(f, hh); return true; };
  const isBare = (n) => !!BARE && BARE.test(n.text.toLowerCase().trim());
  const seenNode = new Set(), idx = new Map(), seen = new Map(), cands = [];
  for (const n of s.nodes) {
    const k5 = [n.l, n.t, n.r, n.b, n.text].join('|');
    if (!n.vis || !n.text.trim() || !/[\p{L}\p{N}]/u.test(n.text) || seenNode.has(k5) || filtered(n)) continue; // a pure symbol has no fact to repeat
    seenNode.add(k5);
    cands.push(n);
    for (const k of [...factsOf(n.text), ...(DATE_ON ? dateKeys(n.text, isBare(n)) : []), 'T:' + n.text.trim().toLowerCase().replace(/\s+/g, ' ')]) { if (!idx.has(k)) idx.set(k, []); idx.get(k).push(n); }
  }
  const cls = siblingClasses(cands);
  for (const [k, hit0] of idx) {
    const hit = hit0.filter((n, i, a) => !cls.has(n) || a.findIndex((m) => cls.get(m) === cls.get(n)) === i);   // sibling twins are not compared with each other
    if (hit.length < 2) continue;
    const sig = hit.map((n) => [n.l, n.t].join(',')).sort().join(';'); if (!seen.has(sig)) seen.set(sig, { hit, keys: [] }); seen.get(sig).keys.push(k);
  }
  for (const { hit, keys } of seen.values())
    DS(s, 'duplicate: a text or value appears once per screen' + rid('duplicate'), hit.map((n) => '"' + label(n) + '"').join(' + '), hit.length, 1, 'fact ' + keys.join(', ') + ' [' + hit.map((n) => n.id || '-').join(', ') + ']');
}

// expected = the most common value in a group (most neighbours within tolerance; ties -> the lower value)
function expectedOf(vals) {
  let best = null, bestN = -1;
  for (const v of [...vals].sort((a, b) => a - b)) {
    const n = vals.filter((x) => Math.abs(x - v) <= TOL).length;
    if (n > bestN) { best = v; bestN = n; }
  }
  return best;
}
const groups = new Map();
for (const m of M) { const k = [m.rule, m.layout, m.group, m.fs].join('|'); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(m); }   // text boxes scale with the font: a font-scale dump is compared with its own kind
const dev = [], excused = [];
// An exception: rule (substring), screen (glob when it contains *, else substring), text (substring), reason, decidedBy, date. Every one needs the user's decision.
const excuse = (d) => exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || (e.screen.includes('*') ? globRe(e.screen).test(d.screen) : d.screen.includes(e.screen))) && (!e.text || d.el.includes(e.text)));
for (const [, ms] of groups) {
  const exp = ms[0].group === 'zero' ? 0 : expectedOf(ms.map((m) => m.value));
  for (const m of ms) {
    if (Math.abs(m.value - exp) <= TOL) continue;
    const d = { ...m, expected: r1(exp), edge: ms[0].rule.includes('edge') }, ex = excuse(d);
    if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
  }
}
for (const d of direct) { const ex = excuse(d); if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d); }

// Motion: what belongs together moves together. A pair = a dump at rest and one mid-gesture (<x>-rest + <x>-moving, <x> + <x>-moving, or --rest/--moving).
const pairs = [...pairArg];
for (const f of allFiles) if (stem(f).endsWith(NM.restMarker) && allFiles.includes(stem(f).slice(0, -NM.restMarker.length) + NM.movingMarker + EXT)) pairs.push([path.join(dir, f), path.join(dir, stem(f).slice(0, -NM.restMarker.length) + NM.movingMarker + EXT)]);
for (const f of allFiles) { const b = stem(f).slice(0, -NM.movingMarker.length); if (stem(f).endsWith(NM.movingMarker) && !allFiles.includes(b + NM.restMarker + EXT) && allFiles.includes(b + EXT)) pairs.push([path.join(dir, b + EXT), path.join(dir, f)]); }
let motionChecks = 0;
const CM = cfg.coMotion || {};
if (on('coMotion') || addons.some((a) => a.pair && Object.keys(a.checks || {}).some(on))) for (const [fr, fm] of pairs) {
  const A = parse(fr), B = parse(fm), name = path.basename(fm).slice(0, -EXT.length), layout = name.split(NM.layoutSeparator)[0]; markSeen(A); markSeen(B);
  const near = (n, list, ok) => list.filter(ok).sort((a, b) => Math.abs(a.l - n.l) + Math.abs(a.t - n.t) - Math.abs(b.l - n.l) - Math.abs(b.t - n.t))[0];
  const push = (rule, el, value, expected) => { motionChecks++; const d = { rule, screen: name, el, value: r1(value), expected: r1(expected), layout, group: 'motion' }; const ex = excuse(d); if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d); };
  for (const a of addons) if (a.pair) a.pair(ctx, A, B, name, push);
  if (on('coMotion')) {
    // Every tagged child of a container (coMotion.children, roles) must be displaced like its container. A dump clips bounds to the visible area: the edge touching the
    // clip box (config clipRoles: viewport / page content of that dump) is not the node's real edge, so the displacement is measured on the OTHER edge.
    const cardsA = A.filter((n) => n.id === T.container), cardsB = B.filter((n) => n.id === T.container);
    const clipBox = (n, list) => list.filter((x) => inRoles(x, CLIPBOX) && x.t <= n.t + 0.5 && x.b >= n.b - 0.5 && (n.l + n.r) / 2 >= x.l && (n.l + n.r) / 2 <= x.r).sort((p, q) => area(p) - area(q))[0];
    const disp = (a, m) => {
      const cb = clipBox(m, B), ca = clipBox(a, A);
      const rCut = (cb && m.r >= cb.r - 0.5) || (ca && a.r >= ca.r - 0.5), lCut = (cb && m.l <= cb.l + 0.5) || (ca && a.l <= ca.l + 0.5);
      const useR = rCut !== lCut ? lCut : Math.abs((m.r - m.l) - (a.r - a.l)) > TOL;
      return { dx: useR ? m.r - a.r : m.l - a.l, dy: m.t - a.t };
    };
    const cardMove = new Map();
    for (const c of cardsA) { const m = near(c, cardsB, (x) => Math.abs((x.b - x.t) - (c.b - c.t)) <= TOL); if (m) cardMove.set(c, disp(c, m)); }
    const smallest = (n) => cardsA.filter((c) => c.l - 0.5 <= n.l && n.r <= c.r + 0.5 && c.t - 0.5 <= n.t && n.b <= c.b + 0.5).sort((a, b) => area(a) - area(b))[0];
    const kidIds = (CM.children || ['cardTitle', 'cardTrailing']).map((k) => T[k] || k);
    for (const n of A.filter((x) => kidIds.includes(x.id))) {
      const c = smallest(n), cm = c && cardMove.get(c); if (!cm) continue;
      const m = near(n, B.filter((x) => x.id === n.id && x.text === n.text), () => true); if (!m) continue;
      const mv = disp(n, m); motionChecks++;
      if (Math.abs(mv.dx - cm.dx) <= TOL && Math.abs(mv.dy - cm.dy) <= TOL) continue;
      const d = { rule: `${RID.coMotion || 'co-motion'} ${n.id} vs its card (dx ${r1(mv.dx)} dp, dy ${r1(mv.dy)} dp vs container dx ${r1(cm.dx)} dp, dy ${r1(cm.dy)} dp)`, screen: name, el: label(n), value: r1(mv.dx), expected: r1(cm.dx), layout, group: 'motion' };
      const ex = excuse(d); if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
    }
  }
}

// No silent skips: an enabled check that cannot run (no tag configured for its roles) is listed; one whose tags are configured but never appear in any dump checked nothing and FAILS
// (unless config.allowAbsent names it: then it is listed as allowed). The generic mechanisms are covered the same way.
// Add-on finish hook: checks over several dumps of one capture that are not rest/moving pairs (held-finger triples of pager-hold). push records a motion row like the pair rules.
for (const a of addons) if (a.finish) a.finish(ctx, { dir, allFiles, stem, EXT, NM, parse, parseHidden: (f) => (adapter.parseHidden ? adapter.parseHidden(f, dp) : []), markSeen, push: (rule, el, value, expected, name, layout) => { motionChecks++; const d = { rule, screen: name, el, value: r1(value), expected: r1(expected), layout, group: 'motion' }; const ex = excuse(d); if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d); } });
const skipped = [], allowAbsent = cfg.allowAbsent || [];
const coverage = (key, roles) => {
  if (!roles.length) return;
  const configured = roles.filter((r) => T[r]);
  if (!configured.length) { skipped.push(`${key}: no tag configured for ${roles.join(' / ')}; nothing checked`); return; }
  if (configured.some((r) => seenRoles.has(r))) return;
  if (allowAbsent.includes(key)) { skipped.push(`${key}: tag ${configured.map((r) => T[r]).join(' / ')} never appears in any dump (allowed by allowAbsent)`); return; }
  dev.push({ rule: `nothing checked: tag ${configured.map((r) => T[r]).join(' / ')} never appears in any dump (check ${key})`, screen: '(all dumps)', el: configured.map((r) => T[r]).join(' / '), value: 0, expected: 1, layout: '', group: 'coverage' });
};
for (const k of Object.keys(CK)) if (on(k) && KNOWN[k]) coverage(k, KNOWN[k].roles || []);
for (const g of GENERIC) (cfg[g] || []).forEach((e, i) => coverage(`${g}[${i}]`, [e.role, e.over, e.containedIn].filter(Boolean)));

const rows = (list, extra) => list.map((d) => `| ${d.screen} | ${d.rule} | ${d.el.replace(/\|/g, '/')} | ${d.value} dp | ${d.expected} dp |${extra ? ' ' + d.reason + ' |' : ''}`).join('\n');
const byRule = {};
for (const d of dev) byRule[d.rule] = (byRule[d.rule] || 0) + 1;
// The header line is parsed by the install gate (`scale N,`); `density N,` is the deprecated alias of the same number. Keep the format.
let out = `# Consistency check\n\nDumps: ${files.length} (${dir}), scale ${scale}, density ${scale}, tolerance +-${TOL} dp, measurements ${M.length}, motion checks ${motionChecks}.\n\n`;
out += `Deviations: **${dev.length}**; recorded exceptions: ${excused.length}.\n\n`;
if (skipped.length) out += '## Checks skipped\n\n' + skipped.map((x) => '- ' + x).join('\n') + '\n\n';
out += dupScreens ? `Duplicates: checked on ${dupScreens.join(', ')} only (config); ${dupSkipped} other screen(s) not checked for repetition.\n\n` : 'Duplicates: checked on every screen.\n\n';
if (dupFilters.length) out += '## Duplicate filters applied\n\n| screen glob | id | text | reason | elements silenced | screens |\n|---|---|---|---|---|---|\n' + dupFilters.map((f) => { const hh = dupHits.get(f); return `| ${f.screen} | ${f.id || '*'} | ${f.text || '.'} | ${f.reason || '(none)'} | ${hh ? hh.n : 0} | ${hh ? [...hh.screens].join(', ') : '-'} |`; }).join('\n') + '\n\n';
if (dev.length) {
  out += '## Count by type of deviation\n\n' + Object.entries(byRule).sort().map(([k, v]) => `- ${k}: ${v}`).join('\n') + '\n\n';
  out += '## Deviations\n\n| screen | rule | element | measured | expected |\n|---|---|---|---|---|\n' + rows(dev) + '\n';
}
if (excused.length) out += '\n## Recorded exceptions\n\n| screen | rule | element | measured | expected | reason |\n|---|---|---|---|---|---|\n' + rows(excused, true) + '\n';
console.log(out);
if (opt('out')) fs.writeFileSync(opt('out'), out);
process.exit(dev.length || (args.includes('--strict') && skipped.length) ? 1 : 0);
