// Self-test of consistency-check.mjs (run: node consistency-check.mjs --self-test). Synthetic fixtures (scale 1, dp = px) are written to fixtures/selftest/: a clean scene of three narrow
// and two wide screens, and one mutation per rule type that must be flagged by exactly the rule it targets. The configs written here (config-full.json ...) are SELF-TEST-ONLY:
// fixture values, not defaults; a project starts from consistency.config.example.json. Scenes use neutral words; the locale add-on tests (it, en) are a labelled section at the end.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export async function selfTest(here, script) {
  const root = path.join(here, 'fixtures', 'selftest');
  fs.rmSync(root, { recursive: true, force: true });
  fs.mkdirSync(root, { recursive: true });
  const nd = (id, text, [l, t, r, b], f = {}) => `<node resource-id="app:id/${id}" text="${text}" clickable="${!!f.click}" scrollable="${!!f.scroll}" bounds="[${l},${t}][${r},${b}]" />`;
  const write = (d, name, nodes) => { fs.mkdirSync(path.join(root, d), { recursive: true }); fs.writeFileSync(path.join(root, d, name + '.xml'), `<hierarchy>${nodes.join('')}</hierarchy>`); };
  const tags = { container: 'card', accentCard: 'accent-card', primaryFigure: 'primary-figure', secondaryFigure: 'secondary-figure', statLabel: 'stat-label', pageContent: 'page-content', pageTitle: 'page-title', pageSubtitle: 'page-subtitle', pageTrailing: 'page-trailing',
    chip: 'chip', pill: 'pill', cardTitle: 'card-title', cardTrailing: 'card-trailing', cardHeader: 'card-header', cardBody: 'card-body', cardBodyRows: 'card-body-rows', contentRow: 'content-row', attentionRow: 'attention-row', actionRow: 'action-row',
    dock: 'dock', dockItem: 'dock-item', island: 'island', gear: 'gear', paneLeft: 'pane-left', paneRight: 'pane-right', panePrefix: 'pane-', chart: 'chart', iconDisc: 'icon-disc', periodViewport: 'period-viewport',
    periodPagePrefix: 'period-page-', periodPagePrev: 'period-page-prev', periodPageNext: 'period-page-next', periodLabelPrefix: 'period-label-', periodLabelPrev: 'period-label-prev', periodLabelNext: 'period-label-next', periodLabelCur: 'period-label-cur', periodTitle: 'period-title', pageHeader: 'page-header', periodChip: 'period-chip', sectionLabel: 'section-label' };
  const full = { _comment: 'SELF-TEST ONLY: fixture values, not defaults. Every add-on and every check on.', tolerance: 1, adapter: 'uiautomator', addons: ['frame-dock-island', 'pager-peek', 'pager-period', 'hierarchy-opinions'], allowAbsent: ['peek', 'periodLabels', 'period', 'periodMotion'],   // the clean scene draws no pager; the pager scenes below do
    wideLayouts: ['wide'], narrowLayouts: ['narrow'], layoutLabels: { wide: 'wide', narrow: 'narrow' }, naming: { layoutSeparator: '-', topMarker: '-top-', setupMarker: '-setup-', fontScaleMarker: '-fs2', fontScaleLabel: 'fs2' },
    hierarchy: { accentScreens: ['a', 'b', 'c'] }, floating: ['dock', 'island', 'gear'], blockRoles: ['container', 'accentCard', 'attentionRow'], clipBoxRoles: ['pill', 'chip', 'container', 'actionRow'], clipRoles: ['pageContent', 'periodViewport'],
    excludeRoles: { cardInset: ['container', 'contentRow', 'accentCard', 'attentionRow', 'dockItem'], cardBody: ['container', 'cardBody', 'cardBodyRows', 'cardHeader', 'contentRow', 'accentCard', 'attentionRow', 'dockItem', 'actionRow', 'pageContent'], overlap: ['periodPagePrefix', 'pageContent'] },
    tags, tokens: { cardInset: 14, headerRowH: 32, gutter: 16, gap: 8, attentionH: 56, minTouch: 48, maxCopy: 70, peekWide: 24, peekNarrow: 16, peekGap: 6, accentPad: 16, islandEdge: 12, islandCentreTol: 4, dockItems: 5, paneMinTexts: 3, paneSplitTol: 4, figureTopRatio: 0.4,
      ratioSecondary: 1.3, ratioLabel: 2.0, ratioSecondaryName: 1.5, ratioLabelName: 2.3 },
    checks: Object.fromEntries(['cardInset', 'cardBody', 'clip', 'overlap', 'peek', 'periodLabels', 'pageHeader', 'labels', 'cardEdges', 'chipHeight', 'firstContent', 'primaryFigure', 'frame', 'panes', 'touch', 'copy', 'accent', 'figureScale', 'attention', 'gutterGap', 'duplicate', 'period', 'periodMotion', 'coMotion'].map((k) => [k, true])) };
  const cfgIt = { ...full, locale: ['it'], dateLocale: 'it', checks: { ...full.checks, dateRanges: true } }, cfgEn = { ...full, locale: ['en'], dateLocale: 'en', checks: { ...full.checks, dateRanges: true } };
  const wj = (f, o) => fs.writeFileSync(path.join(root, f), JSON.stringify(o, null, 1));
  wj('config-full.json', full); wj('config-it.json', cfgIt); wj('config-en.json', cfgEn);
  // One pane of a page. x0 = pane left, cr = card right edge, o = mutation switches.
  const pane = (x0, cr, o, fullPane, title = 'Home') => {
    const L = x0 + 16, N = [];
    N.push(nd('page-title', title, [L + (o.titleDx || 0), 70 + (o.titleDy || 0), L + 184, 102 + (o.titleDy || 0)]), nd('page-subtitle', 'Sub' + (o.sfx || ''), [L, 104 + (o.titleDy || 0), L + 184, 124 + (o.titleDy || 0) + (o.subDh || 0)]));
    if (fullPane) {
      N.push(nd('chip', 'One', [x0 + 220, 70, x0 + 270, 102]), nd('chip', 'Two', [x0 + 280, 70, x0 + 330, 102 + (o.chipDh || 0)]));
      N.push(nd('accent-card', '', [L, 130, cr, 230 + (o.accentDh || 0)]), nd('primary-figure', '1234', [L + 14, 138, L + 184, 138 + (o.pfH ?? 36)]));
      if (o.twoPf) N.push(nd('primary-figure', '9999', [L + 190, 138, L + 280, 174]));
      N.push(nd('secondary-figure', '56', [L + 14, 176, L + 104, 199]), nd('stat-label', 'Label', [L + 14, 202, L + 104, 216]));
    }
    const cy = fullPane ? 238 + (o.gapDy || 0) : 130, cardR = cr + (o.cardRDx || 0);
    N.push(nd('card', '', [L, cy, cardR, cy + 160]), nd('card-body', '', [L + 14 + (o.bodyDx || 0), cy + 14, cardR - 14, cy + 146]), nd('card-header', '', [L + 14, cy + 14, cardR - 14, cy + 46]),
      nd('card-title', 'Title' + (o.sfx || ''), [L + 14, cy + 20 + (o.cardTitleDy || 0), L + 134, cy + 40 + (o.cardTitleDy || 0)]), nd('card-trailing', String(12 + (o.sfx ? 1 : 0)), [cardR - 94, cy + 20, cardR - 14, cy + 40]),
      nd('r1', 'Row' + (o.sfx || ''), [L + 14, cy + 62, L + 134, cy + 82]), nd('r2', o.copy || 'Last' + (o.sfx || ''), [L + 14, cy + 122, L + 134, cy + 146 + (o.lastDb || 0)]));
    if (o.touchDh) N.push(nd('btn', 'Go', [L + 150, cy + 60, L + 190, cy + 90], { click: true }));
    if (o.overlap) N.push(nd('r3', 'Clash', [L + 40, cy + 66, L + 160, cy + 90]));
    if (o.clipText) N.push(nd('chip', 'Tre', [x0 + 220, 110, x0 + 250, 142]), nd('txt', 'Wide label', [x0 + 214, 112, x0 + 262, 140]));
    if (fullPane) {
      N.push(nd('attention-row', 'Attention', [L, 406 + (o.gapDy || 0), cardR, 462 + (o.gapDy || 0) + (o.attDh || 0)]), nd('section-label', 'Section', [L + (o.secDx || 0), 470, L + 100, 486]),
        nd('card', '', [L, 490, cr, 560]), nd('card-title', 'Other', [L + 14, 510, L + 134, 530]), nd('r4', 'More', [L + 14, 530, L + 134, 546]));
    }
    return N;
  };
  const narrowScreen = (o = {}) => {
    const N = [nd('scroller', '', [0, 0, 360, 640], { scroll: true }), nd('page-content', '', [0, 60, 360, 700])];
    N.push(...pane(0, 344, o, true));
    const items = o.dock4 ? 4 : 5;
    N.push(nd('dock', '', [40 + (o.dockDx || 0), 648, 320 + (o.dockDx || 0), 712]));
    for (let i = 0; i < items; i++) N.push(nd('dock-item', '', [40 + (o.dockDx || 0) + i * 56, 652, 96 + (o.dockDx || 0) + i * 56, 708 - (o.touchDh || 0) * (i === 0 ? 1 : 0)], { click: true }));
    return N;
  };
  const wideScreen = (o = {}) => [nd('scroller', '', [0, 0, 832, 696], { scroll: true }), nd('page-content', '', [0, 60, 416, 700]), nd('page-content', '', [416, 60, 832, 700]),
    nd('pane-left', '', [0, 24, o.splitR || 416, 696]), nd('pane-right', '', [416, 24, 832, 696]), ...pane(0, 400, o, true), ...pane(416, 776, { ...o, titleDx: 0, copy: '', cardRDx: 0, sfx: 'R' }, false, 'Right'),
    nd('island', '', [792 - (o.islandDx || 0), 300 + (o.islandDy || 0), 820 - (o.islandDx || 0), 420 + (o.islandDy || 0)]), nd('gear', '', [796 - (o.islandDx || 0), 380 + (o.islandDy || 0), 816 - (o.islandDx || 0), 400 + (o.islandDy || 0)])];
  const scene = (d, bad = {}, wideBad = {}) => {
    write(d, 'narrow-top-a', narrowScreen()); write(d, 'narrow-top-b', narrowScreen()); write(d, 'narrow-top-c', narrowScreen(bad));
    write(d, 'wide-top-a', wideScreen()); write(d, 'wide-top-b', wideScreen(wideBad));
  };
  scene('good');
  const run = (d, cf = 'config-full.json', extra = []) => { try { return [0, execFileSync(process.execPath, [script, path.join(root, d), '--scale', '1', '--config', path.join(root, cf), '--exceptions', '/nonexistent', ...extra], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })]; } catch (e) { return [e.status, String(e.stdout)]; } };
  let fails = 0;
  const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) { fails++; process.exitCode = 1; } };
  const [cg, og] = run('good');
  ok(cg === 0 && /Deviations: \*\*0\*\*/.test(og), 'clean scene (3 narrow + 2 wide screens, every check and add-on on): 0 deviations' + (cg ? '\n' + og.split('\n').filter((l) => l.startsWith('|')).slice(2, 12).join('\n') : ''));
  ok(/scale 1, density 1,/.test(og), 'report header carries `scale N,` and the deprecated `density N,` alias');
  const cases = [   // [dir, narrow mutation, wide mutation, expected rule substring]
    ['cardInset', { cardTitleDy: 6 }, {}, 'card-title top inset'], ['cardInset-bottom', { lastDb: -6 }, {}, 'card-inset bottom'],
    ['cardBody', { bodyDx: 6 }, {}, 'card-body: inset left is 14 dp'], ['clip', { clipText: true }, {}, 'clip: text box wider than its chip'],
    ['overlap', { overlap: true }, {}, 'clip: texts overlap'], ['pageHeader-y', { titleDy: 6 }, {}, 'page-header page-title y'], ['pageHeader-x', { titleDx: 6 }, {}, 'page-header page-title x'],
    ['pageHeader-h', { subDh: 6 }, {}, 'page-header page-subtitle height'], ['labels', { secDx: 6 }, {}, 'label section left minus card left'], ['cardEdges', { cardRDx: -6 }, {}, 'card-edge right (per pane)'],
    ['chipHeight', { chipDh: 8 }, {}, 'chip height'], ['firstContent', { twoPf: true }, {}, 'figure: one primary-figure per pane'], ['accent', { accentDh: 0, pfH: 36, twoPf: true }, {}, 'accent: at most one accent-card and primary-figure per pane'],
    ['figureScale', { pfH: 26 }, {}, 'figure: primary larger than page-title'], ['attention', { attDh: 6 }, {}, 'attention: attention-row height 56 dp'], ['gutter', { cardRDx: 0, gapDy: 4 }, {}, 'gap: 8 dp between stacked blocks'],
    ['touch', { touchDh: 12 }, {}, 'touch: clickable at least 48 x 48 dp'], ['copy', { copy: 'x'.repeat(80) }, {}, 'copy: text longer than 70 chars'], ['dock', { dockDx: 10 }, {}, 'frame: dock centre x equals screen centre'],
    ['dock-items', { dock4: true }, {}, 'frame: exactly 5 dock-item'], ['island', {}, { islandDx: 8 }, 'frame: island 12 dp from the right edge'], ['island-centre', {}, { islandDy: 20 }, 'frame: island vertically centred'],
    ['panes', {}, { splitR: 440 }, 'panes: left pane ends at the split']];
  for (const [d, nb, wb, rule] of cases) { scene(d, nb, wb); const [, o] = run(d); ok(o.includes(rule), `bad: ${d} is flagged by "${rule}"`); }
  scene('gutter', { cardRDx: -6 }, {}); { const [, o] = run('gutter'); ok(o.includes('gutter: block right edge on the 16 dp gutter'), 'bad: gutter right edge is flagged by "gutter: block right edge"'); }

  // ---- project rule ids: the base prints neutral rule names; config.ruleIds adds the project's own id after the text
  wj('config-ids.json', { ...full, ruleIds: { cardBody: 'X-65', coMotion: 'X-MOTION' } });
  { const [, o] = run('cardBody', 'config-ids.json'); ok(o.includes('card-body: inset left is 14 dp (X-65)'), 'rule ids: config.ruleIds appends the project id to the neutral rule name'); }

  // ---- generic mechanisms (base, no add-on): singleton, heightRatio, alignRules (criteria H1, H2, K6 in config form)
  const gen = { tolerance: 1, wideLayouts: ['wide'], narrowLayouts: ['narrow'], naming: { topMarker: '-top-' }, tags, tokens: {}, checks: {},
    singleton: [{ role: 'primaryFigure', scope: 'pane', max: 1 }], heightRatio: [{ role: 'primaryFigure', over: 'pageTitle', min: 1.01 }], alignRules: [{ role: 'dock', layouts: 'narrow', centreX: true }, { role: 'island', layouts: 'wide', fromRightEdge: 12 }] };
  wj('config-generic.json', gen);
  { const [c, o] = run('good', 'config-generic.json'); ok(c === 0 && /Deviations: \*\*0\*\*/.test(o), 'generic mechanisms: clean scene gives 0 deviations (singleton, heightRatio, alignRules, no add-on)'); }
  scene('generic-singleton', { twoPf: true }); { const [, o] = run('generic-singleton', 'config-generic.json'); ok(o.includes('singleton: at most 1 primary-figure per pane'), 'generic singleton: two primary-figure in one pane is flagged'); }
  scene('generic-ratio', { pfH: 26 }); { const [, o] = run('generic-ratio', 'config-generic.json'); ok(o.includes('ratio: primary-figure height >= 1.01 x page-title'), 'generic heightRatio: a primary figure not taller than the page title is flagged'); }
  scene('generic-align', { dockDx: 10 }, { islandDx: 8 }); { const [, o] = run('generic-align', 'config-generic.json'); ok(o.includes('align: dock centre x equals screen centre') && o.includes('align: island 12 dp from the right edge'), 'generic alignRules: a tagged element off-centre or off its edge distance is flagged'); }

  // ---- no silent skips
  { const c = JSON.parse(JSON.stringify(full)); delete c.tags.chip; wj('config-notag.json', c); const [code, o] = run('good', 'config-notag.json', ['--strict']);
    ok(/## Checks skipped/.test(o) && /chipHeight: no tag configured/.test(o) && code === 1, 'no silent skips: an enabled check without a configured tag is listed under "Checks skipped" and fails with --strict'); }
  { const c = JSON.parse(JSON.stringify(full)); c.tags.sectionLabel = 'no-such-tag'; wj('config-absent.json', c); const [code, o] = run('good', 'config-absent.json');
    ok(code === 1 && /nothing checked: tag no-such-tag never appears in any dump \(check labels\)/.test(o), 'no silent skips: an enabled check whose tag never appears fails ("nothing was checked")');
    c.allowAbsent = [...c.allowAbsent, 'labels']; wj('config-absent2.json', c); const [code2, o2] = run('good', 'config-absent2.json'); ok(code2 === 0 && /labels: tag no-such-tag never appears in any dump \(allowed by allowAbsent\)/.test(o2), 'no silent skips: config.allowAbsent turns it into a listed, allowed skip'); }
  { wj('config-unknown.json', { ...full, checks: { ...full.checks, notACheck: true } }); const [c] = run('good', 'config-unknown.json'); ok(c === 2, 'config guard: an unknown check key exits 2'); }
  { wj('config-noaddon.json', { ...full, addons: [] }); const [c] = run('good', 'config-noaddon.json'); ok(c === 2, 'config guard: a check of an add-on that is not listed in config.addons exits 2'); }
  { const bad = JSON.parse(JSON.stringify(full)); delete bad.tokens.gutter; wj('config-notoken.json', bad); const [c] = run('good', 'config-notoken.json'); ok(c === 2, 'config guard: an enabled check with a missing token exits 2'); }
  { const [c] = run('good', 'consistency.config.example.json'.replace(/^/, '../../')); ok(c !== 2, 'the minimal example config loads and runs (no config error)'); }

  // ---- adapter: the same dump as JSON nodes through adapters/json-nodes.mjs
  { const xml = fs.readFileSync(path.join(root, 'good', 'narrow-top-a.xml'), 'utf8'); const nodes = [...xml.matchAll(/<node\b([^>]*?)\/>/g)].map((m) => { const a = {}; for (const k of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) a[k[1]] = k[2]; const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds); return { id: a['resource-id'], text: a.text, clickable: a.clickable === 'true', scroll: a.scrollable === 'true', bounds: b.slice(1, 5).map(Number) }; });
    fs.mkdirSync(path.join(root, 'json-adapter'), { recursive: true }); fs.writeFileSync(path.join(root, 'json-adapter', 'narrow-top-a.json'), JSON.stringify({ nodes }));
    const j = { ...full, adapter: 'json-nodes', addons: [], checks: { cardInset: true, pageHeader: true, touch: true, copy: true, duplicate: true } }; wj('config-json.json', j);
    const [c, o] = run('json-adapter', 'config-json.json'); ok(c === 0 && /Dumps: 1 /.test(o), 'adapter json-nodes: the same screen as JSON nodes gives 0 deviations'); }

  // ---- duplicates (base): identical text and a repeated value token with its attached unit, no locale, no add-on; filters are data naming ONE element each
  const hd = [nd('page-content', '', [0, 60, 360, 700]), nd('page-title', 'T', [16, 70, 120, 94])];
  const dupOut = (o) => o.split(String.fromCharCode(10)).filter((l) => l.startsWith('|') && /duplicate: a text or value/.test(l));
  wj('config-base.json', { ...full, locale: [], dateLocale: undefined, checks: { ...full.checks } });
  write('dup-base-bad', 'narrow-top-f', [...hd, nd('x', 'Spending', [30, 110, 120, 126]), nd('x', ' spending', [30, 130, 120, 146]), nd('x', 'Item 429 €', [30, 150, 140, 166]), nd('x', 'Other 429 €', [30, 170, 140, 186]),
    nd('x', '9 Sep – 8 Oct', [30, 190, 140, 206]), nd('x', '9 Sep', [200, 190, 260, 206]), nd('x', '17%', [30, 210, 60, 226]), nd('x', 'Over 17 %', [100, 210, 200, 226])]);
  write('dup-base-good', 'narrow-top-f', [...hd, nd('x', 'Spending', [30, 110, 120, 126]), nd('x', 'Income', [30, 130, 120, 146]), nd('x', 'Item 429 €', [30, 150, 140, 166]), nd('x', 'Other 1.700 €', [30, 170, 140, 186]),
    nd('x', '9 Sep – 8 Oct', [30, 190, 140, 206]), nd('x', '10 Nov', [200, 190, 260, 206]), nd('x', '17%', [30, 210, 60, 226]), nd('x', '18 %', [100, 210, 200, 226]), nd('x', '-40 €', [30, 230, 80, 246]), nd('x', '41 €', [200, 230, 250, 246])]);
  { const [, o] = run('dup-base-bad', 'config-base.json'), r = dupOut(o);
    ok(r.some((l) => l.includes('"Spending" + " spending"') || l.includes('"Spending" + "spending"')), 'base bad: identical text (whitespace and case only) in two nodes is flagged');
    ok(r.some((l) => l.includes('"Item 429 €" + "Other 429 €"')), 'base bad: the same amount under different labels is flagged');
    ok(r.some((l) => l.includes('"9 Sep – 8 Oct" + "9 Sep"')), 'base bad: a token with its attached word ("9 Sep") in two nodes is flagged, with no locale');
    ok(r.some((l) => l.includes('"17%" + "Over 17 %"')), 'base bad: the same percentage in two nodes is flagged'); }
  { const [, o] = run('dup-base-good', 'config-base.json'); ok(dupOut(o).length === 0, 'base good: distinct texts and distinct tokens ("-40 €" vs "41 €") are not flagged'); }
  write('dup-filter', 'narrow-top-f', [...hd, nd('x', 'Sync', [30, 110, 60, 126]), nd('x', 'Sync', [200, 110, 230, 126]), nd('x', 'Spending', [30, 140, 120, 156]), nd('x', 'Spending', [30, 160, 120, 176])]);
  wj('filters-sync.json', [{ screen: 'narrow-top-f', text: '^\\s*Sync\\s*$', reason: 'sync label of the period navigation', decidedBy: 'user', date: '2026-11-09' }]);
  wj('config-filter.json', { ...full, locale: [], duplicateFilters: 'filters-sync.json' });
  { const [, o] = run('dup-filter', 'config-base.json'); ok(dupOut(o).length === 2, 'filters: without a filter file both repetitions ("Sync", "Spending") are flagged'); }
  { const [, o] = run('dup-filter', 'config-filter.json'); ok(dupOut(o).length === 1 && dupOut(o)[0].includes('"Spending" + "Spending"'), 'filters: the element named by a filter is silenced, the other repetition stays flagged');
    ok(/Duplicate filters applied/.test(o) && /sync label of the period navigation \| 2 \|/.test(o), 'filters: the report lists the applied filter with the number of elements it silenced'); }
  write('dup-symbol', 'narrow-top-f', [...hd, nd('x', '‹', [30, 110, 60, 126]), nd('x', '‹', [200, 110, 230, 126]), nd('x', '—', [30, 130, 60, 146]), nd('x', '—', [200, 130, 230, 146]), nd('x', '›', [300, 110, 330, 126]), nd('x', '›', [300, 130, 330, 146])]);
  write('dup-symbol-word', 'narrow-top-f', [...hd, nd('x', '‹ Oct', [30, 110, 90, 126]), nd('x', '‹ Oct', [200, 110, 260, 126])]);
  // scope by config: repetition is judged per single screen; a wide two-pane layout is not checked (the same text in two panes is not a repetition)
  write('dup-wide-pane', 'wide-top-f', [...hd, nd('x', 'Month', [30, 110, 100, 126]), nd('x', 'Month', [200, 110, 270, 126])]);
  wj('config-scope.json', { ...full, locale: [], duplicateScreens: ['narrow-*'] });
  { const [, o0] = run('dup-wide-pane', 'config-base.json'), [, o1] = run('dup-wide-pane', 'config-scope.json'), [, o2] = run('dup-base-bad', 'config-scope.json');
    ok(dupOut(o0).length === 1, 'scope: without duplicateScreens the same text in two panes of a wide layout is flagged');
    ok(dupOut(o1).length === 0 && /Duplicates: checked on narrow-\* only/.test(o1), 'scope: with duplicateScreens ["narrow-*"] a wide layout is not checked for repetition and the report states the scope');
    ok(dupOut(o2).length > 0, 'scope: a single-screen (narrow) layout is still checked'); }
  { const [, o] = run('dup-symbol', 'config-base.json'); ok(dupOut(o).length === 0, 'narrowing: repeated pure symbols are not compared, with no filter file'); }
  { const [, o] = run('dup-symbol-word', 'config-base.json'); ok(dupOut(o).length === 1, 'narrowing: a symbol with a word ("‹ Oct") twice is still a duplicate'); }
  // stop words are locale data: "3 of 9" twice repeats the unit-token "3of" in the base, and not once config.locale names a locale that lists "of" as a stop word
  write('dup-stop', 'narrow-top-f', [...hd, nd('x', 'A 3 of 9', [30, 110, 120, 126]), nd('x', 'B 3 of 4', [30, 130, 120, 146])]);
  { const [, o] = run('dup-stop', 'config-base.json'); ok(dupOut(o).length === 1, 'locale data: without config.locale the attached word "of" is a unit, so "3 of" repeats'); }
  { const [, o] = run('dup-stop', 'config-en.json'); ok(dupOut(o).length === 0, 'locale data: config.locale "en" lists "of" as a stop word, so it is not a unit'); }

  // ---- period-title, peek and motion add-ons (neutral words)
  const periodScene = (d, ptH) => write(d, 'narrow-top-d', [nd('page-content', '', [0, 60, 360, 700]), nd('page-title', 'Month', [16, 70, 200, 94]), nd('page-subtitle', 'Sub line', [16, 96, 200, 112]), nd('chip', 'Chip', [220, 70, 280, 102]),
    nd('period-viewport', '', [0, 120, 360, 300]), ...(ptH ? [nd('period-title', 'October', [16, 120, 200, 120 + ptH])] : [])]);
  write('period-trailing', 'narrow-top-d', [nd('page-content', '', [0, 60, 360, 700]), nd('page-title', 'Month', [16, 70, 200, 94]), nd('page-trailing', '', [220, 60, 340, 108]), nd('period-viewport', '', [0, 120, 360, 300]), nd('period-title', 'October', [16, 70, 200, 94])]);
  periodScene('period-none', 0); periodScene('period-ok', 32); periodScene('period-tall', 12);
  { const [, o] = run('period-none'); ok(/period: swipe page exposes its current period/.test(o), 'bad: page with period viewport and no period-title is flagged (pager-period)'); }
  { const [, o] = run('period-ok'); ok(!/period: /.test(o), 'good: tagged period-title is not flagged'); }
  { const [, o] = run('period-trailing'); ok(!/period: period-title is the tallest/.test(o), 'good: an empty-text page-trailing touch box (48 tall) is a container, not header text (pager-period)'); }
  { const [, o] = run('period-tall'); ok(/period: period-title is the tallest text of the page header/.test(o), 'bad: period-title shorter than another header text is flagged'); }
  const peek = (d, w) => write(d, 'narrow-top-e', [nd('period-viewport', '', [0, 100, 360, 300]), nd('period-page-prev', 'x', [-344, 150, w, 250])]);
  peek('peek-good', 16); peek('peek-bad', 30);
  { const [, g] = run('peek-good'); const [, b] = run('peek-bad'); ok(!/neighbour page peek/.test(g), 'good: neighbour peek 16 dp on the narrow layout is not flagged (pager-peek)'); ok(/neighbour page peek/.test(b), 'bad: neighbour peek 30 dp on the narrow layout is flagged'); }
  const pairScene = (d, o) => {
    const mv = (dx) => [nd('card', '', [16 + dx, 100, 344 + dx, 200]), nd('card-title', 'Title', [30 + dx * (o.titleStays ? 0 : 1), 110, 150 + dx * (o.titleStays ? 0 : 1), 130]), nd('period-page-cur', '', [20 + dx, 100, 340 + dx, 300]), nd('period-label-cur', 'Cur', [16 + dx * (o.labelStays ? 0 : 1), 80, 100 + dx * (o.labelStays ? 0 : 1), 96]), nd('period-viewport', '', [0, 100, 360, 300]), nd('period-title', 'Oct', [16 + dx * (o.pTitleStays ? 0 : 1), 40, 120 + dx * (o.pTitleStays ? 0 : 1), 70])];
    write(d, 'narrow-top-m-rest', mv(0)); write(d, 'narrow-top-m-moving', mv(o.still ? 0 : -50));
  };
  pairScene('motion-good', {}); pairScene('motion-title', { titleStays: true }); pairScene('motion-label', { labelStays: true }); pairScene('motion-still', { still: true }); pairScene('motion-ptitle', { pTitleStays: true });
  // K2 wave 6: the G-11 narrowing is removed. A card inside a pager viewport on the gutter line (16) is clean, one narrowed to 22 (peek + gap - gutter) is a deviation; a moving dump without the period title that its rest dump has is a capture row.
  const nscene = (d, l) => write(d, 'narrow-top-g', [nd('page-content', '', [0, 60, 360, 700]), nd('card', '', [l, 100, 360 - l, 200]), nd('period-viewport', '', [0, 100, 360, 300])]);
  nscene('narrowing-bad', 22); nscene('narrowing-good', 16);
  write('title-absent', 'narrow-top-m-rest', [nd('period-page-cur', '', [20, 100, 340, 300]), nd('period-viewport', '', [0, 100, 360, 300]), nd('period-title', 'Oct', [16, 40, 120, 70])]);
  write('title-absent', 'narrow-top-m-moving', [nd('period-page-cur', '', [-30, 100, 290, 300]), nd('period-viewport', '', [0, 100, 360, 300])]);
  { const o = (d) => run(d)[1]; ok(/gutter: block left edge/.test(o('narrowing-bad')) && !/gutter: block left edge/.test(o('narrowing-good')), 'narrowing removed: a card narrowed inside a period-viewport is flagged (gutter), the card on the gutter line is clean');
    ok(/capture: .*absent or zero-width/.test(o('title-absent')), 'capture row: period-title present at rest but absent in the moving dump is explicit, not a silent pass'); }
  { const o = (d) => run(d)[1]; ok(!/co-motion|period label moves|vacuity/.test(o('motion-good')), 'good: card, title, page and label displaced together are not flagged');
    ok(/co-motion card-title vs its card/.test(o('motion-title')), 'bad: card-title that stays while its card moves is flagged (base coMotion)'); ok(/period label moves like its page/.test(o('motion-label')), 'bad: period label that stays while its page moves is flagged (pager-period)');
    ok(/period title moves like its page/.test(o('motion-ptitle')) && !/period title moves/.test(o('motion-good')), 'bad: period title that stays while its current page moves is flagged, good twin (title displaced like the page) is clean (pager-period, M1)');
    // wave 8: whole period header (page-subtitle inside the title's page-header, period-chip) moves like its page; neutral words
    const hdScene = (d, o) => { const mk = (dx) => [nd('page-header', '', [10, 30, 350, 110]), nd('period-title', 'Oct', [16 + dx, 32, 120 + dx, 60]), nd('page-subtitle', 'data from 1 to 31', [16 + (o.subStays ? 0 : dx), 62, 200 + (o.subStays ? 0 : dx), 80]), nd('page-subtitle', 'Other pane', [16, 400, 200, 418]),
      ...(o.chip ? [nd('period-chip', 'day 9/30', [250 + (o.chipStays ? 0 : dx), 40, 340 + (o.chipStays ? 0 : dx), 70])] : []), nd('period-viewport', '', [0, 120, 360, 300]), nd('period-page-cur', '', [20 + dx, 120, 340 + dx, 300])];
      write(d, 'narrow-top-h-rest', mk(0)); write(d, 'narrow-top-h-moving', mk(-50).filter((n) => !(o.subAbsent && n.includes('data from 1 to 31')))); };
    hdScene('hdr-good', { chip: true }); hdScene('hdr-sub', { subStays: true }); hdScene('hdr-chip', { chip: true, chipStays: true }); hdScene('hdr-abs', { subAbsent: true });
    ok(!/period header/.test(o('hdr-good')), 'good: subtitle and chip displaced like the page are clean (pager-period, whole header)');
    ok(/period header moves like its page.*page-subtitle/.test(o('hdr-sub')) && !/period header moves.*Other pane/.test(o('hdr-sub')), 'bad: a subtitle that stays while the page moves is flagged; a subtitle outside the title header is not a member');
    ok(/period header moves like its page.*period-chip/.test(o('hdr-chip')), 'bad: a period-chip that stays while the page moves is flagged');
    ok(/capture: period header member page-subtitle absent/.test(o('hdr-abs')), 'capture row: a header member present at rest and absent when moving is explicit');
    ok(/vacuity guard/.test(o('motion-still')), 'bad: moving dump identical to the rest dump is flagged (vacuity guard)'); }

  // ---- wave 7: per-line heights above the wrap scale (K1/K2), Unicode-space normalisation, currencySplit (K10)
  // (a) Above font scale 1.3 a role the spec lets wrap (page subtitle) is compared per line; page title and period title keep the box height. Dumps whose name carries the fontScaleMarker are the group.
  const hdr = (d, nm, tH, sT, sH) => write(d, nm, [nd('page-content', '', [0, 60, 360, 700]), nd('page-title', 'Month', [16, 70, 200, 70 + tH]), nd('page-subtitle', 'Sub line', [16, sT, 200, sT + sH])]);
  for (const d of ['wrap-fs2', 'wrap-s1']) { const p = d === 'wrap-fs2' ? 'narrow-fs2-top-' : 'narrow-top-'; hdr(d, p + 'a', 24, 96, 16); hdr(d, p + 'b', 24, 96, 32); hdr(d, p + 'c', 24, 96, 16); hdr(d, p + 'd', 48, 120, 16); }
  { const [, o] = run('wrap-fs2'), rw = (x) => o.split('\n').filter((l) => l.startsWith('|') && l.includes(x));
    ok(rw('page-subtitle height').length === 0, 'font scale 2: a two-line subtitle (32 vs 16 dp box, one vs two lines) is clean (per-line comparison)');
    ok(rw('narrow-fs2-top-d').some((l) => l.includes('page-title height')) && rw('narrow-fs2-top-d').some((l) => l.includes('page-subtitle y')), 'font scale 2: a two-line page-title (48 vs 24 dp) and the subtitle pushed down by it still flag (must-flag)'); }
  { const [, o] = run('wrap-s1'); ok(o.split('\n').some((l) => l.startsWith('|') && l.includes('narrow-top-b') && l.includes('page-subtitle height')), 'font scale 1: the same two-line subtitle is still flagged on box height (the per-line rule applies only above the wrap scale)'); }
  const per = (d, nm, subH, ptH) => write(d, nm, [nd('page-content', '', [0, 60, 360, 700]), nd('page-title', 'Month', [16, 70, 200, 94]), nd('page-subtitle', 'Sub line', [16, 96, 200, 96 + subH]), nd('period-viewport', '', [0, 200, 360, 300]), nd('period-title', 'October', [16, 150, 200, 150 + ptH])]);
  per('wrap-period-ok', 'narrow-fs2-top-p', 48, 32); per('wrap-period-ok', 'narrow-fs2-top-q', 24, 32); per('wrap-period-bad', 'narrow-fs2-top-p', 24, 20); per('wrap-period-bad', 'narrow-fs2-top-q', 24, 20);
  { const [, g] = run('wrap-period-ok'), [, b] = run('wrap-period-bad'); ok(!/period-title is the tallest/.test(g), 'font scale 2: a two-line subtitle (48 box, 24 per line) is not taller than the period-title (32) (per-line)'); ok(/period-title is the tallest/.test(b), 'font scale 2: a one-line header text taller than the period-title is still flagged (must-flag)'); }
  // (b) U+00A0 / U+202F / U+2007 read as U+0020 by every text match.
  const NB = String.fromCharCode(160), NN = String.fromCharCode(0x202f);
  write('nbsp-dup-bad', 'narrow-top-f', [...hd, nd('x', 'Item 429' + NB + '€', [30, 150, 140, 166]), nd('x', 'Other 429 €', [30, 170, 140, 186]), nd('x', 'Cost 41' + NN + '€', [30, 190, 140, 206]), nd('x', 'Fee 41' + NB + '€', [30, 210, 140, 226])]);
  { const [, o] = run('nbsp-dup-bad', 'config-base.json'), r = dupOut(o); ok(r.some((l) => l.includes('"Item 429 €" + "Other 429 €"')) && r.some((l) => l.includes('"Cost 41 €" + "Fee 41 €"')), 'unicode spaces: repeats written with U+00A0 / U+202F are flagged exactly like those with U+0020'); }
  write('nbsp-filter', 'narrow-top-f', [...hd, nd('x', 'Total 429' + NB + '€', [30, 110, 140, 126]), nd('x', 'Total 429' + NB + '€', [200, 110, 310, 126])]);
  wj('filters-total.json', [{ screen: 'narrow-top-f', text: '^Total 429 .$', reason: 'selftest: pattern written with U+0020 must still match U+00A0 text', decidedBy: 'user', date: '2026-11-09' }]);
  wj('config-filter-total.json', { ...full, locale: [], duplicateFilters: 'filters-total.json' });
  { const [, a] = run('nbsp-filter', 'config-base.json'), [, b] = run('nbsp-filter', 'config-filter-total.json'); ok(dupOut(a).length > 0 && dupOut(b).length === 0 && /\| 2 \|/.test(b), 'unicode spaces: a filter written with U+0020 silences the same element carrying U+00A0 and its count is printed'); }
  // (c) currencySplit: a digit joined to a currency symbol by a breakable space flags; a no-break joiner and a content-desc do not.
  wj('config-currency.json', { ...full, locale: [], checks: { ...full.checks, currencySplit: true } });
  write('cur-bad', 'narrow-top-f', [...hd, nd('x', 'Balance 441 €', [30, 110, 140, 126]), nd('x', '$ 5', [30, 130, 140, 146])]);
  write('cur-good', 'narrow-top-f', [...hd, nd('x', 'Balance 441' + NB + '€', [30, 110, 140, 126]), nd('x', '$' + NN + '5', [30, 130, 140, 146]), nd('x', '12 pieces', [30, 150, 140, 166]),
    '<node resource-id="app:id/x" text="" content-desc="Total 441 €" clickable="false" scrollable="false" bounds="[30,170][140,186]" />']);
  { const [, b] = run('cur-bad', 'config-currency.json'), [, g] = run('cur-good', 'config-currency.json'), cr = (o) => o.split('\n').filter((l) => l.startsWith('|') && /never split by a breakable space/.test(l));
    ok(cr(b).length === 2, 'currencySplit bad: "441 €" and "$ 5" joined by a plain space are flagged'); ok(cr(g).length === 0, 'currencySplit good: U+00A0 / U+202F joiners, a digit not next to a symbol, and a content-desc are clean'); }

  // ---- LOCALE ADD-ON TESTS (it, en): the dateRanges add-on reads locales.json; the base never does. Italian and English strings belong here and only here.
  write('dup-it-bad', 'narrow-top-d', [...hd, nd('x', '1 – 9 nov', [30, 110, 120, 126]), nd('x', '1 nov', [200, 110, 260, 126])]);
  write('dup-en-bad', 'narrow-top-d', [...hd, nd('x', 'Nov 1 – Nov 9', [30, 110, 140, 126]), nd('x', 'Nov 1', [200, 110, 260, 126])]);
  write('dup-it-good', 'narrow-top-d', [...hd, nd('x', '1 – 9 nov', [30, 110, 120, 126]), nd('x', '12 nov', [200, 110, 260, 126])]);
  write('dup-en-good', 'narrow-top-d', [...hd, nd('x', 'Nov 1 – Nov 9', [30, 110, 140, 126]), nd('x', 'Nov 12', [200, 110, 260, 126])]);
  { const [, o] = run('dup-it-bad', 'config-it.json'); ok(dupOut(o).some((l) => l.includes('"1 – 9 nov" + "1 nov"')), 'locale add-on dateRanges bad (it): a range and its own endpoint are one fact and are flagged'); }
  { const [, o] = run('dup-it-bad', 'config-base.json'); ok(dupOut(o).length === 0, 'locale add-on off: the same screen is NOT flagged by the base (the add-on is what knows about dates)'); }
  { const [, o] = run('dup-en-bad', 'config-en.json'); ok(dupOut(o).some((l) => l.includes('"Nov 1 – Nov 9" + "Nov 1"')), 'locale add-on dateRanges bad (en): "Nov 1 – Nov 9" and "Nov 1" are flagged'); }
  { const [, o] = run('dup-it-good', 'config-it.json'); ok(dupOut(o).length === 0, 'locale add-on dateRanges good (it): a different date is not flagged'); }
  { const [, o] = run('dup-en-good', 'config-en.json'); ok(dupOut(o).length === 0, 'locale add-on dateRanges good (en): a different date is not flagged'); }
  { const [, o] = run('dup-it-bad', 'config-it.json'); ok(!/Dumps: 0/.test(o), 'locale add-on (it): runs with the Italian locale entry'); }
  // Add-on pager-hold (CR-73): held-finger triples. Page 292 wide + 8 gap = pitch 300; short 0.25 -> 75 travel, long 0.6 -> 180.
  { const strip = (d, name, dxS, dxL, withPrev = true) => {
      const vp = nd('period-viewport', '', [0, 100, 300, 200]), page = (l) => nd('period-page-cur', '', [Math.max(0, l), 100, Math.min(300, l + 292), 200]), prev = withPrev ? [nd('period-page-prev', '', [0, 100, 6, 200])] : [];
      write(d, name + '-hold-rest', [vp, page(0), ...prev]); write(d, name + '-hold-short', [vp, page(dxS), ...prev]); write(d, name + '-hold-long', [vp, page(dxL), ...prev]); };
    for (const n of ['narrow-a', 'narrow-b', 'narrow-c']) strip('hold-good', n, 38, 130);
    for (const n of ['narrow-a', 'narrow-b']) strip('hold-bad', n, 38, 130); strip('hold-bad', 'narrow-c', 70, 130);   // c follows the finger 1:1 below the commit point
    for (const n of ['narrow-a', 'narrow-b']) strip('hold-nomove', n, 38, 130); strip('hold-nomove', 'narrow-c', 0, 0);
    wj('config-hold.json', { ...full, addons: ['pager-hold'], allowAbsent: [], checks: { periodHold: true } });
    const hr = (d) => run(d, 'config-hold.json')[1].split('\n').filter((l) => l.startsWith('|') && /CR-73/.test(l));
    ok(hr('hold-good').length === 0, 'pager-hold good: three strips with the same resisted ratio (0.5) are clean');
    ok(hr('hold-bad').some((l) => /without resistance/.test(l) && l.includes('narrow-c')) && hr('hold-bad').some((l) => /draws this strip differently/.test(l)), 'pager-hold bad: a strip that follows the finger 1:1 below the commit point is flagged (no resistance, and different from the others)');
    // reference curve (free .08, resisted .48, threshold .42, rubber .3, slop 8 dp; pitch 300): short 0.25 -> 59 dp, long 0.6 -> 172 dp (real position); the old 38 / 130 pair is about half strength
    for (const n of ['narrow-a', 'narrow-b']) strip('hold-curve-good', n, 59, 172); strip('hold-curve-good', 'narrow-c', 57, 175);
    for (const n of ['narrow-a', 'narrow-b']) strip('hold-curve-bad', n, 59, 172); strip('hold-curve-bad', 'narrow-c', 38, 130);   // c: half strength, a lost resistance of the other kind
    for (const n of ['narrow-a', 'narrow-b']) strip('hold-curve-1to1', n, 59, 172); strip('hold-curve-1to1', 'narrow-c', 75, 172);   // c: follows the finger 1:1 below the commit point
    wj('config-hold-curve.json', { ...full, addons: ['pager-hold'], allowAbsent: [], checks: { periodHold: true }, periodHold: { curve: { free: 0.08, resisted: 0.48, threshold: 0.42, rubber: 0.3, slopDp: 8, tolDp: 6 } } });
    const cr = (d) => run(d, 'config-hold-curve.json')[1].split('\n').filter((l) => l.startsWith('|') && /CR-73/.test(l));
    ok(cr('hold-curve-good').length === 0, 'pager-hold curve good: pages on the reference curve (59 / 172 dp, +-3 dp) are clean');
    ok(cr('hold-curve-bad').some((l) => /reference curve/.test(l) && l.includes('narrow-c-hold-short')) && cr('hold-curve-bad').some((l) => /reference curve/.test(l) && l.includes('narrow-c-hold-long')), 'pager-hold curve must-flag: a strip drawn at about half the reference is flagged at both holds');
    ok(cr('hold-curve-1to1').some((l) => /reference curve/.test(l) && l.includes('narrow-c-hold-short')), 'pager-hold curve must-flag: a 1:1 page below the commit point is off the reference curve');
    // moving-overlap (CR-79): a travelling title over a fixed back button is flagged, the clipped twin is clean
    for (const [d, x] of [['mo-bad', 40], ['mo-good', 120]]) { write(d, 'narrow-top-mo', [nd('back', '', [10, 10, 58, 58], { click: true }), nd('page-title', 'Novembre', [100, 10, 220, 50])]); write(d, 'narrow-top-mo-moving', [nd('back', '', [10, 10, 58, 58], { click: true }), nd('page-title', 'Novembre', [x - 20, 10, x + 100, 50])]); }
    wj('config-mo.json', { ...full, addons: ['moving-overlap'], allowAbsent: [], checks: { movingOverlap: true } });
    const mo = (d) => run(d, 'config-mo.json')[1].split('\n').filter((l) => l.startsWith('|') && /CR-79/.test(l));
    ok(mo('mo-bad').length === 1 && mo('mo-good').length === 0, 'moving-overlap: a title moved to x 20..140 over a back button at 10..58 is flagged, the one at 100..220 is clean');
    // label-rows (CR-80): two columns whose figures share a row put their labels on one line
    for (const [d, y] of [['lr-bad', 120], ['lr-good', 60]]) write(d, 'narrow-top-lr', [nd('fa', '10', [10, 10, 100, 60]), nd('la', 'A', [10, 60, 100, 80]), nd('fb', '4', [150, 20, 250, 60]), nd('la', 'B', [150, y, 250, y + 20])]);
    wj('config-lr.json', { ...full, addons: ['label-rows'], allowAbsent: [], checks: { labelRows: true }, labelRows: { a: 'fa', b: 'fb', label: 'la' } });
    ok(run('lr-bad', 'config-lr.json')[1].includes('(CR-80)') && !run('lr-good', 'config-lr.json')[1].includes('(CR-80)'), 'label-rows: labels 30 dp apart under figures of one row are flagged, labels on one line are clean');
    // text-forbid (CR-78)
    for (const [d, t] of [['forbid-bad', 'Novembre · dati dal 1 al 6 nov'], ['forbid-good', 'Novembre · dati al 6 nov'], ['forbid-ok', 'dati dal 12 al 20 nov']]) write(d, 'narrow-top-a', [nd('page-subtitle', t, [10, 10, 290, 40])]);
    wj('config-forbid.json', { ...full, addons: ['text-forbid'], allowAbsent: [], checks: { textForbid: true }, textForbid: [{ pattern: 'dati dal 1 al(?=\\s|$)', flags: 'i', rule: 'text: coverage label starting on the 1st (CR-78)' }] });
    const fr = (d) => run(d, 'config-forbid.json')[1].split('\n').filter((l) => l.startsWith('|') && /CR-78/.test(l));
    ok(fr('forbid-bad').length === 1 && fr('forbid-good').length === 0 && fr('forbid-ok').length === 0, 'text-forbid: "dati dal 1 al 6 nov" is flagged, "dati al 6 nov" and "dati dal 12 al 20 nov" are clean');
    // text-forbid textTemplates (CR-81): one element, one wording
    for (const [d, t] of [['tpl-bad', 'parziale · dal 10 ott'], ['tpl-good', 'dati al 6 nov'], ['tpl-bad2', 'dati dal 10 ott']]) write(d, 'narrow-top-a', [nd('page-subtitle', t, [10, 10, 290, 40])]);
    wj('config-tpl.json', { ...full, addons: ['text-forbid'], allowAbsent: [], checks: { textTemplates: true }, textTemplates: { placeholders: [['(?:gen|feb|mar|apr|mag|giu|lug|ago|set|ott|nov|dic)[a-z]*', '<m>'], ['[0-9]+', '<d>']], groups: [{ name: 'coverage line', roles: ['page-subtitle'], screens: '.', match: '^(dati (al|dal)|parziale)', allowed: { 'dati al <d> <m>': 'user' } }] } });
    const tcr = (d) => run(d, 'config-tpl.json')[1].split(String.fromCharCode(10)).filter((l) => l.startsWith('|') && /CR-81/.test(l));
    ok(tcr('tpl-bad').length === 1 && tcr('tpl-good').length === 0 && tcr('tpl-bad2').length === 1, 'text-forbid textTemplates: "parziale · dal 10 ott" and open-ended "dati dal 10 ott" are flagged, "dati al 6 nov" is clean');
    // text-forbid dateRangeOnce (CR-82): a title range with a second range under it
    write('rng-bad', 'narrow-top-a', [nd('page-title', '7 ott – 6 nov', [10, 10, 290, 40]), nd('page-subtitle', 'dati dal 10 ott al 6 nov', [10, 44, 290, 70])]);
    write('rng-good', 'narrow-top-a', [nd('page-title', '10 ott – 6 nov', [10, 10, 290, 40]), nd('page-subtitle', 'Dati di esempio', [10, 44, 290, 70])]);
    wj('config-rng.json', { ...full, addons: ['text-forbid'], allowAbsent: [], checks: { dateRangeOnce: true }, dateRangeOnce: { pattern: '\\d{1,2} (?:ott|nov) [–-] \\d{1,2} (?:ott|nov)|dal \\d{1,2}(?: (?:ott|nov))? al \\d{1,2} (?:ott|nov)', titleRoles: ['page-title'], noteRoles: ['page-subtitle'] } });
    const rcr = (d) => run(d, 'config-rng.json')[1].split(String.fromCharCode(10)).filter((l) => l.startsWith('|') && /CR-82/.test(l));
    ok(rcr('rng-bad').length === 1 && rcr('rng-good').length === 0, 'text-forbid dateRangeOnce: a title range with a second range under it is flagged, a single range is clean');
    ok(hr('hold-nomove').some((l) => /moved no period page/.test(l) && l.includes('narrow-c')), 'pager-hold vacuity: a hold that moved no page is a capture row'); }
  // Add-on concept-owners (CR-76 / CR-77): source scan, one implementation per concept, gestures use the shared helper.
  { const sd = path.join(root, 'src'), put = (f, t) => { fs.mkdirSync(path.dirname(path.join(sd, f)), { recursive: true }); fs.writeFileSync(path.join(sd, f), t); };
    put('ui/Strip.kt', 'class StripState {\n  fun settle() { position.animateTo(0f) }\n}\n');
    put('screens/Good.kt', 'fun Good() { Strip(state) }\n// position.animateTo(1f) in a comment is ignored\n');
    put('screens/Bad.kt', 'fun Bad() { position.animateTo(1f) }\n');
    put('screens/Drag.kt', 'fun Swipe() {\n  Modifier.draggable(state)\n}\nfun Fine() {\n  Modifier.draggable(state); rememberCommitHaptics()\n}\n');
    const cfgc = { concepts: [{ name: 'strip', owners: ['ui/Strip.kt'], signatures: ['position\\.animateTo\\('], known: [] }], gestures: { signatures: ['\\.draggable\\('], helper: 'rememberCommitHaptics', exceptions: [] } };
    fs.writeFileSync(path.join(root, 'owners.json'), JSON.stringify(cfgc));
    const co = (cf) => { try { return [0, execFileSync(process.execPath, [path.join(here, 'addons', 'concept-owners.mjs'), sd, '--config', path.join(root, cf)], { encoding: 'utf8' })]; } catch (e) { return [e.status, String(e.stdout || '')]; } };
    const [c1, o1] = co('owners.json');
    ok(c1 === 1 && /CR-76 \[strip\] screens\/Bad\.kt:1/.test(o1) && !/Good\.kt/.test(o1) && !/ui\/Strip\.kt:/.test(o1), 'concept-owners bad: a second implementation outside the owner is flagged; the owner, a thin wrapper and a comment are not');
    ok(/CR-77 screens\/Drag\.kt:1/.test(o1) && !/Drag\.kt:4/.test(o1), 'concept-owners gestures: a drag without the shared helper is flagged, the one that uses it is not');
    fs.writeFileSync(path.join(root, 'owners2.json'), JSON.stringify({ ...cfgc, concepts: [{ ...cfgc.concepts[0], known: [{ file: 'screens/Bad.kt', reason: 'legacy' }] }], gestures: undefined }));
    ok(/known entry screens\/Bad\.kt needs a reason and a decision/.test(co('owners2.json')[1]), 'concept-owners: a known second implementation without a decision is itself flagged');
    fs.writeFileSync(path.join(root, 'owners3.json'), JSON.stringify({ ...cfgc, concepts: [{ ...cfgc.concepts[0], known: [{ file: 'screens/Bad.kt', reason: 'legacy', decision: 'pending owner U' }] }], gestures: undefined }));
    ok(co('owners3.json')[0] === 0, 'concept-owners: a known entry with reason and decision passes (and is removed when the duplicate is gone)'); }
  // Add-on aligned-to-nothing (K14): every drawn leaf shares a line with another element; a trailing header control is centred on the title.
  { const { antFixtures, ANT_CONFIG } = await import(pathToFileURL(path.join(here, 'addons', 'aligned-to-nothing.mjs')).href); antFixtures(path.join(root, 'ant'));
    wj('config-ant.json', { ...full, addons: ['aligned-to-nothing'], allowAbsent: [], checks: { alignedToNothing: true }, alignedToNothing: ANT_CONFIG });
    const an = (d) => run(path.join('ant', d), 'config-ant.json')[1].split(String.fromCharCode(10)).filter((l) => l.startsWith('|') && /header-centre|aligned to nothing/.test(l));
    ok(an('ant-header-bad').some((l) => /header-centre: page-trailing is centred on the page-title/.test(l)), 'aligned-to-nothing bad: a trailing control off the title centre-y is flagged');
    ok(an('ant-lone-bad').length === 1 && /Nota/.test(an('ant-lone-bad')[0]), 'aligned-to-nothing bad: a text aligned to nothing is flagged, and only it');
    ok(an('ant-good').length === 0, 'aligned-to-nothing good: centred header, aligned rows and a declared lone point are clean'); }
  // Page gutter at range ends (CR-62 criterion, K21): the add-on hook blockEdges returns the SAME geometry as rangeEndEdges; centred pages return null.
  { const dc = (await import(pathToFileURL(path.join(here, 'addons', 'device-capture.mjs')).href)).default, nd = (id, l, t, r, b) => ({ id, text: '', l, t, r, b });
    const ctx = { cfg: { deviceAddons: { peekVisible: [{ screen: '*', viewport: '^period-viewport$', neighbours: '^period-page-(prev|next)$', current: '^period-page-cur$', surface: '^period-page-surface$', endGutterDp: 16 }] } }, dir: '' };
    const vp = nd('period-viewport', 0, 288, 360, 420), cur = (l, r) => [nd('period-page-cur', l, 288, r, 400), nd('period-page-surface', l, 288, r, 400)], card = (l, r) => nd('card', l, 288, r, 400);
    const prev = nd('period-page-prev', -332, 300, 48, 400), next = nd('period-page-next', 332, 300, 360, 400);
    const e = (nodes, c, left) => dc.blockEdges(ctx, { name: 'phone-x', nodes: [vp, ...nodes] }, c, left);
    const end = e([prev, ...cur(56, 344)], card(56, 344), 36), mid = e([nd('period-page-prev', -332, 300, 28, 400), next, ...cur(36, 324)], card(36, 324), 36), one = e(cur(16, 344), card(16, 344), 36);
    ok(end && end.l === 56 && end.r === 344, 'range-end page gutter: a page with a previous neighbour only rests on the right margin (56..344)');
    ok(mid === null, 'range-end page gutter: a page with both neighbours stays centred (no override, 36..324 is the base geometry)');
    ok(one && one.l === 16 && one.r === 344, 'range-end page gutter: a one-page strip spans margin to margin (16..344)');
    ok(e([nd('period-page-prev', -332, 300, 40, 400), ...cur(46, 344)], card(46, 344), 36).l === 56, 'range-end page gutter: a page at a wrong offset is not accepted (expected edge 56, not 46)'); }
  console.log(fails ? `\n${fails} self-test failure(s)` : '\nself-test passed');
}
