// K26 "Grouping follows purpose" (user 2026-11-10: "apri gmail e incolla link ... sono opzioni della stessa cosa. e non si puo piu cambiare email"; CR-94, DESIGN G-20).
// Generic, config-driven; works on uiautomator dumps (xml) in raw pixels.
// Config block { actionGroups: { tolHeight: 1, tolWidth: 2, tolAlign: 2,
//   groups: [{ screen: "<glob on the dump name>", groups: [{ name, members: [tag | resource-id, ...] }] }],
//   removedByUser: [{ screen, tag, quote }] } }.
// Checks (rows {rule, screen, el, value, expected}):
//   group-form        present members of one group differ in height/width, or are not one row (aligned tops) / one column (aligned lefts)
//   group-split       present members of one group have different parent containers
//   group-mixed-row   members of two groups share a row (overlapping vertical span) inside the same parent
//   removed-action    (only with a baseline folder) a clickable tag of the baseline dump is missing in the new dump of the same screen, unless listed in removedByUser (quote required)
//   removed-action-config  a removedByUser entry has no quote
// A member missing from the dump is not a flag; with one member present there is nothing to compare.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { tolHeight: 1, tolWidth: 2, tolAlign: 2 };
export const matchesScreen = (e, name) => new RegExp('^' + (e.screen || '*').replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$').test(name);
const attr = (s, a) => { const m = new RegExp(` ${a}="([^"]*)"`).exec(s); return m ? m[1] : undefined; };
const tagOf = (id) => (id || '').replace(/^.*:id\//, '');

/** Parse a dump into nodes {i, parent, id, clickable, l,t,r,b} keeping the nesting. */
export function parseDump(file) {
  const x = fs.readFileSync(file, 'utf8'), nodes = [], stack = [];
  for (const m of x.matchAll(/<node [^>]*?(\/?)>|<\/node>/g)) {
    if (m[0] === '</node>') { stack.pop(); continue; }
    const b = /bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/.exec(m[0]);
    const n = { i: nodes.length, parent: stack.length ? stack[stack.length - 1] : -1, id: tagOf(attr(m[0], 'resource-id')), clickable: attr(m[0], 'clickable') === 'true', l: b ? +b[1] : 0, t: b ? +b[2] : 0, r: b ? +b[3] : 0, b: b ? +b[4] : 0 };
    nodes.push(n); if (m[1] !== '/') stack.push(n.i);
  }
  return nodes;
}

const find = (nodes, tag) => nodes.find((n) => n.id === tag);
const spread = (v) => Math.max(...v) - Math.min(...v);

/** Group checks on one parsed dump. */
export function actionGroups(nodes, entry, cfg = {}) {
  const c = { ...DEFAULTS, ...cfg }, rows = [], row = (rule, el, value, expected) => rows.push({ rule, el, value, expected });
  const present = (entry.groups || []).map((g) => ({ name: g.name, m: (g.members || []).map((t) => ({ tag: t, n: find(nodes, t) })).filter((x) => x.n) }));
  for (const g of present) {
    if (g.m.length < 2) continue;
    const ns = g.m.map((x) => x.n), names = g.m.map((x) => x.tag).join(', ');
    const dh = spread(ns.map((n) => n.b - n.t)), dw = spread(ns.map((n) => n.r - n.l)), dt = spread(ns.map((n) => n.t)), dl = spread(ns.map((n) => n.l));
    if (dh > c.tolHeight) row('group-form', `${g.name}: ${names} [height]`, dh, c.tolHeight);
    if (dw > c.tolWidth) row('group-form', `${g.name}: ${names} [width]`, dw, c.tolWidth);
    if (dt > c.tolAlign && dl > c.tolAlign) row('group-form', `${g.name}: ${names} [not one row or one column]`, Math.min(dt, dl), c.tolAlign);
    if (new Set(ns.map((n) => n.parent)).size > 1) row('group-split', `${g.name}: ${names} [different parents]`, new Set(ns.map((n) => n.parent)).size, 1);
  }
  for (let a = 0; a < present.length; a++) for (let b = a + 1; b < present.length; b++)
    for (const p of present[a].m) for (const q of present[b].m)
      if (p.n.parent === q.n.parent && Math.min(p.n.b, q.n.b) - Math.max(p.n.t, q.n.t) > c.tolAlign)
        row('group-mixed-row', `${present[a].name}/${present[b].name}: ${p.tag} + ${q.tag} share a row`, 1, 0);
  return rows;
}

const quoted = (e) => e.quote && String(e.quote).trim();

/** Baseline comparison for one screen: clickable tags lost. removedByUser: [{screen, tag, quote}]. */
export function removedActions(base, now, screen, removedByUser = []) {
  const rows = [], have = new Set(now.map((n) => n.id).filter(Boolean));
  for (const n of base) {
    if (!n.clickable || !n.id || have.has(n.id)) continue;
    if (removedByUser.some((e) => quoted(e) && e.tag === n.id && matchesScreen(e, screen))) continue;
    rows.push({ rule: 'removed-action', el: `${n.id} (clickable in the baseline, missing now)`, value: 1, expected: 0 });
  }
  return rows;
}

const isScreen = (f) => f.endsWith('.xml') && !/-(state|action)\d+-(before|after)\.xml$|-f\d{2,}\.xml$|-moving\.xml$|-hold-/.test(f);

/** Folder run. Returns {rows: [{rule, screen, el, value, expected}], notes: [string]}; baselineDir optional (enables removed-action). */
export function actionGroupsRows(dir, cfg = {}, baselineDir = null) {
  const rows = [], notes = [], files = fs.readdirSync(dir).filter(isScreen), stem = (f) => f.replace(/\.xml$/, '');
  for (const f of files) {
    const s = stem(f), entries = (cfg.groups || []).filter((e) => matchesScreen(e, s));
    if (!entries.length) continue;
    const nodes = parseDump(path.join(dir, f));
    for (const e of entries) for (const r of actionGroups(nodes, e, cfg)) rows.push({ ...r, screen: s });
  }
  if (baselineDir) {
    for (const e of cfg.removedByUser || []) if (!quoted(e)) rows.push({ rule: 'removed-action-config', screen: e.screen || '*', el: `removedByUser ${e.tag}: the user's quote is required`, value: 1, expected: 0 });
    const bf = new Set(fs.readdirSync(baselineDir).filter(isScreen)), nf = new Set(files);
    let only = 0; for (const f of bf) if (!nf.has(f)) only++; for (const f of nf) if (!bf.has(f)) only++;
    if (only) notes.push(`removed-action: ${only} screen(s) present on one side only, not compared`);
    for (const f of files) if (bf.has(f)) for (const r of removedActions(parseDump(path.join(baselineDir, f)), parseDump(path.join(dir, f)), stem(f), cfg.removedByUser || [])) rows.push({ ...r, screen: stem(f) });
  }
  return { rows, notes };
}

export default { checks: { actionGroups: [] } };

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--self-test')) {
  const here = path.dirname(fileURLToPath(import.meta.url)), root = path.resolve(here, '../../../tools/fixtures/action-groups');
  const G = { groups: [{ screen: 'setup-wait', groups: [{ name: 'link', members: ['open-mail', 'paste-link'] }, { name: 'recovery', members: ['resend', 'change-email'] }] }] };
  const run = (d, base, cfg = G) => actionGroupsRows(path.join(root, d), cfg, base ? path.join(root, base) : null).rows;
  const has = (rows, r) => rows.some((x) => x.rule === r);
  const bad = run('bad/new', 'bad/baseline'), withQuote = { ...G, removedByUser: [{ screen: 'setup-wait', tag: 'change-email', quote: 'fixture: the user asked to remove it' }] }, noQuote = { ...G, removedByUser: [{ screen: 'setup-wait', tag: 'change-email' }] };
  const checks = [
    ['bad: group-form or group-split flagged', has(bad, 'group-form') || has(bad, 'group-split')],
    ['bad: group-mixed-row flagged', has(bad, 'group-mixed-row')],
    ['bad: removed-action (change-email) flagged', bad.some((x) => x.rule === 'removed-action' && x.el.startsWith('change-email'))],
    ['good: zero flags (against the four-action baseline)', run('good', 'bad/baseline').length === 0],
    ['good without mail app (open-mail absent): zero flags', run('good-nomail', null).length === 0],
    ['removedByUser with quote silences removed-action', !has(run('bad/new', 'bad/baseline', withQuote), 'removed-action')],
    ['removedByUser without quote is an error', has(run('bad/new', 'bad/baseline', noQuote), 'removed-action-config')],
    ['folder against itself: zero removed-action', !has(run('good', 'good'), 'removed-action')],
  ];
  checks.forEach((c) => console.log((c[1] ? 'PASS ' : 'FAIL ') + c[0]));
  const fail = checks.filter((c) => !c[1]);
  console.log(`action-groups self-test: ${checks.length - fail.length}/${checks.length}`);
  process.exit(fail.length ? 1 : 0);
}
