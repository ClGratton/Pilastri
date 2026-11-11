#!/usr/bin/env node
// Generic self-test: a user rule's numeric threshold in the rules table must equal the threshold in the code, and a must-flag fixture must exist.
// An agent that lowers a user threshold (in code, or in the table) fails here. Config (JSON): { rules, manifest, pins: [{ cr, row: ["40 dp", "28 dp"], code: [{ file, pattern, group? , expect }] }] }
//   rules: the rules markdown; row: strings that MUST appear in that CR's table row (the user's numbers); code: regex with one capture group that must equal `expect`;
//   manifest: fixtures manifest.json (array of {cr,...}); every pinned cr needs >= 1 entry.
// Usage: node user-rule-pins.mjs <config.json>   (paths relative to the config file)
import fs from 'node:fs';
import path from 'node:path';
const cfgPath = path.resolve(process.argv[2] || ''), base = path.dirname(cfgPath), cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
const rd = (f) => fs.readFileSync(path.resolve(base, f), 'utf8');
const rules = rd(cfg.rules), man = JSON.parse(rd(cfg.manifest));
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad = 1; };
for (const p of cfg.pins) {
  const row = rules.split('\n').find((l) => l.startsWith('| ' + p.cr + ' |')) || '';
  ok(!!row, p.cr + ': row exists in the rules table');
  for (const t of p.row) ok(row.includes(t), p.cr + ': table row states "' + t + '"');
  for (const c of p.code) { const m = new RegExp(c.pattern).exec(rd(c.file)); ok(!!m && Number(m[1]) === c.expect, p.cr + ': ' + c.file + ' /' + c.pattern + '/ = ' + (m ? m[1] : 'not found') + ', user value ' + c.expect); }
  ok(man.some((x) => x.cr === p.cr), p.cr + ': a must-flag fixture exists in the manifest');
}
process.exit(bad);
