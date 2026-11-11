// Add-on `work-in-progress` (config list `workInProgress`; SKILL section 2, K18 strengthened and amended / critique rule CR-86; DESIGN G-16).
// User 2026-11-10 13:18, on a page standing still after "Link valido": "se carica non basta feedback pulsante, serve vedere caricamento o nel pulsante o fuori".
// User 2026-11-10 14:05: "È il continua che deve diventare accesso in corso con a destra caricamento circolare ... Di non aggiungere scritte e di sfruttare ciò che già si ha".
// Rule: work shows in the control that owns it (primary: working label + circular loader at the label's right); no text is added for a state; one indicator per piece of work.
// Rest dumps (dp). Config entry: { screen: glob, running?: true, success: regex of the text that says the input was accepted, primary: regex of the primary action's IDLE label, progressIds: [ids, trailing * = prefix], progressClass: regex, allowMultiple?: true }.
//   work-no-progress           the screen is declared `running` or shows the `success` text with the primary disabled, and has no visible progress node.
//   work-primary-idle-label    a primary that is disabled with its idle label (`primary`) while a progress node sits outside it: the page says "input missing" and "working" at once (the 14:05 case).
//   work-double-indicator      more than one drawn progress node on the screen (a loader in the button plus a line, or two lines) for one working state; set allowMultiple for a screen with independent work.
//   pair (workPair, an idle dump and a working dump of the same screen):
//   work-primary-unchanged     in the working dump the primary is disabled with the same label as idle while a progress node sits outside it;
//   work-added-text            a text node that exists only in the working dump (outside the primary): a state was added as text instead of going into the button label.
// A progress node is a drawn node whose id is in progressIds (default includes `progress_primary`, the in-button loader) or whose class matches progressClass (ProgressBar); a disabled button, press feedback or an outcome line alone is not progress.
//   node work-in-progress.mjs --selftest
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { progressIds: ['working-line', 'progress*'], progressClass: 'ProgressBar|ProgressIndicator' };
const idIn = (id, list) => list.some((g) => (g.endsWith('*') ? id.startsWith(g.slice(0, -1)) : id === g));
export const matchesScreen = (e, name) => new RegExp('^' + (e.screen || '*').replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$').test(name);

const drawn = (n) => n.r > n.l && n.b > n.t;
const progressNodes = (dump, C) => dump.nodes.filter((n) => drawn(n) && ((n.id && idIn(n.id, C.progressIds)) || (n.cls && new RegExp(C.progressClass).test(n.cls))));
const inside = (a, b) => a.l >= b.l - 1 && a.r <= b.r + 1 && a.t >= b.t - 1 && a.b <= b.b + 1;
// The smallest clickable box around a node (the primary button around its label).
const boxOf = (dump, label) => label && dump.nodes.filter((n) => n.clickable && inside(label, n)).sort((p, q) => (p.r - p.l) * (p.b - p.t) - (q.r - q.l) * (q.b - q.t))[0];
const primaryOf = (dump, C) => { const label = C.primary && dump.nodes.find((n) => drawn(n) && new RegExp(C.primary).test(n.text || '')); return { label, box: boxOf(dump, label) }; };

export function workInProgress(dump, entry) {
  const C = { ...DEFAULTS, ...entry }, nodes = dump.nodes, progress = progressNodes(dump, C), rows = [];
  if (progress.length > 1 && !C.allowMultiple) rows.push({ rule: 'work-double-indicator', el: progress.map((n) => n.id || n.cls || 'progress').join(' + '), value: progress.length, expected: 1, note: 'more than one progress indicator for one working state: keep the one in the control that owns the work' });
  const { label, box } = primaryOf(dump, C);
  if (progress.length) {
    if (label && box && box.enabled === false && progress.some((p) => !inside(p, box))) rows.push({ rule: 'work-primary-idle-label', el: (label.text || '').slice(0, 24), value: 0, expected: 1, note: 'the primary is disabled with its idle label while the page shows progress elsewhere: the button must say what is running and carry the loader' });
    return rows;
  }
  if (C.running) return [{ rule: 'work-no-progress', el: C.success || 'working state', value: 0, expected: 1, note: 'the screen waits on work and shows no progress indicator' }];
  const ok = C.success && nodes.find((n) => drawn(n) && new RegExp(C.success).test(n.text || ''));
  if (ok && box && box.enabled === false) return [{ rule: 'work-no-progress', el: (ok.text || '').slice(0, 28) + ' + disabled ' + (label.text || '').slice(0, 20), value: 0, expected: 1, note: 'accepted input, primary action disabled and no progress indicator: the page looks idle while it works' }];
  return [];
}

// Idle and working dumps of the same screen.
export function workPair(idle, working, entry) {
  const C = { ...DEFAULTS, ...entry }, rows = [], progress = progressNodes(working, C), { label, box } = primaryOf(working, C);
  if (label && box && box.enabled === false && progress.some((p) => !inside(p, box))) rows.push({ rule: 'work-primary-unchanged', el: (label.text || '').slice(0, 24), value: 0, expected: 1, note: 'working dump: the disabled primary keeps its idle label while progress shows elsewhere' });
  const before = new Set(idle.nodes.filter((n) => drawn(n) && n.text).map((n) => n.text));
  const ib = primaryOf(idle, C).box, pBox = box || (ib && working.nodes.find((n) => n.clickable && n.l === ib.l && n.t === ib.t && n.r === ib.r && n.b === ib.b));
  for (const n of working.nodes) if (drawn(n) && n.text && !before.has(n.text) && !(pBox && inside(n, pBox))) rows.push({ rule: 'work-added-text', el: n.text.slice(0, 28), value: 1, expected: 0, note: 'text exists only while working: a state added as text; put it in the primary label or an existing title' });
  return rows;
}

export function fixtures() {
  const n = (id, text, l, t, r, b, x = {}) => ({ id, text, l, t, r, b, ...x }), entry = { screen: '*setup-eb*', success: '^Link valido$', primary: '^Continua$' };
  const base = [n('setup-title', 'Incolla il link', 16, 118, 826, 154), n('', 'Link valido', 32, 330, 200, 350), n('', '', 20, 546, 676, 594, { clickable: true, enabled: false }), n('', 'Continua', 315, 560, 383, 580)];
  const idle = [n('setup-title', 'Incolla il link', 16, 118, 826, 154), n('', '', 20, 546, 676, 594, { clickable: true, enabled: false }), n('', 'Continua', 315, 560, 383, 580)];
  // The 14:05 case: text under the field, thin line, disabled Continua with its idle label.
  const thin = [...idle, n('', 'Accesso in corso…', 32, 330, 240, 350), n('working-line', '', 0, 100, 829, 103)];
  // Must pass: the primary says what runs and carries the loader; nothing else added.
  const primaryWorking = [idle[0], n('', '', 20, 546, 676, 594, { clickable: true, enabled: false }), n('', 'Accesso in corso', 290, 560, 420, 580), n('progress_primary', '', 430, 560, 450, 580)];
  const spin = [idle[0], n('', '', 20, 546, 676, 594, { clickable: true, enabled: false }), n('', 'Accesso in corso', 290, 560, 420, 580), n('', '', 430, 560, 450, 580, { cls: 'android.widget.ProgressBar' })];
  const D = (nodes) => ({ W: 829, H: 600, nodes });
  return { entry, bad: D(base), good: D(primaryWorking), spinner: D(spin), idle: D(idle), thin: D(thin), double: D([...primaryWorking, n('working-line', '', 0, 100, 829, 103)]) };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), E = f.entry, w = (d, e = E) => workInProgress(d, e), has = (rows, r) => rows.some((x) => x.rule === r);
  const checks = [
    ['valid link + disabled Continua without progress flagged', w(f.bad).length === 1 && has(w(f.bad), 'work-no-progress')],
    ['14:05 case (disabled Continua idle label + thin line) flagged', has(w(f.thin), 'work-primary-idle-label')],
    ['primary "Accesso in corso" + progress_primary clean', w(f.good).length === 0],
    ['working label + ProgressBar in the button clean', w(f.spinner).length === 0],
    ['loader in the button plus a line flagged as double', has(w(f.double), 'work-double-indicator') && w(f.double, { ...E, allowMultiple: true }).length === 0],
    ['running screen without progress flagged', w(f.bad, { ...E, success: undefined, running: true }).length === 1],
    ['pair: 14:05 flagged for unchanged primary and added text', has(workPair(f.idle, f.thin, E), 'work-primary-unchanged') && has(workPair(f.idle, f.thin, E), 'work-added-text')],
    ['pair: label change in the button clean', workPair(f.idle, f.good, E).length === 0],
    ['screen glob', matchesScreen(E, 'fold-setup-eb-2')],
  ];
  const fail = checks.filter((c) => !c[1]);
  console.log(fail.length ? 'work-in-progress selftest FAIL: ' + fail.map((c) => c[0]).join('; ') : 'work-in-progress selftest PASS (' + checks.length + ' checks: 14:05 case flagged, working primary with loader clean, double indicator flagged, pair checks)');
  process.exit(fail.length ? 1 : 0);
}
