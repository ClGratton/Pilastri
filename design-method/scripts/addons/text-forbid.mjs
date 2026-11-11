// Add-on `text-forbid` (config `addons: ["text-forbid"]`, check `textForbid`; CR-78 "a wording the user ruled out never appears").
// Config `textForbid`: [{ pattern: "dati dal 1 al(?=\s|$)", flags: "i", rule: "text: coverage label starting on the 1st reads 'dati dal 1 al ...' (CR-78, write 'dati al ...')", roles?: [role keys] }].
// A visible text node (non-empty `text`, not only a content-desc) whose text matches `pattern` flags one direct row per node. `roles` (optional) limits it to nodes with those tags.
// Rules come from the user's critiques (design-method/SKILL.md section 1 step 6), never from an agent's own findings: each entry names its source remark in its rule text or in the rules table.
// `dateRangeOnce` (CR-82): a header shows at most one date range per period; source: the user's remark of 2026-11-09 on a title "7 ott – 6 nov" over "dati dal 10 ott al 6 nov" ("utente cosa capisce").
// `textTemplates` (CR-81): same element, same wording; source: the user's remarks of 2026-11-09 (two screens worded the same thing differently).
export default {
  name: 'text-forbid',
  checks: { textForbid: [], textTemplates: [], dateRangeOnce: [] },
  roles: { textForbid: [], textTemplates: [], dateRangeOnce: [] },
  screen(c, s) {
    for (const f of c.on('textForbid') ? c.cfg.textForbid || [] : []) {
      const re = new RegExp(f.pattern, f.flags || '');
      for (const n of s.nodes.filter((x) => x.vis && re.test(x.text || '') && (!f.roles || f.roles.some((r) => c.T[r] === x.id)))) c.DS(s, f.rule || 'text: forbidden wording (' + f.pattern + ')', c.label(n), 1, 0);
    }
    // One date range per period (CR-82): config `dateRangeOnce`: { pattern: regex of a date range, titleRoles: [role keys of the period title], noteRoles: [role keys of lines under it], maxBelowPx?: 140 }.
    // A title whose text holds a range, with a second range in a note under it (same column, up to maxBelowPx below its top edge), is a deviation.
    const dr = c.on('dateRangeOnce') ? c.cfg.dateRangeOnce || {} : {};
    if (dr.pattern) {
      const re = new RegExp(dr.pattern, 'iu'), isR = (rs, x) => (rs || []).some((r) => c.T[r] === x.id || r === x.id);
      for (const t of s.nodes.filter((x) => x.vis && isR(dr.titleRoles, x))) {
        const tt = s.nodes.filter((x) => x.vis && x.l >= t.l - 0.5 && x.t >= t.t - 0.5 && x.r <= t.r + 0.5 && x.b <= t.b + 0.5 && (x.text || '').trim()).map((x) => x.text).join(' ') + ' ' + (t.text || '');
        if (!re.test(tt)) continue;
        for (const n of s.nodes.filter((x) => x.vis && isR(dr.noteRoles, x) && re.test(x.text || '') && x.l < t.r && x.r > t.l && x.t >= t.t - 4 && x.t <= t.b + (dr.maxBelowPx ?? 140)))
          c.DS(s, 'text: a header shows one date range per period, a second range sits under the title (CR-82)', '"' + tt.trim().slice(0, 20) + '" + "' + c.label(n) + '"', 2, 1);
      }
    }
    // Same element, same wording (CR-81): config `textTemplates`: { placeholders: [[regex, "<x>"]], groups: [{ name, roles: [role keys], screens: regex, match: regex, allowed: { "<template>": "the user's decision" } }] }.
    // Every visible text of the group's roles on matching screens is reduced to a template (placeholders replace numbers, dates, user data; the part after " · " is ignored); a template not in `allowed` is a deviation.
    const tt = c.on('textTemplates') ? c.cfg.textTemplates || {} : {};
    for (const g of tt.groups || []) {
      if (!new RegExp(g.screens || '.').test(s.name)) continue;
      const mt = new RegExp(g.match || '', 'iu'), ph = (tt.placeholders || []).map(([p, r]) => [new RegExp(p, 'giu'), r]);
      for (const n of s.nodes.filter((x) => x.vis && (g.roles || []).some((r) => c.T[r] === x.id || r === x.id) && (x.text || '').trim())) {
        let t = (x => x)((n.text || '').replace(/ /g, ' ').split(' · ')[0].trim()); if (!mt.test(t)) continue;
        for (const [re, r] of ph) t = t.replace(re, r);
        if (!(t in (g.allowed || {}))) c.DS(s, 'text: ' + (g.name || 'element') + ' has a wording no other screen uses: "' + t + '" (CR-81)', c.label(n), 1, 0);
      }
    }
  },
};
