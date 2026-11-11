// Add-on `wait-fallback` (config block `waitFallback`; SKILL section 2, K19 / critique rule CR-87). User 2026-11-10 (sign-in-link wait with a countdown): "you should have a paste link button before end of countdown".
// Whatever the app offers when an automatic step times out or fails (manual entry, retry, skip) is reachable from the first frame of the wait as a secondary action.
// Config: [{ screen: <glob on the dump name>, wait: <regex on resource-id/testTag>, fallback: <regex on resource-id/testTag> }]. In each rest dump whose name matches `screen` and that has a node matching `wait`,
// a node matching `fallback` must exist, be enabled and clickable, and have non-empty bounds inside the screen. A config entry whose screen glob matches no dump is a warning (unmatched), never a pass.
//   node wait-fallback.mjs --selftest
import { fileURLToPath } from 'node:url';

const globRe = (g) => new RegExp('^' + g.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$');
export const matchesScreen = (entry, name) => globRe(entry.screen).test(name);

/** Rows for one dump (nodes in dp, W/H optional) against one config entry; [] when the dump has no wait node. */
export function waitFallback(dump, entry) {
  const w = new RegExp(entry.wait), f = new RegExp(entry.fallback), nodes = dump.nodes || [];
  if (!nodes.some((n) => w.test(n.id || ''))) return [];
  const inside = (n) => n.r > n.l && n.b > n.t && n.r > 0 && n.b > 0 && (!dump.W || n.l < dump.W) && (!dump.H || n.t < dump.H);
  const cands = nodes.filter((n) => f.test(n.id || ''));
  if (!cands.length) return [{ rule: 'wait-no-fallback', el: entry.fallback, value: 0, expected: 1, note: 'the wait screen offers no fallback action from its first frame (a wait is never a trap)' }];
  if (cands.some((n) => n.enabled !== false && n.clickable && inside(n))) return [];
  const n = cands[0];
  return [{ rule: 'wait-no-fallback', el: entry.fallback, value: 0, expected: 1, note: `fallback present but ${n.enabled === false ? 'disabled' : !n.clickable ? 'not clickable' : 'without visible bounds'}` }];
}

export function fixtures() {
  const node = (id, l, t, r, b, extra = {}) => ({ id, text: '', l, t, r, b, enabled: true, clickable: false, ...extra });
  const base = [node('eb_link_wait', 16, 300, 400, 340)];
  return {
    entry: { screen: '*eb*', wait: '^eb_link_wait$', fallback: '^eb_link_paste_open$' },
    missing: { W: 412, H: 900, nodes: base },
    disabled: { W: 412, H: 900, nodes: [...base, node('eb_link_paste_open', 16, 700, 396, 748, { clickable: true, enabled: false })] },
    ok: { W: 412, H: 900, nodes: [...base, node('eb_link_paste_open', 16, 700, 396, 748, { clickable: true })] },
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv.includes('--selftest')) {
  const f = fixtures(), a = waitFallback(f.missing, f.entry), b = waitFallback(f.disabled, f.entry), c = waitFallback(f.ok, f.entry);
  const pass = a.length === 1 && b.length === 1 && c.length === 0 && matchesScreen(f.entry, 'fold-setup-eb-1') && !matchesScreen(f.entry, 'fold-mese');
  console.log(pass ? 'wait-fallback selftest PASS (missing fallback flagged, disabled fallback flagged, clickable fallback clean)' : 'wait-fallback selftest FAIL');
  process.exit(pass ? 0 : 1);
}
