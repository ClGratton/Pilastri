// Add-on `flow-effort` (SKILL section 2, K31; the sign-in surface of K32 and the return of K34). User 2026-11-10: "E ogni step che si può nascondere con mostra UI, fallo.
// Meno step.possobili, meno accesso possblibili, il più intuitivo e facile possibile"; "Why not in skill as generic ui UX instructions".
// Class: ANY flow of ANY app (setup, connecting a service, import, payment, permission, sign-in). The person does the minimum: a step the system can do itself is not a step
// for the person; a third-party page or app appears only for what only the person can do there (sign-in, strong authentication, a consent the third party requires);
// among documented, permitted methods the one with the fewest person steps wins; one function has one path.
// Usage: node flow-effort.mjs <manifest.json> | node flow-effort.mjs --self-test
// Manifest: { flow_list:[flow ids] | absent, flows:[{ id, function,
//   steps:[{ id, actor:"person|system", surface:"own-ui|third-party-in-app|browser|other-app", kind:"sign-in|input|consent|wait|choice", provider, hideable:true|false,
//            reason, in_app_reason, note }],
//   methods:[{ id, permitted:true|false, first, later, chosen:true|false, reason, source }],
//   return:{ address, owned:true, pending_state } }] }
// `reason` on a step records why a hideable step stays visible, why a third-party surface carries a step that is not sign-in or consent, or why a provider needs a further sign-in.
// `in_app_reason` records that the provider documents no sign-in for apps (with the source), the only ground for its page inside the app.
// `methods` is the flow-effort table: every documented method with the person steps the first time and later; `reason` on the chosen one when a permitted method has fewer.
// `return` is recorded when a sign-in or consent runs in the browser: the address the app owns and where the pending state is kept outside screen memory.
// FAILS (exit 1, one line per finding): a system step on a third-party surface; a hideable person step with no reason; a sign-in or consent on an in-app third-party page with no
// in_app_reason; a third-party surface for another kind of step with no reason; sign-in or consent split between an in-app page and the browser or another app; more than one
// sign-in to one provider with no reason; one function on two paths; one provider's sign-in or consent on two surfaces; a chosen method that is not permitted, or with a permitted
// alternative of fewer person steps (first time or later) and no reason; a function with a third-party step and no method table; a browser sign-in or consent with no owned return
// and pending state; a flow in flow_list with no row; a row with missing fields. There is no filter and no exception file: a finding is fixed in the flow or answered by a reason.
// Limit: it reads a manifest its owner writes. It cannot see a flow that is in neither the manifest nor flow_list, nor a step count that is wrong; the proof is a walk of each flow.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const ACTORS = ['person', 'system'], SURFACES = ['own-ui', 'third-party-in-app', 'browser', 'other-app'], KINDS = ['sign-in', 'input', 'consent', 'wait', 'choice'];
const THIRD = new Set(['third-party-in-app', 'browser', 'other-app']);
const GATE = new Set(['sign-in', 'consent']); // what only the person can do on a third-party surface
const text = (v) => typeof v === 'string' && v.trim().length > 0;
const num = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0;
const person = (f) => (f.steps || []).filter((s) => s.actor === 'person');
const sig = (f) => person(f).map((s) => `${s.surface}:${s.kind}`).join('>');

/** Pure check. Returns { findings:[{scope, text}], stats }. */
export function check(m) {
  const F = [], flows = m.flows || [];
  const add = (scope, msg) => F.push({ scope, text: `${scope}: ${msg}` });
  const ids = new Set();
  for (const f of flows) { if (ids.has(f.id)) add(f.id, 'two manifest rows with this id'); ids.add(f.id); }
  for (const id of m.flow_list || []) if (!ids.has(id)) add(id, 'the flow is in the flow list and has no manifest row');
  const nth = new Map(); // step -> ordinal of this sign-in among the flow's sign-ins to the same provider on the same surface
  for (const f of flows) {
    if (!text(f.function)) add(f.id, 'no function');
    if (!Array.isArray(f.steps) || !f.steps.length) add(f.id, 'no steps');
    for (const s of f.steps || []) {
      const at = `step ${s.id}`;
      if (!ACTORS.includes(s.actor) || !SURFACES.includes(s.surface) || !KINDS.includes(s.kind)) { add(f.id, `${at}: actor, surface or kind missing or unknown`); continue; }
      if (typeof s.hideable !== 'boolean') add(f.id, `${at}: hideable is not recorded (true or false)`);
      if ((THIRD.has(s.surface) || GATE.has(s.kind)) && !text(s.provider)) add(f.id, `${at}: no provider recorded`);
      if (s.actor === 'system' && THIRD.has(s.surface)) add(f.id, `${at}: a step the system does itself is shown on a third-party surface (${s.surface}); run it unseen or behind the app's own screen`);
      if (s.actor === 'person' && s.hideable === true && !text(s.reason)) add(f.id, `${at}: hideable step is visible to the person and has no recorded reason`);
      if (s.actor === 'person' && s.surface === 'third-party-in-app' && GATE.has(s.kind) && !text(s.in_app_reason)) add(f.id, `${at}: ${s.kind} on an in-app third-party page with no recorded reason (the provider's documented sign-in for apps, in the default browser, comes first)`);
      if (s.actor === 'person' && THIRD.has(s.surface) && !GATE.has(s.kind) && !text(s.reason)) add(f.id, `${at}: third-party surface used for a "${s.kind}" step, which is not sign-in or consent, and no reason`);
    }
    const gate = person(f).filter((s) => GATE.has(s.kind));
    const emb = gate.filter((s) => s.surface === 'third-party-in-app'), ext = gate.filter((s) => s.surface === 'browser' || s.surface === 'other-app');
    if (emb.length && ext.length) add(f.id, `sign-in or consent is split between an in-app third-party page (${emb.map((s) => s.id).join(', ')}) and the browser or another app (${ext.map((s) => s.id).join(', ')}); a session started in an in-app page does not exist outside it`);
    if (gate.some((s) => s.surface === 'browser') && !(f.return && text(f.return.address) && f.return.owned === true && text(f.return.pending_state)))
      add(f.id, 'a sign-in or consent runs in the browser and the flow records no return to an address the app owns with a pending state kept outside screen memory');
    const seen = new Map();
    for (const s of person(f)) if (s.kind === 'sign-in' && text(s.provider)) { const k = `${s.provider}|${s.surface}`; seen.set(k, (seen.get(k) || 0) + 1); nth.set(s, seen.get(k)); }
    if (f.methods !== undefined) {
      const ms = Array.isArray(f.methods) ? f.methods : [];
      const ch = ms.filter((x) => x.chosen === true);
      if (ch.length !== 1) add(f.id, `${ch.length} chosen methods, expected 1`);
      for (const x of ms) {
        if (!text(x.id) || typeof x.permitted !== 'boolean') add(f.id, `method ${x.id}: id or permitted (true or false) missing`);
        else if ((x.permitted || x.chosen) && !(num(x.first) && num(x.later))) add(f.id, `method ${x.id}: person steps first time and later are not recorded as numbers`);
      }
      for (const c of ch) {
        if (c.permitted !== true) add(f.id, `chosen method "${c.id}" is not a permitted method`);
        for (const a of ms) if (a !== c && a.permitted === true && num(a.first) && num(a.later) && num(c.first) && num(c.later) && (a.first < c.first || a.later < c.later) && !text(c.reason))
          add(f.id, `chosen method "${c.id}" (${c.first} person steps first time, ${c.later} later) while permitted "${a.id}" has ${a.first} and ${a.later}, and no reason is recorded`);
      }
    }
  }
  const byFn = new Map();
  for (const f of flows) if (text(f.function)) byFn.set(f.function, [...(byFn.get(f.function) || []), f]);
  for (const [fn, list] of byFn) {
    // one function, one path: flows of one function share the same person-step signature
    const sigs = new Map();
    for (const f of list) sigs.set(sig(f), [...(sigs.get(sig(f)) || []), f.id]);
    if (sigs.size > 1) add(`function ${fn}`, `reachable through ${sigs.size} different paths: ${[...sigs].map(([s, i]) => `${i.join('+')} [${s || 'no person step'}]`).join(' | ')}`);
    // the flow-effort table exists for every function that sends the person to a third party
    if (list.some((f) => person(f).some((s) => THIRD.has(s.surface))) && !list.some((f) => Array.isArray(f.methods) && f.methods.length))
      add(`function ${fn}`, 'a person step runs on a third-party surface and no method table is recorded (per documented method: person steps first time and later)');
  }
  // the same provider's sign-in or consent for one function on two surfaces
  const grp = new Map();
  for (const f of flows) for (const s of person(f)) if (GATE.has(s.kind) && text(s.provider)) {
    const k = `${f.function}|${s.provider}|${s.kind}`;
    const g = grp.get(k) || new Map(); g.set(s.surface, [...(g.get(s.surface) || []), `${f.id}/${s.id}`]); grp.set(k, g);
  }
  for (const [k, g] of grp) if (g.size > 1) { const [fn, prov, kind] = k.split('|'); add(`function ${fn}`, `${kind} to "${prov}" is asked on ${g.size} surfaces: ${[...g].map(([s, i]) => `${s} (${i.join(', ')})`).join('; ')}`); }
  // more than one sign-in to one provider. The same sign-in met in two flows of one function (first time and a later renewal) counts once; a second sign-in step inside one flow counts.
  const per = new Map();
  for (const f of flows) for (const s of person(f)) if (s.kind === 'sign-in' && text(s.provider)) {
    const p = per.get(s.provider) || new Map(); const key = `${f.function}|${s.surface}|${nth.get(s)}`;
    if (!p.has(key)) p.set(key, { id: `${f.id}/${s.id}`, reason: s.reason }); per.set(s.provider, p);
  }
  for (const [prov, p] of per) if (p.size > 1 && [...p.values()].filter((x) => !text(x.reason)).length > 1)
    add(`provider ${prov}`, `${p.size} sign-ins (${[...p.values()].map((x) => x.id).join(', ')}); one sign-in serves everything the provider gives, or each further one records its reason`);
  const by = {};
  for (const x of F) by[x.scope] = (by[x.scope] || 0) + 1;
  return { findings: F, stats: { flows: flows.length, steps: flows.reduce((n, f) => n + (f.steps || []).length, 0), findings: F.length, by } };
}

export function run(manifestPath) {
  const r = check(JSON.parse(fs.readFileSync(manifestPath, 'utf8')));
  for (const f of r.findings) console.log('FINDING ' + f.text);
  for (const [k, n] of Object.entries(r.stats.by)) console.log(`COUNT ${n} ${k}`);
  console.log(`flow-effort: ${r.stats.flows} flows, ${r.stats.steps} steps, ${r.stats.findings} findings`);
  return r.findings.length ? 1 : 0;
}

const st = (id, actor, surface, kind, extra = {}) => ({ id, actor, surface, kind, provider: 'p1', hideable: false, ...extra });
const RET = { address: 'https://app.example/return', owned: true, pending_state: 'pending-authorization store' };
const M1 = [{ id: 'm1', permitted: true, first: 2, later: 0, chosen: true }];
export function fixtures() {
  const clean = { flow_list: ['connect', 'renew'], flows: [
    { id: 'connect', function: 'connect-p1', return: RET, steps: [st('s1', 'person', 'browser', 'sign-in'), st('s2', 'system', 'own-ui', 'wait')],
      methods: [{ id: 'm1', permitted: true, first: 2, later: 0, chosen: true }, { id: 'm2', permitted: true, first: 4, later: 1, chosen: false }, { id: 'm3', permitted: false, chosen: false }] },
    { id: 'renew', function: 'connect-p1', return: RET, steps: [st('s1', 'person', 'browser', 'sign-in'), st('s2', 'system', 'own-ui', 'wait')] },
    { id: 'legacy', function: 'connect-p2', methods: M1, steps: [st('s1', 'person', 'third-party-in-app', 'sign-in', { provider: 'p2', in_app_reason: 'the provider documents no sign-in for apps (docs index read)' })] },
    { id: 'form', function: 'profile', steps: [st('s1', 'person', 'own-ui', 'input', { hideable: true, reason: 'only the person knows the value' })] }] };
  // (a) the same provider sign-in present twice: its website in an in-app page and the browser flow
  const a = { flows: [{ id: 'setup', function: 'sign-in-p1', methods: M1, steps: [st('s1', 'person', 'third-party-in-app', 'sign-in', { in_app_reason: 'x' })] },
    { id: 'panel', function: 'sign-in-p1', return: RET, steps: [st('s1', 'person', 'browser', 'sign-in')] }] };
  // (b) a two-step approval, step 1 in an in-app page and step 2 in the browser
  const b = { flows: [{ id: 'approval', function: 'account-approval', methods: M1, return: RET,
    steps: [st('s1', 'person', 'third-party-in-app', 'consent', { provider: 'aggregator', in_app_reason: 'x' }), st('s2', 'person', 'browser', 'consent', { provider: 'account-holder' })] }] };
  // (c) a provider page shown for a step the app does itself
  const c = { flows: [{ id: 'import', function: 'import-p1', steps: [st('s1', 'system', 'third-party-in-app', 'wait')] }] };
  const one = (extra, steps) => ({ flows: [{ id: 'x', function: 'fx', methods: M1, return: RET, steps, ...extra }] });
  return { clean, a, b, c,
    hid: one({}, [st('s1', 'person', 'own-ui', 'input', { hideable: true })]),
    alt: one({ methods: [{ id: 'long', permitted: true, first: 5, later: 2, chosen: true }, { id: 'short', permitted: true, first: 2, later: 2, chosen: false }] }, [st('s1', 'person', 'browser', 'sign-in')]),
    altLater: one({ methods: [{ id: 'again', permitted: true, first: 2, later: 2, chosen: true }, { id: 'kept', permitted: true, first: 2, later: 0, chosen: false }] }, [st('s1', 'person', 'browser', 'sign-in')]),
    altReason: one({ methods: [{ id: 'long', permitted: true, first: 5, later: 2, chosen: true, reason: 'the short method is closed to this kind of app (source)' }, { id: 'short', permitted: true, first: 2, later: 2, chosen: false }] }, [st('s1', 'person', 'browser', 'sign-in')]),
    notPermitted: one({ methods: [{ id: 'site', permitted: false, first: 3, later: 0, chosen: true }] }, [st('s1', 'person', 'browser', 'sign-in')]),
    miss: { flow_list: ['a', 'b'], flows: [{ id: 'a', function: 'fa', steps: [st('s1', 'person', 'own-ui', 'choice')] }] },
    inapp: one({}, [st('s1', 'person', 'third-party-in-app', 'sign-in')]),
    twice: one({}, [st('s1', 'person', 'browser', 'sign-in'), st('s2', 'system', 'own-ui', 'wait'), st('s3', 'person', 'browser', 'sign-in')]),
    noTable: one({ methods: undefined }, [st('s1', 'person', 'browser', 'sign-in')]),
    noReturn: one({ return: undefined }, [st('s1', 'person', 'browser', 'sign-in')]),
    notOwned: one({ return: { address: 'https://provider.example/done', owned: false, pending_state: 'store' } }, [st('s1', 'person', 'browser', 'consent')]),
    otherKind: one({}, [st('s1', 'person', 'browser', 'input')]),
    noHideable: one({}, [{ id: 's1', actor: 'person', surface: 'own-ui', kind: 'choice' }]) };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2);
  if (a.includes('--self-test') || a.includes('--selftest')) {
    const f = fixtures(), has = (m, re) => check(m).findings.some((x) => re.test(x.text)), n = (m) => check(m).findings.length;
    const res = [['clean manifest passes (browser sign-in with return, renewal on the same path, in-app page with its reason, hideable step with its reason)', n(f.clean) === 0],
      ['(a) same provider sign-in twice, in-app page and browser: two paths, two surfaces, two sign-ins', has(f.a, /different paths/) && has(f.a, /asked on 2 surfaces/) && has(f.a, /provider p1: 2 sign-ins/)],
      ['(b) two-step approval, step 1 in an in-app page and step 2 in the browser', has(f.b, /split between an in-app third-party page \(s1\) and the browser or another app \(s2\)/)],
      ['(c) provider page shown for a step the app does itself', has(f.c, /system does itself/)],
      ['hideable visible step without reason', has(f.hid, /hideable step is visible/)],
      ['chosen method with a permitted alternative of fewer first-time steps', has(f.alt, /permitted "short"/)],
      ['chosen method with a permitted alternative of fewer later steps', has(f.altLater, /permitted "kept"/)],
      ['the same choice with a recorded reason passes', n(f.altReason) === 0],
      ['chosen method that is not permitted', has(f.notPermitted, /not a permitted method/)],
      ['flow in the list without a row', has(f.miss, /^b: the flow is in the flow list/)],
      ['in-app sign-in without reason', has(f.inapp, /in-app third-party page with no recorded reason/)],
      ['second sign-in to one provider inside one flow', has(f.twice, /provider p1: 2 sign-ins/)],
      ['third-party step with no method table', has(f.noTable, /no method table/)],
      ['browser sign-in with no recorded return', has(f.noReturn, /records no return/)],
      ['return to an address the app does not own', has(f.notOwned, /records no return/)],
      ['third-party surface for a step that is not sign-in or consent', has(f.otherKind, /not sign-in or consent/)],
      ['step with hideable not recorded', has(f.noHideable, /hideable is not recorded/)]];
    for (const [k, v] of res) console.log((v ? 'ok   ' : 'FAIL ') + k);
    const pass = res.every(([, v]) => v);
    console.log(pass ? `flow-effort selftest PASS (${res.length} cases; the three user cases (a) (b) (c) flagged)` : 'flow-effort selftest FAIL');
    process.exit(pass ? 0 : 1);
  }
  const mp = a.find((x) => !x.startsWith('--'));
  if (!mp) { console.error('usage: flow-effort.mjs <manifest.json> | --self-test'); process.exit(2); }
  process.exit(run(mp));
}
