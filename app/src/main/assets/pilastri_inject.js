// Pilastri: reads the conversation on claude.ai / chatgpt.com and types the user's question when the user taps "Invia".
// Adapted from the user's own Cattedra app (registro_elettronico/nextapp/app/src/main/assets/cattedra_inject.js: DOM-to-
// markdown reading, Claude selectors, send via the page's own editor and button). Same author; Cattedra's repository is
// CC BY-NC-SA 4.0 and its next-app licence is unresolved (see COUNCIL.md). The ChatGPT selectors are new and unverified.
//
// Consumer terms: scripting these consumer pages may conflict with the providers' terms on automated access; the API and
// the documented routine endpoint are the supported routes. This script never sends by itself: __pilSend runs only
// after the user's tap in the app. It never touches sign-in forms, CAPTCHAs or bot checks: it only reports them.
(function () {
  if (window.__pilInstalled) return;
  window.__pilInstalled = true;

  function post(o) { try { if (window.__pilastri && window.__pilastri.postMessage) window.__pilastri.postMessage(JSON.stringify(o)); } catch (e) {} }

  // In this view the page's vh/dvh units can be 0: give it the real height (as Cattedra does for claude.ai).
  function vv() {
    var h = (window.visualViewport ? window.visualViewport.height : window.innerHeight) || window.innerHeight;
    if (document.documentElement && h) document.documentElement.style.setProperty("--app-visual-viewport-height", h + "px");
  }
  vv();
  window.addEventListener("load", vv);
  window.addEventListener("resize", vv);
  if (window.visualViewport) window.visualViewport.addEventListener("resize", vv);

  /* ---- which site ---- */
  function site() {
    var h = location.hostname;
    if (h === "claude.ai" || /\.claude\.ai$/.test(h)) return "claude";
    if (h === "chatgpt.com" || /\.chatgpt\.com$/.test(h)) return "chatgpt";
    // the debug mock page imitates one of the two
    if (document.querySelector('[data-testid="chat-input"]')) return "claude";
    if (document.querySelector("#prompt-textarea")) return "chatgpt";
    return window.__pilMockUi || "unknown";
  }

  /* ---- DOM -> text with simple markdown (from cattedra_inject.js) ---- */
  function cls(e) { return e.className && e.className.toString ? e.className.toString() : ""; }
  var BLOCKS = "p, pre, ul, ol, table, blockquote, h1, h2, h3, h4, h5, h6";

  // Interface pieces that are not answer text: buttons, icons, toggles, hidden or screen-reader-only elements.
  function chrome(e) {
    var t = e.tagName;
    if (t === "BUTTON" || t === "STYLE" || t === "SCRIPT" || t === "SVG" || t === "NOSCRIPT" || t === "TEXTAREA" || t === "INPUT" || t === "IMG" || t === "CANVAS" || t === "VIDEO") return true;
    if (e.getAttribute("aria-hidden") === "true" || e.hasAttribute("hidden") || e.hasAttribute("inert")) return true;
    if (/(^|\s)sr-only(\s|$)/.test(cls(e))) return true;
    var role = e.getAttribute("role");
    if (role === "button" || role === "tab" || role === "progressbar" || role === "menu" || role === "menuitem" || role === "tooltip") return true;
    if (e.hasAttribute("aria-expanded") || e.hasAttribute("aria-haspopup") || e.hasAttribute("aria-controls")) return true;
    var id = e.getAttribute("data-testid") || "";
    if (/thinking|reasoning|thought|tool|artifact|citation|source|feedback|action|copy|status/i.test(id)) return true;
    if ((e.hasAttribute("data-find-omitted") || e.hasAttribute("data-step-key") || e.hasAttribute("aria-live") || role === "status") && !e.querySelector(BLOCKS)) return true;
    return false;
  }
  function visible(e) {
    if (!e.getClientRects || e.getClientRects().length === 0) return false;
    var cs = window.getComputedStyle(e);
    return !(cs.visibility === "hidden" || cs.display === "none" || cs.opacity === "0");
  }
  function clipped(e, root) {
    for (var a = e; a && a !== root; a = a.parentElement) {
      var r = a.getBoundingClientRect();
      if (r.height < 1 && r.width > 0) return true;
    }
    return false;
  }
  function tableMd(t) {
    var rows = Array.prototype.map.call(t.querySelectorAll("tr"), function (tr) {
      return Array.prototype.map.call(tr.children, function (c) { return ser(c).replace(/\|/g, "/").replace(/\s+/g, " ").trim(); });
    }).filter(function (r) { return r.length; });
    if (!rows.length) return "";
    var width = rows.reduce(function (m, r) { return Math.max(m, r.length); }, 0);
    var out = rows.map(function (r) { while (r.length < width) r.push(""); return "| " + r.join(" | ") + " |"; });
    out.splice(1, 0, "|" + new Array(width + 1).join(" --- |"));
    return out.join("\n");
  }
  var srcs = null;
  function linkUrl(a) {
    var h = a.getAttribute("href");
    if (!h) return null;
    var u;
    try { u = new URL(h, location.href); } catch (e) { return null; }
    if (u.protocol !== "https:" && u.protocol !== "http:") return null;
    return u.href.replace(/ /g, "%20").replace(/\(/g, "%28").replace(/\)/g, "%29");
  }
  function linkMd(a, label) {
    var url = linkUrl(a);
    label = label.replace(/\s+/g, " ").trim();
    if (!url) return label;
    // web-search source pills: collected, appended as a "Fonti:" line, and they mark the reply as external
    if (srcs && (/(^|\s)rounded-full(\s|$)|group\/tag|citation/.test(cls(a)) || a.getAttribute("target") === "_blank" && !label)) {
      var name = label.replace(/\s*\+\d+$/, "").replace(/[\[\]]/g, "").slice(0, 60) || url;
      if (!srcs.some(function (s) { return s.url === url; })) srcs.push({ label: name, url: url });
      return "";
    }
    if (!label || label === url || label === a.getAttribute("href")) return label || url;
    return "[" + label.replace(/[\[\]]/g, "") + "](" + url + ")";
  }
  function ser(n) {
    if (n.nodeType === 3) return n.nodeValue.replace(/\s+/g, " ");
    if (n.nodeType !== 1 || chrome(n)) return "";
    var kids = function () { return Array.prototype.map.call(n.childNodes, ser).join(""); };
    switch (n.tagName) {
      case "A": return linkMd(n, kids());
      case "STRONG": case "B": return "**" + kids() + "**";
      case "EM": case "I": return "*" + kids() + "*";
      case "CODE": return "`" + kids() + "`";
      // only the code itself: ChatGPT's code header holds the language label and a "Copy code" button
      case "PRE": var code = n.querySelector("code"); return "\n```\n" + (code ? code.textContent : n.innerText).replace(/\n+$/, "") + "\n```\n";
      case "BR": return "\n";
      case "H1": case "H2": case "H3": case "H4": case "H5": case "H6": return "\n# " + kids().trim() + "\n\n";
      case "LI": return "- " + kids().trim() + "\n";
      case "UL": case "OL": return "\n" + kids() + "\n";
      case "P": return kids().trim() + "\n\n";
      case "TABLE": return "\n" + tableMd(n) + "\n\n";
      case "THEAD": case "TBODY": case "TFOOT": return kids();
      case "TR": return Array.prototype.map.call(n.children, function (c) { return ser(c).trim(); }).filter(function (x) { return x; }).join(" · ") + "\n";
      case "BLOCKQUOTE": return "\n" + kids().trim().split("\n").map(function (l) { return "> " + l; }).join("\n") + "\n\n";
      case "DIV": case "SECTION": case "ARTICLE":
        var pre = n.querySelector("pre");
        if (pre && !n.querySelector("p, ul, ol, h1, h2, h3, h4, h5, h6, table, blockquote") && (n.innerText || "").length - (pre.innerText || "").length < 48) return ser(pre);
        return kids() + "\n";
      default: return kids();
    }
  }
  function clean(s) { return s.replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim(); }
  function outermost(list) {
    var a = Array.prototype.slice.call(list);
    return a.filter(function (e) { return !a.some(function (o) { return o !== e && o.contains(e); }); });
  }
  function usable(e, root) {
    if (!visible(e)) return false;
    for (var a = e; a && a !== root; a = a.parentElement) { if (chrome(a)) return false; }
    return !clipped(e, root);
  }
  // Signs that the reply used outside material (web search, tools, connectors). ADVISORY ONLY: these selectors are not
  // verified on the live claude.ai / chatgpt.com pages, so the app treats EVERY account-page reply as tainted and uses
  // this flag only as extra detail on the card (AiWebTurns.ACCOUNT_PAGE_REASON says when that may be relaxed).
  var EXT = '[data-testid*="tool"], [data-testid*="citation"], [data-testid*="source"], [data-testid*="search"], [class*="citation"], [data-testid*="connector"]';
  function read(msg, roots, streamingNow) {
    var found, t;
    srcs = [];
    try {
      t = roots.length ? clean(roots.map(ser).join("\n\n")) : (streamingNow ? "" : clean(ser(msg)));
    } finally { found = srcs; srcs = null; }
    if (found.length) t += "\n\nFonti: " + found.map(function (s) { return "[" + s.label + "](" + s.url + ")"; }).join(" · ");
    return { t: t, x: found.length > 0 || !!msg.querySelector(EXT) };
  }

  /* ---- site adapters ---- */
  var A = {
    claude: {
      rows: function () { return document.querySelectorAll('[data-testid="user-message"],[data-testid="assistant-message"]'); },
      isUser: function (e) { return e.getAttribute("data-testid") === "user-message"; },
      answer: function (msg) {
        var pick = function (sel) { return outermost(msg.querySelectorAll(sel)).filter(function (e) { return usable(e, msg); }); };
        var roots = pick(".standard-markdown, .progressive-markdown, [data-perf-reply-text]");
        if (!roots.length) roots = pick("[class*='markdown']");
        if (!roots.length) roots = pick(BLOCKS);
        return read(msg, roots, msg.getAttribute("data-is-streaming") === "true");
      },
      editor: function () { return document.querySelector('[data-testid="chat-input"]'); },
      sendButton: function () { return document.querySelector('[data-testid="chat-input-send"]'); },
      busy: function (n) { return n > 0 && !document.querySelector('[data-testid="chat-input-send"]'); },
      loggedOut: function () { return false; },
      stop: function () {
        var b = Array.prototype.filter.call(document.querySelectorAll("button"), function (x) { return /interrompi|stop/i.test(x.getAttribute("aria-label") || ""); })[0];
        if (b) b.click();
      },
      newChat: function () { location.href = "/new"; }
    },
    chatgpt: {
      rows: function () { return document.querySelectorAll('[data-message-author-role="user"],[data-message-author-role="assistant"]'); },
      isUser: function (e) { return e.getAttribute("data-message-author-role") === "user"; },
      answer: function (msg) {
        var roots = outermost(msg.querySelectorAll(".markdown, [class*='markdown']")).filter(function (e) { return usable(e, msg); });
        var streamingNow = !!msg.querySelector(".result-streaming, .streaming-animation") || !!document.querySelector('[data-testid="stop-button"]');
        return read(msg, roots, streamingNow);
      },
      editor: function () { return document.querySelector("#prompt-textarea"); },
      sendButton: function () { return document.querySelector('[data-testid="send-button"], #composer-submit-button'); },
      busy: function () { return !!document.querySelector('[data-testid="stop-button"], .result-streaming'); },
      loggedOut: function () { return !!document.querySelector('[data-testid="login-button"], [data-testid="welcome-login-button"]'); },
      stop: function () { var b = document.querySelector('[data-testid="stop-button"]'); if (b) b.click(); },
      newChat: function () { location.href = "/"; }
    }
  };
  function adapter() { return A[site()] || null; }

  // A CAPTCHA or bot check: reported to the app, which shows the page to the user. Never interacted with.
  function challenge() {
    if (/just a moment|attention required|verify you are human|un momento|verifica di essere/i.test(document.title || "")) return true;
    return !!document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="hcaptcha.com"], iframe[src*="recaptcha"], iframe[src*="arkoselabs"], #challenge-form, #cf-challenge-running, .cf-turnstile, [data-testid*="captcha"]');
  }

  function snapshot() {
    var a = adapter();
    var out = { v: 1, site: site(), path: location.pathname, ready: false, busy: false, loggedOut: false, challenge: challenge(), nu: 0, na: 0, msgs: [] };
    if (!a) return out;
    var rows = a.rows();
    var msgs = [];
    // every message is counted; only the last four are read (a long conversation stays cheap to observe)
    rows.forEach(function (e, i) {
      var user = a.isUser(e);
      if (user) out.nu++; else out.na++;
      if (i < rows.length - 4) return;
      if (user) { msgs.push({ r: "u", t: clean(ser(e)).slice(0, 400) }); return; }
      var r = a.answer(e);
      msgs.push({ r: "a", t: r.t, x: r.x });
    });
    out.msgs = msgs;
    out.ready = !!a.editor() && !out.challenge;
    out.busy = a.busy(rows.length);
    out.loggedOut = a.loggedOut();
    return out;
  }

  var last = "", timer = null;
  function push() {
    timer = null;
    try { var s = JSON.stringify(snapshot()); if (s !== last) { last = s; post({ snap: JSON.parse(s) }); } } catch (e) {}
  }
  function schedule() { if (!timer) timer = setTimeout(push, 150); }
  new MutationObserver(schedule).observe(document, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ["data-testid", "data-is-streaming", "class", "aria-label", "disabled"] });
  setInterval(function () { push(); post({ beat: 1 }); }, 1000);

  /* ---- commands from the app (only after the user's tap) ---- */
  window.__pilSend = function (text) {
    var a = adapter();
    if (!a) return "nosite";
    if (challenge()) return "challenge";
    var ed = a.editor();
    if (!ed) return "noeditor";
    ed.focus();
    if (ed.tagName === "TEXTAREA") {
      var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set;
      setter.call(ed, text);
      ed.dispatchEvent(new Event("input", { bubbles: true }));
    } else {
      document.execCommand("selectAll");
      document.execCommand("insertText", false, text);
    }
    if (window.__pilSendTimer) clearInterval(window.__pilSendTimer);
    var tries = 0, t = window.__pilSendTimer = setInterval(function () {
      var b = a.sendButton();
      if (b && !b.disabled && b.getAttribute("aria-disabled") !== "true") { clearInterval(t); window.__pilSendTimer = null; b.click(); post({ sent: 1 }); }
      else if (++tries > 200) { clearInterval(t); window.__pilSendTimer = null; post({ sent: 0 }); }
    }, 150);
    return "ok";
  };
  window.__pilNew = function () { var a = adapter(); if (a) a.newChat(); };
  window.__pilStop = function () { var a = adapter(); if (a) a.stop(); };
  window.__pilSnap = function () { return JSON.stringify(snapshot()); };
})();
