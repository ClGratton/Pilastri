// Add-on `service-docs` (SKILL section 2, K27). User 2026-11-10: "Assicurati che leggi documentazione per tutte banche sennò risuvcede lo stesso per altri";
// "metti in skill che se si usano servizi si legge tutta parte documentazione necessaria all'app, non solo pezzi".
// When the product uses an external service, the WHOLE part of its documentation the product depends on is read before dependent code is written:
// page list from the docs index, each page needed / not needed (reason), every needed page read to its end (length recorded), coverage recorded for EVERY member of the service.
// Usage: node service-docs.mjs <manifest.json> [--root <dir>] | node service-docs.mjs --self-test
// Manifest: { max_age_days, source:{roots:[...], host_pattern, ignore_hosts:[{host,reason}]},
//   services:[{ id, uses, hosts:[...], docs_index, index_pages:<count of pages the index lists>, docs_checked:"YYYY-MM-DD",
//     pages:[{url, needed, reason, chars_total, chars_read, read:"YYYY-MM-DD", evidence}],
//     members:{what, total, recorded, evidence} | null }] }   (members null or absent: the service covers no set of members)
// Exit 1: one line per finding. A docs_checked older than max_age_days is a warning only.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const isDate = (s) => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s));
const num = (v) => typeof v === 'number' && Number.isFinite(v);

/** Hosts found in source texts: [{host, file}] unique by host. */
export function hostsIn(texts, pattern = 'https?://([a-z0-9.-]+)') {
  const re = new RegExp(pattern, 'gi'), seen = new Map();
  for (const { file, text } of texts) for (const m of text.matchAll(re)) { const h = (m[1] || '').toLowerCase().replace(/\.+$/, ''); if (!seen.has(h)) seen.set(h, file); }
  return [...seen].map(([host, file]) => ({ host, file }));
}

/** Pure check. Returns { findings:[string], warnings:[string], stats }. `today` is YYYY-MM-DD. */
export function check(manifest, hostsFound = [], today = new Date().toISOString().slice(0, 10)) {
  const F = [], W = [], st = { services: 0, needed: 0, whole: 0 };
  const services = manifest.services || [], ignore = new Set((manifest.source?.ignore_hosts || []).map((i) => i.host.toLowerCase()));
  for (const i of manifest.source?.ignore_hosts || []) if (!i.reason) F.push(`ignore_hosts "${i.host}" has no reason`);
  const owned = new Set(services.flatMap((s) => (s.hosts || []).map((h) => h.toLowerCase())));
  for (const { host, file } of hostsFound) if (!owned.has(host) && !ignore.has(host)) F.push(`host "${host}" (${file}) belongs to no service and is not ignored`);
  for (const s of services) {
    st.services++;
    const id = s.id || '(no id)';
    if (!s.docs_index) F.push(`${id}: no docs index or sitemap`);
    if (!Array.isArray(s.pages) || !s.pages.length) F.push(`${id}: no page list`);
    else if (!num(s.index_pages) || s.index_pages <= 0) F.push(`${id}: number of pages in the docs index unknown; the page list is not shown complete`);
    else if (s.pages.length < s.index_pages) F.push(`${id}: page list has ${s.pages.length} of ${s.index_pages} pages in the docs index`);
    for (const p of s.pages || []) {
      if (p.needed === false) { if (!p.reason) F.push(`${id}: not-needed page ${p.url} has no reason`); continue; }
      st.needed++;
      if (!num(p.chars_total) || p.chars_total <= 0) F.push(`${id}: needed page ${p.url} has no recorded length`);
      else if (!num(p.chars_read) || p.chars_read < p.chars_total) F.push(`${id}: needed page ${p.url} read ${p.chars_read ?? 0} of ${p.chars_total} characters`);
      else if (!isDate(p.read)) F.push(`${id}: needed page ${p.url} has no read date`);
      else st.whole++;
    }
    const m = s.members;
    if (m) {
      if (!num(m.total) || m.total <= 0) F.push(`${id}: members (${m.what || '?'}) total unknown`);
      else if (!num(m.recorded) || m.recorded < m.total) F.push(`${id}: members (${m.what || '?'}) recorded ${m.recorded ?? 0} of ${m.total}`);
    }
    if (!isDate(s.docs_checked)) F.push(`${id}: no docs_checked date`);
    else if (manifest.max_age_days && (Date.parse(today) - Date.parse(s.docs_checked)) / 864e5 > manifest.max_age_days) W.push(`${id}: docs_checked ${s.docs_checked} is older than ${manifest.max_age_days} days; re-check the documentation`);
  }
  return { findings: F, warnings: W, stats: { ...st, findings: F.length } };
}

function scan(roots, base) {
  const out = [];
  const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else if (/\.(kt|java|js|mjs|ts|xml|json|html|md)$/.test(e.name)) out.push({ file: path.relative(base, p).replace(/\\/g, '/'), text: fs.readFileSync(p, 'utf8') }); } };
  for (const r of roots) { const d = path.resolve(base, r); if (fs.existsSync(d)) walk(d); }
  return out;
}

export function run(manifestPath, base = process.cwd()) {
  const m = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const found = hostsIn(scan(m.source?.roots || [], base), m.source?.host_pattern);
  const r = check(m, found);
  for (const w of r.warnings) console.log('WARN ' + w);
  for (const f of r.findings) console.log('FINDING ' + f);
  console.log(`service-docs: ${r.stats.services} services, ${r.stats.needed} pages needed, ${r.stats.whole} read whole, ${r.stats.findings} findings`);
  return r.findings.length ? 1 : 0;
}

export function fixtures() {
  const page = { url: 'https://docs.example.test/a', needed: true, chars_total: 500, chars_read: 500, read: '2026-11-10', evidence: 'a.txt' };
  const good = { max_age_days: 90, source: { roots: [], ignore_hosts: [{ host: 'localhost', reason: 'dev host' }] },
    services: [{ id: 'svc', uses: 'x', hosts: ['api.example.test'], docs_index: 'https://docs.example.test/', docs_checked: '2026-11-10', index_pages: 2,
      pages: [page, { url: 'https://docs.example.test/b', needed: false, reason: 'unrelated product line' }], members: { what: 'plans', total: 4, recorded: 4, evidence: 'm.json' } }] };
  const clone = (o) => JSON.parse(JSON.stringify(o));
  const trunc = clone(good); trunc.services[0].pages[0] = { ...page, chars_total: 182754, chars_read: 100000 };
  const noReason = clone(good); delete noReason.services[0].pages[1].reason;
  const partial = clone(good); partial.services[0].members = { what: 'plans', total: 40, recorded: 1 };
  return { good, trunc, noReason, partial };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2);
  if (a.includes('--self-test')) {
    const f = fixtures(), has = (r, re) => r.findings.some((x) => re.test(x));
    const ok = check(f.good, [{ host: 'api.example.test', file: 'x' }, { host: 'localhost', file: 'x' }]);
    const t = check(f.trunc), h = check(f.good, [{ host: 'stray.example.test', file: 'src/A.kt' }]), p = check(f.partial), n = check(f.noReason);
    const old = check(f.good, [], '2027-06-01');
    const res = [['clean manifest passes', ok.findings.length === 0], ['100000 of 182754 flagged', has(t, /100000 of 182754/)], ['host with no service flagged', has(h, /stray\.example\.test/)],
      ['1 of 40 members flagged', has(p, /recorded 1 of 40/)], ['not-needed page without reason flagged', has(n, /no reason/)], ['stale docs is a warning, not a finding', old.findings.length === 0 && old.warnings.length === 1]];
    for (const [k, v] of res) console.log((v ? 'ok   ' : 'FAIL ') + k);
    const pass = res.every(([, v]) => v);
    console.log(pass ? 'service-docs selftest PASS' : 'service-docs selftest FAIL');
    process.exit(pass ? 0 : 1);
  }
  const mp = a.find((x) => !x.startsWith('--')), ri = a.indexOf('--root');
  if (!mp) { console.error('usage: service-docs.mjs <manifest.json> [--root dir] | --self-test'); process.exit(2); }
  process.exit(run(mp, ri >= 0 ? a[ri + 1] : process.cwd()));
}
