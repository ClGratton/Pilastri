#!/usr/bin/env node
// Pilastri consistency check (G-12): elements of one TYPE must share size, position and alignment on every screen.
// Input: a folder of `uiautomator dump` XML files (the app tags every element type, see ui/Tags.kt) + the device density.
// File names: <layout>-<screen>.xml, layout = fold | phone; a screen containing "-top-" is a top-level page (Pilastri, Spese, ...).
// Usage: node tools/consistency-check.mjs <dumpDir> --density 2.75 [--out REPORT.md] [--exceptions tools/consistency-exceptions.json]
// Exit 1 when any deviation is not covered by an exception. Tolerance +-1 dp. The expected value is the most common one.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : d; };
const dir = args.find((a, i) => !a.startsWith('--') && (i === 0 || !args[i - 1].startsWith('--')));
const pairArg = opt('rest') && opt('moving') ? [[opt('rest'), opt('moving')]] : [];
if (args.includes('--self-test')) {} else if (!dir && !pairArg.length) { console.error('usage: consistency-check.mjs <dumpDir> --density <dpi/160> [--out file] [--exceptions file]'); process.exit(2); }
const density = parseFloat(opt('density', '0')) || (parseFloat(opt('dpi', '0')) / 160);
if (!density && !args.includes('--self-test')) { console.error('--density (or --dpi) required'); process.exit(2); }
const TOL = 1;
let rangeEndCfg = {}; try { rangeEndCfg = JSON.parse(fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'device-addons.json'), 'utf8')); } catch {}
// DESIGN.md "## Tokens": values the table fixes are asserted against these, never against the majority (same constants as ConsistencyEngine.kt).
const CR01_PEEK_FOLD = 40, CR01_PEEK_COVER = 28; // user rule CR-01 (tools/critique-rules.md)
const CARD_INSET = 14, GUTTER = 16, GAP = 8, ATTENTION_H = 56;
// Screens whose approved board draws an accent card with a primary figure (1a Pilastri, 3a Spese + Rate, Futuro); elsewhere at most one per pane.
const ACCENT_SCREENS = ['pilastri', 'spese', 'futuro'];
const here = path.dirname(fileURLToPath(import.meta.url));
const excFile = opt('exceptions', path.join(here, 'consistency-exceptions.json'));
const exceptions = fs.existsSync(excFile) ? JSON.parse(fs.readFileSync(excFile, 'utf8')) : [];

if (args.includes('--self-test')) {
  const { execFileSync } = await import('node:child_process');
  const fx = path.join(here, 'fixtures', 'dup');
  const run = (d, f, sc, o = {}) => { try { return [0, execFileSync(process.execPath, [fileURLToPath(import.meta.url), d, '--density', o.density || '1', ...(o.heading ? [] : ['--no-heading-levels']), '--currency', o.currency ?? '', '--separator-roles', o.sep ?? '', '--exceptions', '/nonexistent', '--duplicate-filters', f || '/nonexistent', '--duplicate-scope', sc || '/nonexistent', '--no-visual-integrity', '--no-aligned-to-nothing', '--no-sibling-actions'], { encoding: 'utf8' })]; } catch (e) { return [e.status, String(e.stdout || '')]; } };
  const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) process.exitCode = 1; };
  const dupRows = (o) => o.split('\n').filter((l) => /duplicate: a text or value/.test(l));
  const [, bad] = run(path.join(fx, 'bad')), [, good] = run(path.join(fx, 'good'), path.join(fx, 'good', 'filters.json')), [, add] = run(path.join(fx, 'addon'));
  // base checks (no language)
  ok(dupRows(bad).some((l) => l.includes('"9 set – 8 ott" + "9 set – 8 ott"')), 'base: identical text in subtitle and chip is flagged');
  ok(dupRows(bad).some((l) => l.includes('"Spesa 429 €"') && l.includes('"429 €"')) || dupRows(bad).some((l) => /429 €.*429 €/.test(l)), 'base: the same amount in two nodes is flagged (hero and chart end label)');
  ok(dupRows(bad).some((l) => l.includes('"budget n/d" + "budget n/d"')), 'base: identical label+value text in two nodes is flagged');
  ok(dupRows(bad).some((l) => l.includes('"Spese 441 €"') && l.includes('"dal saldo 441 €"')), 'base: an amount repeated under different labels is flagged');
  ok(dupRows(bad).some((l) => l.includes('"Versato tuo nel mese 150 €"') && l.includes('"150 €"')), 'base: a movement amount repeating a monthly stat is flagged');
  ok(dupRows(bad).some((l) => l.includes('"9 set"')) , 'base: a token with its word ("9 set") in two nodes is flagged');
  ok(/CR-71/.test(bad), 'period: swipe page without period-title is flagged (CR-71)');
  // add-on dateRanges
  ok(dupRows(add).some((l) => l.includes('"1 – 9 nov"') && l.includes('"1 nov"')), 'add-on dateRanges: a range and its own endpoint ("1 – 9 nov" vs "1 nov") is flagged');
  // filters: mechanism
  ok(dupRows(good).length === 0, 'good: unique values, chevron glyphs (pure symbols, never compared) and a filtered per-row "budget n/d", flag nothing');
  ok(/Duplicate filters applied/.test(good) && /per-row budget[^\n]*\| 2 \|/.test(good) && !/chevron glyph/.test(good), 'filters: the report lists every applied filter with the number of elements it silenced');
  const [, noFilt] = run(path.join(fx, 'good'));
  ok(dupRows(noFilt).length === 0, 'filters: without the filter file the same screen is still clean (chevrons are symbols, "Casa/Svago budget n/d" differ in text); the filter only matters where an identical repetition exists (bad-filtered)');
  const [, flt] = run(path.join(fx, 'bad-filtered'), path.join(fx, 'bad-filtered', 'filters.json'));
  ok(!dupRows(flt).some((l) => l.includes('"budget n/d" + "budget n/d"')) && dupRows(flt).some((l) => l.includes('"Spese 441 €"')) && /\| 2 \|/.test(flt), 'filters: a filter silences only its own element; every other repetition stays flagged and the count is printed');
  // User-rule pins (wave 12): the thresholds of user-derived rules in code equal the rules table; a must-flag fixture exists.
  { const { spawnSync } = await import('node:child_process'); const r = spawnSync(process.execPath, [path.join(here, 'user-rule-pins.mjs'), path.join(here, 'user-rule-pins.json')], { encoding: 'utf8' }); ok(r.status === 0, 'user-rule pins: code thresholds match critique-rules.md rows' + (r.status ? ' [' + r.stdout.split(String.fromCharCode(10)).filter((l) => l.startsWith('FAIL')).join(' / ') + ']' : '')); }
  // CR-71 narrowing (X, wave 5): an empty-text `page-trailing` is a 48 dp touch box around a control, not header text.
  { const [, o] = run(path.join(fx, '..', 'period-trailing')); ok(!/CR-71/.test(o), 'period: a page-trailing touch box (empty text, 48 dp) is not header text taller than the period-title (CR-71)'); }
  // CR-59 / chip-height narrowings (X, wave 5): at font scale 2 the attention row grows (min 56 dp); a chip whose top is on the dock's bottom edge is cut by the overlay.
  { const [, o] = run(path.join(fx, '..', 'fs2-narrow')); ok(!/attention: attention-row height/.test(o) && !/chip height/.test(o), 'fs2: a taller attention-row (>= 56) and a chip cut by the dock are not flagged (CR-59, chip height)'); }
  // Scope by config (user, 2026-11-09: repetition only on a single screen, not unfolded): the same text in the left and right pane of an open-Fold dump is not a repetition.
  // The project settings (duplicate-scope.json + duplicate-filters.json) are used, so a scope that stopped covering phone-* or a filter that hid a user case fails here.
  const projScope = path.join(here, 'duplicate-scope.json'), projFilters = path.join(here, 'duplicate-filters.json');
  { const fold = path.join(fx, 'fold-cross-pane'); const [, withScope] = run(fold, projFilters, projScope), [, noScope] = run(fold, '/nonexistent');
    ok(dupRows(noScope).some((l) => /Novembre/.test(l)), 'scope: the open-Fold dump does repeat "Novembre" across the left hero and the right Mese pane (fixture is meaningful: unscoped base flags it)');
    ok(dupRows(withScope).length === 0 && /Duplicates: checked on phone-\* only/.test(withScope), 'scope: with the project scope (phone-* only) an open-Fold dump produces no duplicate rows and the report states the scope'); }
  // Wave 9 R (user 2026-11-09 ~17:25, same concept same code / same gesture same physics): CR-73 held-finger dumps (fixtures built from the wave 9 owner S raw dumps by tools/fixtures/hold/gen-hold.mjs),
  // CR-74 text under its own help button (real Versato tuo dump, wave 8 bad case + the fixed dump), CR-75 separator after a breakable space.
  { const w9 = path.join(fx, '..', 'hold'), W9 = path.join(fx, '..', 'w9'), d9 = { density: 2.625, sep: 'page-subtitle,period-subtitle' };
    const [, g] = run(path.join(w9, 'good'), undefined, undefined, d9), [, nr] = run(path.join(w9, 'bad-noresist'), undefined, undefined, d9), [, dv] = run(path.join(w9, 'bad-diverge'), undefined, undefined, d9);
    ok(/Deviations: \*\*0\*\*/.test(g), 'CR-73 good twin: strips drawn on the reference curve (Motion.kt tokens; mese and hero, spese rubber band) are clean');
    ok(/phone-pilastri[^\n]*without resistance/.test(nr) && /phone-mese[^\n]*without resistance/.test(nr), 'CR-73 must-flag: 1:1 pages below the commit point (resistance lost) are flagged on both strips');
    ok(/phone-pilastri-hold-short[^\n]*differently from the others/.test(dv) && /phone-pilastri-hold-long[^\n]*differently from the others/.test(dv), 'CR-73 must-flag: one strip drawn differently from the others by the same hold is flagged (short and long)');
    { const [, gr] = run(path.join(w9, 'good-real'), undefined, undefined, d9), [, bw] = run(path.join(w9, 'bad-weak'), undefined, undefined, d9), [, rs] = run(path.join(w9, 'real-spese'), undefined, undefined, d9);
      ok(/Deviations: \*\*0\*\*/.test(gr), 'CR-73 reference curve: the real 09j Pilastri and Mese holds (0.80 of the finger short, 0.96 long) lie on the curve (0.806 / 0.967 with the 8 dp slop), clean');
      ok(/phone-pilastri-hold-short[^\n]*reference curve/.test(bw) && /phone-mese-hold-long[^\n]*reference curve/.test(bw) && /phone-mese-hold-short[^\n]*reference curve/.test(bw) && /phone-pilastri-hold-long[^\n]*reference curve/.test(bw), 'CR-73 must-flag: a page drawn at about half the reference (a lost or doubled resistance) is flagged at both holds on both strips');
      ok(!/phone-spese-hold-long[^\n]*reference curve/.test(rs), 'CR-73 narrowing (G3, wave 9): the real 09j Spese end hold (0.18 of a page) lies on the end curve = rubber band then the same resistance (0.175), clean');
      ok(/phone-mese-hold-short[^\n]*reference curve/.test(nr), 'CR-73 must-flag: a 1:1 page below the commit point is also off the reference curve'); }
    { const [, lb] = run(path.join(W9, 'label-bad'), undefined, undefined, d9), [, lg] = run(path.join(W9, 'label-good'), undefined, undefined, d9);
      ok(/CR-78/.test(lb) && !/CR-78/.test(lg), 'CR-78: the real 09j month coverage label "dati dal 1 al 6 nov" is flagged, the "dati al 6 nov" twin is clean'); }
    const [, hb] = run(path.join(W9, 'help-bad'), undefined, undefined, d9), [, hg] = run(path.join(W9, 'help-good'), undefined, undefined, d9);
    ok(/a text overlaps a control \(CR-74\)[^\n]*Versato tuo/.test(hb), 'CR-74 must-flag: the Versato tuo label 11.8 dp under its help button at font scale 2 is flagged (wave 8 bad dump)');
    ok(!/CR-74/.test(hg), 'CR-74 good twin: the fixed dump (label ends where the help button starts) is clean');
    const [, sb] = run(path.join(W9, 'sep-bad'), undefined, undefined, d9), [, sg] = run(path.join(W9, 'sep-good'), undefined, undefined, d9);
    ok(/CR-75/.test(sb) && !/CR-75/.test(sg), 'CR-75: a subtitle with a plain space before the separator is flagged, the NBSP twin is clean');
    { const [, cb] = run(path.join(W9, 'chip-bad'), undefined, undefined, d9), [, cg] = run(path.join(W9, 'chip-good'), undefined, undefined, d9);
      ok(/phone-b \| chip height \| giorno 9/.test(cb) && !/chip height/.test(cg), 'chip height covers the period-chip tag: a Mese day chip taller than the other chips (53.3 vs 32 dp) is flagged, the equal-height twin is clean'); } }
  // Wave 10 (user 2026-11-09 review H1 / M1 / M3): CR-79 a travelling title over a fixed control, CR-75 on every label, CR-80 hero columns on one line. The bad dumps are the real 09j captures.
  { const w10 = path.join(here, 'fixtures', 'w10'), d10 = { density: '2.625', sep: '*', currency: '€$£' };
    const [, mb] = run(path.join(w10, 'mov-bad'), undefined, undefined, d10), [, hb] = run(path.join(w10, 'hero-bad'), undefined, undefined, d10);
    ok(/CR-79[^\n]*Ottobre|CR-79[^\n]*Novembre/.test(mb), 'CR-79 must-flag: the real 09j Mese mid-drag (title under the back button, [17,89][290,157] over [42,84][168,210]) is flagged');
    ok(/CR-80/.test(hb) && /CR-75[^\n]*Versato tuo/.test(hb), 'CR-80 / CR-75 must-flag: the real 09j hero (labels 27 px apart; "Versato tuo · € gen 2026" with a plain space before the dot, any role) is flagged');
    const [, cb] = run(path.join(w10, 'cov-bad'), undefined, undefined, d10); ok(/CR-81/.test(cb), 'CR-81 must-flag: the real 09j Spese header "parziale · dal 10 ott" (another wording for the coverage Mese writes "dati al ...") is flagged');
    { const [, cb2] = run(path.join(w10, 'cov-bad2'), undefined, undefined, d10); ok(/"dati dal <d> <m>"[^\n]*CR-81/.test(cb2), 'CR-81 must-flag: "dati dal 10 ott" with no end date on the coverage role (user 2026-11-09: same wording as Mese, "dati al ..." or "dati dal .. al ..") is flagged'); }
    { const w11 = path.join(here, 'fixtures', 'w11'), [, hb3] = run(path.join(w11, 'head-bad'), undefined, undefined, { ...d10, heading: true }), [, wb] = run(path.join(w11, 'word-bad'), undefined, undefined, d10);
      ok(/CR-83/.test(hb3), 'CR-83 must-flag: the real 09k Spese ("Spese" and the period title "10 ott – 6 nov" both 26 dp line boxes) lacks heading hierarchy');
      ok(/period start phrase[^\n]*"da <d> <m>"/.test(wb), 'CR-81 must-flag: the real 09k Pilastri "da 10 ott" (Variazione) against "dal 27 set" is another phrasing of a period start'); }
    { const [, rb] = run(path.join(w10, 'range-bad'), undefined, undefined, d10); ok(/CR-82/.test(rb), 'CR-82 must-flag: the real 09k Spese header (title "7 ott – 6 nov" over "dati dal 10 ott al 6 nov", two ranges for one period) is flagged'); }
    const good = path.join(w10, 'good'); if (fs.existsSync(good)) { const [, gg] = run(good, undefined, undefined, d10); ok(/Deviations: \*\*0\*\*/.test(gg), 'wave 10 good twins (the fixed dumps of the 09k capture: moving header clipped to its slot, labels bound, hero columns aligned) are clean'); } }
  // regression corpus: tools/fixtures/cr/manifest.json (built by tools/fixtures/cr/gen-fixtures.mjs). Every bad fixture must be flagged by its rule, every good twin must be clean.
  const crRoot = path.join(here, 'fixtures', 'cr');
  const man = JSON.parse(fs.readFileSync(path.join(crRoot, 'manifest.json'), 'utf8'));
  const rows = (o) => o.split('\n').filter((l) => l.startsWith('|'));
  for (const c of man) {
    const [bc, bo] = run(path.join(crRoot, c.bad)), [gc, go] = run(path.join(crRoot, c.good));
    if (c.clean) { ok(bc === 0 && gc === 0, c.cr + ' ' + c.name + ': ' + c.note); continue; }
    ok(rows(bo).some((l) => l.includes(c.rule)), c.cr + ' ' + c.name + ' bad: flagged by "' + c.rule + '"');
    if (c.cr === 'CR-70') { const [, po] = run(path.join(crRoot, c.bad), projFilters, projScope); ok(rows(po).some((l) => l.includes(c.rule)), c.cr + ' ' + c.name + ' bad: still flagged WITH the project filters and scope (a filter or scope that hides a user case fails)'); }
    ok(gc === 0 && /Deviations: \*\*0\*\*/.test(go), c.cr + ' ' + c.name + ' good: corrected twin is clean' + (gc ? ' [' + rows(go).slice(2, 4).join(' / ').slice(0, 300) + ']' : ''));
  }
  // Wave 7 (owner K). The older fixtures run with --currency '' (CR-72 off) so they stay byte-identical; the CR-72 fixtures run with the default symbols.
  const w7 = path.join(here, 'fixtures', 'w7'), D = { density: '2.625', sep: '' }, DC = { density: '2.625', currency: '€$£', sep: '' };
  // (1) per-line heights above font scale 1.3: the 3 real Mese defects still flag, the 4 wrapped-subtitle artefacts are clean (group = all phone-fs2-* dumps of the wave 6 device run).
  { const [, o] = run(path.join(w7, 'fs2-09g'), projFilters, projScope, D), r = rows(o);
    ok(r.some((l) => l.includes('phone-fs2-mese') && l.includes('page-header page-title height') && l.includes('Novembre')), 'fs2 per-line: a two-line page-title (Mese, 82.3 vs 40.8 dp) still flags (must-flag)');
    ok(r.some((l) => l.includes('phone-fs2-mese') && l.includes('page-header page-subtitle y')), 'fs2 per-line: the subtitle pushed down by the two-line title (y 116.2 vs 74.7) still flags (must-flag)');
    ok(r.some((l) => l.includes('phone-fs2-mese') && l.includes('period-title height')), 'fs2 per-line: a two-line period-title (82.3 vs 40.8 dp) still flags (must-flag)');
    for (const sc of ['top-agente', 'top-futuro', 'top-pilastri']) ok(!r.some((l) => l.includes('phone-fs2-' + sc) && l.includes('page-subtitle height')), 'fs2 per-line: ' + sc + ' wrapped page-subtitle (27 vs 55.2 dp box, one vs two lines) is clean');
    ok(!r.some((l) => l.includes('phone-fs2-top-spese') && l.includes('tallest text of the page header')), 'fs2 per-line: top-spese two-line subtitle is not taller than the period-title (CR-71) (clean)');
    const dr = r.filter((l) => l.startsWith('| phone-') && !/CR-78|CR-80|CR-01, user rule|gutter: block/.test(l)), mr = dr.filter((l) => l.includes('phone-fs2-mese')), tr = dr.filter((l) => /CR-74/.test(l)); // data rows only: header/separator rows also start with '|'
    ok(new RegExp('Deviations: \\*\\*' + (4 + r.filter((l) => /CR-78|CR-80|CR-01, user rule|gutter: block/.test(l)).length) + '\\*\\*').test(o) && dr.length === 4 && mr.length === 3 && dr.length - mr.length === tr.length && tr.length === 1 && tr[0].includes('phone-fs2-top-pilastri') && tr[0].includes('Versato tuo'), 'fs2 per-line: exactly 4 data rows on the wave 6 fs2 group: the 3 Mese rows (title height, subtitle y, period-title height) plus the one CR-74 row of the real Versato tuo / help-control overlap on top-pilastri (wave 9: that dump is the user case)'); }
  // (2) U+00A0 / U+202F / U+2007 read as U+0020: the CR-70 user fixtures with a no-break space before the symbol are still flagged, with and without the project filters and scope; a filter still matches.
  { const nbd = path.join(w7, 'nbsp'), pr = (d) => run(path.join(nbd, d), projFilters, projScope)[1];
    ok(rows(run(path.join(nbd, 'cr70-amount-429-twice-bad'))[1]).some((l) => l.includes('429 €') && /CR-70/.test(l)), 'nbsp: "429 €" with U+00A0 in two nodes is still flagged');
    ok(rows(pr('cr70-amount-429-twice-bad')).some((l) => /CR-70/.test(l)), 'nbsp: ... and still flagged WITH the project filters and scope');
    ok(rows(run(path.join(nbd, 'cr70-flusso-441-twice-bad'))[1]).some((l) => l.includes('441 €') && /CR-70/.test(l)), 'nbsp: the Flusso "441 €" repeated under two labels (U+00A0) is still flagged');
    ok(rows(pr('cr70-flusso-441-twice-bad')).some((l) => /CR-70/.test(l)), 'nbsp: ... and still flagged WITH the project filters and scope');
    const nb = run(path.join(nbd, 'dup-bad'))[1]; ok(dupRows(nb).some((l) => l.includes('"Spese 441 €"') && l.includes('"dal saldo 441 €"')) && dupRows(nb).some((l) => l.includes('"Versato tuo nel mese 150 €"')), 'nbsp: the dup/bad amounts joined by U+00A0 are still flagged (same rows as with U+0020)');
    const nf = run(path.join(nbd, 'dup-bad-filtered'), path.join(nbd, 'dup-bad-filtered', 'filters.json'))[1];
    ok(!dupRows(nf).some((l) => l.includes('"budget n/d" + "budget n/d"')) && dupRows(nf).some((l) => l.includes('"Spese 441 €"')) && /\| 2 \|/.test(nf), 'nbsp: a filter keeps silencing exactly its own element on a dump with U+00A0, every other repetition stays flagged'); }
  // (3) CR-72: a figure and its currency symbol are never split by a breakable space.
  { const [, bo] = run(path.join(w7, 'currency-split', 'bad'), '', '', DC), [, go] = run(path.join(w7, 'currency-split', 'good'), '', '', DC);
    ok(rows(bo).some((l) => l.includes('(CR-72)') && l.includes('dal saldo 441 €')), 'CR-72 bad: the real Fold Flusso node "dal saldo 441 €" (plain space; it wrapped leaving the symbol alone) is flagged');
    ok(!rows(go).some((l) => l.includes('(CR-72)')), 'CR-72 good: the same dump with U+00A0 between every figure and symbol raises no CR-72 row'); }
  // Visual integrity (K13, user 2026-11-09 "invisible left ledge"): the generic fixtures (design-method/scripts/addons/visual-integrity.mjs) run through THIS checker with the project config.
  { const { viFixtures, VI_CONFIG } = await import('../design-method/scripts/addons/visual-integrity.mjs'), vroot = path.join(here, 'fixtures', 'vi');
    fs.rmSync(vroot, { recursive: true, force: true }); viFixtures(vroot); fs.writeFileSync(path.join(vroot, 'config.json'), JSON.stringify(VI_CONFIG));
    const vr = (d) => { try { return execFileSync(process.execPath, [fileURLToPath(import.meta.url), path.join(vroot, d), '--density', '1', '--separator-roles', '', '--exceptions', '/nonexistent', '--duplicate-filters', '/nonexistent', '--duplicate-scope', '/nonexistent', '--visual-integrity', path.join(vroot, 'config.json'), '--no-aligned-to-nothing'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }); } catch (e) { return String(e.stdout || ''); } };
    const vi = (d) => vr(d).split(String.fromCharCode(10)).filter((l) => /^\| (narrow|wide)/.test(l) && /clip-edge|overlap: two elements|hidden:/.test(l));
    ok(vi('vi-clip-rest').some((l) => /clip-edge: text is cut on its left/.test(l) && /9 ott/.test(l)), 'visual-integrity bad (rest): a title with its first glyph cut by an invisible edge is flagged');
    ok(vi('vi-clip-wobble').some((l) => /animation frame.*\[2 frames, first narrow-clip-f01\]/.test(l)) && vi('vi-clip-wobble').every((l) => /animation frame/.test(l)), 'visual-integrity bad (wobble): the cut that exists only in spring frames f01-f02 is flagged once; the clean rest dump is not');
    ok(vi('vi-overlap').some((l) => /Versato tuo/.test(l)), 'visual-integrity bad: two unrelated texts that intersect are flagged');
    ok(vi('vi-hidden').length === 3, 'visual-integrity bad: alpha 0, zero size and clipped-away text are flagged');
    ok(vi('vi-end-bad').some((l) => /under the fixed dock/.test(l)), 'visual-integrity bad: last content under the dock at scroll end is flagged');
    ok(vi('vi-peek-good').length === 0 && vi('vi-scroll-good').length === 0, 'visual-integrity good: a declared peek and a scrolled list above the dock are not flagged'); }
  // Aligned to nothing (K14, user 2026-11-09): the generic fixtures (design-method/scripts/addons/aligned-to-nothing.mjs) run through THIS checker.
  { const { antFixtures, ANT_CONFIG } = await import('../design-method/scripts/addons/aligned-to-nothing.mjs'), aroot = path.join(here, 'fixtures', 'ant');
    fs.rmSync(aroot, { recursive: true, force: true }); antFixtures(aroot); fs.writeFileSync(path.join(aroot, 'config.json'), JSON.stringify(ANT_CONFIG));
    const ar = (d) => { try { return execFileSync(process.execPath, [fileURLToPath(import.meta.url), path.join(aroot, d), '--density', '1', '--separator-roles', '', '--exceptions', '/nonexistent', '--duplicate-filters', '/nonexistent', '--duplicate-scope', '/nonexistent', '--no-visual-integrity', '--no-heading-levels', '--aligned-to-nothing', path.join(aroot, 'config.json')], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }); } catch (e) { return String(e.stdout || ''); } };
    const an = (d) => ar(d).split(String.fromCharCode(10)).filter((l) => /^\| narrow/.test(l) && /header-centre|aligned to nothing/.test(l));
    ok(an('ant-header-bad').some((l) => /header-centre: page-trailing is centred on the page-title/.test(l)), 'aligned-to-nothing bad: a trailing control whose centre-y differs from the title centre-y is flagged (the "?" case)');
    ok(an('ant-lone-bad').some((l) => /aligned to nothing/.test(l) && /Nota/.test(l)) && an('ant-lone-bad').length === 1, 'aligned-to-nothing bad: a text with no line shared with any other element is flagged, and only it');
    ok(an('ant-good').length === 0, 'aligned-to-nothing good: centred header, aligned rows and a declared lone chart point are not flagged'); }
    // K14 real case: the Spese dump of 2026-11-09 (09k) whose trailing "?" sat 9 dp below the page-title centre must flag with the PROJECT config.
  { const rb = path.join(here, 'fixtures', 'ant-real', 'bad'); let ro = ''; try { ro = execFileSync(process.execPath, [fileURLToPath(import.meta.url), rb, '--density', '2.625', '--no-visual-integrity', '--no-heading-levels', '--exceptions', '/nonexistent'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }); } catch (e) { ro = String(e.stdout || ''); }
    ok(/header-centre: page-trailing is centred on the page-title/.test(ro), 'aligned-to-nothing must-flag: the real 09k Spese dump (the "?" below the title centre) is flagged by the project config'); }
  // K23 sibling actions (user 2026-11-10, "Apri il link"), K18 work in progress (G-16), K22 keyboard return (G-15): the generic add-on fixtures plus the real dumps through THIS project's config (tools/device-addons.json).
  { const { siblingActions: sa, fixtures: sf } = await import('../design-method/scripts/addons/sibling-actions.mjs'), { workInProgress: wp, fixtures: wf } = await import('../design-method/scripts/addons/work-in-progress.mjs'), { keyboardReturn: kr, fixtures: kf } = await import('../design-method/scripts/addons/keyboard-return.mjs'), { readDump: rd } = await import('../design-method/scripts/addons/device-capture.mjs');
    const cfg = JSON.parse(fs.readFileSync(path.join(here, 'device-addons.json'), 'utf8')), sdir = path.join(here, 'fixtures', 'sibling'), s1 = sf(), w1 = wf(), k1 = kf();
    ok(sa(s1.bad, s1.cfg).length === 2 && sa(s1.good, s1.cfg).length === 0, 'sibling-actions: synthetic Apri il link pills flagged (width and narrow), approved paste page clean');
    for (const c of ['fold', 'phone']) {
      const bad = sa(rd(path.join(sdir, 'bad', c + '-setup-eb-2.xml'), 2.625), cfg.siblingActions), good = sa(rd(path.join(sdir, 'good', c + '-setup-eb-2-paste.xml'), 2.625), cfg.siblingActions);
      ok(bad.some((x) => x.rule === 'sibling-actions-width') && bad.some((x) => x.rule === 'sibling-actions-narrow'), 'sibling-actions must-flag: the real ' + c + ' "Apri il link" dump (Rimanda / Incolla il link pills) is flagged for width and narrowness');
      ok(good.length === 0, 'sibling-actions must-pass: the real ' + c + ' "Incolla il link" page the user approved is not flagged'); }
    { const { confirmRight: cr2, fixtures: cf } = await import('../design-method/scripts/addons/confirm-right.mjs'), c1 = cf();
      ok(cr2(c1.bad, cfg.confirmRight).some((x) => x.rule === 'confirm-right') && cr2(c1.good, cfg.confirmRight).length === 0, 'confirm-right must-flag: "Riprova" (action-confirm) left of "Apri la pagina" on the cover Approva in banca row is flagged; the swapped row (confirm rightmost) is clean (CR-96)'); }
    ok(wp(w1.bad, w1.entry).length === 1 && wp(w1.good, w1.entry).length === 0 && wp(w1.spinner, w1.entry).length === 0, 'work-in-progress: "Link valido" + disabled "Continua" without progress is flagged; working line or spinner is clean');
    ok(cfg.workInProgress.length > 0 && wp(w1.bad, cfg.workInProgress[0]).length === 1, 'work-in-progress must-flag: the Incolla il link case through the project config');
    { const { sourcePatterns: sp } = await import('../design-method/scripts/addons/source-patterns.mjs'), spc = JSON.parse(fs.readFileSync(path.join(here, 'source-patterns.json'), 'utf8')).sourcePatterns, sfx = path.join(here, 'fixtures', 'source-patterns');
      ok(sp([path.join(sfx, 'bad')], spc).length === 1 && sp([path.join(sfx, 'good')], spc).length === 0, 'source-patterns must-flag: the ime-inset visibility gate (user case, G-15) is flagged; the shared-helper signal is clean'); }
    ok(kr(k1.good, k1.entry).length === 0 && kr(k1.bad, k1.entry).length === 2, 'keyboard-return: a dock that follows the keyboard is clean; one that appears after it is late and queued'); }
  process.exit();
}

import { alignedToNothing, REST_ONLY, DEFAULTS as ANT_DEFAULTS } from '../design-method/scripts/addons/aligned-to-nothing.mjs';
import { rangeEndEdges, matchesScreen as peekMatches } from '../design-method/scripts/addons/peek-visible.mjs';   // CR-62 takes the page edges at a range end from the same function as CR-89
import { deviceRows, PAIR_RE } from '../design-method/scripts/addons/device-capture.mjs';   // wave-15 add-ons: state-stability CR-83, safe-area CR-85, action-outcome CR-86 (config tools/device-addons.json)
import { visualIntegrity, FRAME_RE } from '../design-method/scripts/addons/visual-integrity.mjs';   // generic rules (SKILL section 2 K13); the project values are in visual-integrity.json
const dp = (px) => px / density;
// Unicode-space normalisation (wave 7): U+00A0, U+202F, U+2007 are read as U+0020 by every text match (duplicate filters and comparison, value tokens, exception matching). The raw text is kept for the
// currency-split rule only. Not a loosening: a figure joined to its symbol by a no-break space still compares and filters exactly like one joined by a plain space.
const uniSpace = (t) => t.replace(/[   ]/g, ' ');
const HEADING_LEVELS = (() => { try { return JSON.parse(fs.readFileSync(path.join(here, 'heading-levels.json'), 'utf8')); } catch { return {}; } })();
const TEXT_TEMPLATES = (() => { try { return JSON.parse(fs.readFileSync(path.join(here, 'text-templates.json'), 'utf8')); } catch { return {}; } })();
const SEP_ROLES = opt('separator-roles', '*').split(',').filter(Boolean);   // CR-75 roles ('*' = every visible text, wave 10: every "a · b" label, not only page subtitles); the older self-test fixtures (dumps of builds with plain spaces) run with --separator-roles ''
const CURRENCY = opt('currency', '€$£');
const WRAP_ABOVE = 1.3, WRAP_ROLES = new Set(['page-subtitle', 'page-trailing', 'chip']);   // G-12: above font scale 1.3 these header texts may wrap, so box heights are compared per line
const r1 = (v) => Math.round(v * 10) / 10;

// Visual-integrity attributes of the in-app dump (capture-all v2/v3): layout-bounds (unclipped box) -> lb, alpha, scroll-end; `hidden-node` elements are read only by parseHidden.
function viExtras(a) {
  const o = {}, m = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a['layout-bounds'] || '');
  if (m) o.lb = { l: dp(+m[1]), t: dp(+m[2]), r: dp(+m[3]), b: dp(+m[4]) };
  if (a.alpha !== undefined) o.alpha = +a.alpha;
  if (a['scroll-end'] === 'true') o.scrollEnd = true;
  return o;
}
function parseHidden(file) {
  const out = [];
  for (const m of fs.readFileSync(file, 'utf8').matchAll(/<hidden-node\b([^>]*?)\/?>/g)) {
    const a = {}; for (const k of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) a[k[1]] = k[2];
    const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds || ''); if (!b) continue;
    out.push({ id: (a['resource-id'] || '').split('/').pop(), text: uniSpace((a.text || a['content-desc'] || '').replace(/&amp;/g, '&')), vis: (a.text || '').trim().length, clickable: false, scroll: false, l: dp(+b[1]), t: dp(+b[2]), r: dp(+b[3]), b: dp(+b[4]), ...viExtras(a) });
  }
  return out;
}
function parse(file) {
  const xml = fs.readFileSync(file, 'utf8');
  const nodes = [];
  for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
    const a = {};
    for (const k of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) a[k[1]] = k[2];
    const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds || '');
    if (!b) continue;
    const id = (a['resource-id'] || '').split('/').pop();
    nodes.push({ id, raw: (a.text || '').replace(/&amp;/g, '&'), text: uniSpace((a.text || a['content-desc'] || '').replace(/&amp;/g, '&')), vis: (a.text || '').trim().length, clickable: a.clickable === 'true', scroll: a.scrollable === 'true',
      l: dp(+b[1]), t: dp(+b[2]), r: dp(+b[3]), b: dp(+b[4]), ...viExtras(a) });
  }
  return nodes.filter((n) => n.r - n.l > 0.5 && n.b - n.t > 0.5);
}

const allFiles = dir ? fs.readdirSync(dir).filter((f) => f.endsWith('.xml')).sort() : [];
const files = allFiles.filter((f) => !PAIR_RE.test(f.replace(/\.xml$/, '')) && !f.endsWith('-moving.xml') && !f.includes('-hold-') && !FRAME_RE.test(f.replace(/\.xml$/, '')));   // hold dumps are mid-drag (CR-73), not screens at rest
if (!files.length && !pairArg.length && !allFiles.some((f) => f.includes('-hold-'))) { console.error('no .xml dumps in ' + dir); process.exit(2); }
// A pane keeps the previous page composed underneath the new one (back reveals it). Dumps list nodes in draw order, so every node preceding a LATER
// `page-content` of another title, inside that page's pane and bounds, belongs to the covered layer and is not visible. Same title twice (nested tag) is not a layer.
function visibleNodes(all) {
  const pcs = all.map((n, i) => [n, i]).filter(([n]) => n.id === 'page-content');
  if (pcs.length < 2) return all;
  const covered = new Set();
  for (const [pc, i] of pcs) for (const [later, j] of pcs) {
    if (j <= i || later.text === pc.text || !later.text.trim()) continue;
    const cxL = (later.l + later.r) / 2;
    if (Math.abs(later.l - pc.l) >= 2 || !(cxL > pc.l && cxL < pc.r + 1)) continue;
    for (let k = i; k < j; k++) { const n = all[k]; const cx = (n.l + n.r) / 2; if (!n.id.startsWith('pane-') && cx >= later.l && cx <= later.r + 1) covered.add(k); }
  }
  return all.filter((_, k) => !covered.has(k));
}
const screens = files.map((f) => {
  const name = f.replace(/\.xml$/, '');
  const nodes = visibleNodes(parse(path.join(dir, f)));
  const W = Math.max(...nodes.map((n) => n.r), 0);
  const H = Math.max(...nodes.map((n) => n.b), 0);
  // The scroll viewport (a vertical scrollable that spans a pane) ends above the screen bottom (system bar / taskbar band, floating dock): cards cut there are clipped, not short.
  const V = Math.min(H, ...nodes.filter((n) => n.scroll && n.b - n.t >= H * 0.4).map((n) => n.b));
  const VT = Math.max(0, Math.min(...nodes.filter((n) => n.scroll && n.b - n.t >= H * 0.4).map((n) => n.t), H));   // top of the scroll viewport (0 when it spans the window)
  return { name, layout: name.split('-')[0], top: name.includes('-top-'), fs: name.includes('-fs2') ? 'fs2' : '', scale: name.includes('-fs2') ? 2 : 1, nodes, W, H, V, VT };   // fs2: captured at system font scale 2.0 (own measurement group, own CR-56 thresholds)
});

// Per-line heights above font scale 1.3 (wave 7, loosening wrong by construction): a text role the spec lets wrap has no comparable box height, so its height is divided by its line count
// (round(height / smallest single-line height of that role at that layout and scale)). page-title and period-title stay one line (K2) and keep the box-height comparison.
const oneLine = new Map();
for (const s of screens) if (s.scale > WRAP_ABOVE) for (const n of s.nodes) if (WRAP_ROLES.has(n.id) && n.vis) { const k = [s.layout, s.fs, n.id].join('|'); oneLine.set(k, Math.min(oneLine.get(k) ?? Infinity, n.b - n.t)); }
const perLine = (s, n) => { const h = n.b - n.t, one = oneLine.get([s.layout, s.fs, n.id].join('|')); return s.scale > WRAP_ABOVE && WRAP_ROLES.has(n.id) && one ? h / Math.max(1, Math.round(h / one)) : h; };
const inside = (a, c) => a.l >= c.l - 0.5 && a.t >= c.t - 0.5 && a.r <= c.r + 0.5 && a.b <= c.b + 0.5;
const area = (n) => (n.r - n.l) * (n.b - n.t);
const label = (n) => (n.text || '').slice(0, 28);
const MON = { gennaio: 1, febbraio: 2, marzo: 3, aprile: 4, maggio: 5, giugno: 6, luglio: 7, agosto: 8, settembre: 9, ottobre: 10, novembre: 11, dicembre: 12, gen: 1, feb: 2, mar: 3, apr: 4, mag: 5, giu: 6, lug: 7, ago: 8, set: 9, sett: 9, ott: 10, nov: 11, dic: 12 };
const MRE = Object.keys(MON).sort((a, b) => b.length - a.length).join('|'), FULL = Object.keys(MON).filter((k) => k.length > 4).join('|');
const SEP = '(?:–|—|-|→|\\bal\\b)';
const HEADER_IDS = ['page-title', 'page-subtitle', 'page-trailing', 'chip', 'card-title', 'period-title'];
const TOKEN = /(?<![\d.,])[−–+$£-]*(\d[\d.,:/]*)(?:\s*(€|%|[a-zà-ÿ]{2,}))?/gi, UNIT_STOP = new Set(['di', 'da', 'al', 'e', 'a', 'il', 'in', 'su', 'con', 'per', 'of', 'to', 'at', 'on', 'the', 'and']);
const globRe = (g) => new RegExp('^' + g.split('*').map((x) => x.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$');
const dupHits = new Map();   // filter -> { n: elements silenced, screens }; printed in the report so a filter can never hide a row silently
const scopeFile = opt('duplicate-scope', path.join(here, 'duplicate-scope.json')), dupScope = fs.existsSync(scopeFile) ? JSON.parse(fs.readFileSync(scopeFile, 'utf8')) : null;   // screens the duplicate check (CR-70) applies to; absent = every screen
const dupScoped = (name) => !dupScope || !dupScope.screens || dupScope.screens.some((g) => globRe(g).test(name));
let dupSkipped = 0;
const dupFile = opt('duplicate-filters', path.join(here, 'duplicate-filters.json')), dupFilters = fs.existsSync(dupFile) ? JSON.parse(fs.readFileSync(dupFile, 'utf8')).filter((f) => f && f.screen) : [];


// measurements: { rule, layout, screen, el, value, group }
const M = [];
const direct = [];   // deviations against a token or a structural rule: no majority involved
const tok = (rule, s, n, value, expected, note = '') => { if (Math.abs(value - expected) > TOL) direct.push({ rule, layout: s.layout, screen: s.name, el: label(n) || n.id, value: r1(value), expected: r1(expected), group: 'token', note }); };
const add = (rule, s, n, value, group = '') => M.push({ rule, layout: s.layout, screen: s.name, el: label(n), value: r1(value), group, fs: s.fs });

for (const s of screens) {
  // Wave 11a (CR-83): on a month page the period IS the page title (same node bounds, period-title level, 22 sp): it is not a page heading (28 sp) and is measured as the period-title.
  const isPeriodTitle = (n) => !s.fs && n.id === 'page-title' && s.nodes.some((p) => p.id === 'period-title' && Math.abs(p.l - n.l) < 1 && Math.abs(p.t - n.t) < 1 && Math.abs(p.b - n.b) < 1);
  const by = (id) => s.nodes.filter((n) => n.id === id && !isPeriodTitle(n));
  // Peek cards (the clipped neighbours of a swipe strip) are narrower than 40% of the widest card: not measured.
  const allCards = by('card'), widest = Math.max(0, ...allCards.map((c) => c.r - c.l));
  const cards = allCards.filter((c) => c.r - c.l >= widest * 0.4);
  const enclosing = (n) => cards.filter((c) => c !== n && inside(n, c)).sort((a, b) => area(a) - area(b))[0];
  const pane = (n) => (s.layout === 'fold' ? ((n.l + n.r) / 2 < s.W / 2 ? 'L' : 'R') : 'P');
  // The scroll viewport clips a card that ends at the bottom of the screen: its lower rows are not measurable.
  const frame = s.nodes.filter((n) => ['dock', 'island', 'gear'].includes(n.id));
  const clipped = (c) => c.b >= s.V - 0.5 || frame.some((x) => c.b >= x.t - 1 && c.t < x.t && c.l < x.r && c.r > x.l);   // cut by the viewport, or by the floating dock / island drawn over its bottom edge (a card that passes UNDER the dock loses its lower rows from the dump: wave 3 C, fs2 Impostazioni)

  // card-title: inset from its card (top, left), height; header row: title centre vs card top, trailing centre vs title centre
  const titles = by('card-title'), trails = by('card-trailing');
  const VT0 = Math.max(0, ...s.nodes.filter((n) => n.scroll && n.b - n.t >= s.H * 0.4).map((n) => n.t));   // top of the scroll viewport: a card starting there is cut (scrolled under the page header)
  for (const t of titles) {
    const c = enclosing(t); if (!c || clipped(c) || (VT0 > 0 && c.t <= VT0 + 0.5)) continue;
    add('card-title top inset', s, t, t.t - c.t);
    add('card-title left inset', s, t, t.l - c.l);
    add('card-title height', s, t, t.b - t.t);
    add('card-header: title centre below card top', s, t, (t.t + t.b) / 2 - c.t);
    const tr = trails.find((x) => enclosing(x) === c && Math.abs((x.t + x.b) / 2 - (t.t + t.b) / 2) < 40);
    if (tr) add('card-header: trailing centre minus title centre', s, tr, ((tr.t + tr.b) / 2) - ((t.t + t.b) / 2), 'zero');
  }
  // card inner padding: last visible content (texted nodes and `chart` nodes; not the card's own boxes) vs the card, against the token
  const chrome = s.nodes.filter((n) => ['dock', 'island', 'gear'].includes(n.id));   // the floating frame overlays cards: not card content
  for (const c of cards) {
    const ch = c.b - c.t;
    const raw = s.nodes.filter((n) => n !== c && !['card', 'content-row', 'accent-card', 'attention-row', 'dock-item'].includes(n.id) && inside(n, c) && !chrome.some((x) => inside(n, x)) &&
      (n.id === 'chart' || n.id === 'icon-disc' || n.text.trim() !== '') && !(n.vis === 0 && n.id !== 'chart' && (n.r - n.l) >= (c.r - c.l) * 0.9) && (n.id === 'chart' || n.id === 'icon-disc' || (n.b - n.t) < ch * 0.9) &&
      !(n.clickable && (n.r - n.l) >= (c.r - c.l) * 0.9) &&   // a full-width clickable row is a container, not content
      !s.nodes.some((x) => x.clickable && x !== n && (x.r - x.l) >= (c.r - c.l) * 0.9 && (x.b - x.t) < ch * 0.9 && inside(x, c) && Math.abs(x.l - n.l) < 1 && Math.abs(x.r - n.r) < 1 && Math.abs(x.t - n.t) < 1 && Math.abs(x.b - n.b) < 1));   // a texted node with the SAME bounds as its full-width clickable row is that row (merged label)
    // Measure the OUTERMOST content child: text inside a chip is represented by the chip box; text inside a full-width clickable row only counts for left/right.
    const boxes = s.nodes.filter((n) => (n.id === 'chip' || n.id === 'pill') && n !== c && inside(n, c));
    const rowTargets = s.nodes.filter((n) => n.clickable && n.id !== 'pill' && n.id !== 'chip' && (n.r - n.l) >= (c.r - c.l) * 0.9 && (n.b - n.t) < ch * 0.9 && inside(n, c));   // a full-width PillButton is a button (its label is content for the bottom inset), not a list row (wave 3 C, Setup "Configura")
    const outer = (k) => boxes.filter((x) => inside(k, x)).sort((a, b) => area(b) - area(a))[0] || k;
    const kids = raw.map(outer);
    if (!kids.length) continue;
    const bottomKids = raw.filter((k) => !rowTargets.some((r) => inside(k, r))).map(outer);
    const narrow = kids.filter((k) => (k.r - k.l) < (c.r - c.l) * 0.98);   // a full-width node (rule, bar) says nothing about the side inset
    const side = narrow.length ? narrow : kids;
    // Right inset: only content that spans the card (chart, trailing text, wide rows); short left-aligned text says nothing about it.
    const abuts = (k) => kids.some((o) => o !== k && Math.abs(o.t - k.t) < 2 && Math.abs(o.b - k.b) < 2 && o.l >= k.r - 0.5 && o.l - k.r < 1);   // a label slot (weight 1f) ends where its value starts: its box edge is not the content edge
    // Wave 2 fix (item 6, Spese "Da controllare" card read 119.6 dp): a text box that is merely wide (wraps its text, >= half the card) does not span the card; the real right
    // edge of padded content is the card-body (rule CR-65). Content "spans" when it is a chart, a trailing value, or ends within 30 dp of the card edge (right-aligned / fill).
    const wide = side.filter((k) => k.id === 'chart' || k.id === 'card-trailing' || (c.r - k.r) < 30);
    // Artefact: a card cut by the viewport bottom keeps a sliver of its first row, maybe only the right-aligned value; nodes cut by the viewport do not measure the left inset.
    const sideKids = side.filter((k) => !(clipped(c) && k.b >= s.V - 0.5));
    const bot = bottomKids.length ? Math.max(...bottomKids.map((k) => k.b)) : null, lft = sideKids.length ? Math.min(...sideKids.map((k) => k.l)) : null, rgt = Math.max(...wide.map((k) => k.r));
    if (!clipped(c) && bot !== null) tok('card-inset bottom (last content to card bottom)', s, bottomKids.find((k) => k.b === bot), c.b - bot, CARD_INSET);
    if (lft !== null) tok('card-inset left (content to card)', s, sideKids.find((k) => k.l === lft), lft - c.l, CARD_INSET);
    if (wide.length) tok('card-inset right (content to card)', s, wide.find((k) => k.r === rgt), c.r - rgt, CARD_INSET);
  }
  // CR-65 (wave 2 rule 1): card-body 14 dp from its card on all four sides; first / last visible content touches body top / bottom / start, end when it spans.
  // R2a: (1) a titled card's first content is the fixed 32 dp `card-header` row (K3), not the title's line box; a dump from before the tag existed gets the header synthesised
  // from its card-title (centre -+ 16 dp, body width; `legacy`), never when the screen has any card-header. (2) ROW cards (`card-body-rows`: rows carry their own 10 dp inset and
  // 48 dp minimum height, text may follow an untagged leading glyph) are measured by the header and the end only; the body inset itself is still asserted. (3) A card cut at the
  // top of the scroll viewport or the bottom (viewport, dock, island) is not measurable on that side. (4) `end` applies to content that spans: chart, header, trailing, or ends within 30 dp.
  const hasHeaderTag = by('card-header').length > 0;
  const VT = Math.max(0, ...s.nodes.filter((n) => n.scroll && n.b - n.t >= s.H * 0.4).map((n) => n.t));
  for (const b of [...by('card-body'), ...by('card-body-rows')]) {
    const c = allCards.filter((x) => x !== b && inside(b, x)).sort((p, q) => area(p) - area(q))[0]; if (!c) continue;
    // a body that starts at or below the floating dock / island (visible only as the clipped remainder) is outside the viewport: not measurable
    if (frame.some((x) => b.t >= x.t - 0.5 && b.l < x.r && b.r > x.l)) continue;
    // a sliver (body shorter than any text line, 12 dp) is a card cut by the viewport / dock: no side of it is measurable
    const sliver = b.b - b.t < 12, rows = b.id === 'card-body-rows', cutB = sliver || clipped(c), cutT = sliver || (c.t <= VT + 0.5 && VT > 0), nm = label(b) || b.id;
    tok('card-body: inset left is 14 dp (CR-65)', s, b, b.l - c.l, CARD_INSET); tok('card-body: inset right is 14 dp (CR-65)', s, b, c.r - b.r, CARD_INSET);
    if (!cutT) tok('card-body: inset top is 14 dp (CR-65)', s, b, b.t - c.t, CARD_INSET);
    if (!cutB) tok('card-body: inset bottom is 14 dp (CR-65)', s, b, c.b - b.b, CARD_INSET);
    let headers = s.nodes.filter((n) => n.id === 'card-header' && inside(n, b));
    if (!hasHeaderTag) headers = s.nodes.filter((n) => n.id === 'card-title' && inside(n, b)).map((t) => ({ id: 'card-header', text: t.text, vis: 1, l: b.l, r: b.r, t: (t.t + t.b) / 2 - 16, b: (t.t + t.b) / 2 + 16, legacy: true }));
    const bxs = s.nodes.filter((n) => (n.id === 'chip' || n.id === 'pill') && inside(n, b));
    const inHeader = (n) => headers.some((h) => n.l >= h.l - 0.5 && n.r <= h.r + 0.5 && n.t >= h.t - 0.5 && n.b <= h.b + 0.5);
    const raw = s.nodes.filter((n) => n !== b && !['card', 'card-body', 'card-body-rows', 'card-header', 'content-row', 'accent-card', 'attention-row', 'dock-item', 'action-row', 'page-content'].includes(n.id) && inside(n, b) && !frame.some((x) => inside(n, x)) && !inHeader(n) &&
      (['chart', 'icon-disc', 'pill', 'chip'].includes(n.id) || n.text.trim() !== '') && (n.id === 'chart' || (n.b - n.t) < (b.b - b.t) * 0.9));
    const body = [...new Set(raw.map((k) => bxs.filter((x) => inside(k, x)).sort((p, q) => area(q) - area(p))[0] || k))];
    const kids = [...headers, ...(rows ? [] : body)];
    if (!kids.length) continue;
    const touch = (what, v, k) => tok('card-body: first / last content touches the body ' + what + ' (CR-65)', s, k, v, 0, k.legacy ? 'legacy header' : '');
    const top = kids.reduce((p, q) => (q.t < p.t ? q : p)); if (!cutT) touch('top', top.t - b.t, top);
    if (!cutB && !rows) { const bt = kids.reduce((p, q) => (q.b > p.b ? q : p)); touch('bottom', b.b - bt.b, bt); }
    const sk = kids.filter((k) => !(cutB && k.b >= s.V - 0.5)); if (sk.length && !rows) { const st = sk.reduce((p, q) => (q.l < p.l ? q : p)); touch('start', st.l - b.l, st); }
    const sp = [...headers, ...body.filter((k) => k.id === 'chart' || k.id === 'card-trailing' || (b.r - k.r) < 30)]; if (sp.length) { const en = sp.reduce((p, q) => (q.r > p.r ? q : p)); touch('end', b.r - en.r, en); }
  }
  // CR-66 (wave 2 rule 2), dump counterpart: a dump has no text layout, so no overflow / ellipsis flag. (a) geometric: a texted node whose centre is inside a pill, chip or
  // card but whose box leaves it; (b) HEURISTIC: a single-line texted node inside a pill / chip / action-row / clickable narrower than 0.28 line-heights per character is truncated or squeezed.
  const guards = s.nodes.filter((n) => ['pill', 'chip', 'action-row'].includes(n.id) || n.clickable);
  for (const n of s.nodes) {
    if (!n.vis || ['pill', 'chip', 'card', 'action-row'].includes(n.id)) continue;
    for (const bx of s.nodes.filter((x) => ['pill', 'chip', 'card', 'action-row'].includes(x.id))) {
      const cx = (n.l + n.r) / 2, cy = (n.t + n.b) / 2;
      if (cx > bx.l && cx < bx.r && cy > bx.t && cy < bx.b && (n.l < bx.l - TOL || n.r > bx.r + TOL)) direct.push({ rule: `clip: text box wider than its ${bx.id} (CR-66)`, layout: s.layout, screen: s.name, el: label(n), value: r1(n.r - n.l), expected: r1(bx.r - bx.l), group: 'direct', note: '' });
    }
    const h = n.b - n.t, w = n.r - n.l;
    if (h <= 28 && n.text.length >= 4 && w < n.text.length * 0.28 * h && guards.some((g) => g !== n && inside(n, g) && (g.r - g.l) < s.W * 0.8)) direct.push({ rule: 'clip: text narrower than its label needs (heuristic, CR-66)', layout: s.layout, screen: s.name, el: label(n), value: r1(w), expected: r1(n.text.length * 0.28 * h), group: 'direct', note: 'heuristic' });
  }
  // CR-69 (F2b): two visible texts must not overlap (bounds intersect by more than 1 dp in both axes, clamped to the window; a pair where one text contains the other is one label seen twice).
  const tx = s.nodes.filter((n) => n.vis && n.text.trim() && !n.id.startsWith('period-page') && n.id !== 'page-content');
  for (let i = 0; i < tx.length; i++) for (let j = i + 1; j < tx.length; j++) {
    const a = tx[i], b = tx[j]; if (a.text.includes(b.text) || b.text.includes(a.text)) continue;
    const ix = Math.min(a.r, b.r, s.W) - Math.max(a.l, b.l, 0), iy = Math.min(a.b, b.b) - Math.max(a.t, b.t);
    if (ix > TOL && iy > TOL) direct.push({ rule: 'clip: texts overlap (CR-69)', layout: s.layout, screen: s.name, el: label(a) + ' / ' + label(b), value: r1(ix), expected: 0, group: 'direct', note: '' });
  }
  // CR-74 (user 2026-11-09, wave 8 low: the "Versato tuo" label ran 31 px under its own help button at font scale 2): a visible text never overlaps a small control. A "small control" =
  // a clickable node with no text of its own (a help / icon button), at most 56 dp on both sides. Only a PARTIAL intersection (> 1 dp in both axes) counts: a text fully inside a button is
  // the button's own label, a button fully inside a text box is an inline icon (loosening, wrong by construction: both are one control seen twice).
  for (const c of s.nodes.filter((n) => n.clickable && !n.vis && n.r - n.l <= 56 && n.b - n.t <= 56)) for (const a of tx) {
    const ix = Math.min(a.r, c.r, s.W) - Math.max(a.l, c.l, 0), iy = Math.min(a.b, c.b) - Math.max(a.t, c.t);
    if (ix <= TOL || iy <= TOL) continue;
    const aInC = a.l >= c.l - TOL && a.r <= c.r + TOL && a.t >= c.t - TOL && a.b <= c.b + TOL, cInA = c.l >= a.l - TOL && c.r <= a.r + TOL && c.t >= a.t - TOL && c.b <= a.b + TOL;
    if (aInC || cInA) continue;
    if (Math.abs((a.t + a.b) / 2 - (c.t + c.b) / 2) > 0.25 * Math.min(a.b - a.t, c.b - c.t)) continue;   // loosening (wrong by construction): the 48 dp touch box of a control reaches into the NEXT row; only a text on the control's own row (centres within a quarter of the smaller height) is a visual collision (fixture help-good / Futuro "4.500 €", 14 dp under a header control)
    direct.push({ rule: 'clip: a text overlaps a control (CR-74)', layout: s.layout, screen: s.name, el: label(a) + ' / ' + (c.text || 'control').slice(0, 20), value: r1(ix), expected: 0, group: 'direct', note: '' });
  }
  // CR-75 (user: a line never starts with the separator): a subtitle built from parts joined with " · " binds the dot to the part before it with a no-break space. A page-subtitle or
  // period-subtitle whose raw `text` has a breakable U+0020 right before "·" can wrap to a line that starts with "·". (A dump holds the whole string, not its lines: this is the source form.)
  for (const n of s.nodes.filter((x) => x.vis && (SEP_ROLES.includes('*') || SEP_ROLES.includes(x.id)) && / ·/.test(x.raw.replace(/[   ]·/g, '')))) direct.push({ rule: 'text: separator "·" after a breakable space, a wrapped line may start with it (CR-75)', layout: s.layout, screen: s.name, el: label(n), value: 1, expected: 0, group: 'direct', note: '' });
  // CR-80 (wave 9 review M3, hero columns misaligned): the hero's two columns (a primary-figure and a secondary-figure that share rows) put their label on one line: the stat-label directly under
  // each figure starts at the same y (+-1 dp). Pillar columns are not a pair: their figures sit below their labels and share no rows with the primary figure.
  for (const P of by('primary-figure')) for (const S of by('secondary-figure').filter((x) => x.t < P.b && x.b > P.t)) {
    const under = (f) => s.nodes.filter((n) => n.id === 'stat-label' && n.t >= f.b - 2 && n.t < f.b + 80 && Math.abs(n.l - f.l) < 24).sort((a, b) => a.t - b.t)[0];
    const lp = under(P), ls = under(S); if (!lp || !ls) continue;
    if (Math.abs(lp.t - ls.t) > TOL) direct.push({ rule: 'align: the hero columns put their labels on one line (CR-80)', layout: s.layout, screen: s.name, el: label(lp) + ' / ' + label(ls), value: r1(Math.abs(lp.t - ls.t)), expected: 0, group: 'direct', note: '' });
  }

  // CR-81 (user 2026-11-09: "se stesso elemento o stessa funzione ha testo diverso non va bene"): per group (roles x screens) every text is reduced to a template (numbers, dates, months -> placeholders, the " · Dati di esempio" suffix ignored); a template outside `allowed` is a deviation.
  for (const g of (TEXT_TEMPLATES.groups || [])) {
    const sc = new RegExp(g.screens), mt = new RegExp(g.match || '', 'iu'), ph = (TEXT_TEMPLATES.placeholders || []).map(([p, r]) => [new RegExp(p, 'giu'), r]);
    if (!sc.test(s.name)) continue;
    for (const n of s.nodes.filter((x) => x.vis && (g.roles.includes('*') || g.roles.includes(x.id)) && (x.text || '').trim())) {
      const base = (n.text || '').replace(/ /g, ' ').split(' · ')[0].trim(); if (!mt.test(base)) continue;
      let t = base; for (const [re, r] of ph) t = t.replace(re, r);
      if (!(t in (g.allowed || {}))) direct.push({ rule: 'text: ' + g.name + ' has a wording no other screen uses: "' + t + '" (CR-81, same element same wording)', layout: s.layout, screen: s.name, el: label(n), value: 1, expected: 0, group: 'direct', note: '' });
    }
  }
  // CR-82 (user 2026-11-09, on Spese "7 ott – 6 nov" over "dati dal 10 ott al 6 nov": "utente cosa capisce"): a header shows at most ONE date range per period. A period title that is itself a range
  // ("D mon – D mon") with a second range ("D mon – D mon", "dal D [mon] al D mon") in a subtitle / coverage line under it (same column, up to ~53 dp below) is a deviation.
  {
    const RANGE = new RegExp('\\d{1,2} (?:' + MRE + ') [–-] \\d{1,2} (?:' + MRE + ')|\\bdal \\d{1,2}(?: (?:' + MRE + '))? al \\d{1,2} (?:' + MRE + ')', 'iu');
    for (const t of by('period-title')) {
      const tt = s.nodes.filter((x) => x.vis && inside(x, t) && (x.text || '').trim()).map((x) => x.text).join(' ') + ' ' + (t.text || '');
      if (!RANGE.test(tt)) continue;
      for (const n of s.nodes.filter((x) => x.vis && ['period-coverage', 'period-subtitle', 'page-subtitle'].includes(x.id) && RANGE.test(x.text || '') && x.l < t.r && x.r > t.l && x.t >= t.t - 4 && x.t <= t.b + 140))
        direct.push({ rule: 'text: a header shows one date range per period, a second range sits under the title (CR-82)', layout: s.layout, screen: s.name, el: '"' + tt.trim().slice(0, 20) + '" + "' + label(n) + '"', value: 2, expected: 1, group: 'direct', note: '' });
    }
  }
  // CR-78 (user decision 2026-11-09): a month coverage label never reads "dati dal 1 al ..." (coverage that starts on the 1st is "dati al 6 nov").
  for (const n of s.nodes.filter((x) => x.vis && /dati dal 1 al(?=\s|$)/i.test(x.text || ''))) direct.push({ rule: 'text: a coverage label starting on the 1st reads "dati dal 1 al ..." (CR-78, write "dati al ...")', layout: s.layout, screen: s.name, el: label(n), value: 1, expected: 0, group: 'direct', note: '' });
  // CR-01 / CR-67 (USER RULE, 2026-11-08 + 2026-11-09 "ancora più di quella ... era ancora un po' troppo poco visibile"): the neighbour page of a pager shows >= 40 dp on the open Fold and >= 28 dp on the cover. Visible width = overlap with the viewport and the screen. Thresholds are the rows of tools/critique-rules.md (CR-01); the self-test pins them.
  for (const v of by('period-viewport')) for (const id of ['period-page-prev', 'period-page-next']) {
    const p = s.nodes.filter((n) => n.id === id && (n.t + n.b) / 2 > v.t && (n.t + n.b) / 2 < v.b).sort((a, b) => Math.abs((a.l + a.r) / 2 - (v.l + v.r) / 2) - Math.abs((b.l + b.r) / 2 - (v.l + v.r) / 2))[0]; if (!p) continue;
    const vis = Math.max(0, Math.min(p.r, v.r, s.W) - Math.max(p.l, v.l, 0)), min = s.layout === 'fold' ? CR01_PEEK_FOLD : CR01_PEEK_COVER;
    if (vis < min - TOL) direct.push({ rule: 'motion: neighbour page peek >= ' + min + ' dp (CR-01, user rule)', layout: s.layout, screen: s.name, el: id, value: r1(vis), expected: min, group: 'token', note: '' });
  }
  // page header
  // R2a: the Setup title is its own role (board 8a, 30 sp / 36): tag setup-title, measured among the Setup screens only
  for (const n of by('setup-title')) { add('page-header setup-title y', s, n, n.t, 'setup'); add('page-header setup-title height', s, n, n.b - n.t, 'setup'); }
  // A page header whose top sits ON the top of its page-content is cut by the window (the page was scrolled before the dump): its box is not the line box. One CAPTURE row, no measurements.
  const hdrCut = (n) => by('page-content').some((p) => (n.l + n.r) / 2 >= p.l && (n.l + n.r) / 2 <= p.r && n.t <= p.t + 0.5);
  const cutHdr = ['page-title', 'page-subtitle'].flatMap(by).filter(hdrCut);
  if (cutHdr.length) direct.push({ rule: 'capture: page header cut by the window top (page not at scroll 0)', layout: s.layout, screen: s.name, el: label(cutHdr[0]), value: r1(cutHdr[0].b - cutHdr[0].t), expected: 0, group: 'direct', note: 'capture' });
  for (const id of ['page-title', 'page-subtitle']) for (const n of by(id).filter(() => !cutHdr.length)) {
    const g = s.top ? 'top' + pane(n) : s.name.includes('-setup-') ? 'setup' : 'any';   // the Setup flow draws its back row and progress above the header: its own group   // on two panes each pane carries its own page header
    add('page-header ' + id + ' y', s, n, n.t, g);
    add('page-header ' + id + ' height', s, n, perLine(s, n), 'any');   // majority: line boxes in dumps are not the Tokens line heights
    if (s.top) add('page-header ' + id + ' x (top-level pages)', s, n, n.l, g);
  }
  // card edges per pane
  // K2 wave 6 (user 2026-11-08: "Ottobre è allineato a caso"): the G-11 narrowing is removed; cards inside a period-viewport sit on the same gutter line as every other card.
  for (const c of cards) if (!enclosing(c)) {
    add('card-edge right (per pane)', s, c, c.r, s.name + pane(c));
  }
  // period labels and section labels against the card below
  // the card under the label = the nearest one that overlaps it horizontally (a full-width label is not in either pane: two half-width cards share its row, the left one names it)
  const below = (n) => cards.filter((c) => c.t >= n.b - 1 && Math.min(c.r, n.r) - Math.max(c.l, n.l) > 1 && !enclosing(c)).sort((a, b) => a.t - b.t || a.l - b.l)[0];
  // A neighbour label peeks into the strip: only a few dp of its text are inside the pager viewport, its position is the pager geometry, not an alignment.
  // A label inside an accent card (the Pilastri hero month row) sits on the accent card's own side padding (16, Tokens "Accent card"), not on the card below it.
  const ACCENT_PAD = 16, accentOf = (n) => by('accent-card').find((a) => inside(n, a));
  for (const n of by('period-label-prev').filter((x) => x.r - x.l >= 16)) { const a = accentOf(n); if (a) { tok('label period prev left inside accent-card (padding 16)', s, n, n.l - a.l, ACCENT_PAD); continue; } const c = below(n); if (c) add('label period prev left minus card left', s, n, n.l - c.l, 'zero'); }
  for (const n of by('period-label-next').filter((x) => x.r - x.l >= 16)) { const a = accentOf(n); if (a) { tok('label period next right inside accent-card (padding 16)', s, n, a.r - n.r, ACCENT_PAD); continue; } const c = below(n); if (c) add('label period next right minus card right', s, n, n.r - c.r, 'zero'); }
  for (const n of by('section-label')) { const c = below(n); if (c) add('label section left minus card left', s, n, n.l - c.l, 'zero'); }
  const pcTop = Math.max(0, ...by('page-content').map((p) => p.t), s.VT);
  const floatBox = by('dock').concat(by('island'), by('gear'));   // a chip whose top sits on the bottom edge of the floating dock / island is the part of it left below the overlay (the dump clips its bounds): cut, not measurable
  for (const n of by('chip').concat(by('period-chip'))) if (n.b < s.V - 0.5 && n.t > pcTop + 0.5 && !floatBox.some((x) => Math.abs(n.t - x.b) <= 1 && n.l < x.r && n.r > x.l)) add('chip height', s, n, n.b - n.t);   // a chip cut by the scroll viewport bottom or by the window / viewport top (scrolled under the status bar) is not measurable
  checkDirect(s, by);
  checkDup(s, by, pane);
  checkCurrencySplit(s);
}

// Direct rules (no majority): CR-05/40 first content and one primary figure, CR-41 panes, CR-27/24/07 frame, CR-23 touch, CR-33 copy,
// CR-55 accent card, CR-56 figure scale, CR-59 attention row, CR-62 gutter and gap. Same names and values as ConsistencyEngine.kt.
// CR-72 (STRUCTURE, wave 7): a figure and its currency symbol are never split. A visible text (the `text` attribute, not content-desc) in which a digit and a currency symbol are joined by a
// breakable U+0020 space ("441 €") may wrap between them and leave the symbol alone on a line; the joiner must be a no-break space (U+00A0 / U+202F). Symbols: --currency (default € $ £), either order.
function checkCurrencySplit(s) {
  if (!CURRENCY) return;
  const seen = new Set(), cs = [...CURRENCY].map((c) => '\\' + c).join(''), re = new RegExp('\\d +[' + cs + ']|[' + cs + '] +\\d');   // ' +' = the breakable space only; NBSP / NNBSP are not in the pattern
  for (const n of s.nodes) {
    const k = [n.l, n.t, n.r, n.b, n.raw].join('|');
    if (!n.vis || seen.has(k) || !re.test(n.raw)) continue; seen.add(k);
    direct.push({ rule: 'text: a figure and its currency symbol are never split by a breakable space (CR-72)', layout: s.layout, screen: s.name, el: label(n) || n.id, value: 1, expected: 0, group: 'direct', note: 'use U+00A0 between figure and symbol' });
  }
}
function checkDirect(s, by) {
  const H = s.H, W = s.W, half = W / 2;
  const D = (rule, n, value, expected, note = '') => direct.push({ rule, layout: s.layout, screen: s.name, el: (n && (label(n) || n.id)) || '-', value: r1(value), expected: r1(expected), group: 'direct', note });
  const isBlock = (n) => ['card', 'accent-card', 'attention-row'].includes(n.id);
  const pf = by('primary-figure'), ac = by('accent-card');
  const base = s.name.split('-top-')[1] || '';
  if (s.top) {
    const pc = by('page-content');
    if (!pc.length) D('page-header: tag page-content missing (CR-05 first content top)', null, -1, 0, 'request tag');
    else add('page-header content top (CR-05)', s, pc[0], pc[0].t, 'top');
    const nPane = s.layout === 'fold' ? Math.max(pf.filter((n) => (n.l + n.r) / 2 < half).length, pf.filter((n) => (n.l + n.r) / 2 >= half).length) : pf.length;
    if (nPane > 1 || (!pf.length && ACCENT_SCREENS.includes(base))) D('figure: one primary-figure per pane on the board screens, at most one elsewhere (CR-40)', null, pf.length, 1, pf.length ? '' : 'request tag primary-figure');
    else if (pf.length && Math.min(...pf.map((n) => n.t)) > H * 0.4) D('figure: primary-figure top within 40 % of height (CR-40)', pf[0], Math.min(...pf.map((n) => n.t)), H * 0.4);
    // frame
    const dock = by('dock'), island = by('island'), gear = by('gear');
    if (s.layout === 'phone') {
      if (dock.length !== 1) D('frame: exactly one dock on the cover (CR-27)', null, dock.length, 1);
      else {
        if (Math.abs((dock[0].l + dock[0].r) / 2 - W / 2) > TOL) D('frame: dock centre x equals screen centre (CR-27)', dock[0], (dock[0].l + dock[0].r) / 2, W / 2);
        const items = by('dock-item').length;
        if (items !== 5) D('frame: exactly 5 dock-item (CR-27)', null, items, 5, items ? '' : 'request tag dock-item');
      }
    } else if (island.length !== 1) D('frame: exactly one island on the open Fold (CR-24)', null, island.length, 1);
    else {
      const i = island[0], icy = (i.t + i.b) / 2;
      if (Math.abs((W - i.r) - 12) > TOL) D('frame: island 12 dp from the right edge (CR-24)', i, W - i.r, 12);
      // Centred in the safe area the panes occupy (status bar above, navigation bar below: one owner for the insets, AppRoot), not on the raw screen height.
      const pr = by('pane-right')[0], mid = pr ? (pr.t + pr.b) / 2 : H / 2;
      if (Math.abs(icy - mid) > 4) D('frame: island vertically centred +-4 dp (CR-24)', i, icy, mid);
      if (gear[0] && Math.abs((gear[0].l + gear[0].r) / 2 - (i.l + i.r) / 2) > 2) D('frame: gear centre x equals island centre x (CR-07)', gear[0], (gear[0].l + gear[0].r) / 2, (i.l + i.r) / 2);
    }
    if (s.layout === 'fold') {
      const l = by('pane-left'), r = by('pane-right');
      if (!l.length || !r.length) D('panes: tags pane-left and pane-right missing (CR-41)', null, l.length + r.length, 2, 'request tags pane-left, pane-right');
      else {
        if (Math.abs(l[0].r - half) > 4) D('panes: left pane ends at the split (CR-41)', l[0], l[0].r, half);
        const content = s.nodes.filter((n) => n.text.trim() && !['island', 'gear', 'dock-item'].includes(n.id));
        const left = content.filter((n) => (n.l + n.r) / 2 < half).length, right = content.filter((n) => (n.l + n.r) / 2 >= half).length;
        if (left < 3 || right < 3) D('panes: both panes populated (>= 3 texted nodes each) (CR-41)', { text: 'left ' + left + ' / right ' + right, id: '' }, Math.min(left, right), 3);
      }
    }
  }
  // CR-23: merged clickable nodes (a uiautomator dump is already merged), deduped by bounds + label
  const seen = new Set();
  for (const n of s.nodes) if (n.clickable) {
    const k = [r1(n.l), r1(n.t), r1(n.r), r1(n.b), n.text].join('|'); if (seen.has(k)) continue; seen.add(k);
    const w = n.r - n.l, h = n.b - n.t;
    if (n.b >= s.V - 0.5 && h < 48) continue;   // cut by the scroll viewport bottom: the visible part says nothing about the target
    if (n.t <= s.VT + 0.5 && h < 48) continue;   // cut by the window / scroll viewport TOP (scrolled under it): same reasoning (K4/M1: the hero Versato row, min height 48 dp, showed 45 dp at scroll offset)
    // trimmed by the floating dock / island / gear drawn over it: the visible part says nothing about the target either
    if ((w < 48 - TOL || h < 48 - TOL) && s.nodes.some((x) => ['dock', 'island', 'gear'].includes(x.id) && n.l < x.r + 0.5 && n.r > x.l - 0.5 && n.t < x.b + 0.5 && n.b > x.t - 0.5 && (Math.abs(n.b - x.t) < 1 || Math.abs(n.t - x.b) < 1 || Math.abs(n.r - x.l) < 1 || Math.abs(n.l - x.r) < 1))) continue;
    if (w >= 1 && h >= 1 && (w < 48 - TOL || h < 48 - TOL)) D('touch: clickable at least 48 x 48 dp (CR-23)', n, Math.min(w, h), 48);
  }
  // Visible copy only: a node without `text` carries an accessibility description (chart summaries), which is read aloud, not drawn.
  for (const n of s.nodes) if (n.vis && n.text.length > 70) D('copy: text longer than 70 chars on a working screen (CR-33)', n, n.text.length, 70);
  // CR-55 / CR-56
  // Per PANE: the open Fold draws one accent card per pane (3a: Spese trio left, Rate right). Required only where a board draws one: the screen as a whole, and on Spese both panes.
  const twoPanes = s.layout === 'fold' && (s.top || (by('page-content').some((n) => (n.l + n.r) / 2 < half) && by('page-content').some((n) => (n.l + n.r) / 2 >= half)));   // R2a: a non-top Fold capture (spese-rest) still has two panes, one accent card each
  const panes = twoPanes ? [[0, half], [half, W + 1]] : [[0, W + 1]];
  panes.forEach(([lo, hi], i) => {
    const inP = (n) => (n.l + n.r) / 2 >= lo && (n.l + n.r) / 2 < hi;
    const pP = pf.filter(inP), aP = ac.filter(inP), lab = panes.length > 1 ? (i === 0 ? ' (left pane)' : ' (right pane)') : '';
    if (aP.length > 1 || pP.length > 1) D('accent: at most one accent-card and primary-figure per pane (CR-55)' + lab, null, aP.length, 1);
    else if (pP.length === 1 && !aP.length) D('accent: a primary-figure sits inside an accent-card (CR-55)' + lab, pP[0], 0, 1);
    else if (aP.length === 1 && !pP.length) D('accent: accent-card without a primary figure (CR-55)' + lab, null, 1, 0);
    else if (aP.length === 1 && !inside(pP[0], aP[0])) D('accent: the primary-figure sits inside the accent-card (CR-55)' + lab, pP[0], pP[0].t, aP[0].t);
    const need = s.top && ACCENT_SCREENS.includes(base) && (panes.length === 1 || base === 'spese' || i === 0);
    if (need && !aP.length && !(panes.length === 1 && ac.length)) D('accent: exactly one accent-card on a screen the board draws one on (CR-55)' + lab, null, 0, 1);
  });
  // A dump has no font size: line boxes are compared (32 / 23 / 14 dp lines for 26 / 17 / 11 sp), so the thresholds are the line-box ratios; the JVM test uses font sizes.
  const hh = (n) => n.b - n.t;
  if (pf.length >= 1) {
    const pfFull = pf.filter((p) => { const c = ac.find((a) => inside(p, a)); return !c || p.t - c.t >= 4; });   // a primary-figure at the very top of its card is cut by the scroll viewport: its box is not the line box
    const pfFor = (n) => pfFull.find((p) => s.layout !== 'fold' || ((p.l + p.r) / 2 < half) === ((n.l + n.r) / 2 < half));
    // Ratios only against an uncut primary: when the scroll viewport cuts the card's top, its figure, secondary figures and labels are all clipped boxes.
    // At system font scale 2.0 Android 14+ scales NONLINEARLY (large sizes grow less than small ones, developer.android.com/about/versions/14/features), so the 26 / 17 / 11 sp
    // ratios compress by design: a -fs2 dump asserts only that the hierarchy keeps its order (primary line box >= secondary, >= label). The 1.5 / 2.3 ratios stay asserted at scale 1.0.
    const rSec = s.fs ? 1.0 : 1.3, rLab = s.fs ? 1.0 : 2.0;
    for (const n of by('secondary-figure')) { const f = pfFor(n); if (f && hh(f) < rSec * hh(n)) D('figure: primary >= 1.5 x secondary-figure (CR-56)', n, hh(f) / hh(n), rSec, 'line boxes'); }
    // At font 2.0 a label wraps (box = N lines): compare with ONE line, the shortest label box of the screen.
    const oneLine = s.fs ? Math.min(...by('stat-label').map(hh)) : 0;
    // polish A 2026-11-10: a label that wraps (box = N lines, e.g. 'Variazione del mese · valore' in the 135 dp Fold column) is compared by ONE line; the wrap itself is CR-66's, not a font-size ratio
    for (const n of by('stat-label')) { const f = pfFor(n); const minLab = Math.min(...by('stat-label').map(hh)); const lh = s.fs ? Math.max(oneLine, 0) : (hh(n) > 1.5 * minLab ? minLab : hh(n)); if (f && hh(f) < rLab * lh) D('figure: primary >= 2.3 x stat-label (CR-56)', n, hh(f) / lh, rLab, 'line boxes'); }
    for (const n of [] /* wave 11a: the page heading is the top level (CR-83) */ || by('page-title').filter((x) => !by('page-content').some((p) => (x.l + x.r) / 2 >= p.l && (x.l + x.r) / 2 <= p.r && x.t <= p.t + 0.5))) { const f = pfFor(n); if (f && hh(f) <= hh(n)) D('figure: primary larger than page-title (CR-56)', n, hh(n), hh(f)); }
  }
  // CR-59 structure
  for (const r of by('attention-row')) {
    if (s.fs ? hh(r) < ATTENTION_H - TOL : Math.abs(hh(r) - ATTENTION_H) > TOL) D('attention: attention-row height 56 dp (CR-59)', r, hh(r), ATTENTION_H);
    const inner = s.nodes.filter((n) => n.clickable && n !== r && inside(n, r) && !(Math.abs(n.l - r.l) < 1 && Math.abs(n.r - r.r) < 1));
    if (inner.length) D('attention: no button inside the attention-row (CR-59)', inner[0], inner.length, 0);
  }
  // CR-62: gutter and gap
  const all = s.nodes.filter(isBlock), widest = Math.max(0, ...all.map((n) => n.r - n.l));
  const top = all.filter((n) => n.r - n.l >= widest * 0.4).filter((c) => !all.some((o) => o !== c && inside(c, o) && area(o) > area(c)));
  for (const b of top) {
    const cx = (b.l + b.r) / 2;
    if (s.layout === 'fold' && !s.top && cx >= half) continue;
    // K2 wave 6: no narrowing accepted any more; a card inside a period-viewport is on the gutter line like every other block.
    // Wave 12 (user 2026-11-09: "UX va sopra UI come hierarchy"): a card inside a period-viewport is inset by the CR-01 peek + gap, never narrower; the alignment gives way, not the peek.
    const swipes = by('period-viewport').some((v) => b.l >= v.l - 1 && b.r <= v.r + 1 && b.t >= v.t - 1 && b.b <= v.b + 1), extra = swipes ? (s.layout === 'fold' ? CR01_PEEK_FOLD : CR01_PEEK_COVER) + GAP - GUTTER : 0;
    const le = (s.layout === 'fold' && s.top && cx >= half ? half + GUTTER : GUTTER) + extra;
    // Range ends (user 2026-11-10 "va bene modifica schermata", G-11 range ends): a page at the end of its strip rests on the margin of the empty side, a single page spans margin to margin.
    // The geometry is rangeEndEdges (peek-visible.mjs), the one CR-89 uses. A page still centred at a range end is CR-89's finding, not reported twice; any other offset is a deviation here.
    let re = null;
    if (swipes) for (const e of ((rangeEndCfg.peekVisible || []).filter((x) => x.endGutterDp !== undefined && peekMatches(x, s.name, dir)))) {
      for (const v of by('period-viewport').filter((n) => b.l >= n.l - 1 && b.r <= n.r + 1 && b.t >= n.t - 1 && b.b <= n.b + 1)) { re = re || rangeEndEdges(s.nodes, e, v, le - v.l); }
    }
    if (!(Math.abs(b.l - le) <= TOL || (re && Math.abs(b.l - re.l) <= TOL))) D('gutter: block left edge on the 16 dp gutter (CR-62)', b, b.l, re ? re.l : le);
    if (s.layout === 'phone' && !(Math.abs((W - b.r) - GUTTER - extra) <= TOL || (re && Math.abs(b.r - re.r) <= TOL))) D('gutter: block right edge on the 16 dp gutter (CR-62)', b, W - b.r, re ? W - re.r : GUTTER + extra);
  }
  for (const p of [0, 1]) {
    const col = top.filter((b) => (s.layout === 'fold' && (b.l + b.r) / 2 >= half ? 1 : 0) === p).sort((a, b) => a.t - b.t);
    for (let i = 1; i < col.length; i++) {
      const a = col[i - 1], b = col[i];
      if (Math.min(a.r, b.r) - Math.max(a.l, b.l) < Math.min(a.r - a.l, b.r - b.l) * 0.5) continue;
      if (s.nodes.some((n) => !isBlock(n) && n.text.trim() && n.t >= a.b - 0.5 && n.b <= b.t + 0.5 && n.r - n.l > 1 && Math.min(n.r, a.r) - Math.max(n.l, a.l) > 1)) continue;
      const gap = b.t - a.b;
      if (Math.abs(gap - GAP) > TOL) D('gap: 8 dp between stacked blocks (CR-62)', b, gap, GAP);
    }
  }
}


// BASE value tokens: every token with a digit plus its attached word or unit ("429 €", "17%", "12 mesi", "9 set", "0/4"); a plain integer without a unit is too ambiguous and
// is skipped. No language, no date logic: the same token in two nodes is a repetition.
function factsOf(text) {
  const keys = new Set();
  for (const m of text.toLowerCase().matchAll(TOKEN)) {
    const d = m[1].replace(/[.,:/]+$/, '').replace(/[,.]00$/, ''); let u = (m[2] || '').toLowerCase(); if (UNIT_STOP.has(u)) u = '';
    if (!u && /^\d+$/.test(d)) continue;
    keys.add('V:' + d + u);
  }
  return keys;
}
// ADD-ON dateRanges (enabled in this project; Italian locale inline, same data as design-method/scripts/locales.json "it"): a date range and its own endpoints, or a date inside a sentence and the same date
// written bare, are ONE fact. Keys: R range, E endpoint of a range, D date in a sentence, M month name, MY month+year. Off by default; the base check never reads a locale.
function dateKeys(text, isBare = false) {
  let t = text.toLowerCase(); const keys = new Set();
  t = t.replace(new RegExp(String.raw`(${MRE})\s*(\d{4})\s*${SEP}\s*(${MRE})\s*(\d{4})`, 'g'), (_, m1, y1, m2, y2) => { keys.add(`R:${MON[m1]}/${y1}-${MON[m2]}/${y2}`); keys.add(`MY:${MON[m1]}/${y1}`); keys.add(`MY:${MON[m2]}/${y2}`); return ' '; });
  if (false) {
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
// CR-70 (generalised again 2026-11-09, user: "cerchi duplicati in una schermata, lo flagga, poi l'agente mette un filtro solo per quell'elemento"): scope is ONE screen (one
// dump). Every duplicate is flagged: (a) text nodes with identical normalised text (whitespace and case only); (b) any value token found in more than one node. There are NO
// built-in exclusions and no fact types. The only way to silence a repetition is a filter naming ONE element (screen glob + resource-id + text pattern + reason) in the
// duplicate-filters file, added after looking at the flagged element and judging its repetition legitimate.
function checkDup(s, by, pane) {
  if (!dupScoped(s.name)) { dupSkipped++; return; }   // scope by config (duplicate-scope.json): a wide two-pane layout is not checked for repetition
  const D = (rule, el, value, expected, note = '') => direct.push({ rule, layout: s.layout, screen: s.name, el, value: r1(value), expected: r1(expected), group: 'direct', note });
  const flt = dupFilters.filter((f) => globRe(f.screen).test(s.name));
  const filtered = (n) => { const f = flt.find((f) => (!f.id || f.id === n.id) && new RegExp(f.text || '.', 'i').test(n.text)); if (!f) return false; const h = dupHits.get(f) || { n: 0, screens: new Set() }; h.n++; h.screens.add(s.name); dupHits.set(f, h); return true; };
  const bare = new RegExp(String.raw`^[‹›\s]*\d{1,2}(\s*(${MRE}))?(\s*${SEP}\s*\d{1,2}\s*(${MRE}))?[‹›\s]*$`);
  const isBare = (n) => bare.test(n.text.toLowerCase().trim());
  const seenNode = new Set(), idx = new Map(), seen = new Map(), cands = [];
  for (const n of s.nodes) {
    const k5 = [n.l, n.t, n.r, n.b, n.text].join('|');
    if (!n.vis || !n.text.trim() || !/[\p{L}\p{N}]/u.test(n.text) || seenNode.has(k5) || filtered(n)) continue; // a pure symbol ("‹", "›", "—") has no fact to repeat
    seenNode.add(k5);
    cands.push(n);
    for (const k of [...factsOf(n.text), ...dateKeys(n.text, isBare(n)), 'T:' + n.text.trim().toLowerCase().replace(/\s+/g, ' ')]) { if (!idx.has(k)) idx.set(k, []); idx.get(k).push(n); }
  }
  const cls = siblingClasses(cands);
  for (const [k, hit0] of idx) {
    const hit = hit0.filter((n, i, a) => !cls.has(n) || a.findIndex((m) => cls.get(m) === cls.get(n)) === i);   // sibling twins are not compared with each other
    if (hit.length < 2) continue;
    const sig = hit.map((n) => [n.l, n.t].join(',')).sort().join(';'); if (!seen.has(sig)) seen.set(sig, { hit, keys: [] }); seen.get(sig).keys.push(k);
  }
  for (const { hit, keys } of seen.values())
    D('duplicate: a text or value appears once per screen (CR-70)', hit.map((n) => '"' + label(n) + '"').join(' + '), hit.length, 1, 'fact ' + keys.join(', ') + ' [' + hit.map((n) => n.id || '-').join(', ') + ']');
  const cut = (n) => (s.layout === 'fold' ? ((n.l + n.r) / 2 < s.W / 2 ? 'L' : 'R') : 'P');
  // CR-83 (user 2026-11-09 ~21:50: "the spese heading should be bigger, same for other pages, you lack a bit of hierarchy"): heading roles by level (tools/heading-levels.json: page title > period title > card title)
  // must have strictly decreasing sizes, at least `minRatio` x apart. A dump has no font size, so line boxes are compared (per pane, first node of each level; a node that IS the next level too, Mese's page title = period title, is skipped).
  if (HEADING_LEVELS.levels && !s.fs && !args.includes('--no-heading-levels')) for (const P of s.layout === 'fold' ? ['L', 'R'] : ['P']) {
    const lv = HEADING_LEVELS.levels.map((id) => [id, s.nodes.find((n) => n.id === id && cut(n) === P)]).filter(([, n]) => n && n.b > n.t);
    for (let i = 0; i + 1 < lv.length; i++) { const [ia, a] = lv[i], [ib, b] = lv[i + 1]; if (Math.abs(a.l - b.l) < 1 && Math.abs(a.t - b.t) < 1 && Math.abs(a.b - b.b) < 1) continue;
      if ((a.b - a.t) < HEADING_LEVELS.minRatio * (b.b - b.t) - 0.01) D('hierarchy: ' + ia + ' line box >= ' + HEADING_LEVELS.minRatio + ' x ' + ib + ' (CR-83, heading levels)', a.text || ia, (a.b - a.t) / (b.b - b.t), HEADING_LEVELS.minRatio, 'line boxes ' + ia + ' vs ' + ib + (s.layout === 'fold' ? ' (' + (P === 'L' ? 'left' : 'right') + ' pane)' : '')); }
  }
  // CR-71: a pane that has a page-title and period viewports (Mese, Spese; not the Pilastri hero, which names its month with period-label-cur) exposes `period-title`.
  for (const P of s.layout === 'fold' ? ['L', 'R'] : ['P']) {
    const inP = (n) => cut(n) === P, titles = s.nodes.filter((n) => n.id === 'page-title' && inP(n));
    if (!titles.length || !s.nodes.some((n) => n.id === 'period-viewport' && inP(n)) || s.nodes.some((n) => n.id === 'period-label-cur' && inP(n))) continue;
    const pts = s.nodes.filter((n) => n.id === 'period-title' && inP(n));
    if (!pts.length) { D('period: swipe page exposes its current period in a `period-title` node (CR-71)', '', 0, 1, 'request tag period-title'); continue; }
    for (const n of pts) add('period-title height (same on every period page, CR-71)', s, n, n.b - n.t, 'any');
    const pt = Math.max(...pts.map((n) => n.b - n.t));
    for (const n of s.nodes.filter((x) => ['page-subtitle', 'page-trailing', 'chip'].includes(x.id) && inP(x) && !(x.id === 'page-trailing' && !x.vis) && !pts.some((p) => Math.abs(p.l - x.l) < 1 && Math.abs(p.t - x.t) < 1 && Math.abs(p.b - x.b) < 1)))
      if (perLine(s, n) > pt + TOL) D('period: period-title is the tallest text of the page header (CR-71)', label(n), pt, perLine(s, n), 'header text taller');
  }
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
for (const m of M) {
  const k = [m.rule, m.layout, m.group, m.fs].join('|');   // text boxes scale with the font: a font-scale-2 dump is compared with its own kind, never with scale-1 dumps
  if (!groups.has(k)) groups.set(k, []);
  groups.get(k).push(m);
}
const dev = [], excused = [];
if (process.env.CK_DEBUG) for (const [k, ms] of groups) if (k.startsWith(process.env.CK_DEBUG)) console.log(k, ms.map((m) => m.screen + ":" + m.el + "=" + m.value).join("; "));
for (const [, ms] of groups) {
  const zero = ms[0].group === 'zero';
  const edge = ms[0].rule.includes('edge');
  // edge rules: expected is per (screen, pane); others: across every screen of the layout class
  const exp = zero ? 0 : expectedOf(ms.map((m) => m.value));
  for (const m of ms) {
    if (Math.abs(m.value - exp) <= TOL) continue;
    const d = { ...m, expected: r1(exp), edge };
    const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text)));
    if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
  }
}

for (const d of direct) {
  const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text)));
  if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
}

// CO-MOTION: what belongs together moves together. A pair = a dump at rest and one mid-gesture (<x>-rest.xml + <x>-moving.xml, or
// --rest/--moving). Every tagged descendant of a card (card-title, card-trailing) must be displaced like its card; a period-label
// like the card it names (cur = the card under it, prev/next = its neighbours in the strip). Tolerance +-1 dp.
const pairs = [...pairArg];
for (const f of allFiles) if (f.endsWith('-rest.xml') && allFiles.includes(f.replace(/-rest\.xml$/, '-moving.xml'))) pairs.push([path.join(dir, f), path.join(dir, f.replace(/-rest\.xml$/, '-moving.xml'))]);
// A "<x>-moving.xml" with no "<x>-rest.xml" is paired with "<x>.xml" (the plain dump of the same screen).
for (const f of allFiles) { const base = f.replace(/-moving\.xml$/, ''); if (f.endsWith('-moving.xml') && !allFiles.includes(base + '-rest.xml') && allFiles.includes(base + '.xml')) pairs.push([path.join(dir, base + '.xml'), path.join(dir, f)]); }
let motionChecks = 0;
// CR-68 (wave 2 rule 4, on the period-* tags): every period-label moves by the same dx as its period-page (+-1 dp), and a moving dump identical to its rest dump
// (same node bounds) is a deviation, so a capture that never moved cannot pass (vacuity guard). Page and label are matched with either edge (a peeking node is clipped at one side).
for (const [fr, fm] of pairs) {
  const A = parse(fr), B = parse(fm), name = path.basename(fm).replace(/\.xml$/, ''), layout = name.split('-')[0];
  const push = (rule, el, value, expected) => { motionChecks++; const d = { rule, screen: name, el, value: r1(value), expected: r1(expected), layout, group: 'motion' }; const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text))); if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d); };
  const key = (n) => [n.id, r1(n.l), r1(n.t), r1(n.r), r1(n.b)].join('|');
  if (A.length === B.length && A.every((n, i) => key(n) === key(B[i]))) push('motion: moving dump identical to its rest dump (vacuity guard, CR-68)', '-', 0, 1);
  // CR-79 (user 2026-11-09, wave 9 review H1: on Mese mid-drag the travelling title passed under the fixed back button): a moving visible text never overlaps a fixed control.
  // Fixed control = a small clickable node with no text of its own (CR-74's definition) that has the same bounds in the rest dump; moving = a visible text with no node of the same id and
  // text at the same place in the rest dump. Overlap > 1 dp in both axes is a deviation (a text fully inside a control is that control's label, loosening as in CR-74).
  for (const c of B.filter((n) => n.clickable && !n.vis && n.r - n.l <= 56 && n.b - n.t <= 56 && A.some((x) => x.id === n.id && x.clickable && Math.abs(x.l - n.l) < 1 && Math.abs(x.t - n.t) < 1 && Math.abs(x.r - n.r) < 1 && Math.abs(x.b - n.b) < 1))) {
    for (const t of B.filter((n) => n.vis && n.r - n.l > 0 && !A.some((x) => x.vis && x.id === n.id && x.text === n.text && Math.abs(x.l - n.l) < 1 && Math.abs(x.t - n.t) < 1))) {
      const ix = Math.min(t.r, c.r) - Math.max(t.l, c.l), iy = Math.min(t.b, c.b) - Math.max(t.t, c.t);
      if (ix <= TOL || iy <= TOL) continue;
      if (t.l >= c.l - TOL && t.r <= c.r + TOL && t.t >= c.t - TOL && t.b <= c.b + TOL) continue;
      push('motion: a moving element overlaps a fixed control (CR-79)', label(t).slice(0, 20) + ' / ' + (c.text || c.desc || 'control').slice(0, 20), ix, 0);
    }
  }
  const near = (n, list) => list.filter((x) => x.id === n.id && Math.abs(x.t - n.t) < 1).sort((a, b) => Math.abs(a.l - n.l) + Math.abs(a.t - n.t) - Math.abs(b.l - n.l) - Math.abs(b.t - n.t))[0];
  const pages = A.filter((x) => x.id.startsWith('period-page-'));
  if (A.some((x) => x.id.startsWith('period-label-')) && !pages.length) push('motion: period pages are not tagged (CR-68)', 'period-page-*', 0, 1);
  for (const l of A.filter((x) => x.id.startsWith('period-label-'))) {
    // each label is paired with the page that CONTAINS it (D4): its horizontal extent holds the label centre, nearest below it
    const lcx = (l.l + l.r) / 2;
    const p0 = pages.filter((x) => lcx >= x.l - 1 && lcx <= x.r + 1 && x.t >= l.b - 1).sort((a, b) => a.t - b.t)[0];
    if (!p0) { push('motion: period label is inside no period page (CR-68)', l.id + ' ' + label(l).slice(0, 14), lcx, 0); continue; }
    const p1 = near(p0, B.filter((x) => x.id === p0.id)), l1 = near(l, B); if (!p1 || !l1) continue;
    if (!(Math.abs((l1.l - l.l) - (p1.l - p0.l)) <= TOL || Math.abs((l1.r - l.r) - (p1.r - p0.r)) <= TOL)) push('motion: period label moves like its page (CR-68)', l.id + ' ' + label(l).slice(0, 14), l1.l - l.l, p1.l - p0.l);
  }
  // CR-68 / M1 extension (K, wave 6, R2-M1; user 2026-11-08: "se cose appartengono allo stesso elemento, quasi sempre avranno lo stesso movimento"): a `period-title` on a pane with a
  // `period-viewport` belongs to that pane's `period-page-cur` and is displaced like it (same direction, +-1 dp). The page is measured on the edge that does not touch its clip box (the
  // viewport): uiautomator clips bounds, so the touching edge understates the move; the title may match on either edge (it can be clipped by the window).
  for (const t of A.filter((x) => x.id === 'period-title' && x.r - x.l > 0)) {
    const tcx = (t.l + t.r) / 2, vp = A.filter((x) => x.id === 'period-viewport' && tcx >= x.l - 1 && tcx <= x.r + 1)[0]; if (!vp) continue;
    const p0 = A.filter((x) => x.id === 'period-page-cur' && x.l >= vp.l - 1 && x.r <= vp.r + 1 && x.t >= t.b - 1).sort((a, b) => a.t - b.t)[0]; if (!p0) continue;
    const p1 = near(p0, B.filter((x) => x.id === 'period-page-cur')), t1 = near(t, B.filter((x) => x.id === 'period-title' && x.r - x.l > 0));
    if (!t1) { push('capture: period-title absent or zero-width in the moving dump while its rest dump has it (CR-68)', t.id + ' ' + label(t).slice(0, 14), 0, t.r - t.l); continue; }   // K2: never a silent pass
    if (!p1) continue;
    const clipL = p1.l <= vp.l + 0.5 || p0.l <= vp.l + 0.5, clipR = p1.r >= vp.r - 0.5 || p0.r >= vp.r - 0.5; if (clipL && clipR) continue;   // both edges clipped: not measurable
    const pdx = clipL ? p1.r - p0.r : p1.l - p0.l;
    if (!(Math.abs((t1.l - t.l) - pdx) <= TOL || Math.abs((t1.r - t.r) - pdx) <= TOL)) push('motion: period-title moves like its page (CR-68, M1)', t.id + ' ' + label(t).slice(0, 14), t1.l - t.l, pdx);
  }
  // CR-68 / M1, whole period header (wave 8, K; same user quote): what describes the period belongs to the swiped page, not only the title. Members: the `page-subtitle` that lies inside the
  // `page-header` containing the period-title, every `period-chip`, every `period-subtitle`. Each member present at rest is displaced like the pane's `period-page-cur` (same dx, +-1 dp,
  // measured on the edge that does not touch the clip box, either member edge); absent or zero-width when moving is an explicit capture row.
  for (const t of A.filter((x) => x.id === 'period-title' && x.r - x.l > 0)) {
    const tcx = (t.l + t.r) / 2, hd = A.filter((x) => x.id === 'page-header' && x.l <= t.l + 1 && x.r >= t.r - 1 && x.t <= t.t + 1 && x.b >= t.b - 1).sort((a, b) => (a.r - a.l) * (a.b - a.t) - (b.r - b.l) * (b.b - b.t))[0];
    const vp = A.filter((x) => x.id === 'period-viewport' && tcx >= x.l - 1 && tcx <= x.r + 1)[0]; if (!vp) continue;
    const mem = A.filter((x) => x.r - x.l > 0 && x !== t && x.l < vp.r && x.r > vp.l && ((x.id === 'page-subtitle' && hd && x.l >= hd.l - 1 && x.r <= hd.r + 1 && x.t >= hd.t - 1 && x.b <= hd.b + 1) || x.id === 'period-chip' || x.id === 'period-subtitle'));
    for (const m of mem) {
      const mcx = (m.l + m.r) / 2; if (mcx < vp.l - 1 || mcx > vp.r + 1) continue;
      const p0 = A.filter((x) => x.id === 'period-page-cur' && x.l >= vp.l - 1 && x.r <= vp.r + 1 && x.t >= m.b - 1).sort((a, b) => a.t - b.t)[0]; if (!p0) continue;
      const p1 = near(p0, B.filter((x) => x.id === 'period-page-cur')), m1 = near(m, B.filter((x) => x.id === m.id && x.r - x.l > 0));
      if (!m1) { push('capture: period header member ' + m.id + ' absent or zero-width in the moving dump while its rest dump has it (CR-68)', m.id + ' ' + label(m).slice(0, 14), 0, m.r - m.l); continue; }
      if (!p1) continue;
      const clipL = p1.l <= vp.l + 0.5 || p0.l <= vp.l + 0.5, clipR = p1.r >= vp.r - 0.5 || p0.r >= vp.r - 0.5; if (clipL && clipR) continue;
      const pdx = clipL ? p1.r - p0.r : p1.l - p0.l;
      if (!(Math.abs((m1.l - m.l) - pdx) <= TOL || Math.abs((m1.r - m.r) - pdx) <= TOL)) push('motion: period header moves like its page (CR-68, M1)', m.id + ' ' + label(m).slice(0, 14), (Math.abs(m1.l - m.l) <= Math.abs(m1.r - m.r) ? m1.l - m.l : m1.r - m.r), pdx);
    }
  }
}
for (const [fr, fm] of pairs) {
  const A = parse(fr), B = parse(fm), name = path.basename(fm).replace(/\.xml$/, '');
  const cardsA = A.filter((n) => n.id === 'card'), cardsB = B.filter((n) => n.id === 'card');
  const near = (n, list, ok) => list.filter(ok).sort((a, b) => Math.abs(a.l - n.l) + Math.abs(a.t - n.t) - Math.abs(b.l - n.l) - Math.abs(b.t - n.t))[0];
  // Displacement: the left edge, or the right edge when the node's width changed (it is clipped by the viewport at one side).
  // uiautomator clips bounds to the visible area: the edge that touches the clip box (period-viewport / page-content of that dump) is not the node's real edge, so the
  // displacement is measured on the OTHER edge (wave 3 C: a card sliding past the pane edge read dx 30.1 on the clipped right edge while the card moved 68.6, as its title did).
  const clipBox = (n, list) => list.filter((x) => ['period-viewport', 'page-content'].includes(x.id) && x.t <= n.t + 0.5 && x.b >= n.b - 0.5 && (n.l + n.r) / 2 >= x.l && (n.l + n.r) / 2 <= x.r).sort((p, q) => area(p) - area(q))[0];
  const disp = (a, m) => {
    const cb = clipBox(m, B), ca = clipBox(a, A);
    const rCut = (cb && m.r >= cb.r - 0.5) || (ca && a.r >= ca.r - 0.5), lCut = (cb && m.l <= cb.l + 0.5) || (ca && a.l <= ca.l + 0.5);
    const useR = rCut !== lCut ? lCut : Math.abs((m.r - m.l) - (a.r - a.l)) > TOL;
    return { dx: useR ? m.r - a.r : m.l - a.l, dy: m.t - a.t };
  };
  const cardMove = new Map();
  for (const c of cardsA) { const m = near(c, cardsB, (x) => Math.abs((x.b - x.t) - (c.b - c.t)) <= TOL); if (m) cardMove.set(c, disp(c, m)); }
  const note = (rule, n, mv, exp) => {
    motionChecks++;
    if (Math.abs(mv.dx - exp.dx) <= TOL && Math.abs(mv.dy - exp.dy) <= TOL) return;
    const d = { rule, screen: name, el: label(n), value: r1(mv.dx), expected: r1(exp.dx), layout: name.split('-')[0], group: 'motion' };
    d.rule += ` (dx ${r1(mv.dx)} dp, dy ${r1(mv.dy)} dp vs container dx ${r1(exp.dx)} dp, dy ${r1(exp.dy)} dp)`;
    const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text)));
    if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
  };
  const smallest = (n) => cardsA.filter((c) => c.l - 0.5 <= n.l && n.r <= c.r + 0.5 && c.t - 0.5 <= n.t && n.b <= c.b + 0.5).sort((a, b) => area(a) - area(b))[0];
  for (const n of A.filter((x) => x.id === 'card-title' || x.id === 'card-trailing')) {
    const c = smallest(n), cm = c && cardMove.get(c); if (!cm) continue;
    const m = near(n, B.filter((x) => x.id === n.id && x.text === n.text), () => true); if (!m) continue;
    note('CO-MOTION ' + n.id + ' vs its card', n, disp(n, m), cm);
  }
  // Period labels are NOT paired here any more (K4/M1, 2026-11-09): this block matched a label to the nearest same-id label of the moving dump, which
  // jumps to another pane (the label of a page that left the tree is replaced by the other pane's) and compared with a viewport-clipped edge. CR-68 above
  // pairs each label with the page that contains it (same row, same pane) and carries the guard that a moving dump differs from its rest.
}

// CR-73 same gesture, same physics (user, 2026-11-09 ~17:25 "sono al stessa cosa e stessa classe come fanno a essere diverse"; ~17:05 "le schede hanno molto meno peso"). Held-finger captures
// `<viewport>-<strip>-hold-rest.xml` + `-hold-short.xml` + `-hold-long.xml` (tools/capture: `capture.sh hold <viewport>`): on every period strip the finger is held at the SAME travel,
// a fraction of the strip's own page pitch (period-page-cur width + 8 dp gap): short 0.25 page and long 0.60 page, one below and one above the 0.42-page commit point, towards the neighbour
// that exists (previous page first), or towards the end of the strip when no neighbour exists (position class `end`, the strip rubber-bands). Per hold and position class:
//   (a) every strip's page displacement / (fraction x pitch) agrees within HOLD_SPREAD of the median of its group (same code, same ratio); a lone strip is not compared.
//   (b) below the commit point (short) the page is drawn at most HOLD_RESIST_MAX of the finger travel (resistance, Xtraordinary MagneticSwipe draws ~0.08 + 0.48 sin of it): a 1:1 page lost it.
//   (c) a hold that moved no page by 4 dp or more is a capture row (vacuity guard); a displacement against the finger direction is a capture row.
// Tolerances: HOLD_SPREAD 0.05 page = about 2.5 x the touch-slop effect (8 dp of slop is 0.022 of a 355 dp cover page and 0.027 of a 296 dp Fold pane, so the same code differs by that
// much between widths) and 10 x the 1 dp rounding; HOLD_RESIST_MAX 0.85 sits between the pre-wave-9 1:1 mapping (0.92-0.98 with slop) and the resisted curve (about 0.5).
// (d) K2 wave 9 (the gate let a lost resistance pass on 0.78-0.80): the page displacement equals the REFERENCE curve at that finger travel within HOLD_TOL_DP. Curve = ui/Period.kt
//   PeriodPhysics.displayedProgress with the tokens PeriodFreeTravel / PeriodResistedTravel / PeriodThreshold / PeriodRubber READ from ui/Motion.kt (never typed twice; --motion-kt overrides the path):
//   signed = finger - slop (the drag starts after the touch slop, HOLD_SLOP_DP = 8 dp = ViewConfiguration default); real position: below the threshold shown = free + resisted*sin((signed-free)/(1-free)*pi/2)
//   capped at signed, from the threshold on the resistance is released (shown = signed); end position: rubber band x*L/(x+L). Tolerance HOLD_TOL_DP = 6 dp: 2 dp of px rounding on two edges + the slop
//   uncertainty (+-2 dp times the curve slope 0.8) + the 8 dp pitch gutter read from a tag width; the 09j dumps (0.80 short, 0.96 long) sit within 1-2 dp of it, a 1:1 page is 8-13 dp off, a curve at half
//   strength is 30 dp off. Measured on the real 09j dumps: phone short 0.20 page vs reference 0.2016.
const MOTION_KT = opt('motion-kt', path.join(here, '..', 'app', 'src', 'main', 'java', 'xyz', 'grtlabs', 'pilastri', 'ui', 'Motion.kt'));
const motionConst = (n) => { try { const m = new RegExp('const val ' + n + ' = ([0-9.]+)f').exec(fs.readFileSync(MOTION_KT, 'utf8')); return m ? +m[1] : null; } catch { return null; } };
const CURVE = { free: motionConst('PeriodFreeTravel'), resisted: motionConst('PeriodResistedTravel'), threshold: motionConst('PeriodThreshold'), rubber: motionConst('PeriodRubber') }, HOLD_SLOP_DP = +opt('hold-slop-dp', 8), HOLD_TOL_DP = +opt('hold-tol-dp', 6);
const curveOk = Object.values(CURVE).every((v) => v != null);
const refShown = (finger, cls) => { const x = Math.max(0, finger), p = cls === 'end' ? x * CURVE.rubber / (x + CURVE.rubber) : x, c = (p - CURVE.free) / (1 - CURVE.free); /* end: ui/Period.kt PeriodGesture.drag rubber-bands the finger first, then displayed() applies the SAME resistance curve (the reference pager has no end behaviour); wave 9 G3 narrowing */ return p < CURVE.threshold ? (p <= CURVE.free ? p : Math.min(CURVE.free + CURVE.resisted * Math.sin(c * Math.PI / 2), p)) : p; };
const HOLD = { short: 0.25, long: 0.6 }, HOLD_SPREAD = +opt('hold-spread', 0.05), HOLD_RESIST_MAX = +opt('hold-resist-max', 0.85), PEAK_GAP = 8;
{
  const groups = new Map();
  for (const f of allFiles) {
    const m = /^(.*)-hold-(short|long)\.xml$/.exec(f); if (!m || !allFiles.includes(m[1] + '-hold-rest.xml')) continue;
    const A = parse(path.join(dir, m[1] + '-hold-rest.xml')), B = parse(path.join(dir, f)), name = f.replace(/\.xml$/, ''), layout = name.split('-')[0];
    const push = (rule, el, value, expected) => { motionChecks++; const d = { rule, screen: name, el, value: r1(value), expected: r1(expected), layout, group: 'motion' }; const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text))); if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d); };
    let best = null;
    for (const vp of A.filter((x) => x.id === 'period-viewport')) {
      const inV = (x, v) => x.l >= v.l - 1 && x.r <= v.r + 1 && x.t >= v.t - 1 && x.b <= v.b + 1;
      const a = A.filter((x) => x.id === 'period-page-cur' && inV(x, vp)).sort((p, q) => p.t - q.t)[0];
      const vpB = B.filter((x) => x.id === 'period-viewport').sort((p, q) => Math.abs(p.t - vp.t) + Math.abs(p.l - vp.l) - Math.abs(q.t - vp.t) - Math.abs(q.l - vp.l))[0];
      const b = vpB && B.filter((x) => x.id === 'period-page-cur' && inV(x, vpB)).sort((p, q) => Math.abs(p.t - a?.t) - Math.abs(q.t - a?.t))[0];
      if (!a || !b) continue;
      const rClip = b.r >= vpB.r - 0.5 && a.r < vp.r - 0.5, lClip = b.l <= vpB.l + 0.5 && a.l > vp.l + 0.5;
      const dx = lClip && !rClip ? b.r - a.r : b.l - a.l;
      const has = (id) => A.some((x) => x.id === id && inV(x, vp) && x.r - x.l > 0);
      const dir = has('period-page-prev') ? 1 : has('period-page-next') ? -1 : 1, neighbour = dir > 0 ? has('period-page-prev') : has('period-page-next');
      if (!best || Math.abs(dx) > Math.abs(best.dx)) best = { dx, pitch: a.r - a.l + PEAK_GAP, dir, cls: neighbour ? 'real' : 'end' };
    }
    if (!best || Math.abs(best.dx) < 4) { push('capture: a held finger moved no period page by 4 dp or more (CR-73)', m[1], best ? Math.abs(best.dx) : 0, 4); continue; }
    if (Math.sign(best.dx) !== best.dir) { push('capture: the page moved against the finger direction (CR-73)', m[1], best.dx, best.dir); continue; }
    const travel = HOLD[m[2]] * best.pitch, ratio = Math.abs(best.dx) / travel, key = m[2] + '|' + best.cls;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push({ strip: m[1], hold: m[2], ratio, travel, dx: Math.abs(best.dx), name, push });
    if (!curveOk) push('capture: the reference curve tokens were not found in Motion.kt (CR-73)', m[1], 0, 1);
    else { const pitchDp = best.pitch, expDp = refShown(HOLD[m[2]] - HOLD_SLOP_DP / pitchDp, best.cls) * pitchDp; if (Math.abs(Math.abs(best.dx) - expDp) > HOLD_TOL_DP) push(`motion: the page does not follow the reference curve at this finger travel (CR-73 Xtraordinary displayedProgress, ${m[2]} hold, ${best.cls}; page ${Math.round(Math.abs(best.dx) / pitchDp * 100) / 100} of a page, reference ${Math.round(expDp / pitchDp * 100) / 100})`, m[1] + ' ' + best.cls, Math.abs(best.dx), expDp); }
    if (m[2] === 'short' && ratio > HOLD_RESIST_MAX) push(`motion: a page below the commit point follows the finger without resistance (CR-73, ratio ${Math.round(ratio * 100) / 100} of the finger, max ${HOLD_RESIST_MAX})`, m[1] + ' ' + best.cls, Math.abs(best.dx), HOLD_RESIST_MAX * travel);
  }
  for (const [key, g] of groups) {
    if (g.length < 2) continue;
    const med = g.map((x) => x.ratio).sort((p, q) => p - q)[Math.floor((g.length - 1) / 2)];
    for (const x of g) if (Math.abs(x.ratio - med) > HOLD_SPREAD) x.push(`motion: the same hold draws this strip differently from the others (CR-73, ${key.replace('|', ' hold, ')} position, ratio ${Math.round(x.ratio * 100) / 100} vs median ${Math.round(med * 100) / 100})`, x.strip, x.dx, med * x.travel);
  }
}

// Visual integrity (K13): clip-edge, overlap, hidden on every rest dump AND every animation frame (<screen>-f00.xml, <screen>-sw-f03.xml). One row per rule+element; the frames of one screen merge into one row.
if (!args.includes('--no-visual-integrity')) {
  const viFile = opt('visual-integrity', path.join(here, 'visual-integrity.json')), viCfg = fs.existsSync(viFile) ? JSON.parse(fs.readFileSync(viFile, 'utf8')) : {};
  const merged = new Map();
  for (const f of allFiles) {
    const name = f.replace(/\.xml$/, ''), frame = FRAME_RE.test(name), mid = name.endsWith('-moving') || name.includes('-hold-');
    const nodes = parse(path.join(dir, f)), hidden = parseHidden(path.join(dir, f)), base = name.replace(/-f[0-9]{2,}$/, '');
    const W = Math.max(0, ...nodes.map((n) => n.r)), H = Math.max(0, ...nodes.map((n) => n.b));
    // polish A 2026-11-10: (1) a hidden-node whose centre lies beyond the visible edge of a scroller that spans it horizontally is ordinary scrolled-off list content (the pane ends above the window bottom), not text 'clipped away'.
    const hiddenKept = hidden.filter((h) => { const cx = (h.l + h.r) / 2, cy = (h.t + h.b) / 2; return !nodes.some((sc) => sc.scroll && cx >= sc.l && cx <= sc.r && (cy > sc.b || cy < sc.t)); });
    for (const d of visualIntegrity({ nodes, hidden: hiddenKept, W, H, name, frame: frame || mid }, viCfg)) {
      // (2) page text passing under the floating dock label is content scrolling under a fixed overlay (the add-on's 'within a float' skip cannot tell it from the dock: both lie inside the dock box); the scroll-end rule owns reachability.
      if (/^overlap:/.test(d.rule) && (viCfg.overlapDockLabels || []).some((l) => d.el.split(' / ').includes(l))) continue;
      motionChecks++;
      const key = base + '|' + d.rule + '|' + d.el, m = merged.get(key);
      if (m) { m.n++; if (d.value > m.value) m.value = d.value; } else merged.set(key, { ...d, name, base, layout: base.split('-')[0], n: 1, frame: frame || mid });
    }
  }
  for (const m of merged.values()) {
    const d = { rule: m.rule + (m.frame && m.n > 1 ? ` [${m.n} frames, first ${m.name}]` : ''), screen: m.frame ? m.base : m.name, el: m.el, value: r1(m.value), expected: m.expected, layout: m.layout, group: 'motion' };
    const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text)));
    if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
  }
}

// Aligned to nothing (K14): every drawn leaf shares an edge or centre line with another element; trailing header control centred on the title. Rest dumps only. Config tools/aligned-to-nothing.json.
if (!args.includes('--no-aligned-to-nothing')) {
  const anFile = opt('aligned-to-nothing', path.join(here, 'aligned-to-nothing.json')), anCfg = fs.existsSync(anFile) ? JSON.parse(fs.readFileSync(anFile, 'utf8')) : {};
  for (const f of allFiles) {
    const name = f.replace(/\.xml$/, ''); if (!REST_ONLY(name) || PAIR_RE.test(name)) continue;
    const nodes = parse(path.join(dir, f)), W = Math.max(0, ...nodes.map((n) => n.r)), H = Math.max(0, ...nodes.map((n) => n.b));
    for (const x of alignedToNothing({ nodes, W, H }, anCfg)) {
      motionChecks++;
      const d = { rule: x.rule, screen: name, el: x.el + (x.note ? ' [' + x.note + ']' : ''), value: r1(x.value), expected: x.expected, layout: name.split('-')[0], group: 'motion' };
      const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text)));
      if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
    }
  }
}

// Source patterns (K22 / M8, CR-90): forbidden source patterns outside their allowed files, read-only scan of the roots in tools/source-patterns.json (a source file is not a dump: reported as a deviation row).
if (!args.includes('--no-source-patterns')) {
  const spFile = opt('source-patterns', path.join(here, 'source-patterns.json'));
  if (fs.existsSync(spFile)) { const spc = JSON.parse(fs.readFileSync(spFile, 'utf8')), { sourcePatterns: spRun } = await import('../design-method/scripts/addons/source-patterns.mjs');
    for (const x of spRun((spc.roots || []).map((r) => path.resolve(here, '..', r)), spc.sourcePatterns || [])) { motionChecks++; dev.push({ rule: 'source-pattern', screen: x.file.split('/').slice(-1)[0] + ':' + x.line, el: x.el + ' [' + x.note + ']', value: 1, expected: 0, layout: 'source', group: 'source' }); } }
}

// Device add-ons (CR-83 state-stability, CR-85 safe-area, CR-86 action-outcome): in-app capture pairs `<screen>-stateN-before/after`, `-actionN-before/after` and the root `insets`. Config tools/device-addons.json.
if (!args.includes('--no-device-addons')) {
  const daFile = opt('device-addons', path.join(here, 'device-addons.json')), daCfg = fs.existsSync(daFile) ? JSON.parse(fs.readFileSync(daFile, 'utf8')) : {};
  if (args.includes('--no-sibling-actions')) delete daCfg.siblingActions;   // older fixtures predate K23 (their expected counts are fixed); the K23 fixtures run with the rule on
  for (const x of deviceRows(dir, allFiles, daCfg, density)) {
    motionChecks++;
    const d = { rule: x.rule, screen: x.screen, el: x.el + (x.note ? ' [' + x.note + ']' : ''), value: r1(x.value), expected: x.expected, layout: x.screen.split('-')[0], group: 'motion' };
    const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text)));
    if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d);
  }
}


// K26 grouping follows purpose (CR-94): declared action groups per screen; removed-action only with --baseline <dir> (a previous capture folder).
if (!args.includes('--no-device-addons')) {
  const agFile = opt('device-addons', path.join(here, 'device-addons.json')), agCfg = fs.existsSync(agFile) ? (JSON.parse(fs.readFileSync(agFile, 'utf8')).actionGroups || null) : null;
  if (agCfg) { const { actionGroupsRows } = await import('../design-method/scripts/addons/action-groups.mjs'), ag = actionGroupsRows(dir, agCfg, opt('baseline') || null);
    ag.notes.forEach((x) => console.log(x));
    for (const x of ag.rows) { motionChecks++; const d = { rule: x.rule, screen: x.screen, el: x.el, value: r1(x.value), expected: x.expected, layout: x.screen.split('-')[0], group: 'motion' };
      const ex = exceptions.find((e) => (!e.rule || d.rule.includes(e.rule)) && (!e.screen || d.screen.includes(e.screen)) && (!e.text || d.el.includes(e.text)));
      if (ex) excused.push({ ...d, reason: ex.reason }); else dev.push(d); } }
}

const rows = (list, extra) => list.map((d) => `| ${d.screen} | ${d.rule} | ${d.el.replace(/\|/g, '/')} | ${d.value} dp | ${d.expected} dp |${extra ? ' ' + d.reason + ' |' : ''}`).join('\n');
const byRule = {};
for (const d of dev) byRule[d.rule] = (byRule[d.rule] || 0) + 1;
let out = `# Consistency check\n\nDumps: ${files.length} (${dir}), density ${density}, tolerance +-${TOL} dp, measurements ${M.length}, motion checks ${motionChecks}.\n\n`;
out += `Deviations: **${dev.length}**; recorded exceptions: ${excused.length}.\n\n`;
out += dupScope && dupScope.screens ? `Duplicates: checked on ${dupScope.screens.join(', ')} only (config ${path.basename(scopeFile)}); ${dupSkipped} other screen(s) not checked for repetition.\n\n` : 'Duplicates: checked on every screen.\n\n';
if (dupFilters.length) out += '## Duplicate filters applied\n\n| screen glob | id | text | reason | elements silenced | screens |\n|---|---|---|---|---|---|\n' + dupFilters.map((f) => { const h = dupHits.get(f); return `| ${f.screen} | ${f.id || '*'} | ${f.text || '.'} | ${f.reason || '(none)'} | ${h ? h.n : 0} | ${h ? [...h.screens].join(', ') : '-'} |`; }).join('\n') + '\n\n';
if (dev.length) {
  out += '## Count by type of deviation\n\n' + Object.entries(byRule).sort().map(([k, v]) => `- ${k}: ${v}`).join('\n') + '\n\n';
  out += '## Deviations\n\n| screen | rule | element | measured | expected |\n|---|---|---|---|---|\n' + rows(dev) + '\n';
}
if (excused.length) out += '\n## Recorded exceptions\n\n| screen | rule | element | measured | expected | reason |\n|---|---|---|---|---|---|\n' + rows(excused, true) + '\n';
console.log(out);
if (opt('out')) fs.writeFileSync(opt('out'), out);
process.exit(dev.length ? 1 : 0);
