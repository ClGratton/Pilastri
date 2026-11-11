#!/usr/bin/env node
// node hold-point.mjs <rest.xml> <viewport-index> -> "x y pitch" in px for the held-finger capture (CR-73): the finger goes DOWN at 20 % of the index-th period-viewport
// (centre height) and travels RIGHT (towards the previous page); pitch = width of the period-page-cur inside that viewport + 8 dp gap (21 px at density 2.625), the unit CR-73 reads.
import fs from 'node:fs';
const [file, idx = '0', dens = '2.625'] = process.argv.slice(2), xml = fs.readFileSync(file, 'utf8');
const nodes = [...xml.matchAll(/<node [^>]*resource-id="(period-viewport|period-page-cur)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)].map((m) => ({ id: m[1], l: +m[2], t: +m[3], r: +m[4], b: +m[5] })).filter((n) => n.r > n.l && n.b > n.t);
const v = nodes.filter((n) => n.id === 'period-viewport')[+idx];
if (!v) { console.error('no period-viewport #' + idx + ' in ' + file); process.exit(3); }
const a = nodes.find((n) => n.id === 'period-page-cur' && n.l >= v.l - 2 && n.r <= v.r + 2 && n.t >= v.t - 2 && n.b <= v.b + 2);
if (!a) { console.error('no period-page-cur inside viewport #' + idx); process.exit(3); }
console.log(`${Math.round(v.l + (v.r - v.l) * 0.2)} ${Math.round((v.t + v.b) / 2)} ${Math.round(a.r - a.l + 8 * +dens)}`);
