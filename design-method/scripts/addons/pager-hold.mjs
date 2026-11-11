// Add-on `pager-hold` (config `addons: ["pager-hold"]`, check `periodHold`; CR-73 "same gesture, same physics"). Held-finger captures per period strip:
// `<layout>-<strip>-hold-rest`, `-hold-short`, `-hold-long` (dump while the finger is DOWN at the same travel, as a fraction of the strip's own page pitch).
// Config `periodHold`: { short: 0.25, long: 0.6 (fractions of the pitch, one below and one above the commit point), gapDp: 8 (pitch = current page width + gap),
//   spread: 0.05 (strips of one group agree within this ratio), resistMax: 0.85 (below the commit point a page is drawn at most this fraction of the finger travel), minMoveDp: 4 }.
// (a) same ratio displacement / finger travel across strips (same hold, same position class: `real` = a neighbour exists, `end` = rubber band), within `spread` of the group median;
// (b) the short hold is resisted (ratio <= resistMax): a 1:1 page lost the resistance; (c) a hold that moved no page, or moved it against the finger, is a capture row (vacuity guard).
// (d) optional `curve` { free, resisted, threshold, rubber, slopDp: 8, tolDp: 6 } (the app's own constants, copied by the project config or read by its wrapper, never typed twice): the page displacement must equal the REFERENCE curve at the finger travel
//   (finger - slopDp: the drag starts after the touch slop; real position: below threshold shown = free + resisted*sin((x-free)/(1-free)*pi/2) capped at x, from the threshold on shown = x; end position: rubber band first, then the same curve (wave 9 G3); rubber band x*L/(x+L) with L = rubber)
//   within tolDp (px rounding on two edges + slop uncertainty x the curve slope + the gutter read from a tag width). A page drawn weaker OR stronger than the reference flags; a lost resistance is not hidden by a ratio threshold.
// The finger always moves towards the PREVIOUS page (displacement positive). Roles used: periodViewport, periodPagePrefix + 'cur' / 'prev' / 'next'.
import path from 'node:path';
export default {
  name: 'pager-hold',
  checks: { periodHold: [] },
  roles: { periodHold: ['periodViewport', 'periodPagePrefix'] },
  finish(c, X) {
    if (!c.on('periodHold')) return;
    const { T, r1 } = c, O = { short: 0.25, long: 0.6, gapDp: 8, spread: 0.05, resistMax: 0.85, minMoveDp: 4, ...(c.cfg.periodHold || {}) };
    const HOLD = { short: O.short, long: O.long }, pg = (k) => T.periodPagePrefix + k, groups = new Map();
    const re = new RegExp('^(.*)' + X.NM.holdMarker + '(short|long)' + X.EXT.replace('.', '\\.') + '$');
    for (const f of X.allFiles) {
      const m = re.exec(f); if (!m || !X.allFiles.includes(m[1] + X.NM.holdMarker + 'rest' + X.EXT)) continue;
      const A = X.parse(path.join(X.dir, m[1] + X.NM.holdMarker + 'rest' + X.EXT)), B = X.parse(path.join(X.dir, f)), name = f.slice(0, -X.EXT.length), layout = name.split(X.NM.layoutSeparator)[0];
      X.markSeen(A); X.markSeen(B);
      const push = (rule, el, v, e) => X.push(rule, el, v, e, name, layout);
      let best = null;
      for (const vp of A.filter((x) => x.id === T.periodViewport)) {
        const inV = (x, v) => x.l >= v.l - 1 && x.r <= v.r + 1 && x.t >= v.t - 1 && x.b <= v.b + 1;
        const a = A.filter((x) => x.id === pg('cur') && inV(x, vp)).sort((p, q) => p.t - q.t)[0]; if (!a) continue;
        const vpB = B.filter((x) => x.id === T.periodViewport).sort((p, q) => Math.abs(p.t - vp.t) + Math.abs(p.l - vp.l) - Math.abs(q.t - vp.t) - Math.abs(q.l - vp.l))[0];
        const b = vpB && B.filter((x) => x.id === pg('cur') && inV(x, vpB)).sort((p, q) => Math.abs(p.t - a.t) - Math.abs(q.t - a.t))[0]; if (!b) continue;
        const rClip = b.r >= vpB.r - 0.5 && a.r < vp.r - 0.5, lClip = b.l <= vpB.l + 0.5 && a.l > vp.l + 0.5, dx = lClip && !rClip ? b.r - a.r : b.l - a.l;   // a dump clips the edge at the viewport: measure the other edge
        const has = (id) => A.some((x) => x.id === id && inV(x, vp) && x.r - x.l > 0), prev = has(pg('prev')), next = has(pg('next')), dir = next && !prev ? -1 : 1;
        if (!best || Math.abs(dx) > Math.abs(best.dx)) best = { dx, pitch: a.r - a.l + O.gapDp, dir, cls: prev || next ? 'real' : 'end' };
      }
      if (!best || Math.abs(best.dx) < O.minMoveDp) { push('capture: a held finger moved no period page by ' + O.minMoveDp + ' dp or more (CR-73)', m[1], best ? Math.abs(best.dx) : 0, O.minMoveDp); continue; }
      if (Math.sign(best.dx) !== best.dir) { push('capture: the page moved against the finger direction (CR-73)', m[1], best.dx, best.dir); continue; }
      const travel = HOLD[m[2]] * best.pitch, ratio = Math.abs(best.dx) / travel, key = m[2] + '|' + best.cls;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push({ strip: m[1], ratio, travel, dx: Math.abs(best.dx), push });
      if (O.curve) { const K = { slopDp: 8, tolDp: 6, ...O.curve }, x = Math.max(0, HOLD[m[2]] - K.slopDp / best.pitch), xe = best.cls === 'end' ? x * K.rubber / (x + K.rubber) : x, shown = xe < K.threshold ? (xe <= K.free ? xe : Math.min(K.free + K.resisted * Math.sin((xe - K.free) / (1 - K.free) * Math.PI / 2), xe)) : xe, expDp = shown * best.pitch;
        if (Math.abs(Math.abs(best.dx) - expDp) > K.tolDp) push(`motion: the page does not follow the reference curve at this finger travel (CR-73, ${m[2]} hold, ${best.cls}; page ${Math.round(Math.abs(best.dx) / best.pitch * 100) / 100} of a page, reference ${Math.round(shown * 100) / 100})`, m[1] + ' ' + best.cls, Math.abs(best.dx), expDp); }
      if (m[2] === 'short' && ratio > O.resistMax) push(`motion: a page below the commit point follows the finger without resistance (CR-73, ratio ${Math.round(ratio * 100) / 100} of the finger, max ${O.resistMax})`, m[1] + ' ' + best.cls, Math.abs(best.dx), O.resistMax * travel);
    }
    for (const [key, g] of groups) {
      if (g.length < 2) continue;
      const med = g.map((x) => x.ratio).sort((p, q) => p - q)[Math.floor((g.length - 1) / 2)];
      for (const x of g) if (Math.abs(x.ratio - med) > O.spread) x.push(`motion: the same hold draws this strip differently from the others (CR-73, ${key.replace('|', ' hold, ')} position, ratio ${Math.round(x.ratio * 100) / 100} vs median ${Math.round(med * 100) / 100})`, x.strip, x.dx, med * x.travel);
    }
  },
};
