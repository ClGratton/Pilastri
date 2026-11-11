#!/usr/bin/env node
// Regenerates tools/fixtures/w7/** (wave 7). Sources: the wave 6 device dumps in output/consistency-2026-11-09g (density 2.625) and the existing fixtures.
//   fs2-09g/      every phone-fs2-*.xml of that run: the group the per-line rule compares (3 Mese rows must flag, the 4 wrapped-subtitle rows must not)
//   currency-split/{bad,good}/fold-top-pilastri.xml: the real dump with "dal saldo 441 EUR-symbol" split by U+0020 (bad) and with every digit+space+symbol joined by U+00A0 (good)
//   nbsp/<case>/  the CR-70 user fixtures re-expressed with U+00A0 between figure and symbol: the duplicate check must still flag them
// Run: node tools/fixtures/w7/gen-w7.mjs   Then: node tools/consistency-check.mjs --self-test
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.dirname(fileURLToPath(import.meta.url)), fx = path.join(root, '..'), g = path.join(root, '..', '..', '..', 'output', 'consistency-2026-11-09g');
const NB = String.fromCharCode(160), put = (d, f, s) => { fs.mkdirSync(path.join(root, d), { recursive: true }); fs.writeFileSync(path.join(root, d, f), s); };
for (const f of fs.readdirSync(g).filter((x) => /^phone-fs2-.*\.xml$/.test(x))) put('fs2-09g', f, fs.readFileSync(path.join(g, f)));
const top = fs.readFileSync(path.join(g, 'fold-top-pilastri.xml'), 'utf8');
put('currency-split/bad', 'fold-top-pilastri.xml', top);
put('currency-split/good', 'fold-top-pilastri.xml', top.replace(/(\d) (?=[€$£])/g, '$1' + NB));
const nb = (s) => s.replace(/ (?=€)/g, NB);
for (const [name, src, file] of [['cr70-amount-429-twice-bad', 'cr/CR-70/amount-429-twice-bad', 'phone-top-spese.xml'], ['cr70-flusso-441-twice-bad', 'cr/CR-70/flusso-441-twice-bad', 'phone-top-pilastri.xml'],
  ['dup-bad', 'dup/bad', 'phone-top-spese-facts.xml'], ['dup-bad', 'dup/bad', 'phone-top-spese.xml'], ['dup-bad-filtered', 'dup/bad-filtered', 'phone-top-spese-facts.xml']])
  put('nbsp/' + name, file, nb(fs.readFileSync(path.join(fx, src, file), 'utf8')));
fs.copyFileSync(path.join(fx, 'dup/bad-filtered/filters.json'), path.join(root, 'nbsp/dup-bad-filtered/filters.json'));
