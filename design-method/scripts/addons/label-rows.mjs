// Add-on `label-rows` (config `addons: ["label-rows"]`, check `labelRows`; CR-80 "columns of one block put their labels on one line").
// Config `labelRows`: { a: role of the first figure (primary), b: role of the second figure, label: role of the label under a figure, reachDp: 80, alignDp: 24 }.
// For every pair (a, b) whose vertical extents overlap (one row of columns), the label directly under each figure (same left edge within alignDp, within reachDp below it) must start at
// the same y (+-1 dp). Source: user/review remark "the hero columns are misaligned" (wave 9 design review M3).
export default {
  name: 'label-rows',
  checks: { labelRows: [] },
  roles: { labelRows: [] },
  screen(c, s) {
    if (!c.on('labelRows')) return;
    const f = c.cfg.labelRows || {}, reach = f.reachDp || 80, al = f.alignDp || 24, id = (r) => c.T[r] || r;
    const under = (g) => s.nodes.filter((n) => n.id === id(f.label) && n.t >= g.b - 2 && n.t < g.b + reach && Math.abs(n.l - g.l) < al).sort((x, y) => x.t - y.t)[0];
    for (const A of s.nodes.filter((n) => n.id === id(f.a))) for (const B of s.nodes.filter((n) => n.id === id(f.b) && n.t < A.b && n.b > A.t)) {
      const la = under(A), lb = under(B); if (!la || !lb) continue;
      if (Math.abs(la.t - lb.t) > 1) c.DS(s, 'align: the columns of one block put their labels on one line (CR-80)', c.label(la) + ' / ' + c.label(lb), Math.abs(la.t - lb.t), 0);
    }
  },
};
