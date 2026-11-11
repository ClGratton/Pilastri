#!/usr/bin/env node
// Capture every screen of an Android app with ONE command (emulator only), in the file names and XML format the consistency checker reads.
//   node capture-all.mjs --settings <capture-config.json> [--config fold|cover|fs2|all] [--only <glob,glob>] [--out <dir>] [-s <serial>] [--no-shots] [--no-motion] [--motion-all] [--list] [--crawl] [--legacy] [--frames [N:ms]]
// No hand-written screen list: the APP enumerates its destinations from its own navigation model (the capture hook), so a new screen is captured with no edit here.
// v2 (2026-11-09): the APP captures itself. One intent per layout makes the app walk every destination, wait for settle, write its own semantics tree as
// uiautomator-format XML and a half-size PNG (PixelCopy) into one device folder; this script sets wm size / font scale, sends the intent, waits for DONE, runs ONE adb pull.
// Only the motion captures (hold / drag on the discovered swipe strips) still use `uiautomator dump`, because the finger must be real (adb input motionevent).
// In-app capture contract (see design-method/SKILL.md step 5): list destinations, navigate, settle, self-dump, self-screenshot, DONE:
//   0. an intent `--es <extras.all> <id> --es <extras.prefix> <p> [--es <extras.perkind> N] [--es <extras.only> g1,g2] [--es <extras.vptag> <tag>] [--es <extras.mid> 1] [--es <extras.shots> 0|1]` runs the whole walk inside the app
//      (every destination of 1., settle as in 2., then `<p>-<name>.xml`, `half/<p>-<name>.png`, and `<p>-<name>-mid.*` for the previous month when the page has a `vptag` node) into the app's external files dir
//      `capture/<id>/` and logs `<tag> done <id> <files> files <ms> ms` (plus `dump-failed|skipped <id> ...` lines); the script pulls that folder once. Synthetic data only. `--legacy` = the v1 per-screen uiautomator path.
// Per-screen contract the in-app walk is built from:
//   1. an intent `--es <extras.list> <id>` makes the app log `<tag> dest <id> <name>|<tab>|<route>` per destination, then `<tag> listed <id> <n> unmapped=<names>`
//      (destinations come from the Route type itself; classes with arguments expand from the sample data; an unmapped class is reported and fails the run);
//   2. an intent `--es <extras.id> <n> --es <extras.route> <route> [--es <extras.tab> <t>] [--es <extras.month> <yyyy-MM|prev>]` navigates with no taps and logs
//      `<tag> settled <n> <route>` once the screen stopped drawing (or `ignored <n> <why>`: honoured on synthetic sample data only);
//   3. swipeable strips carry the resource-id `viewportTag` (default period-viewport): each one found in a dump gets drag (<n>-vpK-rest/-moving), hold
//      (<n>-vpK-hold-{rest,short,long}) and, for the month before the latest, mid captures (<n>-mid.xml, <n>-mid-vpK-rest/-moving), once per distinct viewport geometry.
// File names: <layout prefix>-<destination name>[...]. Fallback without the hook: --crawl taps clickables breadth-first (skipping destructive labels), dedupes screens by structure.
// Settings keys (a layout may set perKind: N to keep only the first N destinations of each route kind, e.g. 1 for the font-scale pass): adb, serial, package, activity, extras{list,id,route,tab,month,all,prefix,perkind,only,vptag,mid,shots}, deviceDir (default /sdcard/Android/data/<package>/files/capture), logTag, layouts{<name>:{prefix,wmSize,fontScale?,motion?}}, viewportTag, viewportPoint,
//   holdPoint, halfBatch (argv with {src} {dst}, optional), density (printed hint), destructive (regex of labels the crawler never taps).
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';

const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const flag = (n) => argv.includes(n);
const S = JSON.parse(fs.readFileSync(opt('--settings', 'capture-config.json'), 'utf8'));
const serial = opt('-s', S.serial || 'emulator-5554');
if (!/^emulator-\d+$/.test(serial)) { console.error(`refused: ${serial} is not an emulator serial (emulator-NNNN). This tool never touches a phone.`); process.exit(2); }
const only = (opt('--only', '') || '').split(',').filter(Boolean).map((g) => new RegExp('^' + g.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$'));
const cfgSel = opt('--config', 'all');
const out = opt('--out', `output/capture-${new Date().toISOString().slice(0, 10)}`);
// --frames [N:ms] (default 18:32): v2 only; the app also writes N self-dumps at fixed intervals after every navigation / month change, `<name>-f00.xml` ... (visual-integrity checks, see SKILL section 2 K13)
const framesOpt = flag('--frames') ? (/^\d+(:\d+)?$/.test(opt('--frames', '') || '') ? opt('--frames') : '18:32') : null;
const shots = !flag('--no-shots'), motionOn = !flag('--no-motion'), crawl = flag('--crawl'), legacy = flag('--legacy');
const ADB = process.env.ADB || S.adb || 'adb';
const TAG = S.logTag, PKG = S.package, X = S.extras, VP = S.viewportTag || 'period-viewport';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...a) => spawnSync(ADB, ['-s', serial, ...a], { maxBuffer: 256 << 20, timeout: 45000, killSignal: 'SIGKILL' }); // a hung adb call becomes a FAIL row, never a stuck run
const sh = (cmd) => adb('shell', cmd);
const passes = ['fold', 'cover', 'fs2'].filter((c) => (cfgSel === 'all' || cfgSel === c) && S.layouts[c]);
const wanted = (n) => !only.length || only.some((r) => r.test(n));
fs.mkdirSync(out, { recursive: true });
const half = path.join(out, 'half'), full = path.join(out, 'png-full');
if (shots) { fs.mkdirSync(half, { recursive: true }); if (legacy || crawl) fs.mkdirSync(full, { recursive: true }); }

// ---- device log: one long-lived logcat
const seen = new Map(), dests = new Map(), waiters = new Map();
adb('logcat', '-c');
const lc = spawn(ADB, ['-s', serial, 'logcat', '-v', 'raw', `${TAG}:I`, '*:S']);
let buf = '';
lc.stdout.on('data', (d) => {
  buf += d; let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const l = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
    let m = /^dest (\d+) (.*)$/.exec(l); if (m) { (dests.get(m[1]) || dests.set(m[1], []).get(m[1])).push(m[2]); continue; }
    m = /^(dump-failed|skipped) (\d+) (.*)$/.exec(l); if (m) { console.log(`warn  in-app ${m[1]}: ${m[3]}`); continue; }
    m = /^(settled(?:-timeout)?|ignored|listed|done) (\d+)\s*(.*)$/.exec(l); if (m) { seen.set(m[2], { st: m[1], rest: m[3] }); waiters.get(m[2])?.(); }
  }
});
let navId = 0;
async function launch(extra, name) {
  const id = String(++navId);
  adb('shell', 'am', 'start', '-n', `${PKG}/${S.activity}`, '--activity-single-top', '--es', ...extra(id));
  if (!seen.has(id)) await Promise.race([new Promise((r) => waiters.set(id, r)), sleep(20000)]);
  const r = seen.get(id);
  if (!r) throw new Error(`no capture line for ${name} within 20 s (is the build new enough, and is the archive the sample data?)`);
  if (r.st === 'ignored') throw new Error(`capture refused: ${r.rest}. The app honours capture intents only on the synthetic sample archive; load it first.`);
  return { id, ...r };
}
const nav = (d, month) => launch((id) => { const a = [X.id, id, '--es', X.route, d.route]; if (d.tab) a.push('--es', X.tab, d.tab); if (month) a.push('--es', X.month, month); return a; }, d.name);
async function list() {
  const r = await launch((id) => [X.list, id], 'list'); const un = (/unmapped=(.*)$/.exec(r.rest) || [, ''])[1];
  return { items: (dests.get(r.id) || []).map((s) => { const [name, tab, route] = s.split('|'); return { name, tab, route }; }), unmapped: un.split(',').filter(Boolean) };
}
function dumpXml() {
  for (let k = 0; k < 6; k++) { if (k) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
    const s = adb('shell', 'rm -f /sdcard/ca.xml; uiautomator dump /sdcard/ca.xml >/dev/null 2>&1; cat /sdcard/ca.xml').stdout.toString('utf8'), i = s.indexOf('</hierarchy>');
    if (i > 0 && s.includes('resource-id')) return s.slice(s.indexOf('<?xml') >= 0 ? s.indexOf('<?xml') : 0, i + 12);
  }
  throw new Error('uiautomator dump failed 6 times');
}
const write = (n, x) => fs.writeFileSync(path.join(out, n + '.xml'), x);
const shot = (n) => { if (shots) fs.writeFileSync(path.join(full, n + '.png'), adb('exec-out', 'screencap', '-p').stdout); };
const viewports = (x) => [...x.matchAll(new RegExp(`<node [^>]*resource-id="${VP}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`, 'g'))].map((m) => m.slice(1, 5).map(Number)).filter((b) => b[2] > b[0] && b[3] > b[1]).map((b) => b.join(','));
const tool = (script, ...a) => { const r = spawnSync(process.execPath, [script, ...a], { encoding: 'utf8' }); if (r.status !== 0) throw new Error(`${path.basename(script)} ${a.join(' ')}: ${r.stderr.trim()}`); return r.stdout.trim().split(/\s+/).map(Number); };
function held(x1, y, x2, holdSleep) { // one adb call: finger down, four moves, hold, dump while held, release
  const mv = [1, 2, 3, 4].map((i) => `input motionevent MOVE ${x1 + Math.trunc((x2 - x1) * i / 4)} ${y}`).join('; ');
  const s = adb('shell', `input motionevent DOWN ${x1} ${y}; ${mv}; sleep ${holdSleep}; uiautomator dump /sdcard/ch.xml >/dev/null 2>&1; input motionevent UP ${x2} ${y}; cat /sdcard/ch.xml`).stdout.toString('utf8'), i = s.indexOf('</hierarchy>');
  if (i < 0) throw new Error('held dump failed');
  return s.slice(s.indexOf('<?xml'), i + 12);
}
async function drag(base, vi, short, r) { // r = the settled dump of the page (taken once by the caller): it is the rest file
  write(`${base}-rest`, r);
  let [x1, y, x2] = tool(S.viewportPoint, path.join(out, `${base}-rest.xml`), 'auto', String(vi));
  if (short) x2 = x1 - Math.trunc((x1 - x2) * 2 / 5);
  write(`${base}-moving`, held(x1, y, x2, 0.3)); await sleep(900);
}
async function hold(base, vi, r) {
  write(`${base}-hold-rest`, r);
  const [x, y, p] = tool(S.holdPoint, path.join(out, `${base}-hold-rest.xml`), String(vi));
  for (const [h, f] of [['short', 25], ['long', 60]]) { write(`${base}-hold-${h}`, held(x, y, x + Math.trunc(p * f / 100), 0.5)); await sleep(1300); }
}
const motionDone = new Set();
async function captureV1(c, pre, d, L) {
  const t0 = Date.now(), n = `${pre}-${d.name}`; let files = 1;
  const r = await nav(d); const x = dumpXml(); write(n, x); shot(n);
  const vps = viewports(x);
  if (motionOn && L.motion !== false && vps.length) {
    const todo = vps.map((b, vi) => [b, vi]).filter(([b]) => flag('--motion-all') || !motionDone.has(c + b));
    let mid = null;
    for (const [b, vi] of todo) {
      motionDone.add(c + b);
      await nav(d); await drag(`${n}-vp${vi}`, vi, false, x);
      await nav(d); await hold(`${n}-vp${vi}`, vi, x);
      await nav(d, 'prev'); if (!mid) { mid = dumpXml(); write(`${n}-mid`, mid); shot(`${n}-mid`); files++; }
      try { await drag(`${n}-mid-vp${vi}`, vi, true, mid); files += 7; } catch (e) { console.log(`note  ${n}: mid vp${vi} skipped (${e.message.split(": ").pop()})`); }
    }
  }
  console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(5)} s  ${n}${r.st === 'settled-timeout' ? '  (settle timeout)' : ''}  [${files} files]`);
}

// v2: the static files are already in <out> (pulled once); only the real-finger sets are taken here.
async function motion(c, pre, d, L) {
  const n = `${pre}-${d.name}`, f = path.join(out, n + '.xml'); if (!fs.existsSync(f)) return 0;
  const x = fs.readFileSync(f, 'utf8'), vps = viewports(x); let files = 0;
  if (!(motionOn && L.motion !== false && vps.length)) return 0;
  const todo = vps.map((b, vi) => [b, vi]).filter(([b]) => flag('--motion-all') || !motionDone.has(c + b));
  const mf = path.join(out, `${n}-mid.xml`), mid = fs.existsSync(mf) ? fs.readFileSync(mf, 'utf8') : null;
  for (const [b, vi] of todo) {
    motionDone.add(c + b);
    await nav(d); await drag(`${n}-vp${vi}`, vi, false, x);
    await nav(d); await hold(`${n}-vp${vi}`, vi, x); files += 5;
    if (mid) { await nav(d, 'prev'); try { await drag(`${n}-mid-vp${vi}`, vi, true, mid); files += 2; } catch (e) { console.log(`note  ${n}: mid vp${vi} skipped (${e.message.split(': ').pop()})`); } }
  }
  return files;
}

// ---- fallback: breadth-first crawl by taps (apps without the hook)
async function crawlPass(c, pre) {
  const bad = new RegExp(S.destructive || 'elimina|cancella|rimuovi|delete|remove|invia|send|conferma|confirm|paga|pay|acquista|buy|logout|esci|reset', 'i');
  const sig = (x) => crypto.createHash('sha1').update([...x.matchAll(/<node [^>]*class="([^"]*)"[^>]*resource-id="([^"]*)"/g)].map((m) => m[1] + '|' + m[2]).sort().join('\n')).digest('hex');
  const known = new Set(); const q = [[]]; let k = 0;
  const reach = async (taps) => { sh(`am force-stop ${PKG}`); sh(`am start -n ${PKG}/${S.activity}`); await sleep(3500); for (const [x, y] of taps) { sh(`input tap ${x} ${y}`); await sleep(1500); } };
  while (q.length && k < 60) {
    const taps = q.shift(); await reach(taps); const x = dumpXml(), s = sig(x); if (known.has(s)) continue; known.add(s);
    const n = `${pre}-crawl-${String(++k).padStart(2, '0')}`; write(n, x); shot(n); console.log(`crawl ${n} depth ${taps.length}`);
    if (taps.length >= 3) continue;
    for (const m of x.matchAll(/<node [^>]*>/g)) {
      const a = m[0]; if (!/clickable="true"/.test(a)) continue;
      const t = (/ text="([^"]*)"/.exec(a) || [, ''])[1] + ' ' + (/content-desc="([^"]*)"/.exec(a) || [, ''])[1]; if (bad.test(t)) continue;
      const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(a); if (b && +b[3] > +b[1]) q.push([...taps, [(+b[1] + +b[3]) >> 1, (+b[2] + +b[4]) >> 1]]);
    }
  }
}

const T0 = Date.now(); let total = 0, statT = 0, motionT = 0;
const vpName = (pre, name) => only.some((r) => r.test(`${pre}-${name}-mid`) || r.test(`${pre}-${name}-vp0`));
async function pass(c) {
  const L = S.layouts[c], pre = L.prefix || c, p0 = Date.now(); let n = 0;
  sh(`wm size ${L.wmSize}`); if (L.fontScale) sh(`settings put system font_scale ${L.fontScale}`);
  sh(`am force-stop ${PKG}`); await sleep(800);
  if (crawl) { await crawlPass(c, pre); return; }
  const { items, unmapped } = await list();
  if (unmapped.length) { console.log(`FAIL  Route classes with arguments nobody maps (add them to the capture hook): ${unmapped.join(', ')}`); process.exitCode = 1; }
  if (flag('--list')) { for (const d of items) console.log(`${pre}-${d.name}\t${d.tab}\t${d.route}`); return; }
  if (legacy) {
    const kinds = new Map(), keep = (d) => { if (!L.perKind) return true; const k = d.route.split(':')[0] + (d.route === 'root' ? d.tab : ''), v = (kinds.get(k) || 0) + 1; kinds.set(k, v); return v <= L.perKind; };
    const sel = items.filter(keep).filter((d) => wanted(`${pre}-${d.name}`) || (only.length && vpName(pre, d.name)));
    for (const d of sel) { try { await captureV1(c, pre, d, L); n++; } catch (err) { console.log(`FAIL  ${pre}-${d.name}: ${err.message}`); process.exitCode = 1; } }
    total += n; console.log(`-- ${c}: ${n} destinations in ${((Date.now() - p0) / 1000).toFixed(1)} s`); return;
  }
  // v2: ONE intent, the app walks and captures itself; ONE pull.
  const id = String(++navId), ts = Date.now();
  const a = ['shell', 'am', 'start', '-n', `${PKG}/${S.activity}`, '--activity-single-top', '--es', X.all, id, '--es', X.prefix, pre, '--es', X.vptag, VP, '--es', X.mid, motionOn && L.motion !== false ? '1' : '0', '--es', X.shots, shots ? '1' : '0'];
  if (L.perKind) a.push('--es', X.perkind, String(L.perKind));
  if (framesOpt) a.push('--es', X.frames || 'capture_frames', framesOpt);
  if (only.length) a.push('--es', X.only, `'${opt('--only', '')}'`);
  adb(...a);
  if (!seen.has(id)) await Promise.race([new Promise((r) => waiters.set(id, r)), sleep(240000)]);
  const r = seen.get(id);
  if (!r) throw new Error('no DONE line from the app within 240 s (is the build new enough, and is the archive the sample data?)');
  if (r.st === 'ignored') throw new Error(`capture refused: ${r.rest}. The app honours capture intents only on the synthetic sample archive; load it first.`);
  const pl = adb('pull', `${S.deviceDir || `/sdcard/Android/data/${PKG}/files/capture`}/${id}/.`, out);
  if (pl.status !== 0) throw new Error('adb pull failed: ' + pl.stderr.toString().trim());
  n = fs.readdirSync(out).filter((f) => f.startsWith(pre + '-') && f.endsWith('.xml')).length;
  const t1 = Date.now(); console.log(`${((t1 - ts) / 1000).toFixed(1).padStart(5)} s  static ${c}: ${n} dumps (${r.rest})`);
  let mf = 0;
  const sel = items.filter((d) => fs.existsSync(path.join(out, `${pre}-${d.name}.xml`)));
  for (const d of sel) { try { mf += await motion(c, pre, d, L); } catch (err) { console.log(`FAIL  ${pre}-${d.name} motion: ${err.message}`); process.exitCode = 1; } }
  statT += t1 - ts; motionT += Date.now() - t1; total += sel.length;
  console.log(`-- ${c}: ${sel.length} destinations, static ${((t1 - ts) / 1000).toFixed(1)} s, motion ${((Date.now() - t1) / 1000).toFixed(1)} s (${mf} files)`);
}
const restore = () => { sh('wm size reset'); sh('settings put system font_scale 1.0'); try { lc.kill(); } catch {} };
process.on('SIGINT', () => { restore(); process.exit(130); });
try { for (const c of passes) await pass(c); } finally { restore(); }
if ((legacy || crawl) && shots && fs.readdirSync(full).length) {
  if (S.halfBatch) { const a = S.halfBatch.map((s) => s.replace('{src}', path.resolve(full)).replace('{dst}', path.resolve(half))); spawnSync(a[0], a.slice(1)); }
  else for (const f of fs.readdirSync(full)) fs.copyFileSync(path.join(full, f), path.join(half, f));
}
console.log(`TOTAL ${((Date.now() - T0) / 1000).toFixed(1)} s (static ${(statT / 1000).toFixed(1)} s, motion ${(motionT / 1000).toFixed(1)} s), ${total} destinations, ${fs.readdirSync(out).filter((f) => f.endsWith('.xml')).length} dumps -> ${out}` + (S.density ? `\nnext: node tools/consistency-check.mjs ${out} --density ${S.density}` : ''));
