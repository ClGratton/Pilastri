package xyz.grtlabs.pilastri.sync.auto

import org.json.JSONObject

/**
 * The sign-in script and its message protocol. Pure Kotlin; [WebLoginDriver] installs it.
 *
 * The script is installed with `WebViewCompat.addDocumentStartJavaScript` for the provider's exact login origins only, and talks
 * to Kotlin through the `WebViewCompat.addWebMessageListener` object [BRIDGE], which the WebView injects only into frames of those
 * same origins. No `addJavascriptInterface`.
 *
 * The script carries NO credentials. It reports what the page shows (a sign-in form step, a 2FA step, a challenge, a sign-in
 * error); Kotlin answers a reported form step with the saved values through the reply proxy of that very frame, and the script fills
 * them (native value setter plus input/change events, so script-driven forms see them) and presses the form's own button. It fills
 * each step at most once per document. It never touches a CAPTCHA or bot check: it only reports "challenge".
 *
 * When "Ricorda l'accesso" is on and the person signs in by hand, the script (told by a `capture` message) sends the typed
 * username/password back at submit time; Kotlin keeps them only after the sign-in has worked.
 *
 * Page to Kotlin: `{"t":"hello"}`, `{"t":"form","step":"username|password|both"}`, `{"t":"sca","kind":"code|push|choice"}`,
 * `{"t":"challenge"}`, `{"t":"none"}`, `{"t":"error"}`, `{"t":"submitted","step":…}`, `{"t":"stale"}`, `{"t":"captured","u":…,"p":…}`.
 * Kotlin to page: `{"t":"fill","step":…,"u":…,"p":…}`, `{"t":"capture","on":true|false}`, `{"t":"rescan"}`.
 */
object LoginAutofill {
    const val BRIDGE = "PilastriLogin"
    const val MAX_MESSAGE_CHARS = 4096

    sealed interface PageMessage {
        data object Hello : PageMessage
        data class Form(val step: LoginStep) : PageMessage
        data class Sca(val kind: ScaKind) : PageMessage
        data object Challenge : PageMessage
        data object Nothing : PageMessage
        data object Error : PageMessage
        data class Submitted(val step: LoginStep) : PageMessage
        data object Stale : PageMessage
        class Captured(val username: String?, val password: String?) : PageMessage {
            override fun toString() = "Captured(***)"
        }
    }

    /** Null for anything malformed, oversized or unknown. */
    fun parse(data: String?): PageMessage? {
        if (data == null || data.length > MAX_MESSAGE_CHARS) return null
        val o = try { JSONObject(data) } catch (_: Exception) { return null }
        return when (o.optString("t")) {
            "hello" -> PageMessage.Hello
            "form" -> LoginStep.of(o.optString("step"))?.let { PageMessage.Form(it) }
            "sca" -> ScaKind.of(o.optString("kind"))?.let { PageMessage.Sca(it) }
            "challenge" -> PageMessage.Challenge
            "none" -> PageMessage.Nothing
            "error" -> PageMessage.Error
            "submitted" -> LoginStep.of(o.optString("step"))?.let { PageMessage.Submitted(it) }
            "stale" -> PageMessage.Stale
            "captured" -> {
                val u = o.optString("u").takeIf { o.has("u") && !o.isNull("u") && it.isNotEmpty() && it.length <= 256 }
                val p = o.optString("p").takeIf { o.has("p") && !o.isNull("p") && it.isNotEmpty() && it.length <= 256 }
                if (u == null && p == null) null else PageMessage.Captured(u, p)
            }
            else -> null
        }
    }

    /** The reply that fills [step]. Contains the secret: post it only through the reply proxy of the reporting frame; never log it. */
    fun fillMessage(step: LoginStep, login: SavedLogin): String = JSONObject().apply {
        put("t", "fill")
        put("step", step.wire)
        if (step != LoginStep.PASSWORD) put("u", login.username)
        if (step != LoginStep.USERNAME) put("p", login.password)
    }.toString()

    fun captureMessage(on: Boolean): String = JSONObject().put("t", "capture").put("on", on).toString()
    fun rescanMessage(): String = """{"t":"rescan"}"""

    /** The document-start script for [rules]. The rules are JSON data; U+2028/2029 are escaped for older JS parsers. */
    fun script(rules: LoginRules): String =
        SCRIPT.replace("__BRIDGE__", BRIDGE).replace("__RULES__", rules.toJson().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))

    // Mirrors LoginMatcher (Kotlin): norm, has, usable, isOtp, passwordScore, usernameScore, isChallenge, scan. Keep them in step.
    private val SCRIPT = """
(function () {
  'use strict';
  if (window.__pilastriLogin) return;
  window.__pilastriLogin = true;
  var B = window.__BRIDGE__;
  if (!B || typeof B.postMessage !== 'function') return;
  var R = __RULES__;
  var TEXTUAL = { text: 1, email: 1, tel: 1, password: 1, number: 1 };
  var last = '', filled = {}, capture = false, errorSent = false, timer = 0, started = Date.now();

  function post(o) { try { B.postMessage(JSON.stringify(o)); } catch (e) {} }
  function norm(s) {
    s = String(s == null ? '' : s);
    try { s = s.normalize('NFD').replace(/[\u0300-\u036f]/g, ''); } catch (e) {}
    return s.replace(/([a-z])([A-Z])/g, '${'$'}1 ${'$'}2').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  }
  function has(hay, list) {
    var h = ' ' + hay + ' ';
    for (var i = 0; i < list.length; i++) if (list[i] && h.indexOf(' ' + list[i] + ' ') >= 0) return true;
    return false;
  }
  function shown(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var s = window.getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.05;
  }
  function labelOf(el) {
    var parts = [], i;
    var a = el.getAttribute('aria-label'); if (a) parts.push(a);
    var lb = el.getAttribute('aria-labelledby');
    if (lb) { var ids = lb.split(/\s+/); for (i = 0; i < ids.length; i++) { var n = document.getElementById(ids[i]); if (n) parts.push(n.textContent || ''); } }
    if (el.labels) for (i = 0; i < el.labels.length; i++) parts.push(el.labels[i].textContent || '');
    var t = el.getAttribute('title'); if (t) parts.push(t);
    return parts.join(' ').slice(0, 300);
  }
  function fields() {
    var forms = Array.prototype.slice.call(document.forms), list = document.querySelectorAll('input'), out = [];
    for (var i = 0; i < list.length && i < 200; i++) {
      var el = list[i], type = (el.getAttribute('type') || 'text').toLowerCase(), ac = el.getAttribute('autocomplete') || '';
      out.push({
        el: el, type: type, ac: ac.toLowerCase().split(/\s+/), max: el.maxLength > 0 ? el.maxLength : null,
        hay: norm([el.name || '', el.id || '', labelOf(el), el.placeholder || '', ac].join(' ')),
        usable: TEXTUAL[type] === 1 && !el.disabled && !el.readOnly && shown(el),
        form: el.form ? forms.indexOf(el.form) : -1
      });
    }
    return out;
  }
  function isOtp(f) { return f.usable && !has(f.hay, R.exclude) && (f.ac.indexOf('one-time-code') >= 0 || has(f.hay, R.otp)); }
  function pwScore(f) {
    if (!f.usable || has(f.hay, R.exclude) || isOtp(f)) return 0;
    if (f.type === 'password' || f.ac.indexOf('current-password') >= 0) return 3;
    if ((f.type === 'tel' || f.type === 'number' || f.type === 'text') && has(f.hay, R.password)) return 2;
    return 0;
  }
  function userScore(f) {
    if (!f.usable || has(f.hay, R.exclude) || isOtp(f) || pwScore(f) > 0) return 0;
    if (f.ac.indexOf('username') >= 0 || f.ac.indexOf('email') >= 0 || f.type === 'email') return 3;
    if (has(f.hay, R.username)) return 2;
    if (f.type === 'text' || f.type === 'tel' || f.type === 'number') return 1;
    return 0;
  }
  function marked(v) {
    v = String(v || '').toLowerCase();
    var i, hit = false;
    for (i = 0; i < R.challengeMarkers.length; i++) if (v.indexOf(R.challengeMarkers[i]) >= 0) hit = true;
    if (!hit) return false;
    for (i = 0; i < R.challengeIgnore.length; i++) if (v.indexOf(R.challengeIgnore[i]) >= 0) return false;
    return true;
  }
  function challenge(text) {
    var i, frames = document.querySelectorAll('iframe');
    for (i = 0; i < frames.length && i < 50; i++) if (shown(frames[i]) && marked(frames[i].src)) return true;
    var els = document.querySelectorAll('[id*="captcha" i],[class*="captcha" i],[name*="captcha" i],[id*="arkose" i],[class*="turnstile" i]');
    for (i = 0; i < els.length && i < 50; i++) {
      var e = els[i];
      if (shown(e) && marked([e.id, typeof e.className === 'string' ? e.className : '', e.getAttribute('name') || ''].join(' '))) return true;
    }
    return has(text, R.challengePhrases);
  }
  function visibleText() { return norm(((document.body && document.body.innerText) || '').slice(0, 20000)); }
  function alertText() {
    var out = [], els = document.querySelectorAll('[role="alert"],[aria-live="assertive"],[aria-live="polite"],[class*="error" i],[class*="notification" i]');
    for (var i = 0; i < els.length && i < 40; i++) if (shown(els[i])) out.push(els[i].innerText || '');
    return norm(out.join(' ').slice(0, 2000));
  }
  function scan() {
    var text = visibleText();
    if (challenge(text)) return { t: 'challenge' };
    var fs = fields(), otp = 0, boxes = 0, pw = -1, pwS = 0, i;
    for (i = 0; i < fs.length; i++) {
      if (isOtp(fs[i])) otp++;
      if (fs[i].usable && fs[i].type !== 'password' && fs[i].max === 1) boxes++;
      var s = pwScore(fs[i]); if (s > pwS) { pwS = s; pw = i; }
    }
    if (otp > 0 || boxes >= 4) return { t: 'sca', kind: 'code' };
    var u = -1, uS = 0;
    for (i = 0; i < fs.length; i++) {
      var us = userScore(fs[i]);
      if (us === 1 && !(pw >= 0 && i < pw && fs[i].form === fs[pw].form)) us = 0;
      if (us > uS) { uS = us; u = i; }
    }
    if (pw >= 0) return { t: 'form', step: u >= 0 ? 'both' : 'password', u: u, p: pw, fs: fs };
    if (u >= 0 && uS >= 2) return { t: 'form', step: 'username', u: u, p: -1, fs: fs };
    if (has(text, R.scaPush)) return { t: 'sca', kind: 'push' };
    if (has(text, R.scaChoice)) return { t: 'sca', kind: 'choice' };
    if (has(text, R.scaCode)) return { t: 'sca', kind: 'code' };
    return { t: 'none' };
  }
  var trusted = [];
  // Keeps the provider's own "stay signed in / remember this device" option on. Only a checkbox whose label says so is ticked;
  // the verification itself (code, push, choice of method) is never touched.
  function keepTrust() {
    if (!R.trust || !R.trust.length) return;
    var list = document.querySelectorAll('input[type="checkbox"], [role="checkbox"]');
    for (var i = 0; i < list.length && i < 60; i++) {
      var el = list[i];
      if (trusted.indexOf(el) >= 0) continue;
      var on = el.checked === true || el.getAttribute('aria-checked') === 'true';
      var lab = norm([el.name || '', el.id || '', labelOf(el), el.textContent || '', (el.parentElement && el.parentElement.textContent) || ''].join(' ').slice(0, 300));
      if (!has(lab, R.trust) || has(lab, R.trustExclude)) continue;
      trusted.push(el);
      if (on || el.disabled) continue;
      var target = shown(el) ? el : ((el.labels && el.labels[0]) || el.parentElement || el);
      try { target.click(); } catch (e) {}
      post({ t: 'trust' });
    }
  }
  function report() {
    timer = 0;
    keepTrust();
    var s = scan();
    if (!errorSent && (filled.username || filled.password || filled.both) && has(alertText(), R.errorPhrases)) { errorSent = true; post({ t: 'error' }); }
    var key = s.t + ':' + (s.step || s.kind || '');
    if (key === last) return;
    last = key;
    if (s.t === 'form') post({ t: 'form', step: s.step });
    else if (s.t === 'sca') post({ t: 'sca', kind: s.kind });
    else post({ t: s.t });
  }
  function schedule() { if (!timer) timer = setTimeout(report, 400); }
  function setValue(el, v) {
    try { el.focus(); } catch (e) {}
    var d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    if (d && d.set) d.set.call(el, v); else el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  function buttonText(c) { return norm(c.innerText || c.value || c.getAttribute('aria-label') || ''); }
  function buttonIn(scope) {
    var cands = scope.querySelectorAll('button, input[type="submit"], input[type="button"], [role="button"]'), best = null, bestS = 0;
    for (var i = 0; i < cands.length && i < 100; i++) {
      var c = cands[i];
      if (c.disabled || !shown(c)) continue;
      var t = buttonText(c), type = (c.getAttribute('type') || '').toLowerCase(), s = 0;
      if (has(t, R.submit) || has(t, R.next)) s = 3;
      else if (type === 'submit' || (c.tagName === 'BUTTON' && !type && c.form)) s = 2;
      if (has(t, R.avoid)) s = 0;
      if (s > bestS) { bestS = s; best = c; }
    }
    return best;
  }
  function submit(el) {
    var b = (el.form && buttonIn(el.form)) || buttonIn(document);
    if (b) { b.click(); return; }
    if (el.form) { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); return; }
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
  }
  function fill(m) {
    var s = scan();
    if (s.t !== 'form' || s.step !== m.step || filled[m.step]) { post({ t: 'stale' }); return; }
    filled[m.step] = true;
    errorSent = false;
    var target = null;
    if (s.u >= 0 && typeof m.u === 'string') { setValue(s.fs[s.u].el, m.u); target = s.fs[s.u].el; }
    if (s.p >= 0 && typeof m.p === 'string') { setValue(s.fs[s.p].el, m.p); target = s.fs[s.p].el; }
    if (!target) { post({ t: 'stale' }); return; }
    setTimeout(function () { submit(target); post({ t: 'submitted', step: m.step }); }, 250);
  }
  function grab() {
    if (!capture) return;
    var s = scan();
    if (s.t !== 'form') return;
    var u = s.u >= 0 ? s.fs[s.u].el.value : '', p = s.p >= 0 ? s.fs[s.p].el.value : '';
    if (u || p) post({ t: 'captured', u: u || null, p: p || null });
  }
  B.addEventListener('message', function (ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    if (m.t === 'fill') fill(m);
    else if (m.t === 'capture') capture = !!m.on;
    else if (m.t === 'rescan') { last = ''; schedule(); }
  });
  document.addEventListener('submit', grab, true);
  document.addEventListener('click', function (e) {
    var b = e.target && e.target.closest ? e.target.closest('button, input[type="submit"], [role="button"]') : null;
    if (!b) return;
    var t = buttonText(b);
    if (has(t, R.submit) || has(t, R.next) || (b.getAttribute('type') || '').toLowerCase() === 'submit') grab();
  }, true);
  document.addEventListener('keydown', function (e) { if (e.key === 'Enter') grab(); }, true);
  new MutationObserver(schedule).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['style', 'class', 'hidden', 'disabled', 'aria-hidden'] });
  document.addEventListener('DOMContentLoaded', schedule);
  window.addEventListener('load', schedule);
  var tick = setInterval(function () { if (Date.now() - started > 600000) clearInterval(tick); else schedule(); }, 1500);
  post({ t: 'hello' });
  schedule();
})();
""".trimIndent()
}
