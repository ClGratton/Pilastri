// Adapter: any platform that can export { "nodes": [ { "id", "text", "desc"?, "clickable"?, "scroll"?, "bounds": [l, t, r, b] } ] } in draw order
// (iOS accessibility inspector, web DOM export, desktop UI automation ...). Bounds are in the platform's pixels or points; `--scale` converts them (1 when already in dp or pt).
import fs from 'node:fs';
export default {
  ext: '.json',
  parse(file, dp) {
    const j = JSON.parse(fs.readFileSync(file, 'utf8'));
    return (j.nodes || []).filter((n) => Array.isArray(n.bounds) && n.bounds.length === 4).map((n) => ({
      id: String(n.id || '').split('/').pop(), text: String(n.text || n.desc || ''), vis: String(n.text || '').trim().length, clickable: !!n.clickable, scroll: !!n.scroll,
      l: dp(n.bounds[0]), t: dp(n.bounds[1]), r: dp(n.bounds[2]), b: dp(n.bounds[3]) }));
  },
};
