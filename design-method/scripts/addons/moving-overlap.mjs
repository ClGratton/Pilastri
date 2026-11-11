// Add-on `moving-overlap` (config `addons: ["moving-overlap"]`, check `movingOverlap`; CR-79 "a moving element never passes over or under a fixed control").
// On every rest/moving pair: a fixed control = a small (<= maxControlDp on both sides) clickable node with no text of its own that has the same bounds in the rest dump;
// a moving element = a visible text with no node of the same id, text and place in the rest dump. Overlap > 1 dp in both axes flags one motion row; a text wholly inside the
// control (its own label) or a control wholly inside the text (an inline icon) is one control seen twice and is not flagged.
// Source: user critique "title passes under the back button while dragging" (wave 9 design review H1). Config `movingOverlap`: { maxControlDp: 56 }.
export default {
  name: 'moving-overlap',
  checks: { movingOverlap: [] },
  roles: { movingOverlap: [] },
  pair(c, A, B, name, push) {
    if (!c.on('movingOverlap')) return;
    const max = (c.cfg.movingOverlap || {}).maxControlDp || 56, same = (x, n) => Math.abs(x.l - n.l) < 1 && Math.abs(x.t - n.t) < 1 && Math.abs(x.r - n.r) < 1 && Math.abs(x.b - n.b) < 1;
    for (const k of B.filter((n) => n.clickable && !n.vis && n.r - n.l <= max && n.b - n.t <= max && A.some((x) => x.id === n.id && x.clickable && same(x, n))))
      for (const t of B.filter((n) => n.vis && n.r - n.l > 0 && !A.some((x) => x.vis && x.id === n.id && x.text === n.text && Math.abs(x.l - n.l) < 1 && Math.abs(x.t - n.t) < 1))) {
        const ix = Math.min(t.r, k.r) - Math.max(t.l, k.l), iy = Math.min(t.b, k.b) - Math.max(t.t, k.t);
        if (ix <= 1 || iy <= 1) continue;
        if (t.l >= k.l - 1 && t.r <= k.r + 1 && t.t >= k.t - 1 && t.b <= k.b + 1) continue;
        push('motion: a moving element overlaps a fixed control (CR-79)', (t.text || '').slice(0, 20) + ' / ' + (k.text || 'control').slice(0, 20), ix, 0);
      }
  },
};
