package xyz.grtlabs.pilastri.ai

/**
 * Debug builds only. A fake provider page that imitates, as far as `pilastri_inject.js` needs, the DOM of claude.ai
 * (`ui = claude`) or chatgpt.com (`ui = chatgpt`), so the account flow can be tried on the emulator with no account and no
 * network (idea from Cattedra's `nextapp/tools/mock_claude.html`). Loaded by `DebugMock` in the main source set through
 * reflection, only when the app is debuggable and `files/ai_mock` exists:
 *
 *     adb shell run-as xyz.grtlabs.pilastri sh -c "echo 'claude proposal' > files/ai_mock"
 *     adb shell run-as xyz.grtlabs.pilastri rm files/ai_mock          # back to the real sites
 *
 * Scenarios: `chat` (short prose), `proposal` (one Tier-1 plan item), `batch` (two operations: both wait for a tap),
 * `hostile` (unknown tool, read tool, extra keys: all refused), `sources` (a web-source pill: the turn is tainted),
 * `login` (the page asks to sign in), `captcha` (a bot check is showing), `nosend` (no editor: handoff fallback),
 * `slow` (20 s of streaming).
 */
object MockProviderPage {
    fun html(ui: String, scenario: String): String {
        val u = if (ui == "chatgpt") "chatgpt" else "claude"
        val s = scenario.filter { it in 'a'..'z' }.ifEmpty { "chat" }
        return PAGE.replace("__UI__", u).replace("__SCENARIO__", s)
    }

    private val PAGE = """
<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pagina finta (__UI__ · __SCENARIO__)</title>
<style>
  body { font-family: sans-serif; margin: 0; background: #fff; color: #111; }
  #banner { padding: 8px 12px; background: #fff3cd; font-size: 13px; }
  #log { padding: 12px 12px 96px; }
  .u, .a { margin: 8px 0; padding: 8px 12px; border-radius: 12px; background: #eee; white-space: pre-wrap; }
  .a { background: #efe7fb; }
  #bar { position: fixed; bottom: 0; left: 0; right: 0; padding: 8px; background: #fff; border-top: 1px solid #ccc; display: flex; gap: 8px; align-items: flex-end; }
  #ed { flex: 1; min-height: 40px; border: 1px solid #888; border-radius: 8px; padding: 6px; }
  pre { background: #222; color: #eee; padding: 8px; border-radius: 6px; overflow: auto; }
</style>
</head>
<body>
<div id="banner">Pagina finta per le prove (__UI__, scenario __SCENARIO__): nessun account, nessuna rete.</div>
<div id="log"></div>
<div id="bar"></div>
<script>
(function () {
  var UI = "__UI__", SC = "__SCENARIO__";
  window.__pilMockUi = UI;
  var log = document.getElementById("log"), bar = document.getElementById("bar");
  var ed = null, send = null, stop = null, streaming = false;

  if (SC === "captcha") {
    document.title = "Just a moment...";
    var c = document.createElement("div"); c.className = "cf-turnstile"; c.textContent = "Verifica di essere una persona (finta: l'app non deve toccarla)";
    document.body.appendChild(c);
  }
  if (SC === "login") {
    if (UI === "claude") history.replaceState(null, "", "/login");
    else { var l = document.createElement("button"); l.setAttribute("data-testid", "login-button"); l.textContent = "Accedi"; document.body.appendChild(l); }
  }

  function makeComposer() {
    ed = document.createElement("div");
    ed.id = UI === "chatgpt" ? "prompt-textarea" : "ed";
    if (UI === "claude") ed.setAttribute("data-testid", "chat-input");
    ed.contentEditable = "true";
    if (UI === "chatgpt") ed.style.cssText = "flex:1;min-height:40px;border:1px solid #888;border-radius:8px;padding:6px";
    bar.appendChild(ed);
    send = document.createElement("button");
    send.textContent = "Invia";
    send.setAttribute("data-testid", UI === "chatgpt" ? "send-button" : "chat-input-send");
    send.disabled = true;
    bar.appendChild(send);
    ed.addEventListener("input", function () { send.disabled = !ed.textContent.trim(); });
    send.addEventListener("click", function () { var t = ed.textContent; ed.textContent = ""; send.disabled = true; ask(t); });
  }
  if (SC !== "nosend") makeComposer();

  function userMsg(t) {
    var d = document.createElement("div"); d.className = "u";
    if (UI === "chatgpt") d.setAttribute("data-message-author-role", "user"); else d.setAttribute("data-testid", "user-message");
    d.textContent = t.length > 300 ? t.slice(0, 300) + "…" : t;
    log.appendChild(d);
  }
  function assistantShell() {
    var d = document.createElement("div"); d.className = "a";
    var md = document.createElement("div"); md.className = UI === "chatgpt" ? "markdown result-streaming" : "standard-markdown";
    if (UI === "chatgpt") d.setAttribute("data-message-author-role", "assistant");
    else { d.setAttribute("data-testid", "assistant-message"); d.setAttribute("data-is-streaming", "true"); }
    d.appendChild(md); log.appendChild(d);
    return { box: d, md: md };
  }
  function setBusy(on) {
    streaming = on;
    if (UI === "claude") { if (on) send.remove(); else bar.appendChild(send); }
    else {
      if (on) { stop = document.createElement("button"); stop.setAttribute("data-testid", "stop-button"); stop.textContent = "Ferma"; bar.appendChild(stop); }
      else if (stop) { stop.remove(); stop = null; }
    }
  }
  function block(obj) { return "<pre><code class=\"language-json\">" + JSON.stringify(obj).replace(/</g, "&lt;") + "</code></pre>"; }
  function reply(q) {
    var head = "<p>Risposta finta allo scenario <strong>" + SC + "</strong>. Ho ricevuto " + q.length + " caratteri.</p>";
    if (SC === "proposal") return head + "<p>Propongo una voce di piano di prova.</p>" + block({ pilastri: "proposte-v1", operazioni: [
      { strumento: "aggiungi_voce_piano", argomenti: { mese: "2026-12", etichetta: "Voce di prova (pagina finta)", tipo: "EXPENSE", stato: "SCENARIO", importo_eur: "12.34" } }] });
    if (SC === "batch") return head + block({ pilastri: "proposte-v1", operazioni: [
      { strumento: "aggiungi_voce_piano", argomenti: { mese: "2026-12", etichetta: "Prova A", tipo: "EXPENSE", stato: "SCENARIO", importo_eur: "1.00" } },
      { strumento: "registra_movimento", argomenti: { pilastro: 4, tipo: "PERSONAL_CONTRIBUTION", data: "2026-10-01", importo_eur: "10.00" } }] });
    if (SC === "hostile") return head + block({ pilastri: "proposte-v1", extra: "x", operazioni: [
      { strumento: "ripristina_archivio", argomenti: {} },
      { strumento: "elenca_movimenti", argomenti: {} },
      { strumento: "aggiungi_voce_piano", argomenti: { mese: "2026-12", etichetta: "x", tipo: "EXPENSE", stato: "SCENARIO", importo_eur: "1.00", chiave_ostile: 1 } }] });
    if (SC === "sources") return head + "<p>Secondo una fonte esterna <a class=\"rounded-full\" href=\"https://example.com/fonte\">example.com</a> va tutto bene.</p>" +
      block({ pilastri: "proposte-v1", operazioni: [{ strumento: "aggiungi_voce_piano", argomenti: { mese: "2026-12", etichetta: "Da fonte esterna", tipo: "EXPENSE", stato: "SCENARIO", importo_eur: "5.00" } }] });
    return head + "<p>Nessuna modifica da proporre.</p>";
  }
  function ask(q) {
    userMsg(q);
    var shell = assistantShell();
    setBusy(true);
    var full = reply(q), steps = SC === "slow" ? 80 : 8, i = 0;
    var t = setInterval(function () {
      i++;
      shell.md.innerHTML = i < steps ? "<p>" + "…".repeat(i) + "</p>" : full;
      if (i >= steps) {
        clearInterval(t);
        if (UI === "claude") shell.box.setAttribute("data-is-streaming", "false"); else shell.md.className = "markdown";
        setBusy(false);
      }
    }, 250);
  }
})();
</script>
</body>
</html>
""".trimIndent()
}
