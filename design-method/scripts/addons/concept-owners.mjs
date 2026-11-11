#!/usr/bin/env node
// Add-on `concept-owners` (CR-76 "same concept, same code" / CR-77 "same gesture, same physics"). A SOURCE check, not a dump check: run it next to the dump checker.
//   node concept-owners.mjs <sourceDir> --config concept-owners.json [--ext .kt,.java,.ts]      (exit 1 on any offender)
// Config (one file, project data): { concepts: [{ name, owners: [relative paths], signatures: [regex | { re, near }], known: [{ file, reason, decision }] }],
//   gestures: { signatures: [regex], helper: regex, exceptions: [{ file, segment, reason }] } }.
//  - CR-76: a signature (a line regex; comments and imports are ignored; `near` = a second regex within 15 lines) may appear only in the concept's owner files. Describe the IMPLEMENTATION
//    (the spring, the gesture detector, the tags), never a function name, so a wrapper that only passes parameters has no signature and passes. `known` = a second implementation that
//    exists today: it needs a reason AND a decision (the user's, or the pending-owner note); remove the entry when the duplicate is gone.
//  - CR-77: a top-level declaration that holds a gesture signature must reference the shared helper (the one place that owns the physics / haptics of that gesture).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
export function scan(root, cfg, exts = ['.kt']) {
  const files = [];
  const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const f = path.join(d, e.name); if (e.isDirectory()) walk(f); else if (exts.some((x) => f.endsWith(x))) files.push({ rel: path.relative(root, f).split(path.sep).join('/'), text: fs.readFileSync(f, 'utf8') }); } };
  walk(root);
  const code = (t) => t.split('\n').map((l) => (/^\s*(\/\/|\*|\/\*)/.test(l) || /^\s*import\s/.test(l) ? '' : l));
  const sig = (s) => (typeof s === 'string' ? { re: new RegExp(s) } : { re: new RegExp(s.re), near: s.near && new RegExp(s.near) });
  const hits = (text, sigs) => { const L = code(text), out = []; L.forEach((l, i) => { for (const s of sigs) if (s.re.test(l) && (!s.near || L.slice(Math.max(0, i - 15), i + 16).some((x) => s.near.test(x)))) { out.push([i + 1, l.trim().slice(0, 100)]); break; } }); return out; };
  const out = [];
  for (const c of cfg.concepts || []) {
    for (const k of c.known || []) if (!(k.reason || '').trim() || !(k.decision || '').trim()) out.push(`CR-76 [${c.name}] known entry ${k.file} needs a reason and a decision`);
    const owners = new Set(c.owners), known = new Set((c.known || []).map((k) => k.file)), sigs = c.signatures.map(sig);
    for (const f of files) { if (owners.has(f.rel) || known.has(f.rel)) continue; for (const [n, l] of hits(f.text, sigs)) out.push(`CR-76 [${c.name}] ${f.rel}:${n}  ${l}   (owner: ${[...owners].join(', ')})`); }
  }
  const g = cfg.gestures;
  if (g) {
    const gs = g.signatures.map(sig), helper = new RegExp(g.helper), decl = /^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public|inline|override|abstract|open|export|async)\s+)*(?:fun|class|object|function)\b/;
    for (const f of files) {
      const L = f.text.split('\n'), starts = L.map((l, i) => (decl.test(l) ? i : -1)).filter((i) => i >= 0);
      (starts.length ? starts : [0]).forEach((s, i) => {
        const e = starts.length && i + 1 < starts.length ? starts[i + 1] : L.length, seg = L.slice(s, e).join('\n'), head = (L[s] || '').trim();
        if (hits(seg, gs).length && !helper.test(seg) && !(g.exceptions || []).some((x) => x.file === f.rel && head.includes(x.segment))) out.push(`CR-77 ${f.rel}:${s + 1}  ${head.slice(0, 70)}  (gesture without the shared helper)`);
      });
    }
  }
  return out;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2), root = a[0], cf = a[a.indexOf('--config') + 1], ex = a.includes('--ext') ? a[a.indexOf('--ext') + 1].split(',') : ['.kt'];
  if (!root || !cf) { console.error('usage: concept-owners.mjs <sourceDir> --config <concept-owners.json> [--ext .kt,.ts]'); process.exit(2); }
  const o = scan(root, JSON.parse(fs.readFileSync(cf, 'utf8')), ex);
  console.log(o.length ? o.join('\n') : 'concept-owners: clean'); process.exit(o.length ? 1 : 0);
}
