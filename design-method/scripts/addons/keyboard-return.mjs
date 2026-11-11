// Add-on `keyboard-return` (config list `keyboardReturn`; SKILL section 2, K22 / critique rule CR-90; DESIGN G-15).
// User 2026-11-10: "Quando faccio indietro tastiera, i pulsanti sotto commettono 1s a comparire".
// Input: the ordered dumps of one keyboard hide (files `<screen>-kbd-f00.xml` ...; each root carries `ime="[l,t][r,b]"` or has a node of an input-method package, giving the keyboard height; uiautomator alone does not record it).
// Entry: { screen: glob, control: regex over id or text of the control that returns (the setup dock, the cover dock) }. Frames: [{ ime: heightDp, nodes, H }].
//   keyboard-return-late    from the first frame in which the keyboard shrinks, the control must be present and on screen; absent or off screen while the keyboard is still partly up is late.
//   keyboard-return-queued  the control must be settled (at its final position) in the first frame in which the keyboard is gone; still moving after that means a second animation queued behind the keyboard's.
//   node keyboard-return.mjs --selftest
import { fileURLToPath } from 'node:url';

export const DEFAULTS = { tolDp: 1 };
export const matchesScreen = (e, name) => new RegExp('^' + (e.screen || '*').replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$').test(name);
const find = (f, re) => f.nodes.find((n) => n.r > n.l && n.b > n.t && (re.test(n.id || '') || re.test(n.text || '')));

export function keyboardReturn(frames, entry) {
  const C = { ...DEFAULTS, ...entry }, re = new RegExp(C.control), out = [], t = C.tolDp;
  const start = frames.findIndex((f, i) => i > 0 && f.ime < frames[i - 1].ime), gone = frames.findIndex((f, i) => i > 0 && f.ime <= 0 && frames[i - 1].ime > 0);
  if (start < 0 || gone < 0) return [{ rule: 'keyboard-return-unmeasurable', el: C.control, value: 0, expected: 1, note: 'the frames do not show a keyboard hide (no shrinking ime height, or it never reaches 0)' }];
  const pos = (f) => { const n = find(f, re); return n && n.t < (f.H || 1e9) && n.b > 0 ? n.t : null; }, last = pos(frames[frames.length - 1]);
  for (let i = start; i < gone; i++) if (pos(frames[i]) === null) { out.push({ rule: 'keyboard-return-late', el: C.control, value: i - start, expected: 0, note: `the control is not on screen in frame ${i} while the keyboard is still hiding (from frame ${start}, gone at ${gone})` }); break; }
  const g = pos(frames[gone]);
  if (g === null || last === null || Math.abs(g - last) > t) out.push({ rule: 'keyboard-return-queued', el: C.control, value: g === null ? -1 : Math.abs(g - last), expected: 0, note: `the control is not settled in the frame where the keyboard is gone (${gone}): its animation runs after the keyboard's` });
  return out;
}

export function fixtures() {
  const ctl = (t) => (t === null ? [] : [{ id: 'setup-dock', text: '', l: 0, t, r: 400, b: t + 56, clickable: true }]), fr = (ime, t) => ({ ime, H: 800, nodes: ctl(t) });
  // keyboard 300 dp -> 0 over 4 frames; the dock follows it (good) or waits and slides in only after (bad)
  return { entry: { screen: '*', control: '^setup-dock$' },
    good: [fr(300, null), fr(220, 700), fr(110, 730), fr(40, 742), fr(0, 744), fr(0, 744)],
    bad: [fr(300, null), fr(220, null), fr(110, null), fr(40, null), fr(0, 790), fr(0, 744)] };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), g = keyboardReturn(f.good, f.entry), b = keyboardReturn(f.bad, f.entry);
  const pass = g.length === 0 && b.some((x) => x.rule === 'keyboard-return-late') && b.some((x) => x.rule === 'keyboard-return-queued');
  console.log(pass ? 'keyboard-return selftest PASS (a dock that follows the keyboard is clean; one that appears after it is late and queued)' : 'keyboard-return selftest FAIL ' + JSON.stringify(g.concat(b)));
  process.exit(pass ? 0 : 1);
}
