// Saves the full HTML of every page shown in the app's WebViews (provider / bank / Enable Banking pages) while the user walks a flow,
// so a simpler native interface can later be built on top of those pages.
// Read-only: it attaches to WebView DevTools (enabled in debug/fast builds) through adb forward; it never clicks, types or navigates.
// Privacy: files stay under output/ (git-ignored). Form field values are stripped in the page before saving (value attributes and
// live values of input/textarea/select, password fields always); URLs are saved without query string or fragment.
// Usage: PHONE=<serial> node tools/webview-html-log.mjs [minutes] [outDir]
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

const ADB = process.env.ADB || 'F:/Android/Sdk/platform-tools/adb.exe'
const PHONE = process.env.PHONE
if (!PHONE) { console.error('set PHONE'); process.exit(2) }
const PKG = 'xyz.grtlabs.pilastri'
const minutes = Number(process.argv[2] || 120)
const out = process.argv[3] || `output/webview-html-${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`
fs.mkdirSync(out, { recursive: true })
const PORT = 9333
const end = Date.now() + minutes * 60e3
const adb = (...a) => { try { return execFileSync(ADB, ['-s', PHONE, ...a], { encoding: 'utf8', timeout: 15000 }) } catch { return '' } }
const log = (m) => { const l = `${new Date().toTimeString().slice(0, 8)} ${m}`; console.log(l); fs.appendFileSync(path.join(out, 'index.txt'), l + '\n') }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// Runs inside the page: clone, strip values, return HTML plus a clean location.
const SNAP = `(() => {
  const d = document.documentElement.cloneNode(true);
  const live = document.querySelectorAll('input,textarea,select');
  d.querySelectorAll('input,textarea,select').forEach((e) => { e.removeAttribute('value'); if (e.tagName === 'TEXTAREA') e.textContent = ''; });
  d.querySelectorAll('option[selected]').forEach((o) => o.removeAttribute('selected'));
  const fields = Array.from(live).map((e) => ({ tag: e.tagName.toLowerCase(), type: e.type || '', name: e.name || '', id: e.id || '', filled: !!(e.value && e.value.length) }));
  return JSON.stringify({ loc: location.origin + location.pathname, title: document.title, html: '<!doctype html>\\n' + d.outerHTML, fields });
})()`

let forwardedPid = null
const seen = new Map() // targetId -> ws
const lastHash = new Map()

async function attach(t) {
  if (seen.has(t.id) || !t.webSocketDebuggerUrl) return
  const ws = new WebSocket(t.webSocketDebuggerUrl.replace(/^ws:\/\/[^/]+/, `ws://127.0.0.1:${PORT}`))
  seen.set(t.id, ws)
  let id = 0
  const send = (method, params = {}) => ws.send(JSON.stringify({ id: ++id, method, params }))
  const snap = () => send('Runtime.evaluate', { expression: SNAP, returnByValue: true })
  ws.onopen = () => { log(`attached ${t.id}`); send('Page.enable'); send('Runtime.enable'); snap() }
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    if (m.method === 'Page.loadEventFired' || m.method === 'Page.frameNavigated' || m.method === 'Page.domContentEventFired') setTimeout(snap, 400)
    const v = m.result && m.result.result && m.result.result.value
    if (typeof v === 'string') {
      const s = JSON.parse(v)
      const h = crypto.createHash('sha1').update(s.html).digest('hex').slice(0, 10)
      if (lastHash.get(t.id) === h) return
      lastHash.set(t.id, h)
      const name = `${new Date().toTimeString().slice(0, 8).replace(/:/g, '')}-${s.loc.replace(/^https?:\/\//, '').replace(/[^a-zA-Z0-9.-]+/g, '_').slice(0, 80)}-${h}`
      fs.writeFileSync(path.join(out, name + '.html'), s.html)
      fs.writeFileSync(path.join(out, name + '.fields.json'), JSON.stringify({ loc: s.loc, title: s.title, fields: s.fields }, null, 1))
      log(`saved ${name} (${s.html.length} chars, ${s.fields.length} fields) ${s.loc}`)
    }
  }
  ws.onclose = () => { seen.delete(t.id); log(`detached ${t.id}`) }
  ws.onerror = () => {}
  // also re-snapshot periodically: single-page flows change the DOM without navigation
  const iv = setInterval(() => { if (ws.readyState === 1) snap(); else clearInterval(iv) }, 3000)
}

log(`start, out=${out}, ${minutes} min`)
while (Date.now() < end) {
  const pid = adb('shell', 'pidof', PKG).trim()
  if (pid) {
    const unix = adb('shell', 'cat', '/proc/net/unix')
    const sock = (unix.match(new RegExp(`webview_devtools_remote_${pid}\\b`)) || [])[0]
    if (sock && forwardedPid !== pid) {
      execFileSync(ADB, ['-s', PHONE, 'forward', `tcp:${PORT}`, `localabstract:${sock}`])
      forwardedPid = pid; log(`forward ${sock}`)
    }
    if (forwardedPid === pid) {
      try {
        const r = await fetch(`http://127.0.0.1:${PORT}/json/list`)
        for (const t of await r.json()) if (t.type === 'page') await attach(t)
      } catch { /* no webview right now */ }
    }
  }
  await sleep(2000)
}
log('end')
process.exit(0)
