#!/usr/bin/env node
// Regenerates tools/fixtures/cr/<CR-id>/<case>-{bad,good}/*.xml (density 1, dp = px) and manifest.json. The XML files are the fixtures; this script documents how they were
// built (a clean scene of Pilastri-named screens, one mutation per case). Run: node tools/fixtures/cr/gen-fixtures.mjs   Then: node tools/consistency-check.mjs --self-test
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.dirname(fileURLToPath(import.meta.url));
const nd = (id, text, [l, t, r, b], f = {}) => `<node resource-id="app:id/${id}" text="${text}" clickable="${!!f.click}" scrollable="${!!f.scroll}" bounds="[${l},${t}][${r},${b}]" />`;
const write = (dir, name, nodes) => { const d = path.join(root, dir); fs.mkdirSync(d, { recursive: true }); fs.writeFileSync(path.join(d, name + '.xml'), `<hierarchy>${nodes.join('')}</hierarchy>`); };
// One pane of a top-level page. x0 = pane left, cr = card right edge, o = mutation switches.
const pane = (x0, cr, o, full, title) => {
  const L = x0 + 16, N = [];
  N.push(nd('page-title', title, [L + (o.titleDx || 0), 70 + (o.titleDy || 0), L + 184, 102 + (o.titleDy || 0)]), nd('page-subtitle', 'Sub' + (o.sfx || ''), [L, 104 + (o.titleDy || 0), L + 184, 124 + (o.titleDy || 0) + (o.subDh || 0)]));
  if (full) {
    N.push(nd('chip', 'Uno', [x0 + 220, 70, x0 + 270, 102]), nd('chip', 'Due', [x0 + 280, 70, x0 + 330, 102 + (o.chipDh || 0)]));
    N.push(nd('accent-card', '', [L, 130, cr, 230]), nd('primary-figure', '1234', [L + 14, 138, L + 184, 138 + (o.pfH ?? 36)]));
    if (o.twoPf) N.push(nd('primary-figure', '9999', [L + 190, 138, L + 280, 174]));
    N.push(nd('secondary-figure', '56', [L + 14, 176, L + 104, 176 + (o.secH ?? 23)]), nd('stat-label', 'Label', [L + 14, 202, L + 104, 216]));
  }
  const cy = full ? 238 + (o.gapDy || 0) : 130, cardR = cr + (o.cardRDx || 0);
  N.push(nd('card', '', [L, cy, cardR, cy + 160]), nd('card-body', '', [L + 14 + (o.bodyDx || 0), cy + 14, cardR - 14, cy + 146]), nd('card-header', '', [L + 14, cy + 14, cardR - 14, cy + 46]),
    nd('card-title', (o.cardTitle || 'Flusso') + (o.sfx || ''), [L + 14, cy + 20 + (o.cardTitleDy || 0), L + 134, cy + 40 + (o.cardTitleDy || 0)]), nd('card-trailing', String(12 + (o.sfx ? 1 : 0)), [cardR - 94, cy + 20, cardR - 14, cy + 40]),
    nd('r1', 'Row' + (o.sfx || ''), [L + 14, cy + 62, L + 134, cy + 82]), nd('r2', o.copy || 'Last' + (o.sfx || ''), [L + 14, cy + 122, L + 134, cy + 146 + (o.lastDb || 0)]));
  if (o.touchDh) N.push(nd('btn', 'Go', [L + 150, cy + 60, L + 190, cy + 90], { click: true }));
  if (o.overlap) N.push(nd('r3', 'Novembre', [L + 40, cy + 66, L + 160, cy + 90]));
  if (o.clipText) N.push(nd('chip', 'Tre', [x0 + 220, 110, x0 + 250, 142]), nd('txt', 'Salta', [x0 + 214, 112, x0 + 262, 140]));
  if (full) {
    N.push(nd('attention-row', 'Da controllare', [L, 406 + (o.gapDy || 0), cardR, 462 + (o.gapDy || 0) + (o.attDh || 0)]), nd('section-label', 'Section', [L + (o.secDx || 0), 470, L + 100, 486]),
      nd('card', '', [L, 490, cr, 560]), nd('card-title', 'Versamenti', [L + 14, 510, L + 134, 530]), nd('r4', 'More', [L + 14, 530, L + 134, 546]));
    if (o.attBtn) N.push(nd('btn2', 'Controlla', [cardR - 90, 420, cardR - 20, 450], { click: true }));
  }
  return N;
};
const phone = (title, o = {}) => {
  const N = [nd('scroller', '', [0, 0, 360, 640], { scroll: true }), nd('page-content', '', [0, 60, 360, 700])];
  N.push(...pane(0, 344, o, true, title));
  const items = o.dock4 ? 4 : 5;
  N.push(nd('dock', '', [40 + (o.dockDx || 0), 648, 320 + (o.dockDx || 0), 712]));
  for (let i = 0; i < items; i++) N.push(nd('dock-item', '', [40 + (o.dockDx || 0) + i * 56, 652, 96 + (o.dockDx || 0) + i * 56, 708 - (o.touchDh || 0) * (i === 0 ? 1 : 0)], { click: true }));
  return N;
};
const fold = (title, o = {}) => [nd('scroller', '', [0, 0, 832, 696], { scroll: true }), nd('page-content', '', [0, 60, 416, 700]), nd('page-content', '', [416, 60, 832, 700]),
  nd('pane-left', '', [0, 24, o.splitR || 416, 696]), nd('pane-right', '', [416, 24, 832, 696]), ...pane(0, 400, o, true, title), ...pane(416, 776, { ...o, titleDx: 0, copy: '', cardRDx: 0, sfx: 'R', cardTitle: 'Flusso' }, false, 'Right'),
  nd('island', '', [792 - (o.islandDx || 0), 300 + (o.islandDy || 0), 820 - (o.islandDx || 0), 420 + (o.islandDy || 0)]), nd('gear', '', [796 - (o.islandDx || 0) + (o.gearDx || 0), 380 + (o.islandDy || 0), 816 - (o.islandDx || 0) + (o.gearDx || 0), 400 + (o.islandDy || 0)])];
const manifest = [];
// kind 'phone' = 3 phone pages (the third carries the mutation); 'fold' = 2 open-Fold pages (the second carries it)
const caseOf = (cr, name, kind, mut, rule, note) => {
  const dirs = { bad: `${cr}/${name}-bad`, good: `${cr}/${name}-good` };
  for (const [which, o] of [['bad', mut], ['good', {}]]) {
    const d = dirs[which];
    fs.rmSync(path.join(root, d), { recursive: true, force: true });
    if (kind === 'phone') { write(d, 'phone-top-pilastri', phone('Pilastri')); write(d, 'phone-top-spese', phone('Spese')); write(d, 'phone-top-futuro', phone('Futuro', { ...o, cardTitle: o.cardTitleDy ? 'Versamenti' : 'Flusso' })); }
    else { write(d, 'fold-top-pilastri', fold('Pilastri')); write(d, 'fold-top-futuro', fold('Futuro', o)); }
  }
  manifest.push({ cr, name, bad: dirs.bad, good: dirs.good, rule, note });
};
// ---- geometry cases (clean scene + one mutation)
caseOf('CR-08', 'versamenti-title-top', 'phone', { cardTitleDy: 6 }, 'card-title top inset', 'Versamenti title 6 dp lower than Flusso (user: versamenti is too high while flusso is good)');
caseOf('CR-09', 'versamenti-bottom', 'phone', { lastDb: -6 }, 'card-inset bottom', 'last row 6 dp short of the card bottom inset (user: same goes for the bottom)');
caseOf('CR-65', 'card-body-left', 'phone', { bodyDx: 6 }, 'card-body: inset left is 14 dp (CR-65)', 'card body inset differs from 14 dp');
caseOf('CR-66', 'salta-clipped', 'phone', { clipText: true }, 'clip: text box wider than its chip (CR-66)', '"Salta" wider than its pill');
caseOf('CR-69', 'ottobre-novembre', 'phone', { overlap: true }, 'clip: texts overlap (CR-69)', 'Ottob over Novembre (hero strip)');
caseOf('CR-05', 'header-x', 'phone', { titleDx: 6 }, 'page-header page-title x', 'page title x differs between pages (user: dimensioni, posizioni e allineamento diversi)');
caseOf('CR-05', 'header-y', 'phone', { titleDy: 6 }, 'page-header page-title y', 'page title y differs between pages');
caseOf('CR-05', 'subtitle-height', 'phone', { subDh: 6 }, 'page-header page-subtitle height', 'subtitle height differs between pages');
caseOf('CR-06', 'chip-height', 'phone', { chipDh: 8 }, 'chip height', 'chips of one type differ in height');
caseOf('CR-06', 'section-label-left', 'phone', { secDx: 6 }, 'label section left minus card left', 'section label not on the card left edge');
caseOf('CR-06', 'card-right-edge', 'phone', { cardRDx: -6 }, 'card-edge right (per pane)', 'card right edge differs');
caseOf('CR-62', 'gap-8', 'phone', { gapDy: 4 }, 'gap: 8 dp between stacked blocks', 'gap between stacked blocks is not 8 dp');
caseOf('CR-62', 'gutter-right', 'phone', { cardRDx: -6 }, 'gutter: block right edge on the 16 dp gutter (CR-62)', 'card right edge off the 16 dp gutter');
caseOf('CR-23', 'touch-under-48', 'phone', { touchDh: 12 }, 'touch: clickable at least 48 x 48 dp', 'clickable shorter than 48 dp');
caseOf('CR-27', 'dock-centre', 'phone', { dockDx: 10 }, 'frame: dock centre x equals screen centre', 'cover dock off the screen centre');
caseOf('CR-27', 'dock-5-items', 'phone', { dock4: true }, 'frame: exactly 5 dock-item', 'cover dock with 4 items');
caseOf('CR-33', 'copy-80', 'phone', { copy: 'x'.repeat(80) }, 'copy: text longer than 70 chars', 'explanation sentence on a working screen');
caseOf('CR-40', 'two-primary', 'phone', { twoPf: true }, 'figure: one primary-figure per pane', 'two primary figures in one pane');
caseOf('CR-55', 'two-primary-accent', 'phone', { twoPf: true }, 'accent: at most one accent-card and primary-figure per pane', 'more than one primary figure inside the accent card');
caseOf('CR-56', 'primary-vs-secondary', 'phone', { secH: 30 }, 'figure: primary >= 1.5 x secondary-figure (CR-56)', 'secondary figure too close to the primary figure');
caseOf('CR-59', 'attention-height', 'phone', { attDh: 6 }, 'attention: attention-row height 56 dp', 'attention row not 56 dp');
caseOf('CR-59', 'attention-button', 'phone', { attBtn: true }, 'attention: no button inside the attention-row (CR-59)', 'a "Controlla" button inside the quiet attention row');
caseOf('CR-07', 'gear-axis', 'fold', { gearDx: -10 }, 'frame: gear centre x equals island centre x (CR-07)', 'gear not on the island axis');
caseOf('CR-24', 'island-edge', 'fold', { islandDx: 8 }, 'frame: island 12 dp from the right edge', 'island 20 dp from the right edge');
caseOf('CR-24', 'island-centre', 'fold', { islandDy: 20 }, 'frame: island vertically centred', 'island 20 dp off the vertical centre');
caseOf('CR-41', 'pane-split', 'fold', { splitR: 440 }, 'panes: left pane ends at the split', 'left pane ends past the hinge');
// ---- single-page cases (no cross-page comparison)
const DOCK = [nd('dock', '', [40, 648, 320, 712]), ...[0, 1, 2, 3, 4].map((i) => nd('dock-item', '', [40 + i * 56, 652, 96 + i * 56, 708], { click: true }))];
const hd = (extra = []) => [nd('page-content', '', [0, 60, 360, 700]), nd('page-title', 'Spese', [16, 70, 120, 94]), nd('accent-card', '', [16, 200, 344, 280]), nd('primary-figure', '1234', [30, 210, 200, 246]), ...DOCK, ...extra];
const single = (cr, name, bad, good, rule, note, file = 'phone-top-spese', extra = {}) => {
  write(`${cr}/${name}-bad`, file, bad); write(`${cr}/${name}-good`, file, good); manifest.push({ cr, name, bad: `${cr}/${name}-bad`, good: `${cr}/${name}-good`, rule, note, ...extra });
};
const at = (id, text, y, x = 30, w = 120) => nd(id, text, [x, y, x + w, y + 16]);
const DUP = 'duplicate: a text or value appears once per screen (CR-70)';
single('CR-70', 'date-three-times', hd([at('', 'lun 9 nov', 110), at('', '9 nov', 130, 200, 60), at('', 'Aggiornato al 9 nov', 150)]), hd([at('', 'lun 9 nov', 110), at('', '10 nov', 130, 200, 60), at('', 'Aggiornato ieri', 150)]), DUP, 'user: I can see the date 3 times');
single('CR-70', 'amount-429-twice', hd([at('', 'Spesa 429 €', 110), at('', '429 €', 130, 300, 50)]), hd([at('', 'Spesa 429 €', 110), at('', 'Entrate 1.700 €', 130, 200, 100)]), DUP,
  'hero figure and chart end label both "429 €" (the chart label is synthetic: a canvas label is invisible in a real dump)');
single('CR-70', 'budget-nd-twice', hd([at('', 'budget n/d', 110), at('', 'budget n/d', 130)]), hd([at('', 'budget n/d', 110), at('', 'budget 120 €', 130)]), DUP, 'identical "budget n/d" in two nodes');
single('CR-70', 'flusso-441-twice', hd([at('', 'Spese 441 €', 110), at('', 'dal saldo 441 €', 130)]), hd([at('', 'Spese 441 €', 110), at('', 'dal saldo 512 €', 130)]), DUP, 'the Flusso amount repeated under two labels', 'phone-top-pilastri');
single('CR-70', 'range-endpoint-mese', hd([at('', '1 – 9 nov', 110), at('', '1 nov', 130, 200, 60)]), hd([at('', '1 – 9 nov', 110), at('', '12 nov', 130, 200, 60)]), DUP,
  'range "1-9 nov" and its own endpoint on Mese (dateRanges add-on, Italian)', 'phone-mese');
single('CR-70', 'symbol-chevrons', hd([at('', '‹', 110, 30, 30), at('', '›', 110, 300, 30), at('', '‹', 130, 30, 30), at('', '—', 130, 300, 30), at('', '—', 150, 300, 30)]),
  hd([at('', '‹', 110, 30, 30), at('', '›', 110, 300, 30), at('', '—', 130, 300, 30)]), '', 'NARROWING: a pure symbol (no letter, no digit) is never compared; the repeated "‹" and "—" in the bad twin must NOT be flagged', 'phone-top-spese', { clean: true });
const pv = (title, ptH) => [...DOCK, nd('page-content', '', [0, 60, 360, 700]), nd('page-title', title, [16, 70, 200, 94]), nd('page-subtitle', 'Sub line', [16, 96, 200, 112]), nd('period-viewport', '', [0, 120, 360, 300]),
  ...(ptH ? [nd('period-title', 'Ottobre', [16, 120, 200, 120 + ptH])] : [])];
single('CR-71', 'period-title-missing', pv('Mese', 0), pv('Mese', 32), 'period: swipe page exposes its current period in a `period-title` node (CR-71)', 'user: not even one is big; swipe page without the big period-title', 'phone-top-mese');
single('CR-71', 'period-title-small', pv('Mese', 12), pv('Mese', 32), 'period: period-title is the tallest text of the page header (CR-71)', 'period-title smaller than the page title', 'phone-top-mese');
// ---- peek (CR-01 / CR-67): the neighbour page's visible width inside the swiping viewport
const peek = (w, W = 360) => [...DOCK, nd('page-content', '', [0, 60, W, 700]), nd('page-title', 'Mese', [16, 70, 200, 94]), nd('period-viewport', '', [0, 100, W, 300]), nd('period-title', 'Ottobre', [16, 100, 200, 132]), nd('period-page-prev', 'x', [-344, 150, w, 250])];
single('CR-01', 'peek-too-small-cover', peek(8), peek(28), 'CR-01, user rule', 'user: the previous month is barely visible (2026-11-08), and again "è tornato a non vedersi" / "ancora un po troppo poco visibile" (2026-11-09): >= 28 dp on the cover; the wave 6 8 dp peek (agent decision D-14) is the bad fixture', 'phone-top-mese')
const foldPeek = (w) => [...fold('Pilastri'), nd('period-viewport', '', [416, 440, 832, 540]), nd('period-title', 'Ottobre', [432, 440, 600, 470]), nd('period-page-prev', 'x', [416 - 400, 450, 416 + w, 530])];
single('CR-01', 'peek-too-small-fold', foldPeek(8), foldPeek(40), 'CR-01, user rule', 'same rule on the open Fold: >= 40 dp; the 8 dp peek of output/consistency-2026-11-09k fold Mese is the bad fixture', 'fold-top-pilastri');
// CR-01 tightened 2026-11-10 (add-on peek-visible, K21): the hero month strip lies inside the accent card; its neighbour slot must hold a drawn surface (`period-page-surface`) at least the peek wide. Bad = the real
// output/consistency-2026-11-10d hero (slot 40 dp / 28 dp wide, nothing drawn in it: a blank indent); good twin = the tonal tile drawn on prev and cur.
const heroPeek = (fold_, w, surf) => { const [L, T, R] = fold_ ? [16, 190, 400] : [16, 205, 344], cur = [L + w + 8, T, R - w - 8 + L, T + 38];
  return [...(fold_ ? fold('Pilastri') : hd()), nd('period-viewport', '', [L, T, R, T + 38]), nd('period-label-cur', 'Ottobre', [130, 176, 250, 190]), nd('period-page-prev', '', [L - 400, T, L + w, T + 38]), nd('period-page-cur', '', cur),
    ...(surf ? [nd('period-page-surface', '', [L, T, L + w, T + 38]), nd('period-page-surface', '', cur)] : [])]; };
single('CR-01', 'peek-no-surface-fold', heroPeek(true, 40, false), heroPeek(true, 40, true), 'CR-01, no drawn peek', 'user 2026-11-10: "si deve vedere ancora più"; the hero strip of output/consistency-2026-11-10d/fold-top-pilastri.xml has a 40 dp neighbour slot and nothing drawn in it (a blank indent); the good twin draws the tonal tile', 'fold-top-pilastri');
single('CR-01', 'peek-no-surface-cover', heroPeek(false, 28, false), heroPeek(false, 28, true), 'CR-01, no drawn peek', 'same on the cover (phone-top-pilastri.xml: slot 28 dp, nothing drawn)', 'phone-top-pilastri');
// ---- G-11 range ends (CR-89, add-on peek-visible `endGutterDp`): user 2026-11-10 on Spese: "Qua perché così stretto?", then "va bene modifica schermata". Newest period: no next page, so the current page surface must reach the content margin.
const endsRule = 'G-11 range ends: the page surface stops short of the content margin on a side with no neighbour';
const ends = (fold_, mode) => { const [VL, VR, G, PK] = fold_ ? [0, 416, 48, 40] : [0, 360, 36, 28], M = 16, T = fold_ ? 190 : 205, B = T + 38;
  const [cl, cr, pr] = mode === 'bad' ? [VL + G, VR - G, VL + PK] : mode === 'good' ? [VL + G + (G - M), VR - M, VL + PK + (G - M)] : mode === 'single-bad' ? [VL + G, VR - G, null] : [VL + M, VR - M, null];
  return [...(fold_ ? fold('Pilastri') : hd()), nd('period-viewport', '', [VL, T, VR, B]), ...(pr === null ? [] : [nd('period-page-prev', '', [VL - 300, T, pr, B]), nd('period-page-surface', '', [VL, T, pr, B])]), nd('period-label-cur', 'Ottobre', [130, 176, 250, 190]), nd('period-page-cur', '', [cl, T, cr, B]), nd('period-page-surface', '', [cl, T, cr, B])]; };
single('CR-89', 'range-ends-cover', ends(false, 'bad'), ends(false, 'good'), endsRule, 'user 2026-11-10: "Qua perché così stretto?" / "va bene modifica schermata": newest period, no next page, the page stopped 36 dp from the edge instead of the 16 dp content margin', 'phone-top-pilastri');
single('CR-89', 'range-ends-fold', ends(true, 'bad'), ends(true, 'good'), endsRule, 'same on the open Fold (gutter 48 dp, margin 16 dp)', 'fold-top-pilastri');
single('CR-89', 'range-ends-single', ends(false, 'single-bad'), ends(false, 'single-good'), endsRule, 'a strip with one page spans margin to margin; the centred page leaves an empty band on both sides', 'phone-top-pilastri');
// CR-62 against the range-end geometry (user 2026-11-10 "va bene modifica schermata"; CR-62 takes the expected edges from the same rangeEndEdges as CR-89). A card inside the strip: good = on the range-end geometry
// (a shifted page at a range end, a one-page strip margin to margin, a centred middle page), bad = a card at any other offset. Directory CR-89/ so the config entry with endGutterDp applies.
const g62Rule = 'gutter: block left edge on the 16 dp gutter (CR-62)';
const g62 = (kind, cl) => { const VR = 360, G = 36, PK = 28, M = 16, sh = G - M;
  const L = kind === 'single' ? [M, VR - M] : kind === 'mid' ? [G, VR - G] : [G + sh, VR - M], c = cl ?? L[0];
  const pr = PK + (kind === 'mid' ? 0 : sh), nb = kind === 'single' ? [] : [nd('period-page-prev', '', [-332, 300, pr, 400]), nd('period-page-surface', '', [0, 300, pr, 400])];
  const nx = kind === 'mid' ? [nd('period-page-next', '', [VR - PK, 300, VR, 400]), nd('period-page-surface', '', [VR - PK, 300, VR, 400])] : [];
  return hd([nd('period-viewport', '', [0, 288, VR, 420]), nd('period-title', 'Ottobre', [16, 130, 200, 160]), ...nb, ...nx, nd('period-page-cur', '', [L[0], 288, L[1], 400]), nd('period-page-surface', '', [L[0], 288, L[1], 400]), nd('card', '', [c, 288, L[1], 400])]); };
single('CR-89', 'page-gutter-range-end-cover', g62('end', 46), g62('end'), g62Rule, 'CR-62 range ends: the newest page rests on the right margin (card 56..344 on the cover); a card at another offset is a deviation', 'phone-top-spese');
single('CR-89', 'page-gutter-single', g62('single', 30), g62('single'), g62Rule, 'a one-page strip: card margin to margin (16)', 'phone-top-spese');
single('CR-89', 'page-gutter-middle', g62('mid', 46), g62('mid'), g62Rule, 'a page with both neighbours stays centred (card 36); a card at another offset is a deviation', 'phone-top-spese');
// K2 wave 6 (reviewer R1-03; user "Ottobre è allineato a caso"): the G-11 narrowing is a deviation. Bad = cards inside a period-viewport at gutter + 6 dp (the pre-fix app, output/consistency-2026-11-09f phone-mese-rest / phone-spese-rest: x = 16 + 6), good twin = on the gutter line with the 8 dp peek.
const narrowing = (l) => hd([nd('period-viewport', '', [0, 288, 360, 420]), nd('period-title', 'Ottobre', [16, 130, 200, 160]), nd('period-page-prev', 'x', [-332, 300, 28, 400]), nd('card', '', [l, 288, 360 - l, 400])]);
single('CR-62', 'narrowing', narrowing(22), narrowing(36), 'gutter: block left edge on the 16 dp gutter (CR-62)', 'cards inside a period-viewport are on the gutter line like every other card (G-11 narrowing removed 2026-11-09)', 'phone-top-spese');
// ---- co-motion (CR-53 / CR-68): rest + moving dumps
const pairs = (cr, name, bad, rule, note) => {
  for (const [which, o] of [['bad', bad], ['good', {}]]) {
    const mv = (dx) => [...DOCK, nd('page-content', '', [0, 60, 360, 700]), nd('r', 'Row', [30 + dx, 172, 150 + dx, 186]), nd('card', '', [16 + dx, 100, 344 + dx, 200]), nd('card-title', 'Flusso', [30 + dx * (o.titleStays ? 0 : 1), 110, 150 + dx * (o.titleStays ? 0 : 1), 130]), nd('period-page-cur', '', [0 + dx, 100, 360 + dx, 300]),
      nd('period-label-cur', 'Ottobre', [16 + dx * (o.labelStays ? 0 : 1), 80, 100 + dx * (o.labelStays ? 0 : 1), 96])];
    write(`${cr}/${name}-${which}`, 'phone-top-mese-rest', mv(0)); write(`${cr}/${name}-${which}`, 'phone-top-mese-moving', mv(-50));
  }
  manifest.push({ cr, name, bad: `${cr}/${name}-bad`, good: `${cr}/${name}-good`, rule, note });
};
pairs('CR-68', 'label-not-moving', { labelStays: true }, 'period label moves like its page (CR-68)', 'user: the month of the card must scroll together with the card');
pairs('CR-53', 'title-not-moving', { titleStays: true }, 'CO-MOTION card-title vs its card', 'card title stays while its card moves (what belongs together moves together)');
// R2-M1 (wave 6): device dumps (output/consistency-2026-11-09f phone-spese-rest/-moving), copied as they are; the good twin only moves the period-title bounds with its page. Not written by this script.
manifest.push({ cr: 'CR-68', name: 'title-still', bad: 'CR-68/title-still-bad', good: 'CR-68/title-still-good', rule: 'period-title moves like its page (CR-68, M1)', note: 'user: "se cose appartengono allo stesso elemento, quasi sempre avranno lo stesso movimento" (Spese period-title stayed while its content moved)' });
// K2 wave 6: title-absent-bad = title-still-good with the period-title removed from the moving dump (explicit capture row); good twin = title-still-good itself.
{ const src = path.join(root, 'CR-68', 'title-still-good'), dst = path.join(root, 'CR-68', 'title-absent-bad'); fs.mkdirSync(dst, { recursive: true });
  fs.copyFileSync(path.join(src, 'phone-top-spese-rest.xml'), path.join(dst, 'phone-top-spese-rest.xml'));
  fs.writeFileSync(path.join(dst, 'phone-top-spese-moving.xml'), fs.readFileSync(path.join(src, 'phone-top-spese-moving.xml'), 'utf8').replace(/<node [^>]*id\/period-title"[^>]*\/>/, ''));
  manifest.push({ cr: 'CR-68', name: 'title-absent', bad: 'CR-68/title-absent-bad', good: 'CR-68/title-still-good', rule: 'capture: period-title absent or zero-width', note: 'K2 wave 6: a period-title present at rest and missing in the moving dump is an explicit capture row, never a silent pass' }); }
fs.writeFileSync(path.join(root, 'manifest.json'), JSON.stringify(manifest, null, 1) + '\n');
console.log(manifest.length + ' cases');
