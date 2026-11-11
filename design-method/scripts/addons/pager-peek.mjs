// Add-on `pager-peek` (config `addons: ["pager-peek"]`, checks `peek`, `periodLabels`). A pager (swipe between periods or pages) shows a sliver of the neighbour page.
// peek: the neighbour page is visible in the gutter margin = `tokens.gutter - tokens.gap` dp (16 - 8 = 8), +-tolerance; optional per-layout overrides `tokens.peekWide` / `tokens.peekNarrow` replace that value.
// periodLabels: a label row above the pager sits on the same gutter line as every other card (REMOVED 2026-11-09, K2 wave 6: the G-11 'narrowed edge' and its inset adjuster; cards and labels in a pager are NOT narrowed, a narrowed one is a deviation).
// A design that still wants a narrowed block says so by config `insetAdjust` (generic mechanism), never by this add-on.
export default {
  name: 'pager-peek',
  checks: { peek: ['gutter', 'gap'], periodLabels: ['accentPad'] },
  roles: { peek: ['periodViewport'], periodLabels: ['periodLabelPrev', 'periodLabelNext'] },
  screen(c, s, h) {
    const { T, TK, LL, inside, add, tok, rid } = c, by = h.by;
    const peekOf = (s.wide ? TK.peekWide : TK.peekNarrow) ?? (TK.gutter - TK.gap);
    if (c.on('peek')) {
      for (const v of by(T.periodViewport)) for (const id of [T.periodPagePrev, T.periodPageNext].filter(Boolean)) {
        const p = s.nodes.filter((n) => n.id === id && (n.t + n.b) / 2 > v.t && (n.t + n.b) / 2 < v.b).sort((a, b) => Math.abs((a.l + a.r) / 2 - (v.l + v.r) / 2) - Math.abs((b.l + b.r) / 2 - (v.l + v.r) / 2))[0]; if (!p) continue;
        tok(s, `motion: neighbour page peek ${peekOf} dp (gutter - gap)${rid('peek')}`, { text: id, id }, Math.max(0, Math.min(p.r, v.r, s.W) - Math.max(p.l, v.l, 0)), peekOf);
      }
    }
    if (c.on('periodLabels')) {
      // A neighbour label peeks into the strip: only a few dp of its text are inside the viewport. A label inside an accent card sits on its padding.
      const accentOf = (n) => by(T.accentCard).find((a) => inside(n, a)), minW = c.H.labelMinWidth;
      for (const n of by(T.periodLabelPrev).filter((x) => x.r - x.l >= minW)) { const a = accentOf(n); if (a) { tok(s, `label period prev left inside ${T.accentCard} (padding ${TK.accentPad})`, n, n.l - a.l, TK.accentPad); continue; } const cd = h.below(n); if (cd) add(s, 'label period prev left minus card left', n, n.l - cd.l, 'zero'); }
      for (const n of by(T.periodLabelNext).filter((x) => x.r - x.l >= minW)) { const a = accentOf(n); if (a) { tok(s, `label period next right inside ${T.accentCard} (padding ${TK.accentPad})`, n, a.r - n.r, TK.accentPad); continue; } const cd = h.below(n); if (cd) add(s, 'label period next right minus card right', n, n.r - cd.r, 'zero'); }
    }
  },
};
