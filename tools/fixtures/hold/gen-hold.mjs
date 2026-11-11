// Generates the CR-73 fixtures (hold dumps) from the wave 9 owner S raw dumps (cover 1080x2340, density 2.625): rest dumps are the real ones, the hold dumps move the first
// `period-page-cur` of the rest dump by a chosen dx (px). Run: node tools/fixtures/hold/gen-hold.mjs (needs output/fixwave9/S-*.xml; the generated dirs are committed).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url)), S = path.join(here, '..', '..', '..', 'output', 'fixwave9');
const strips = { 'phone-pilastri': 'S-pil-250-rest.xml', 'phone-mese': 'S-mese-250-rest.xml', 'phone-spese': 'S-sp-250-rest.xml' };
const bounds = (xml, id) => { const m = new RegExp(`resource-id="${id}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`).exec(xml); return m && { s: m[0], l: +m[1], t: +m[2], r: +m[3], b: +m[4] }; };
const hold = (xml, dx) => { const c = bounds(xml, 'period-page-cur'), v = bounds(xml, 'period-viewport'); const nb = `[${c.l + dx},${c.t}][${Math.min(c.r + dx, v.r)},${c.b}]`; return xml.replace(c.s, c.s.replace(/bounds="[^"]*"/, `bounds="${nb}"`)); };
// dx per scene (px): [short, long] for each strip. pitch: hero 933, mese and spese 1017 (end position: rubber band). Short travel = 0.25 pitch, long = 0.60 pitch.
// `good` = the REFERENCE curve (Xtraordinary displayedProgress, tokens read from ui/Motion.kt) at the finger travel minus the 8 dp touch slop (21 px at 2.625); `bad-weak` = a curve at about half strength
// (the old 0.5 fixtures, now flagged: drawn less than the reference); bad-noresist = 1:1 below the commit point; `good-real` = copy of the real 09j hold dumps (see below, written by hand once).
const kt = fs.readFileSync(path.join(here, '..', '..', '..', 'app', 'src', 'main', 'java', 'xyz', 'grtlabs', 'pilastri', 'ui', 'Motion.kt'), 'utf8'), K = (n) => +new RegExp('const val ' + n + ' = ([0-9.]+)f').exec(kt)[1];
const F = K('PeriodFreeTravel'), R = K('PeriodResistedTravel'), TH = K('PeriodThreshold'), RB = K('PeriodRubber'), SLOP = 21;
const ref = (pitch, frac, end) => { const p = Math.max(0, frac - SLOP / pitch), c = (p - F) / (1 - F); const q = end ? p * RB / (p + RB) : p, cq = (q - F) / (1 - F); const s = q < TH ? (q <= F ? q : Math.min(F + R * Math.sin(cq * Math.PI / 2), q)) : q; return Math.round(s * pitch); };
const refs = { 'phone-pilastri': [933, 0], 'phone-mese': [1017, 0], 'phone-spese': [1017, 1] };
const R2 = Object.fromEntries(Object.entries(refs).map(([k, [pt, e]]) => [k, [ref(pt, 0.25, e), ref(pt, 0.6, e)]]));
const scenes = {
  good: R2,
  'bad-weak': { ...R2, 'phone-pilastri': [117, 300], 'phone-mese': [127, 330] },
  'bad-noresist': { ...R2, 'phone-pilastri': [212, R2['phone-pilastri'][1]], 'phone-mese': [231, R2['phone-mese'][1]] },
  'bad-diverge': { ...R2, 'phone-pilastri': [R2['phone-pilastri'][0], 539], 'phone-mese': [100, 400] },
};
for (const [scene, o] of Object.entries(scenes)) {
  const d = path.join(here, scene); fs.rmSync(d, { recursive: true, force: true }); fs.mkdirSync(d, { recursive: true });
  for (const [strip, file] of Object.entries(strips)) {
    const xml = fs.readFileSync(path.join(S, file), 'utf8'); fs.writeFileSync(path.join(d, `${strip}-hold-rest.xml`), xml);
    fs.writeFileSync(path.join(d, `${strip}-hold-short.xml`), hold(xml, o[strip][0])); fs.writeFileSync(path.join(d, `${strip}-hold-long.xml`), hold(xml, o[strip][1]));
  }
}
console.log('hold fixtures written');
