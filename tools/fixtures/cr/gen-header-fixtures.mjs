#!/usr/bin/env node
// Wave 8 (K): CR-68 whole period header group. Derived from the wave-7 -mid captures (output/consistency-2026-11-09h phone-mese-mid-{rest,moving}, fold-mese-mid-{rest,moving});
// only the tagged nodes of the period header and page are kept, bounds divided by the capture density (2.625) so the fixture runs at density 1.
// bad = the dumps as captured (period-title travels, page-subtitle stays); good = page-subtitle displaced by the page's dx; chip-still adds a `period-chip` (new tag, "giorno d/D").
// Run: node tools/fixtures/cr/gen-header-fixtures.mjs   (appends/replaces its cases in manifest.json; then node tools/consistency-check.mjs --self-test)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.dirname(fileURLToPath(import.meta.url)), src = path.resolve(root, '..', '..', '..', 'output', 'consistency-2026-11-09h'), D = 2.625;
const KEEP = ['page-header', 'period-title', 'page-subtitle', 'period-viewport', 'period-page-cur'];
const nd = (id, text, [l, t, r, b]) => `<node resource-id="app:id/${id}" text="${text}" clickable="false" scrollable="false" bounds="[${l},${t}][${r},${b}]" />`;
const read = (f) => [...fs.readFileSync(path.join(src, f), 'utf8').matchAll(/<node [^>]*resource-id="([^"]*)"[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"|<node [^>]*text="([^"]*)"[^>]*resource-id="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)]
  .map((m) => (m[1] !== undefined ? { id: m[1], text: m[2], b: m.slice(3, 7) } : { id: m[8], text: m[7], b: m.slice(9, 13) })).filter((n) => KEEP.includes(n.id)).map((n) => ({ ...n, b: n.b.map((v) => Math.round(+v / D)) }));
const write = (dir, name, nodes) => { const d = path.join(root, 'CR-68', dir); fs.mkdirSync(d, { recursive: true }); fs.writeFileSync(path.join(d, name + '.xml'), `<hierarchy>${nodes.map((n) => nd(n.id, n.text, n.b)).join('')}</hierarchy>`); };
const man = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8')).filter((c) => !/^(header-still|chip-still)/.test(c.name));
for (const lay of ['phone', 'fold']) {
  const rest = read(`${lay}-mese-mid-rest.xml`), mov = read(`${lay}-mese-mid-moving.xml`);
  write('header-still-bad', `${lay}-mese-mid-rest`, rest); write('header-still-bad', `${lay}-mese-mid-moving`, mov);
  // good twin: every page-subtitle inside the swiped pane is displaced by the page's dx, clipped at the screen edge like uiautomator does
  const ti = rest.find((n) => n.id === 'period-title').b, tc = (ti[0] + ti[2]) / 2, vp = rest.filter((n) => n.id === 'period-viewport' && tc >= n.b[0] && tc <= n.b[2])[0].b, inVp = (n) => n.b[0] >= vp[0] - 1 && n.b[2] <= vp[2] + 1;
  const pr = rest.filter((n) => n.id === 'period-page-cur' && inVp(n))[0], pm = mov.filter((n) => n.id === 'period-page-cur' && inVp(n))[0], dx = pm.b[2] - pr.b[2];
  write('header-still-good', `${lay}-mese-mid-rest`, rest);
  write('header-still-good', `${lay}-mese-mid-moving`, mov.map((n) => (n.id === 'page-subtitle' && inVp(n) ? { ...n, b: [Math.max(vp[0], n.b[0] + dx), n.b[1], n.b[2] + dx, n.b[3]] } : n)));
}
// chip-still: a "giorno d/D" chip (period-chip) beside the title; bad = it stays while the page moves 50 dp, good = it moves with the page
const scene = (chipDx) => [
  [nd('page-header', '', [16, 30, 344, 100]), nd('period-title', 'Novembre', [20, 32, 120, 60]), nd('period-chip', 'giorno 9/30', [240 + 0, 40, 340, 70]), nd('period-viewport', '', [0, 120, 360, 400]), nd('period-page-cur', '', [20, 120, 340, 400])],
  [nd('page-header', '', [16, 30, 344, 100]), nd('period-title', 'Novembre', [20, 32, 70, 60]), nd('period-chip', 'giorno 9/30', [240 + chipDx, 40, 340 + chipDx, 70]), nd('period-viewport', '', [0, 120, 360, 400]), nd('period-page-cur', '', [0, 120, 290, 400])]];
for (const [dir, dx] of [['chip-still-bad', 0], ['chip-still-good', -50]]) { const [r, m] = scene(dx); const f = (n) => `<hierarchy>${n.join('')}</hierarchy>`; const d = path.join(root, 'CR-68', dir); fs.mkdirSync(d, { recursive: true });
  fs.writeFileSync(path.join(d, 'phone-mese-chip-rest.xml'), f(r)); fs.writeFileSync(path.join(d, 'phone-mese-chip-moving.xml'), f(m)); }
const q = 'user: "se cose appartengono allo stesso elemento, quasi sempre avranno lo stesso movimento" (Mese/Spese: month title moved with the page, subtitle "dati dal ..." and the "giorno d/D" chip stayed)';
man.push({ cr: 'CR-68', name: 'header-still', bad: 'CR-68/header-still-bad', good: 'CR-68/header-still-good', rule: 'period header moves like its page (CR-68, M1)', note: q + '; wave-7 -mid captures, phone and fold' });
man.push({ cr: 'CR-68', name: 'chip-still', bad: 'CR-68/chip-still-bad', good: 'CR-68/chip-still-good', rule: 'period header moves like its page (CR-68, M1)', note: q + '; period-chip tag' });
fs.writeFileSync(path.join(root, 'manifest.json'), JSON.stringify(man, null, 1) + '\n');
console.log(man.length + ' cases');
