// Debug-only fake provider + bank for the emulator proof of the bank return (wave bank-browser-return).
// Serves what EbApi asks (aspsps, auth, sessions), the fake bank pages, and the site's return page byte-for-byte from site/pilastri/.
// Usage: node tools/fake-bank-provider.mjs [port] ; control: GET /ctl?mode=stay|wait ; counters: GET /stats ; log lines on stdout.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PORT = Number(process.argv[2] || 8787), ORIGIN = `http://10.0.2.2:${PORT}`;
const auths = new Map(); let n = 0, mode = 'stay'; const stats = { auth: 0, sessions: 0, returnPage: 0, approve: 0 };
const log = (s) => console.log(new Date().toISOString().slice(11, 23) + ' ' + s);
const send = (res, code, body, type = 'application/json', extra = {}) => { res.writeHead(code, { 'content-type': type, ...extra }); res.end(body); };
http.createServer((req, res) => {
  const u = new URL(req.url, ORIGIN); let body = '';
  req.on('data', (c) => body += c);
  req.on('end', () => {
    log(`${req.method} ${u.pathname}${u.search.replace(/(code|state)=[^&]*/g, '$1=#')}`);
    if (u.pathname === '/ctl') { mode = u.searchParams.get('mode') || mode; return send(res, 200, JSON.stringify({ mode })); }
    if (u.pathname === '/stats') return send(res, 200, JSON.stringify({ ...stats, mode }));
    if (u.pathname === '/aspsps') return send(res, 200, JSON.stringify({ aspsps: [{ name: 'Fake Bank', country: 'IT', psu_types: ['personal'], auth_methods: [], required_psu_headers: [], maximum_consent_validity: 7776000 }] }));
    if (u.pathname === '/auth' && req.method === 'POST') {
      const b = JSON.parse(body || '{}'); const id = 'a' + (++n); stats.auth++;
      auths.set(id, { state: b.state, redirect: b.redirect_url, code: 'code' + n });
      return send(res, 200, JSON.stringify({ url: `${ORIGIN}/bank/auth?id=${id}`, authorization_id: id }));
    }
    if (u.pathname === '/bank/auth') {
      const id = u.searchParams.get('id');
      return send(res, 200, `<!doctype html><meta name=viewport content="width=device-width"><h1>Fake Bank (browser)</h1><p>Approva.</p>` +
        (mode === 'stay' ? `<script>setTimeout(function(){location.href='/bank/approve?id=${id}'},2500)</script>` : ''), 'text/html');
    }
    if (u.pathname === '/bank/approve') {
      const a = auths.get(u.searchParams.get('id')); if (!a) return send(res, 404, 'unknown');
      stats.approve++;
      const r = new URL(a.redirect); r.searchParams.set('code', a.code); r.searchParams.set('state', a.state);
      return send(res, 302, '', 'text/plain', { location: `${ORIGIN}${r.pathname}${r.search}` }); // the site's origin is swapped for this server: the local copy of the site
    }
    if (u.pathname === '/ritorno-banca.html') { stats.returnPage++; return send(res, 200, fs.readFileSync(path.join(root, 'site/pilastri/ritorno-banca.html')), 'text/html; charset=utf-8'); }
    if (u.pathname === '/ritorno-banca-manual.html') { // same page with ONLY the automatic hand-back removed (tests the button with the query kept)
      const h = fs.readFileSync(path.join(root, 'site/pilastri/ritorno-banca.html'), 'utf8').replace(/\r?\n  go\(\);/, ''); return send(res, 200, h, 'text/html; charset=utf-8'); }
    if (u.pathname === '/sessions' && req.method === 'POST') {
      const code = JSON.parse(body || '{}').code; if (![...auths.values()].some((a) => a.code === code)) return send(res, 401, '{"error":"bad code"}');
      stats.sessions++;
      return send(res, 200, JSON.stringify({ session_id: 'sess' + (++n), accounts: [{ uid: 'acc-1', identification_hash: 'h1', name: 'Conto', currency: 'EUR' }], access: { valid_until: '2027-02-01T00:00:00Z' }, aspsp: { name: 'Fake Bank', country: 'IT' } }));
    }
    send(res, 404, '{"error":"not found"}');
  });
}).listen(PORT, '0.0.0.0', () => log('fake provider on ' + PORT));
