#!/usr/bin/env node
// Equivalence of two capture folders (e.g. in-app capture vs validated uiautomator captures of the SAME APK), screen by screen.
//   node capture-equiv.mjs <baseline-dir> <candidate-dir> [--tol 1] [--only <regex>] [--show 6]
// For every <name>.xml present in BOTH folders, the visible `<node>` elements (never `<hidden-node>`) are compared as a multiset of
// (resource-id, text, content-desc) with bounds equal within --tol px. Nodes with none of the three are View/layout wrappers and are ignored.
// Prints one line per screen (EQUAL / DIFF n) and the first --show differences; exit 1 when any screen differs or a baseline file is missing.
import fs from 'node:fs';
import path from 'node:path';

const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const [base, cand] = argv.filter((a, i) => !a.startsWith('--') && !(i > 0 && argv[i - 1].startsWith('--')));
if (!base || !cand) { console.error('usage: capture-equiv.mjs <baseline-dir> <candidate-dir> [--tol 1] [--only <regex>] [--show 6]'); process.exit(2); }
const tol = +opt('--tol', 1), show = +opt('--show', 6), only = opt('--only') ? new RegExp(opt('--only')) : null;
const attr = (s, a) => { const m = new RegExp(` ${a}="([^"]*)"`).exec(s); return m ? m[1] : ''; };
export function nodes(xml) {
  const out = [];
  for (const m of xml.matchAll(/<node [^>]*>/g)) {
    const s = m[0], id = attr(s, 'resource-id'), text = attr(s, 'text'), desc = attr(s, 'content-desc');
    if (!id && !text && !desc) continue;
    if (/^android:id\//.test(id) && !text && !desc) continue;
    const b = /bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/.exec(s); if (!b) continue;
    out.push({ key: `${id}|${text}|${desc}`, b: b.slice(1, 5).map(Number) });
  }
  return out;
}
export function diff(a, b, t = 1) {
  const rest = [...b], miss = [];
  for (const n of a) {
    const i = rest.findIndex((m) => m.key === n.key && m.b.every((v, k) => Math.abs(v - n.b[k]) <= t));
    if (i >= 0) rest.splice(i, 1); else miss.push(n);
  }
  return { miss, extra: rest };
}
const optv = (n) => { const i = process.argv.indexOf(n); return i >= 0 ? process.argv[i + 1] : undefined; };
const settingsPath = optv('--settings'), curM = optv('--cur') || '2026-11', prevM = optv('--prev') || '2026-10';
const renameMap = {};
if (settingsPath && fs.existsSync(settingsPath)) {
  const sub = (t) => t.replace('{cur}', curM).replace('{prev}', prevM);
  for (const [from, tos] of Object.entries(JSON.parse(fs.readFileSync(settingsPath, 'utf8')).rename || {})) renameMap[sub(from) + '.xml'] = sub(tos[0]) + '.xml';
}
const renameOf = (f) => renameMap[f] || f;
if (import.meta.url === `file://${process.argv[1].replace(/\\/g, '/')}` || process.argv[1].endsWith('capture-equiv.mjs')) {
  const names = fs.readdirSync(base).filter((f) => f.endsWith('.xml') && (!only || only.test(f))).sort();
  let bad = 0, eq = 0;
  for (const f of names) {
    // the in-app tool renames device names to the checker's older names (settings `rename`): map the baseline name through it
    const alias = renameOf(f);
    const cf = path.join(cand, alias);
    if (!fs.existsSync(cf)) { console.log(`MISSING ${f}`); bad++; continue; }
    const { miss, extra } = diff(nodes(fs.readFileSync(path.join(base, f), 'utf8')), nodes(fs.readFileSync(cf, 'utf8')), tol);
    if (!miss.length && !extra.length) { eq++; console.log(`EQUAL   ${f}`); continue; }
    bad++; console.log(`DIFF    ${f}  only-baseline ${miss.length}, only-candidate ${extra.length}`);
    for (const n of miss.slice(0, show)) console.log(`   - ${n.key} [${n.b}]`);
    for (const n of extra.slice(0, show)) console.log(`   + ${n.key} [${n.b}]`);
  }
  console.log(`equivalence: ${eq} equal, ${bad} differing/missing of ${names.length}`);
  process.exit(bad ? 1 : 0);
}
