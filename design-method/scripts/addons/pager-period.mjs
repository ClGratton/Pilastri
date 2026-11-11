// Add-on `pager-period` (config `addons: ["pager-period"]`, checks `period`, `periodMotion`). For a page that swipes between periods.
// period: a pane with a page title and period viewports exposes a `periodTitle` node, the same height on every period page, and no other header text is taller.
// periodMotion: every period label moves by the same dx as the page that contains it (rest + moving dumps); a moving dump identical to its rest dump is a deviation (vacuity guard).
// periodMotion also covers the whole period header (config `periodHeader`, see RULES.md): subtitle in the title's header, chips, period subtitles move like the current page.
// The generic form of the motion rule is the base `coMotion`.
export default {
  name: 'pager-period',
  checks: { period: [], periodMotion: [] },
  roles: { period: ['periodViewport', 'periodTitle'], periodMotion: ['periodPagePrefix', 'periodLabelPrefix'] },
  screen(c, s) {
    if (!c.on('period')) return;
    const { T, TOL, is, add, DS, rid, label } = c, cut = (n) => (s.wide ? ((n.l + n.r) / 2 < s.W / 2 ? 'L' : 'R') : 'P');
    const idp = c.cfg.ruleIds?.period ? ', ' + c.cfg.ruleIds.period : '';
    for (const P of s.wide ? ['L', 'R'] : ['P']) {
      const inP = (n) => cut(n) === P, titles = s.nodes.filter((n) => n.id === T.pageTitle && inP(n));
      if (!titles.length || !s.nodes.some((n) => n.id === T.periodViewport && inP(n)) || s.nodes.some((n) => n.id === T.periodLabelCur && inP(n))) continue;
      const pts = s.nodes.filter((n) => n.id === T.periodTitle && inP(n));
      if (!pts.length) { DS(s, `period: swipe page exposes its current period in a \`${T.periodTitle}\` node${rid('period')}`, '', 0, 1, `request tag ${T.periodTitle}`); continue; }
      for (const n of pts) add(s, `${T.periodTitle} height (same on every period page${idp})`, n, n.b - n.t, 'any');
      const pt = Math.max(...pts.map((n) => n.b - n.t));
      for (const n of s.nodes.filter((x) => is(x, 'pageTitle', 'pageSubtitle', 'pageTrailing', 'chip') && inP(x) && !(is(x, 'pageTrailing') && !x.vis) && !pts.some((p) => Math.abs(p.l - x.l) < 1 && Math.abs(p.t - x.t) < 1 && Math.abs(p.b - x.b) < 1)))
        if (c.perLine(s, n) > pt + TOL) DS(s, `period: ${T.periodTitle} is the tallest text of the page header${rid('period')}`, label(n), pt, c.perLine(s, n), 'header text taller');   // per line above the wrap scale (wrapRoles), box height otherwise
    }
  },
  pair(c, A, B, name, push) {
    if (!c.on('periodMotion')) return;
    const { T, r1, hasPrefix, label, TOL } = c, rid = c.rid('periodMotion'), idm = c.cfg.ruleIds?.periodMotion ? ', ' + c.cfg.ruleIds.periodMotion : '';
    const near = (n, list, ok) => list.filter(ok).sort((a, b) => Math.abs(a.l - n.l) + Math.abs(a.t - n.t) - Math.abs(b.l - n.l) - Math.abs(b.t - n.t))[0];
    const key = (n) => [n.id, r1(n.l), r1(n.t), r1(n.r), r1(n.b)].join('|');
    if (A.length === B.length && A.every((n, i) => key(n) === key(B[i]))) push(`motion: moving dump identical to its rest dump (vacuity guard${idm})`, '-', 0, 1);
    const pages = A.filter((x) => hasPrefix(x, 'periodPagePrefix')), labs = A.filter((x) => hasPrefix(x, 'periodLabelPrefix'));
    if (labs.length && !pages.length) push(`motion: period pages are not tagged${rid}`, `${T.periodPagePrefix}*`, 0, 1);
    for (const l of labs) {
      const lcx = (l.l + l.r) / 2;   // each label is paired with the page that CONTAINS it: its horizontal extent holds the label centre, nearest below it
      const p0 = pages.filter((x) => lcx >= x.l - 1 && lcx <= x.r + 1 && x.t >= l.b - 1).sort((a, b) => a.t - b.t)[0];
      if (!p0) { push(`motion: period label is inside no period page${rid}`, l.id + ' ' + label(l).slice(0, 14), lcx, 0); continue; }
      const same = (n) => (x) => x.id === n.id && Math.abs(x.t - n.t) < 1;   // same tag, same row
      const p1 = near(p0, B, same(p0)), l1 = near(l, B, same(l)); if (!p1 || !l1) continue;
      if (!(Math.abs((l1.l - l.l) - (p1.l - p0.l)) <= TOL || Math.abs((l1.r - l.r) - (p1.r - p0.r)) <= TOL)) push(`motion: period label moves like its page${rid}`, l.id + ' ' + label(l).slice(0, 14), l1.l - l.l, p1.l - p0.l);
    }
    // M1 extension: a period title on a pane with a period viewport belongs to that pane's CURRENT page (tag `periodPageCur`, default periodPagePrefix + "cur") and is displaced like it,
    // same direction, +-TOL. The page is measured on the edge that does not touch its clip box (the viewport): bounds are clipped there, so the touching edge understates the move.
    if (T.periodTitle && T.periodViewport) {
      const curId = T.periodPageCur || (T.periodPagePrefix ? T.periodPagePrefix + 'cur' : null);
      for (const t of curId ? A.filter((x) => x.id === T.periodTitle && x.r - x.l > 0) : []) {
        const tcx = (t.l + t.r) / 2, vp = A.filter((x) => x.id === T.periodViewport && tcx >= x.l - 1 && tcx <= x.r + 1)[0]; if (!vp) continue;
        const p0 = A.filter((x) => x.id === curId && x.l >= vp.l - 1 && x.r <= vp.r + 1 && x.t >= t.b - 1).sort((a, b) => a.t - b.t)[0]; if (!p0) continue;
        const same = (n) => (x) => x.id === n.id, p1 = near(p0, B, same(p0)), t1 = near(t, B.filter((x) => x.r - x.l > 0), same(t));
        if (!t1) { push(`capture: ${T.periodTitle} absent or zero-width in the moving dump while its rest dump has it${rid}`, t.id + ' ' + label(t).slice(0, 14), 0, t.r - t.l); continue; }   // never a silent pass
        if (!p1) continue;
        const clipL = p1.l <= vp.l + 0.5 || p0.l <= vp.l + 0.5, clipR = p1.r >= vp.r - 0.5 || p0.r >= vp.r - 0.5; if (clipL && clipR) continue;   // both edges clipped: not measurable
        const pdx = clipL ? p1.r - p0.r : p1.l - p0.l;
        if (!(Math.abs((t1.l - t.l) - pdx) <= TOL || Math.abs((t1.r - t.r) - pdx) <= TOL)) push(`motion: period title moves like its page${rid}`, t.id + ' ' + label(t).slice(0, 14), t1.l - t.l, pdx);
      }
      // Whole period header (wave 8): what describes the period belongs to the swiped page too. Config `periodHeader`: { header: role of the header node (default pageHeader), headerMembers: roles
      // that count only when their bounds lie inside the header node that contains the title (default [pageSubtitle]), members: roles that always count (default [periodChip, periodSubtitle]) }.
      // A role without a tag is skipped. Each member present at rest is displaced like the pane's current page (same dx, +-TOL, edge not touching the clip box); absent or zero-width when moving
      // is an explicit capture row.
      const ph = c.cfg.periodHeader || {}, hdTag = T[ph.header || 'pageHeader'], inTags = (ph.headerMembers || ['pageSubtitle']).map((k) => T[k]).filter(Boolean), allTags = (ph.members || ['periodChip', 'periodSubtitle']).map((k) => T[k]).filter(Boolean);
      for (const t of curId ? A.filter((x) => x.id === T.periodTitle && x.r - x.l > 0) : []) {
        const tcx = (t.l + t.r) / 2, vp = A.filter((x) => x.id === T.periodViewport && tcx >= x.l - 1 && tcx <= x.r + 1)[0]; if (!vp) continue;
        const hd = hdTag ? A.filter((x) => x.id === hdTag && x.l <= t.l + 1 && x.r >= t.r - 1 && x.t <= t.t + 1 && x.b >= t.b - 1).sort((a, b) => (a.r - a.l) * (a.b - a.t) - (b.r - b.l) * (b.b - b.t))[0] : null;
        const mem = A.filter((x) => x !== t && x.r - x.l > 0 && x.l < vp.r && x.r > vp.l && ((hd && inTags.includes(x.id) && x.l >= hd.l - 1 && x.r <= hd.r + 1 && x.t >= hd.t - 1 && x.b <= hd.b + 1) || allTags.includes(x.id)));
        for (const m of mem) {
          const mcx = (m.l + m.r) / 2; if (mcx < vp.l - 1 || mcx > vp.r + 1) continue;
          const p0 = A.filter((x) => x.id === curId && x.l >= vp.l - 1 && x.r <= vp.r + 1 && x.t >= m.b - 1).sort((a, b) => a.t - b.t)[0]; if (!p0) continue;
          const p1 = near(p0, B, (x) => x.id === curId), m1 = near(m, B.filter((x) => x.r - x.l > 0), (x) => x.id === m.id && Math.abs(x.t - m.t) < 1);   // same tag, same row
          if (!m1) { push(`capture: period header member ${m.id} absent or zero-width in the moving dump while its rest dump has it${rid}`, m.id + ' ' + label(m).slice(0, 14), 0, m.r - m.l); continue; }
          if (!p1) continue;
          const clipL = p1.l <= vp.l + 0.5 || p0.l <= vp.l + 0.5, clipR = p1.r >= vp.r - 0.5 || p0.r >= vp.r - 0.5; if (clipL && clipR) continue;
          const pdx = clipL ? p1.r - p0.r : p1.l - p0.l;
          if (!(Math.abs((m1.l - m.l) - pdx) <= TOL || Math.abs((m1.r - m.r) - pdx) <= TOL)) push(`motion: period header moves like its page${rid}`, m.id + ' ' + label(m).slice(0, 14), Math.abs(m1.l - m.l) <= Math.abs(m1.r - m.r) ? m1.l - m.l : m1.r - m.r, pdx);
        }
      }
    }
  },
};
