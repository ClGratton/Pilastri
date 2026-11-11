// Runs the Enable Banking page-state script (EbPanelScripts.STATE in sync/eb/EbPanel.kt) against page shapes seen on the Fold.
// Usage: node tools/eb-state-check.mjs      exit 0 when every shape gives the expected state.
// Shapes come from the saved pages under output/webview-html-* (markers only: which selectors exist, the page's own sentence).
import fs from 'fs';

const src = fs.readFileSync('app/src/main/java/xyz/grtlabs/pilastri/sync/eb/EbPanel.kt', 'utf8');
const m = src.match(/const val STATE = """([\s\S]*?)"""/);
if (!m) { console.error('STATE script not found'); process.exit(2); }
const body = m[1].replaceAll("${'$'}", '$');
const state = new Function('document', 'location', 'getComputedStyle', body);

const page = (path, selectors, text = '') => ({
  location: { pathname: path },
  document: {
    querySelector: s => (selectors.includes(s) ? {} : null),
    querySelectorAll: () => [],
    body: { innerText: text },
  },
});
const FORM = '#firebaseui-auth-container', EMAIL = '#firebaseui-auth-container input[type=email]';
const cases = [
  ['sign-in page, nothing drawn yet', page('/sign-in/', []), 'loading'],
  ['sign-in page, form container without the field', page('/sign-in/', [FORM]), 'loading'],
  ['sign-in page, e-mail form', page('/sign-in/', [FORM, EMAIL]), 'form'],
  // Fold, 2026-11-10 13:47: the session was still there; the page has no form and says so.
  ['sign-in page of a live session', page('/sign-in/', ['#app', '.navbar-name'], 'Enable Banking You are already signed in as name@example.com'), 'in'],
  ['sign-in page of a live session, sentence only', page('/sign-in/', ['#app'], 'You are already signed in as name@example.com'), 'in'],
  ['sign-in form with a name in the bar is still a form', page('/sign-in/', [FORM, EMAIL, '.navbar-name']), 'form'],
  ['panel with a name in the bar', page('/cp/applications', ['.navbar-name']), 'in'],
  ['panel showing the sign-in form', page('/cp/applications', [FORM, EMAIL, '.navbar-name']), 'form'],
  ['link sent', page('/sign-in/', [FORM, '.firebaseui-id-page-email-link-sign-in-sent']), 'sent'],
];
let bad = 0;
for (const [name, p, want] of cases) {
  const got = state(p.document, p.location, () => ({ visibility: 'visible' })).st;
  if (got !== want) { bad++; console.log(`FAIL ${name}: got ${got}, want ${want}`); }
}
console.log(`eb-state-check: ${cases.length - bad}/${cases.length} shapes`);
process.exit(bad ? 1 : 0);
