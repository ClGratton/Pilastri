// Add-on `frame-dock-island` (enable with config `addons: ["frame-dock-island"]`, checks `frame`, `panes`).
// Navigation chrome of one design: on the narrow layout exactly one floating dock, centred, with N items; on the wide layout one floating island at a fixed distance from the
// right edge, centred in the safe area, with a tagged "gear" aligned to it; and two panes split at the hinge, both populated. A project with other chrome does not enable it;
// the base has the generic form (`alignRules`: a tagged element centred or at edge N).
export default {
  name: 'frame-dock-island',
  checks: { frame: ['islandEdge', 'islandCentreTol', 'dockItems'], panes: ['paneMinTexts', 'paneSplitTol'] },
  roles: { frame: ['dock', 'island'], panes: ['paneLeft', 'paneRight'] },
  screen(c, s, h) {
    const { T, TK, LL, TOL, is, D, rid } = c, by = h.by, W = s.W, H = s.H, half = W / 2;
    if (s.top && c.on('frame')) {
      const dock = by(T.dock), island = by(T.island), gear = by(T.gear);
      if (s.narrow) {
        if (dock.length !== 1) D(s, `frame: exactly one ${T.dock} on the ${LL.narrow}${rid('frameDock')}`, null, dock.length, 1);
        else {
          if (Math.abs((dock[0].l + dock[0].r) / 2 - W / 2) > TOL) D(s, `frame: ${T.dock} centre x equals screen centre${rid('frameDock')}`, dock[0], (dock[0].l + dock[0].r) / 2, W / 2);
          const items = by(T.dockItem).length;
          if (items !== TK.dockItems) D(s, `frame: exactly ${TK.dockItems} ${T.dockItem}${rid('frameDock')}`, null, items, TK.dockItems, items ? '' : `request tag ${T.dockItem}`);
        }
      } else if (s.wide) {
        if (island.length !== 1) D(s, `frame: exactly one ${T.island} on the ${LL.wideLong}${rid('frameIsland')}`, null, island.length, 1);
        else {
          const i = island[0], icy = (i.t + i.b) / 2;
          if (Math.abs((W - i.r) - TK.islandEdge) > TOL) D(s, `frame: ${T.island} ${TK.islandEdge} dp from the right edge${rid('frameIsland')}`, i, W - i.r, TK.islandEdge);
          // Centred in the safe area the panes occupy (one owner for the insets), not on the raw screen height.
          const pr = by(T.paneRight)[0], mid = pr ? (pr.t + pr.b) / 2 : H / 2;
          if (Math.abs(icy - mid) > TK.islandCentreTol) D(s, `frame: ${T.island} vertically centred +-${TK.islandCentreTol} dp${rid('frameIsland')}`, i, icy, mid);
          if (gear[0] && Math.abs((gear[0].l + gear[0].r) / 2 - (i.l + i.r) / 2) > c.H.gearTol) D(s, `frame: ${T.gear} centre x equals ${T.island} centre x${rid('frameGear')}`, gear[0], (gear[0].l + gear[0].r) / 2, (i.l + i.r) / 2);
        }
      }
    }
    if (s.top && s.wide && c.on('panes')) {
      const l = by(T.paneLeft), r = by(T.paneRight);
      if (!l.length || !r.length) D(s, `panes: tags ${T.paneLeft} and ${T.paneRight} missing${rid('panes')}`, null, l.length + r.length, 2, `request tags ${T.paneLeft}, ${T.paneRight}`);
      else {
        if (Math.abs(l[0].r - half) > TK.paneSplitTol) D(s, `panes: left pane ends at the split${rid('panes')}`, l[0], l[0].r, half);
        const content = s.nodes.filter((n) => n.text.trim() && !is(n, 'island', 'gear', 'dockItem'));
        const left = content.filter((n) => (n.l + n.r) / 2 < half).length, right = content.filter((n) => (n.l + n.r) / 2 >= half).length;
        if (left < TK.paneMinTexts || right < TK.paneMinTexts) D(s, `panes: both panes populated (>= ${TK.paneMinTexts} texted nodes each)${rid('panes')}`, { text: 'left ' + left + ' / right ' + right, id: '' }, Math.min(left, right), TK.paneMinTexts);
      }
    }
  },
};
