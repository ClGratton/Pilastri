// Add-on `hierarchy-opinions` (config `addons: ["hierarchy-opinions"]`, checks `primaryFigure`, `accent`, `figureScale`, `attention`). One design language's board rules, in their
// board-specific form: an accent card per PANE on the screens `hierarchy.accentScreens` (both panes on `hierarchy.accentBothPanes`), the primary figure inside it and in the upper
// `tokens.figureTopRatio` of the screen, line-box ratios primary vs secondary / label / page title (relaxed at a large font scale), a fixed-height attention row with no button inside.
// The base has the generic mechanisms for the same criteria (`singleton`, `heightRatio`, `heightEquals`, `forbidInside`), configured by tag; use those when the board is simpler.
export default {
  name: 'hierarchy-opinions',
  checks: { primaryFigure: ['figureTopRatio'], accent: [], figureScale: ['ratioSecondary', 'ratioLabel', 'ratioSecondaryName', 'ratioLabelName'], attention: ['attentionH'] },
  roles: { primaryFigure: ['primaryFigure'], accent: ['accentCard', 'primaryFigure'], figureScale: ['primaryFigure'], attention: ['attentionRow'] },
  screen(c, s, h) {
    const { T, TK, TOL, H: HU, inside, D, rid } = c, by = h.by, W = s.W, Hh = s.H, half = W / 2, hh = (n) => n.b - n.t;
    const HC = c.cfg.hierarchy || {}, ACCENT = HC.accentScreens || [], pf = by(T.primaryFigure), ac = by(T.accentCard);
    if (s.top && c.on('primaryFigure')) {
      const nPane = s.wide ? Math.max(pf.filter((n) => (n.l + n.r) / 2 < half).length, pf.filter((n) => (n.l + n.r) / 2 >= half).length) : pf.length;
      if (nPane > 1 || (!pf.length && ACCENT.includes(s.base))) D(s, `figure: one ${T.primaryFigure} per pane on the board screens, at most one elsewhere${rid('primaryFigure')}`, null, pf.length, 1, pf.length ? '' : `request tag ${T.primaryFigure}`);
      else if (pf.length && Math.min(...pf.map((n) => n.t)) > Hh * TK.figureTopRatio) D(s, `figure: ${T.primaryFigure} top within ${Math.round(TK.figureTopRatio * 100)} % of height${rid('primaryFigure')}`, pf[0], Math.min(...pf.map((n) => n.t)), Hh * TK.figureTopRatio);
    }
    if (c.on('accent')) {
      // Per PANE: a wide layout draws one accent card per pane where a board draws one. Required only on accentScreens: the screen as a whole; on screens listed in
      // hierarchy.accentBothPanes both panes. A non-top wide capture with two page-contents still has two panes.
      const twoPanes = s.wide && (s.top || (by(T.pageContent).some((n) => (n.l + n.r) / 2 < half) && by(T.pageContent).some((n) => (n.l + n.r) / 2 >= half)));
      const panes = twoPanes ? [[0, half], [half, W + 1]] : [[0, W + 1]];
      panes.forEach(([lo, hi], i) => {
        const inP = (n) => (n.l + n.r) / 2 >= lo && (n.l + n.r) / 2 < hi;
        const pP = pf.filter(inP), aP = ac.filter(inP), lab = panes.length > 1 ? (i === 0 ? ' (left pane)' : ' (right pane)') : '', R = rid('accent');
        if (aP.length > 1 || pP.length > 1) D(s, `accent: at most one ${T.accentCard} and ${T.primaryFigure} per pane${R}` + lab, null, aP.length, 1);
        else if (pP.length === 1 && !aP.length) D(s, `accent: a ${T.primaryFigure} sits inside an ${T.accentCard}${R}` + lab, pP[0], 0, 1);
        else if (aP.length === 1 && !pP.length) D(s, `accent: ${T.accentCard} without a primary figure${R}` + lab, null, 1, 0);
        else if (aP.length === 1 && !inside(pP[0], aP[0])) D(s, `accent: the ${T.primaryFigure} sits inside the ${T.accentCard}${R}` + lab, pP[0], pP[0].t, aP[0].t);
        const need = s.top && ACCENT.includes(s.base) && (panes.length === 1 || (HC.accentBothPanes || []).includes(s.base) || i === 0);
        if (need && !aP.length && !(panes.length === 1 && ac.length)) D(s, `accent: exactly one ${T.accentCard} on a screen the board draws one on${R}` + lab, null, 0, 1);
      });
    }
    if (c.on('figureScale') && pf.length >= 1) {
      // A dump has no font size: line boxes are compared, so the thresholds are line-box ratios. At a system font scale that compresses sizes nonlinearly, only the order is asserted.
      const R = rid('figureScale'), pfFull = pf.filter((p) => { const cd = ac.find((a) => inside(p, a)); return !cd || p.t - cd.t >= HU.primaryTopTol; });   // a primary at the very top of its card is cut by the scroll viewport
      const pfFor = (n) => pfFull.find((p) => !s.wide || ((p.l + p.r) / 2 < half) === ((n.l + n.r) / 2 < half));
      const rSec = s.fs ? 1.0 : TK.ratioSecondary, rLab = s.fs ? 1.0 : TK.ratioLabel;
      for (const n of by(T.secondaryFigure)) { const f = pfFor(n); if (f && hh(f) < rSec * hh(n)) D(s, `figure: primary >= ${TK.ratioSecondaryName} x ${T.secondaryFigure}${R}`, n, hh(f) / hh(n), rSec, 'line boxes'); }
      const oneLine = s.fs ? Math.min(...by(T.statLabel).map(hh)) : 0;   // at large font scale a label wraps: compare with ONE line, the shortest label box
      for (const n of by(T.statLabel)) { const f = pfFor(n); const lh = Math.max(oneLine, s.fs ? 0 : hh(n)); if (f && hh(f) < rLab * lh) D(s, `figure: primary >= ${TK.ratioLabelName} x ${T.statLabel}${R}`, n, hh(f) / lh, rLab, 'line boxes'); }
      for (const n of by(T.pageTitle).filter((x) => !by(T.pageContent).some((p) => (x.l + x.r) / 2 >= p.l && (x.l + x.r) / 2 <= p.r && x.t <= p.t + 0.5))) { const f = pfFor(n); if (f && hh(f) <= hh(n)) D(s, `figure: primary larger than ${T.pageTitle}${R}`, n, hh(n), hh(f)); }
    }
    if (c.on('attention')) for (const r of by(T.attentionRow)) {
      if (s.fs ? hh(r) < TK.attentionH - TOL : Math.abs(hh(r) - TK.attentionH) > TOL) D(s, `attention: ${T.attentionRow} height ${TK.attentionH} dp${rid('attention')}`, r, hh(r), TK.attentionH);
      const inner = s.nodes.filter((n) => n.clickable && n !== r && inside(n, r) && !(Math.abs(n.l - r.l) < 1 && Math.abs(n.r - r.r) < 1));
      if (inner.length) D(s, `attention: no button inside the ${T.attentionRow}${rid('attention')}`, inner[0], inner.length, 0);
    }
  },
};
