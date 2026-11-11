// Offline harness for the Enable Banking page scripts (EbPanelScripts). Usage: node tools/eb-script-harness.mjs <dir>
// <dir> holds the REAL script texts written by EbPanelScriptsHarnessTest: REGISTER.js, ASPSPS.js, STATUS.js, LINK.js.
// Each script runs as the page runs it: the body of an async function with ARGS, a fake page (document, location, #app.__vue__.$store),
// a fake sign-in SDK (store.dispatch('getIdTokenResult', true)) and a fetch that answers from a scenario table. Nothing leaves the machine.
// ASSUMED shapes are marked "ASSUMED"; the others come from RESEARCH.md "EB API shapes" and output/eb-bundle-2026-11-10/app.js
// (getApplications stores response.data as is and the page reads applications.length: a bare array; registerApp reads data.app_id;
// errors read t.response.data.error.message).
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
if (!dir) { console.error('usage: node eb-script-harness.mjs <dir>'); process.exit(2); }
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const JWT = 'hdr.payload-SECRETMARK.sig';
const JWT2 = 'hdr2.payload2-SECRETMARK.sig2';
const SECRET = 'SECRETMARK';
const APP = { kid: 'app-1', active: true, whitelisted_accounts: [{ uid: 1 }, { uid: 2 }] };
const OTHER = { kid: 'other', active: false };

const R = (status, body, ct = 'application/json; charset=utf-8') => ({ status, body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)), ct });
const REJECT = { reject: true };
const HANG = { hang: true };
const LIST_OK = (a) => R(200, a);
const CREATED = R(201, { app_id: 'new-app' });
const CERT_MSG = "Cannot read properties of undefined (reading 'length')";

function page(sc) {
  const store = {
    getters: sc.getters ?? { idToken: sc.storeToken },
    state: sc.state ?? { idToken: sc.storeToken, applications: sc.apps },
    dispatch: async (name) => {
      if (name === 'getIdTokenResult') { if (sc.sdk === 'reject') throw new Error('sdk'); return sc.sdk; }
      return undefined;
    },
  };
  if (sc.noState) { store.state = undefined; store.getters = {}; }
  const rows = sc.rows ?? 0;
  const document = {
    querySelector: (s) => (s === '#app' ? { __vue__: sc.noStore ? undefined : { $store: store } } : null),
    querySelectorAll: () => new Array(rows).fill({}),
    body: { innerText: '' },
  };
  return { document, location: { pathname: '/cp/applications' } };
}

function fakeFetch(sc, calls) {
  const queue = (sc.routes || []).slice();
  return (url, opt = {}) => {
    const method = (opt.method || 'GET').toUpperCase();
    calls.push(method + ' ' + url);
    const a = queue.length > 1 ? queue.shift() : queue[0];
    if (!a) return Promise.reject(new Error('no route'));
    if (a.reject) return Promise.reject(new TypeError('Failed to fetch'));
    if (a.hang) return new Promise(() => {});
    return Promise.resolve({
      status: a.status, ok: a.status >= 200 && a.status < 300,
      headers: { get: (h) => (h.toLowerCase() === 'content-type' ? a.ct : null) },
      text: async () => (a.body === undefined ? '' : a.body),
      json: async () => JSON.parse(a.body === undefined ? '' : a.body),
    });
  };
}

async function runScript(sc) {
  const body = fs.readFileSync(path.join(dir, (sc.script || 'REGISTER') + '.js'), 'utf8');
  const calls = [];
  const { document, location } = page(sc);
  const fn = new AsyncFunction('ARGS', 'document', 'location', 'fetch', 'setTimeout', body);
  const args = JSON.parse(JSON.stringify(sc.args ?? { appId: null, email: 'a@b.c', payload: { name: 'x' } }));
  if (sc.nullPayload) args.payload = null;
  const p = fn(args, document, location, fakeFetch(sc, calls), (f) => setImmediate(f));
  const out = await Promise.race([p.then((r) => ({ r }), (e) => ({ thrown: e })), new Promise((res) => setTimeout(() => res({ pending: true }), 250))]);
  return { ...out, calls };
}

const tok = (extra = {}) => ({ sdk: { token: JWT }, storeToken: { token: JWT2 }, ...extra });
const reg = (name, routes, expect, extra = {}) => ({ name, script: 'REGISTER', ...tok(), routes, expect, ...extra });
const REG_OK = { code: undefined, appId: 'new-app', reused: false };
const listErr = (name, res, reason) => reg('list 400 ' + name, [res], { code: 'http', http: 400, reason });
const WITH_KNOWN = { args: { appId: 'app-1', email: 'a@b.c', payload: {} } };

const S = [
  // token sources
  reg('sdk token object (src=sdk)', [LIST_OK([]), CREATED], { ...REG_OK, tsrc: 'sdk' }),
  reg('sdk token string (no .token) falls back to store', [LIST_OK([]), CREATED], { ...REG_OK, tsrc: 'store' }, { sdk: JWT }),
  reg('sdk token undefined falls back to store', [LIST_OK([]), CREATED], { ...REG_OK, tsrc: 'store' }, { sdk: undefined }),
  reg('sdk rejected -> auth', [LIST_OK([])], { code: 'auth' }, { sdk: 'reject' }),
  reg('store token string', [LIST_OK([]), CREATED], { ...REG_OK, tsrc: 'store' }, { sdk: undefined, storeToken: JWT2 }),
  reg('store token object', [LIST_OK([]), CREATED], { ...REG_OK, tsrc: 'store' }, { sdk: undefined, storeToken: { token: JWT2 } }),
  reg('store token undefined and sdk undefined -> net (tbad)', [LIST_OK([])], { code: 'net', tbad: true }, { sdk: undefined, storeToken: undefined }),
  reg('no store -> page', [], { code: 'page' }, { noStore: true }),
  reg('store without state and no token -> script/token, not a TypeError', [], { code: 'script', where: 'token' }, { sdk: undefined, noState: true }),
  // list 200 shapes
  reg('list [] then create', [LIST_OK([]), CREATED], { ...REG_OK, calls: ['GET /api/applications', 'POST /api/applications'] }),
  reg('list with ours -> reused, no create', [LIST_OK([OTHER, APP])], { code: undefined, appId: 'app-1', reused: true, calls: ['GET /api/applications'] }, WITH_KNOWN),
  reg('list without ours -> create', [LIST_OK([OTHER]), CREATED], { ...REG_OK }, WITH_KNOWN),
  reg('list with null element is tolerated', [LIST_OK([null, APP])], { appId: 'app-1', reused: true }, WITH_KNOWN),
  reg('list wrapper {applications:[]} (ASSUMED)', [LIST_OK({ applications: [] }), CREATED], { ...REG_OK }),
  reg('list wrapper {data:[]} (ASSUMED)', [LIST_OK({ data: [] }), CREATED], { ...REG_OK }),
  reg('list null -> parse-list, no create', [R(200, 'null')], { code: 'script', where: 'parse-list', calls: ['GET /api/applications'] }),
  reg('list empty body -> parse-list', [R(200, undefined)], { code: 'script', where: 'parse-list', calls: ['GET /api/applications'] }),
  reg('list object of unknown shape -> parse-list with key names', [R(200, { foo: 1, bar: [] })], { code: 'script', where: 'parse-list', shapeKeys: 'foo,bar', calls: ['GET /api/applications'] }),
  reg('list string -> parse-list', [R(200, '"x"')], { code: 'script', where: 'parse-list' }),
  reg('list HTML (not JSON) -> parse-list', [R(200, '<html></html>', 'text/html')], { code: 'script', where: 'parse-list' }),
  // list errors (real: 400 {"error":{"message":...}} seen 2026-11-10 16:09; other shapes ASSUMED)
  listErr('{error:{message}}', R(400, { error: { message: 'Bad thing' } }), 'Bad thing'),
  listErr('{error:"text"}', R(400, { error: 'Bad text' }), 'Bad text'),
  listErr('{message}', R(400, { message: 'Msg' }), 'Msg'),
  listErr('{detail}', R(400, { detail: 'Det' }), 'Det'),
  listErr('{errors:[..]}', R(400, { errors: ['First'] }), 'First'),
  listErr('plain text', R(400, 'Bad Request', 'text/plain'), ''),
  listErr('empty body', R(400, undefined), ''),
  reg('list 401 -> auth', [R(401, { error: { message: 'no' } })], { code: 'auth' }),
  reg('list 403 -> auth', [R(403, {})], { code: 'auth' }),
  reg('list 404 -> empty then create', [R(404, ''), CREATED], { ...REG_OK, lh: 404 }),
  reg('list 429 then 200', [R(429, ''), LIST_OK([]), CREATED], { ...REG_OK, calls: ['GET /api/applications', 'GET /api/applications', 'POST /api/applications'] }),
  reg('list 500 twice -> http 500', [R(500, 'oops', 'text/plain')], { code: 'http', http: 500, reason: '' }),
  reg('list 500 then 200', [R(500, ''), LIST_OK([]), CREATED], { ...REG_OK }),
  // the server's own exception on the list (HYPOTHESIS: list unavailable)
  reg('list 400 server exception, guards hold -> create', [R(400, { error: { message: CERT_MSG } }), CREATED], { ...REG_OK, lunavail: true, calls: ['GET /api/applications', 'POST /api/applications'] }, { apps: [] }),
  reg('list 400 server exception, applications undefined -> create', [R(400, { error: { message: CERT_MSG } }), CREATED], { ...REG_OK, lunavail: true }, {}),
  reg('list 400 server exception, stored app id -> refused path', [R(400, { error: { message: CERT_MSG } })], { code: 'http', http: 400, reason: CERT_MSG, calls: ['GET /api/applications'] }, WITH_KNOWN),
  reg('list 400 server exception, page store has applications -> refused path', [R(400, { error: { message: CERT_MSG } })], { code: 'http', http: 400, reason: CERT_MSG, calls: ['GET /api/applications'] }, { apps: [APP] }),
  reg('list 400 server exception, page shows application rows -> refused path', [R(400, { error: { message: CERT_MSG } })], { code: 'http', http: 400, reason: CERT_MSG, calls: ['GET /api/applications'] }, { rows: 2 }),
  reg('list 400 other message is not treated as unavailable', [R(400, { error: { message: 'Something else' } })], { code: 'http', http: 400, reason: 'Something else', calls: ['GET /api/applications'] }),
  // create
  reg('create 200 with app_id', [LIST_OK([]), R(200, { app_id: 'new-app' })], { ...REG_OK }),
  reg('create 201 without app_id -> parse-create', [LIST_OK([]), R(201, { ok: true })], { code: 'script', where: 'parse-create', shapeKeys: 'ok' }),
  reg('create 200 not JSON -> parse-create', [LIST_OK([]), R(200, 'ok', 'text/plain')], { code: 'script', where: 'parse-create' }),
  reg('create 400 with reason -> refused', [LIST_OK([]), R(400, { error: { message: 'Bad cert' } })], { code: 'refused', reason: 'Bad cert', http: 400 }),
  reg('create 422 {detail} -> refused', [LIST_OK([]), R(422, { detail: 'Invalid' })], { code: 'refused', reason: 'Invalid', http: 422 }),
  reg('create 400 without reason -> http', [LIST_OK([]), R(400, 'nope', 'text/plain')], { code: 'http', http: 400 }),
  reg('create 500 with reason -> http (no refusal)', [LIST_OK([]), R(500, { error: { message: 'boom' } })], { code: 'http', http: 500 }),
  reg('create 401 -> auth', [LIST_OK([]), R(401, {})], { code: 'auth' }),
  reg('create with null payload -> script/create, not a TypeError', [LIST_OK([])], { code: 'script', where: 'create' }, { nullPayload: true }),
  // network
  reg('fetch rejects on list -> net', [REJECT], { code: 'net' }),
  reg('fetch rejects on create -> net', [LIST_OK([]), REJECT], { code: 'net' }),
  reg('fetch never answers -> pending (the Kotlin bound ends it)', [HANG], { pending: true }),
  // other scripts: smoke
  { name: 'ASPSPS smoke', script: 'ASPSPS', ...tok(), args: { country: 'IT' }, routes: [R(200, [{ name: 'B1', country: 'IT', psu_types: ['personal'] }, { name: 'B2', country: 'FR' }])], expect: { code: undefined, banks: 1 } },
  { name: 'ASPSPS 500 -> http', script: 'ASPSPS', ...tok(), args: { country: 'IT' }, routes: [R(500, '')], expect: { code: 'http', http: 500 } },
  { name: 'ASPSPS bad JSON -> script, not a TypeError', script: 'ASPSPS', ...tok(), args: { country: 'IT' }, routes: [R(200, 'x', 'text/plain')], expect: { code: 'script' } },
  { name: 'STATUS smoke', script: 'STATUS', ...tok(), args: { appId: 'app-1' }, routes: [LIST_OK([APP])], expect: { code: undefined, active: true, linked: 2 } },
  { name: 'STATUS no accounts', script: 'STATUS', ...tok(), args: { appId: 'app-1', bank: 'Banca Esempio' }, routes: [LIST_OK([{ kid: 'app-1', active: true, whitelisted_accounts: [] }])], expect: { code: undefined, active: true, linked: 0, here: 0, statusKeys: [] } },
  { name: 'STATUS one account mentioning the bank (case-insensitive)', script: 'STATUS', ...tok(), args: { appId: 'app-1', bank: 'banca esempio' }, routes: [LIST_OK([{ kid: 'app-1', active: true, whitelisted_accounts: [{ uid: 1, aspsp: { name: 'Banca Esempio' } }] }])], expect: { code: undefined, active: true, linked: 1, here: 1, statusKeys: ['uid', 'aspsp'] } },
  { name: 'STATUS one account of another bank', script: 'STATUS', ...tok(), args: { appId: 'app-1', bank: 'Banca Esempio' }, routes: [LIST_OK([{ kid: 'app-1', active: true, whitelisted_accounts: [{ uid: 1, aspsp: { name: 'Altra Banca' } }] }])], expect: { code: undefined, active: true, linked: 1, here: 0 } },
  { name: 'STATUS whitelisted_accounts missing does not throw', script: 'STATUS', ...tok(), args: { appId: 'app-1', bank: 'Banca Esempio' }, routes: [LIST_OK([{ kid: 'app-1', active: true }])], expect: { code: undefined, active: true, linked: 0, here: 0, statusKeys: [] } },
  { name: 'STATUS unknown app', script: 'STATUS', ...tok(), args: { appId: 'zz' }, routes: [LIST_OK([APP])], expect: { code: 'refused' } },
  { name: 'LINK smoke', script: 'LINK', ...tok(), args: { country: 'IT', aspsp: 'B1', appId: 'app-1', psuType: 'personal', redirect: 'https://x/y' }, routes: [R(200, { url: 'https://bank/auth' })], expect: { code: undefined, url: 'https://bank/auth' } },
  { name: 'LINK refused', script: 'LINK', ...tok(), args: { country: 'IT', aspsp: 'B1', appId: 'app-1', psuType: 'personal', redirect: 'https://x/y' }, routes: [R(400, { message: 'no bank' })], expect: { code: 'refused', reason: 'no bank' } },
];

const only = process.env.ONLY ? process.env.ONLY.split(",") : null;
let failed = 0;
for (const sc of S.filter((x) => !only || only.includes(x.script))) {
  const res = await runScript(sc);
  const why = [];
  const e = sc.expect;
  if (res.thrown) why.push('THROWN ' + String((res.thrown && res.thrown.message) || res.thrown));
  else if (e.pending) { if (!res.pending) why.push('expected pending, got ' + JSON.stringify(res.r)); }
  else if (res.pending) why.push('pending (never returned)');
  else {
    const r = res.r;
    if (r === null || typeof r !== 'object') why.push('result is not an object: ' + JSON.stringify(r));
    else {
      if (JSON.stringify(r).includes(SECRET)) why.push('RESULT HOLDS THE TOKEN');
      if ('code' in e && r.code !== e.code) why.push(`code ${r.code} != ${e.code} (${JSON.stringify(r).slice(0, 160)})`);
      for (const k of ['where', 'http', 'reason', 'appId', 'reused', 'lh', 'lunavail', 'active', 'linked', 'here', 'url']) if (k in e && r[k] !== e[k]) why.push(`${k} ${r[k]} != ${e[k]}`);
      if ('statusKeys' in e && JSON.stringify(r.keys) !== JSON.stringify(e.statusKeys)) why.push('keys ' + JSON.stringify(r.keys));
      if ('banks' in e && !(Array.isArray(r.banks) && r.banks.length === e.banks)) why.push('banks ' + JSON.stringify(r.banks));
      if ('tsrc' in e && (!r.tshape || r.tshape.src !== e.tsrc)) why.push('token src ' + (r.tshape && r.tshape.src));
      if ('shapeKeys' in e && (!r.shape || r.shape.keys !== e.shapeKeys)) why.push('shape keys ' + JSON.stringify(r.shape));
      if (r.code === 'script' && (!r.where || !r.msg)) why.push('script result without where/msg');
    }
  }
  if (e.calls && JSON.stringify(res.calls) !== JSON.stringify(e.calls)) why.push('calls ' + JSON.stringify(res.calls));
  if (why.length) { failed++; console.log('FAIL ' + sc.name + ' :: ' + why.join(' | ')); } else console.log('ok   ' + sc.name);
}
console.log(`${S.filter((x) => !only || only.includes(x.script)).length} scenarios, ${failed} failed`);
process.exit(failed ? 1 : 0);
