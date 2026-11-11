// Adapter: Android `uiautomator dump` XML (platform specific, chosen by config `adapter: "uiautomator"`).
// Contract of every adapter: export default { ext, parse(file, dp) } -> nodes {id, text, vis, clickable, scroll, l, t, r, b}; l/t/r/b in dp (dp(px) converts).
// `vis` is the length of the node's visible `text` (a content-description alone is not drawn), `text` falls back to the description.
import fs from 'node:fs';
// Optional in-app attributes: layout-bounds (unclipped box, dp) -> lb, alpha, scroll-end.
function extras(a, dp) {
  const o = {}, m = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a['layout-bounds'] || '');
  if (m) o.lb = { l: dp(+m[1]), t: dp(+m[2]), r: dp(+m[3]), b: dp(+m[4]) };
  if (a.alpha !== undefined) o.alpha = +a.alpha;
  if (a['scroll-end'] === 'true') o.scrollEnd = true;
  if (a['text-ellipsized'] === 'true') o.ellipsized = true;   // in-app text layout: a line ends in an ellipsis
  if (a['text-midword'] === 'true') o.midword = true;         // in-app text layout: a line break falls inside a word
  return o;
}
export default {
  ext: '.xml',
  parse(file, dp) {
    const xml = fs.readFileSync(file, 'utf8');
    const nodes = [];
    for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
      const a = {};
      for (const k of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) a[k[1]] = k[2];
      const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds || '');
      if (!b) continue;
      nodes.push({ id: (a['resource-id'] || '').split('/').pop(), text: (a.text || a['content-desc'] || '').replace(/&amp;/g, '&'), vis: (a.text || '').trim().length, clickable: a.clickable === 'true', scroll: a.scrollable === 'true',
        l: dp(+b[1]), t: dp(+b[2]), r: dp(+b[3]), b: dp(+b[4]), ...extras(a, dp) });
    }
    return nodes;
  },
  // `hidden-node` elements of the in-app dump (text not visible to the user): never part of parse(), read by the visual-integrity add-on only.
  parseHidden(file, dp) {
    const xml = fs.readFileSync(file, 'utf8'), out = [];
    for (const m of xml.matchAll(/<hidden-node\b([^>]*?)\/?>/g)) {
      const a = {};
      for (const k of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) a[k[1]] = k[2];
      const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds || ''); if (!b) continue;
      out.push({ id: (a['resource-id'] || '').split('/').pop(), text: (a.text || a['content-desc'] || '').replace(/&amp;/g, '&'), vis: (a.text || '').trim().length, clickable: false, scroll: false,
        l: dp(+b[1]), t: dp(+b[2]), r: dp(+b[3]), b: dp(+b[4]), ...extras(a, dp) });
    }
    return out;
  },
};
