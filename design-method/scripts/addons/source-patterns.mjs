// Add-on `source-patterns` (config list `sourcePatterns`; SKILL section 2, K22 / M8; DESIGN G-15). Generic: a list of forbidden source patterns with the files that may hold them.
// Entry: { id, pattern: regex (per line), glob?: file-name regex (default \.kt$), allowFiles: [file-name regexes that may contain it, e.g. the one shared helper], reason }.
// Scan: every file under `roots` (config) matching `glob`; a line matching `pattern` in a file not in allowFiles is a row { rule: 'source-pattern', el: id, file, line }.
// Optional `unlessNear: { pattern, linesAbove }`: the line is fine when the pattern appears on it or within linesAbove (default 3) lines above (K24: a call needs its timeout wrapper).
// User 2026-11-10: "Quando faccio indietro tastiera, i pulsanti sotto commettono 1s a comparire". Project entry: a visibility gate on the animated keyboard inset (stays true until the last frame).
//   node source-patterns.mjs --selftest
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const walk = (d, out = []) => { try { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p, out); else out.push(p); } } catch {} return out; };
const slash = (f) => f.split(path.sep).join('/');

export function sourcePatterns(roots, entries) {
  const rows = [], files = roots.flatMap((r) => walk(r));
  for (const e of entries) {
    const re = new RegExp(e.pattern), g = new RegExp(e.glob || '\\.kt$'), allow = (e.allowFiles || []).map((x) => new RegExp(x));
    for (const f of files) {
      if (!g.test(f) || allow.some((a) => a.test(slash(f)))) continue;
      const lines = fs.readFileSync(f, 'utf8').split('\n'), near = e.unlessNear && new RegExp(e.unlessNear.pattern), up = e.unlessNear ? (e.unlessNear.linesAbove ?? 3) : 0;
      lines.forEach((ln, i) => { if (re.test(ln) && !/^\s*(\/\/|\*)/.test(ln) && !(near && lines.slice(Math.max(0, i - up), i + 1).some((x) => near.test(x)))) rows.push({ rule: 'source-pattern', el: e.id, file: slash(f), line: i + 1, note: e.reason }); });
    }
  }
  return rows;
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const here = path.dirname(fileURLToPath(import.meta.url)), fx = path.resolve(here, '../../../tools/fixtures/source-patterns');
  const cfg = JSON.parse(fs.readFileSync(path.resolve(here, '../../../tools/source-patterns.json'), 'utf8')).sourcePatterns;
  const bad = sourcePatterns([path.join(fx, 'bad')], cfg), good = sourcePatterns([path.join(fx, 'good')], cfg);
  const pass = bad.length >= 1 && good.length === 0;
  console.log(pass ? 'source-patterns selftest PASS (the ime-inset gate flagged, the target-based helper clean)' : 'source-patterns selftest FAIL ' + JSON.stringify(bad.concat(good)));
  process.exit(pass ? 0 : 1);
}
