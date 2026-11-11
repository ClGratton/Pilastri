#!/usr/bin/env node
// Drag start for tools/capture-moving.sh, read from a REST dump so a held drag can never miss the pager (K4/M1, 2026-11-09:
// the drag was typed as fixed pixels, the page was scrolled, and the strip was 350 px higher: the "moving" dump equalled the rest dump).
//   node tools/viewport-point.mjs <rest.xml> auto [index]       -> "x1 y x2 y" (px): the centre of the index-th visible period-viewport (default 0), x1 at 85 %, x2 at 35 % of its width
//   node tools/viewport-point.mjs <rest.xml> check <x> <y>      -> exit 0 when (x,y) lies inside a visible period-viewport, else exit 3
import fs from 'node:fs';
const [file, mode, ...rest] = process.argv.slice(2);
const xml = fs.readFileSync(file, 'utf8');
const vps = [...xml.matchAll(/<node [^>]*resource-id="period-viewport"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)]
  .map((m) => ({ l: +m[1], t: +m[2], r: +m[3], b: +m[4] })).filter((v) => v.r - v.l > 0 && v.b - v.t > 0);
export const pick = (list, i) => list[i];
if (mode === 'auto') {
  const v = vps[parseInt(rest[0] || '0', 10)];
  if (!v) { console.error('no period-viewport in ' + file); process.exit(3); }
  const y = Math.round((v.t + v.b) / 2), w = v.r - v.l;
  console.log(`${Math.round(v.l + w * 0.85)} ${y} ${Math.round(v.l + w * 0.35)} ${y}`);
} else if (mode === 'check') {
  const x = +rest[0], y = +rest[1];
  if (!vps.some((v) => x >= v.l && x <= v.r && y >= v.t && y <= v.b)) { console.error(`drag start (${x},${y}) is outside every period-viewport of ${file}: ` + vps.map((v) => `[${v.l},${v.t}][${v.r},${v.b}]`).join(' ')); process.exit(3); }
} else { console.error('usage: viewport-point.mjs <rest.xml> auto [index] | check <x> <y>'); process.exit(2); }
