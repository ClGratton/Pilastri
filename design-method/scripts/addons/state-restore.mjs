// Add-on `state-restore` (SKILL section 2, K28). Rule, user 2026-11-10: "Avevo fatto apro banca, stava lavorando, e quando ho aperto si è resettato. E chissà in quanti posti sta roba non va."
// Leaving the app never resets a flow. Whatever takes the app out of the foreground or recreates it leaves the person where they were: step, typed input and work in progress are kept outside
// the screen's memory and restored; work that had to stop resumes by itself or becomes a named failure with a retry; never a second request for something already done.
// Triggers: background (return from another app / screen off), recreate (configuration change), process (process death), link (return through an intent or redirect).
// Config JSON: { roots[], exts[]?, flow_file_patterns[] (regex on the relative path), rules[{id, regex, flags?, why, near?, window?, until?}],
//   allow[{rule, file, symbol|line_text, reason}], flows[{flow, owner (path[:from-to] or array of them), holder, inputs[], work, tests{background,recreate,process,link: "Class#method"},
//   known_lost[{trigger, what, reason, size}]}], tests_root }.
// Fails (exit 1, one line per finding): a source line matches a rule and is neither allowed nor inside a flow owner range; an allow entry without reason; a file matching flow_file_patterns owned by no flow;
// a named test that does not exist under tests_root; a flow with neither a test nor a known_lost item for a trigger. known_lost items do not fail; they are printed and counted.
// Line-based: it sees a line and, for `near` rules, the next `window` lines (stopping at `until`); it cannot see state assigned through helper functions, nor a restore that is declared but never wired.
//   node state-restore.mjs <config.json> [baseDir]      node state-restore.mjs --selftest
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const TRIGGERS = ['background', 'recreate', 'process', 'link'];

/** The generic rule set; a project copies it into its config and may loosen a rule with a reason. */
export const DEFAULT_RULES = [
  { id: 'R1-typed-input-in-memory', regex: 'var\\s+\\w+\\s+by\\s+remember(\\([^)]*\\))?\\s*\\{\\s*mutableStateOf\\((""|TextFieldValue\\()', why: 'typed input kept in screen memory is lost on recreation and process death: rememberSaveable or a store' },
  { id: 'R2-flow-position-in-memory', regex: 'var\\s+(step|screen|stage|page|selected\\w*|mode(?!l)|tab|position)\\w*\\s+by\\s+remember(?!Saveable)', why: 'the flow position kept in screen memory returns the person to the start' },
  { id: 'R3-work-owner-in-remember', regex: 'remember\\s*\\{\\s*\\w*(State|Session|Runner|Page|App)\\(', why: 'an object that owns work, held in remember, dies with the screen: it must belong to a listed flow with a holder outside the screen' },
  { id: 'R4-work-destroyed-on-dispose', regex: '(onDispose\\s*\\{[^}]*(destroy|cancel|clear|stopLoading)\\()|(onRelease\\s*=\\s*\\{[^}]*destroy\\()', why: 'in-flight work destroyed when the screen leaves composition: resume by itself or named failure with retry' },
  { id: 'R5-lifecycle-handler-assigns-flow-state', regex: 'Lifecycle\\.Event\\.ON_(STOP|PAUSE|RESUME|START)|override\\s+fun\\s+on(Stop|Pause|Resume|Start|NewIntent)\\(', near: '\\b(model|step|screen|stack)\\s*=[^=]', window: 12, until: '^\\s*override\\s+fun\\s+on\\w+\\(.*\\{\\s*$', why: 'a stop/resume/new-intent handler that assigns flow state can reset the flow on return' },
  { id: 'R6-process-singleton-flow-state', regex: '^object\\s+\\w+', near: '^( {4}|\\t)(private |internal )?(@Volatile )?var\\s', window: 40, until: '^\\}', why: 'a process singleton holding mutable flow state is lost with the process' },
];

const stripComment = (l) => l.replace(/\/\/.*$/, '');
const isComment = (l) => { const t = l.trimStart(); return t.startsWith('//') || t.startsWith('*') || t.startsWith('/*') || t.startsWith('import '); };
const norm = (p) => p.replace(/\\/g, '/');

/** Findings {rule, rel, line, text} for the files [{rel, text}] against rules (before allow / flow coverage). */
export function scanRules(files, rules) {
  const out = [];
  for (const f of files) {
    const lines = f.text.split(/\r?\n/);
    for (const r of rules) {
      const re = new RegExp(r.regex, r.flags || ''), near = r.near && new RegExp(r.near), until = r.until && new RegExp(r.until);
      lines.forEach((raw, i) => {
        if (isComment(raw) || !re.test(stripComment(raw))) return;
        if (near) {
          let hit = false;
          for (let j = i; j < Math.min(lines.length, i + 1 + (r.window || 12)); j++) {
            if (j > i && until && until.test(lines[j])) break;
            if (!isComment(lines[j]) && near.test(stripComment(lines[j]))) { hit = true; break; }
          }
          if (!hit) return;
        }
        out.push({ rule: r.id, rel: norm(f.rel), line: i + 1, text: raw.trim() });
      });
    }
  }
  return out;
}

const owners = (flow) => [].concat(flow.owner || []).map((o) => { const m = /^(.*?)(?::(\d+)-(\d+))?$/.exec(norm(o)); return { file: m[1], from: m[2] ? +m[2] : 0, to: m[3] ? +m[3] : Infinity }; });
const sameFile = (rel, file) => rel === file || rel.endsWith('/' + file);
const owns = (o, rel, line) => sameFile(rel, o.file) && line >= o.from && line <= o.to;

/** Findings (strings), known-lost list and counts; files [{rel, text}] of the sources, testText [{rel, text}] of the tests. */
export function check(cfg, files, testText) {
  const findings = [], known = [];
  const flows = cfg.flows || [], allow = cfg.allow || [];
  for (const a of allow) {
    if (!a.reason || !String(a.reason).trim()) findings.push(`allow-no-reason ${a.rule} ${a.file}`);
    if (!a.symbol && !a.line_text) findings.push(`allow-no-anchor ${a.rule} ${a.file} (needs symbol or line_text)`);
  }
  for (const f of scanRules(files, cfg.rules || [])) {
    const covered = flows.some((fl) => owners(fl).some((o) => owns(o, f.rel, f.line)));
    const allowed = allow.some((a) => a.reason && a.rule === f.rule && sameFile(f.rel, norm(a.file)) && ((a.line_text && f.text.includes(a.line_text)) || (a.symbol && f.text.includes(a.symbol))));
    if (!covered && !allowed) findings.push(`${f.rule} ${f.rel}:${f.line} ${f.text.slice(0, 110)} (not in a flow, not allowed)`);
  }
  const pats = (cfg.flow_file_patterns || []).map((p) => new RegExp(p));
  for (const f of files) {
    const rel = norm(f.rel);
    if (pats.some((p) => p.test(rel)) && !flows.some((fl) => owners(fl).some((o) => sameFile(rel, o.file)))) findings.push(`flow-file-unlisted ${rel} (matches flow_file_patterns, no flow entry)`);
  }
  const testExists = (spec) => {
    const [cls, m] = String(spec).split('#');
    return testText.some((t) => (/\.(kt|java|mjs)$/.test(t.rel) && t.rel.split('/').pop().replace(/\.\w+$/, '') === cls || new RegExp('\\b(class|object)\\s+' + cls + '\\b').test(t.text))
      && (!m || new RegExp('(fun|function|void)\\s+`?' + m + '\\b').test(t.text)));
  };
  let tested = 0;
  for (const fl of flows) {
    const lost = fl.known_lost || [];
    for (const t of TRIGGERS) {
      const spec = (fl.tests || {})[t];
      if (spec) { if (testExists(spec)) tested++; else findings.push(`test-missing ${fl.flow}/${t}: ${spec} not found under tests_root`); }
      else if (!lost.some((k) => k.trigger === t)) findings.push(`trigger-uncovered ${fl.flow}/${t}: no test and no known_lost item`);
    }
    for (const k of lost) {
      if (!TRIGGERS.includes(k.trigger)) findings.push(`known-lost-bad-trigger ${fl.flow}: ${k.trigger}`);
      else known.push(`${fl.flow}/${k.trigger}: ${k.what} [${k.reason}${k.size ? '; ' + k.size : ''}]`);
    }
  }
  return { findings, known, flows: flows.length, tested };
}

const walk = (dir, exts, out = []) => {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (!['build', 'node_modules', '.git'].includes(e.name)) walk(p, exts, out); }
    else if (exts.some((x) => e.name.endsWith(x))) out.push(p);
  }
  return out;
};

export function run(configPath, baseDir) {
  const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  const exts = cfg.exts || ['.kt', '.java'];
  const files = (cfg.roots || []).flatMap((r) => walk(path.join(baseDir, r), exts).map((p) => ({ rel: norm(path.relative(path.join(baseDir, r), p)), text: fs.readFileSync(p, 'utf8') })));
  const testText = walk(path.join(baseDir, cfg.tests_root || 'tests'), ['.kt', '.java', '.mjs', '.js']).map((p) => ({ rel: norm(p), text: fs.readFileSync(p, 'utf8') }));
  const r = check(cfg, files, testText);
  for (const k of r.known) console.log('known-lost ' + k);
  for (const f of r.findings) console.log(f);
  console.log(`state-restore: ${r.flows} flows, ${r.tested} triggers restore-tested, ${r.known.length} known lost, ${r.findings.length} findings`);
  return r.findings.length ? 1 : 0;
}

function selftest() {
  let ok = true;
  const say = (c, m) => { if (!c) { ok = false; console.log('FAIL ' + m); } };
  const bad = {
    'R1-typed-input-in-memory': 'var email by remember { mutableStateOf("") }',
    'R2-flow-position-in-memory': 'var step by remember { mutableStateOf(0) }',
    'R3-work-owner-in-remember': 'val runner = remember { EbSetupRunner(panel) }',
    'R4-work-destroyed-on-dispose': 'DisposableEffect(Unit) { onDispose { page.destroy() } }',
    'R5-lifecycle-handler-assigns-flow-state': 'if (event == Lifecycle.Event.ON_RESUME) {\n  step = 0\n}',
    'R6-process-singleton-flow-state': 'object LinkInbox {\n    var waiting = false\n}',
  };
  for (const r of DEFAULT_RULES) {
    const t = bad[r.id];
    say(!!t, 'fixture for ' + r.id);
    say(scanRules([{ rel: 'A.kt', text: t || '' }], [r]).length === 1, 'must-flag ' + r.id);
  }
  say(scanRules([{ rel: 'A.kt', text: 'var step by rememberSaveable { mutableStateOf(0) }\n// var step by remember { mutableStateOf(0) }\nobject K {\n val x = 1\n}' }], DEFAULT_RULES).length === 0, 'saveable, comment and val-only object are clean');
  // the user's case: a step reset by a resume handler, the wait living only in a composable scope
  const userCase = 'var step by remember { mutableStateOf(2) }\nval page = remember { WebPage(ctx) }\nDisposableEffect(lifecycle) {\n  val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { step = 0 } }\n  onDispose { page.destroy() }\n}';
  const uc = scanRules([{ rel: 'Bank.kt', text: userCase }], DEFAULT_RULES).map((f) => f.rule);
  for (const id of ['R2-flow-position-in-memory', 'R3-work-owner-in-remember', 'R4-work-destroyed-on-dispose', 'R5-lifecycle-handler-assigns-flow-state']) say(uc.includes(id), "user's case flagged by " + id);
  const files = [{ rel: 'ui/Bank.kt', text: userCase }, { rel: 'ui/Other.kt', text: 'var email by remember { mutableStateOf("") }' }, { rel: 'ui/Flow.kt', text: 'x' }];
  const base = { rules: DEFAULT_RULES, flow_file_patterns: ['Flow\\.kt$'], tests_root: 't' };
  const tests = [{ rel: 't/FooTest.kt', text: 'class FooTest { @Test fun restores() {} }' }];
  let r = check({ ...base, flows: [] }, files, tests);
  say(r.findings.some((f) => f.startsWith('R1') && f.includes('Other.kt')), 'unlisted typed input flagged');
  say(r.findings.some((f) => f.startsWith('flow-file-unlisted ui/Flow.kt')), 'unlisted flow file flagged');
  const lost = TRIGGERS.map((t) => ({ trigger: t, what: 'w', reason: 'r', size: 's' }));
  const flowFor = (extra) => ({ flow: 'f', owner: ['ui/Bank.kt', 'ui/Flow.kt', 'ui/Other.kt'], holder: 'h', inputs: [], work: 'w', tests: {}, known_lost: lost, ...extra });
  r = check({ ...base, flows: [flowFor({})] }, files, tests);
  say(r.findings.length === 0 && r.known.length === 4, 'flow with known_lost for all triggers is clean (' + r.findings.join('|') + ')');
  r = check({ ...base, flows: [flowFor({ known_lost: lost.slice(1) })] }, files, tests);
  say(r.findings.some((f) => f.startsWith('trigger-uncovered f/background')), 'trigger without test or known_lost flagged');
  r = check({ ...base, flows: [flowFor({ tests: { background: 'FooTest#nope' } })] }, files, tests);
  say(r.findings.some((f) => f.startsWith('test-missing f/background')), 'missing test method flagged');
  r = check({ ...base, flows: [flowFor({ tests: { background: 'FooTest#restores' }, known_lost: lost.slice(1) })] }, files, tests);
  say(r.findings.length === 0 && r.tested === 1, 'existing test counted (' + r.findings.join('|') + ')');
  const a1 = { rule: 'R1-typed-input-in-memory', file: 'ui/Other.kt', line_text: 'var email', reason: 'secret field, never stored' };
  r = check({ ...base, flows: [flowFor({ owner: ['ui/Bank.kt', 'ui/Flow.kt'] })], allow: [a1] }, files, tests);
  say(r.findings.length === 0, 'allowed line is clean (' + r.findings.join('|') + ')');
  r = check({ ...base, flows: [flowFor({ owner: ['ui/Bank.kt', 'ui/Flow.kt'] })], allow: [{ ...a1, reason: '' }] }, files, tests);
  say(r.findings.some((f) => f.startsWith('allow-no-reason')) && r.findings.some((f) => f.startsWith('R1')), 'allow without reason flagged and does not allow');
  r = check({ ...base, flows: [flowFor({ owner: ['ui/Bank.kt:1-2', 'ui/Flow.kt'] })], allow: [a1] }, files, tests);
  say(r.findings.some((f) => f.startsWith('R3') || f.startsWith('R4') || f.startsWith('R5')), 'owner line range limits coverage');
  console.log(ok ? 'state-restore selftest PASS (6 rules must-flag, user case flagged, allowed line clean, 5 config checks)' : 'state-restore selftest FAIL');
  return ok ? 0 : 1;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv.includes('--selftest')) process.exit(selftest());
  const cfgPath = process.argv[2];
  if (!cfgPath) { console.log('usage: state-restore.mjs <config.json> [baseDir] | --selftest'); process.exit(2); }
  process.exit(run(cfgPath, path.resolve(process.argv[3] || path.dirname(path.dirname(path.resolve(cfgPath))))));
}
