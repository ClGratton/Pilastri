package xyz.grtlabs.pilastri.sync.eb

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/*
 * Wave 16, the approved native Enable Banking setup (DESIGN.md "EB native setup"; RESEARCH.md "Enable Banking control panel map
 * (2026-11-09)"). One hidden page on enablebanking.com does what the control panel does, by driving the page's OWN code:
 * FirebaseUI's sign-in form, then same-origin calls with the page's own Firebase ID token. The token never leaves the page:
 * every script returns only results (states, the app id, bank names, the bank's address, error codes). Nothing here logs.
 * Never used: the organisations' "Request activation" (PUT /api/applications).
 */

/** Why the hidden page is shown to the person; null keeps it hidden. */
enum class EbSheet { CHALLENGE, MFA, BANK, FALLBACK }

/** The hidden page. The real one is [WebViewEbPage]; tests use a fake that scripts the JS results. */
interface EbPage {
    suspend fun load(url: String)
    /** Runs [body], the body of an async JS function with `ARGS` bound to [args], inside the page. Null: no answer in time. */
    suspend fun run(body: String, args: JSONObject = JSONObject()): JSONObject?
    /** The page's current address (the target of [load] as soon as it is called). */
    fun url(): String?
    /** Shows the page as a sheet titled [title] for [sheet], or hides it (null). */
    fun show(sheet: EbSheet?, title: String? = null)
    /** Keeps the panel session (cookies, storage) after a sign-in: AGENTS "A sign-in stays". */
    fun persist() {}
    /** What the page shows now, for the debug log only. */
    suspend fun diagnose(): JSONObject? = null
}

/**
 * The Production registration fields, in one place (coordinator, 2026-11-09). gdpr_email is not here: it is the e-mail typed on
 * EB-1, the same as the Enable Banking account, and it goes into the page only, with the sign-in.
 */
class EbProductionConfig(val name: String, val description: String, val privacyUrl: String, val termsUrl: String) {
    val complete: Boolean get() = listOf(name, description, privacyUrl, termsUrl).all { it.isNotBlank() }

    /** The POST /api/applications body without gdpr_email (the page adds `ARGS.email`). */
    fun payload(certificate: String, redirectUrl: String): JSONObject = JSONObject()
        .put("broker_origin", "").put("name", name).put("certificate", certificate).put("environment", "PRODUCTION")
        .put("redirect_urls", JSONArray().put(redirectUrl)).put("description", description)
        .put("privacy_url", privacyUrl).put("terms_url", termsUrl)

    companion object {
        val PILASTRI = EbProductionConfig(
            name = "Pilastri",
            description = "Pilastri — app personale di finanza, uso privato del titolare dei conti",
            privacyUrl = "https://pilastri.grtlabs.xyz/privacy.html",
            termsUrl = "https://pilastri.grtlabs.xyz/termini.html",
        )
    }
}

/** What went wrong, mapped one to one onto the board's failure table by the setup flow. */
enum class EbPanelFailure { INTERNAL, UNREACHABLE, HTTP_ERROR, RATE_LIMIT, LINK_INVALID, EXPIRED, REFUSED, NOT_LINKED, NO_ACCOUNTS, PAGE_CHANGED, CONFIG_MISSING }

sealed interface EbPanelResult<out T> {
    data class Ok<T>(val value: T) : EbPanelResult<T>
    data class Failed(val failure: EbPanelFailure, val reason: String? = null, val status: Int? = null) : EbPanelResult<Nothing>
}

class EbRegistration(val appId: String, val active: Boolean, val reused: Boolean) {
    override fun toString() = "EbRegistration(***, active=$active, reused=$reused)"
}

/** A bank as the panel's linking picker lists it. */
data class EbPanelBank(val name: String, val psuTypes: List<String>)

/** The application's linking state: [linked] counts the whitelisted accounts. */
/** [here]: accounts of the application that mention the asked bank; [keys]: key names of the first account (names only, for the log). */
data class EbAppStatus(val active: Boolean, val linked: Int, val here: Int = 0, val keys: List<String> = emptyList())

/** Pure rules of the panel: addresses, the bank's return, and what may be shown of an error. */
object EbPanelRules {
    const val ORIGIN = "https://enablebanking.com"
    const val SIGN_IN = "$ORIGIN/sign-in/?next=/cp/applications"
    const val PANEL = "$ORIGIN/cp/applications"
    /** The linking return address the panel itself sends (RESEARCH, link_accounts). */
    const val LINK_REDIRECT = "$ORIGIN/api/auth_redirect"

    private fun uri(url: String?): URI? = try { url?.let(::URI) } catch (_: Exception) { null }
    fun isEb(url: String?): Boolean = uri(url)?.host?.lowercase()?.let { it == "enablebanking.com" || it.endsWith(".enablebanking.com") } == true
    fun onPanel(url: String?): Boolean = isEb(url) && (uri(url)?.path ?: "").startsWith("/cp")

    /** The magic link: https, enablebanking.com, Firebase's action handler in sign-in mode. */
    fun isSignInLink(url: String?): Boolean {
        val u = uri(url) ?: return false
        return u.scheme == "https" && isEb(url) && u.path == "/__/auth/action" && (u.rawQuery ?: "").split('&').any { it == "mode=signIn" }
    }

    /**
     * The bank's return to the panel: true linked, false `#error=…` (accounts_linking_failure or cancelled), null still away.
     * [left] is true once the page has been away from enablebanking.com, so the panel the linking started from never counts.
     */
    fun bankReturn(url: String?, left: Boolean): Boolean? {
        if (!left || !onPanel(url)) return null
        val u = uri(url) ?: return null
        val tail = listOfNotNull(u.rawFragment, u.rawQuery).joinToString("&")
        return tail.split('&').none { it.startsWith("error=") }
    }

    private val EMAIL = Regex("[^\\s@]+@[^\\s@]+")
    private val LONG = Regex("[A-Za-z0-9_\\-.]{32,}")

    /** The "<motivo>" of a refusal: one short line, with anything shaped like an e-mail or a token removed. */
    fun shortReason(raw: String?): String? {
        val t = raw?.replace(EMAIL, "…")?.replace(LONG, "…")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return t.take(160).ifEmpty { null }
    }

    /** "n•••@esempio.it" for EB-2: the first letter and the domain only. */
    fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at < 1) return "•••"
        return email.first() + "•••" + email.substring(at)
    }
}

/**
 * The scripts run inside the page. Each returns a small object; none returns the token or reads it out of the page.
 * Selectors come from the 2026-11-09 snapshot and bundle (FirebaseUI v6, Vue 2 store at `#app.__vue__.$store`); a selector
 * that breaks shows up as [EbPanelFailure.PAGE_CHANGED] and the "Apri la pagina" fallback.
 */
object EbPanelScripts {
    /**
     * The page state: in, form, sent, confirm, captcha, mfa, rate, invalid, loading. Only the state leaves the page.
     * "in" is the panel with a user, and also the sign-in page of a session that is still there: it has no sign-in form and
     * says "You are already signed in as …" (Fold run 2026-11-10 13:47; checked by `tools/eb-state-check.mjs`).
     */
    const val STATE = """
var R=(function(){
var q=function(s){return document.querySelector(s)};
var app=q('#app'), st=app&&app.__vue__&&app.__vue__.${'$'}store;
if(location.pathname.indexOf('/cp')===0&&((st&&st.getters&&st.getters.user)||(q('.navbar-name')&&!q('#firebaseui-auth-container input[type=email]')))) return {st:'in'};
var t=(document.body&&document.body.innerText)||'';
if(location.pathname.indexOf('/sign-in')===0&&!q('#firebaseui-auth-container')&&(q('.navbar-name')||/already signed in/i.test(t))) return {st:'in'};
var ch=[].slice.call(document.querySelectorAll('iframe[src*="recaptcha"][src*="bframe"]')).some(function(f){var r=f.getBoundingClientRect();var c=f.closest('div[style]');return r.height>100&&(!c||getComputedStyle(c).visibility!=='hidden')});
if(ch) return {st:'captcha'};
if(/too many|troppi|unusual activity|trouble getting email/i.test(t)&&!q('.firebaseui-id-page-email-link-sign-in-sent')) return {st:'rate'};
if(/expired|already been used|invalid action code|new device or browser|same device/i.test(t)) return {st:'invalid'};
if(q('input[autocomplete="one-time-code"],input[name="verificationCode"],.firebaseui-id-page-mfa-verify,.firebaseui-id-page-phone-sign-in-finish')) return {st:'mfa'};
if(q('.firebaseui-id-page-email-link-sign-in-confirmation')) return {st:'confirm'};
if(q('.firebaseui-id-page-email-link-sign-in-sent')||/sign-in email sent/i.test(t)) return {st:'sent'};
if(q('#firebaseui-auth-container input[type=email]')) return {st:'form'};
return {st:'loading'};
})();
R.p=location.pathname; return R;
"""

    /** Debug log only: address (path and parameter names), what is present, the visible buttons and the start of the page text. */
    const val DIAG = """
var q=function(s){return document.querySelector(s)};
var app=q('#app'), st=app&&app.__vue__&&app.__vue__.${'$'}store;
var vis=function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0};
var btn=[].slice.call(document.querySelectorAll('button,a.btn,input[type=submit],[role=button]')).filter(vis).slice(0,14).map(function(b){return String(b.innerText||b.value||b.getAttribute('aria-label')||'').trim().slice(0,40)});
var inputs=[].slice.call(document.querySelectorAll('input,textarea,select')).filter(vis).slice(0,12).map(function(i){return (i.tagName+':'+(i.type||'')+':'+(i.name||i.id||'')).slice(0,50)});
var qs=location.search.replace(/^\?/,'').split('&').filter(Boolean).map(function(p){var k=p.split('=');return k[0]+'#'+(k[1]||'').length});
var txt=((document.body&&document.body.innerText)||'').replace(/\s+/g,' ').slice(0,300);
var errs=[].slice.call(document.querySelectorAll('.alert,.error,.firebaseui-error,.invalid-feedback,[role=alert]')).filter(vis).slice(0,4).map(function(e){return String(e.innerText||'').trim().slice(0,120)});
return {path:location.pathname,query:qs.join('&'),title:document.title.slice(0,60),app:!!app,vueStore:!!st,userGetter:!!(st&&st.getters&&st.getters.user),
  navbarName:!!q('.navbar-name'),fuiContainer:!!q('#firebaseui-auth-container'),emailInput:!!q('#firebaseui-auth-container input[type=email]'),fuiSent:!!q('.firebaseui-id-page-email-link-sign-in-sent'),
  fuiConfirm:!!q('.firebaseui-id-page-email-link-sign-in-confirmation'),recaptchaFrame:!!q('iframe[src*="recaptcha"]'),buttons:btn,fields:inputs,errors:errs,text:txt};
"""

    /** Fills FirebaseUI's e-mail field (sign-in form or the confirmation page) and presses its own submit. */
    const val SUBMIT_EMAIL = """
var e=document.querySelector('#firebaseui-auth-container input[type=email]'); if(!e) return {st:'missing'};
Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,ARGS.email);
e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
var b=document.querySelector('#firebaseui-auth-container .firebaseui-id-submit,#firebaseui-auth-container button[type=submit]'); if(!b) return {st:'missing'};
b.click(); return {st:'submitted'};
"""

    /** EB's own Terms and Privacy links on the sign-in form (FirebaseUI's tos/pp links): public addresses only. */
    const val LEGAL_LINKS = """
var a=function(s){var e=document.querySelector(s);return e&&e.href?e.href:null};
return {terms:a('.firebaseui-tos-link'),privacy:a('.firebaseui-pp-link')};
"""

    /** Runs [body] so that an exception of OUR script comes back as `code=script` with the phase `W` (never as the provider's answer). */
    private fun guarded(body: String): String =
        "var W='token';try{" + body + "\n}catch(e){ if(e&&e.net) return {code:'net'}; return {code:'script',where:W,msg:String(e&&e.message||e).slice(0,160)}; }"

    /** Refreshes the page's own token through its store and keeps it in a local variable: `T` and `H` for the calls below. */
    private const val TOKEN = """
var app=document.querySelector('#app'), s=app&&app.__vue__&&app.__vue__.${'$'}store; if(!s) return {code:'page'};
var sr=null; try{ sr=await s.dispatch('getIdTokenResult', true); }catch(x){ return {code:'auth'}; }
var it=s.getters.idToken||s.state.idToken, ST=it&&(typeof it==='string'?it:it.token), KT=sr&&sr.token;
var ok3=function(v){return typeof v==='string'&&v.split('.').length===3};
var T=ok3(KT)?KT:ST, tsrc=ok3(KT)?'sdk':'store';
var tshape={src:tsrc,type:typeof T,len:String(T||'').length,dots:String(T||'').split('.').length-1,same:KT===ST};
if(!ok3(T)) return {code:'net',tshape:tshape,tbad:true};
var H={'Authorization':'Bearer '+T,'Accept':'application/json, text/plain, */*'};
var bad=function(r){return r.status===401||r.status===403?{code:'auth'}:null};
var ct=function(r){return String(r.headers.get('content-type')||'').slice(0,40)};
var F=async function(u,o){try{return await fetch(u,o)}catch(x){throw {net:1}}};
var shapeOf=function(b,tx){var o=b!==null&&typeof b==='object'; return {type:b===null?'null':typeof b,isArray:Array.isArray(b),keys:o?Object.keys(b).slice(0,8).map(function(k){return String(k).slice(0,24)}).join(','):'',length:Array.isArray(b)?b.length:-1,text:String(tx||'').length};};
var apps=async function(){var r,n=0; W='list'; for(;;){ r=await F('/api/applications',{headers:H}); if(bad(r)) return bad(r);
  if((r.status===429||r.status>=500)&&n++<1){ await new Promise(function(f){setTimeout(f,1500)}); continue; } break; }
  if(r.status===404) return {list:[],lh:404,lct:ct(r),tshape:tshape};
  if(!r.ok){ var tx=await bodyOf(r); return {code:'http',http:r.status,ct:ct(r),body:tx,reason:why(tx),tshape:tshape}; }
  W='parse-list'; var lx=''; try{ lx=await r.text(); }catch(x){} var b=null; try{ b=JSON.parse(lx); }catch(x){}
  var L=Array.isArray(b)?b:(b&&typeof b==='object'?(Array.isArray(b.applications)?b.applications:(Array.isArray(b.data)?b.data:null)):null);
  if(!L) return {code:'script',where:'parse-list',msg:'list shape not recognised',shape:shapeOf(b,lx),tshape:tshape};
  return {list:L,lh:r.status,lct:ct(r),tshape:tshape};};
var bodyOf=async function(r){try{return (await r.text()).slice(0,2000)}catch(x){return ''}};
var why=function(t){var j=null; try{j=JSON.parse(t)}catch(x){} if(!j||typeof j!=='object') return '';
  var e=j.error, m=(e&&typeof e==='object'?(e.message||e.detail):e)||j.message||j.detail||(Array.isArray(j.errors)&&j.errors[0])||(Array.isArray(j.detail)&&j.detail[0])||'';
  if(m&&typeof m==='object') m=m.msg||m.message||''; return String(m||'').slice(0,160);};
var count=function(w){return Array.isArray(w)?w.length:(w&&typeof w==='object'?Object.keys(w).length:0)};
"""

    /** Reuses the stored application when it is in the account's list; otherwise registers PRODUCTION with the payload. */
    val REGISTER = guarded(TOKEN + """
var l=await apps();
if(l.code==='http'&&l.http===400&&/^Cannot read properties of undefined/.test(String(l.reason||''))){
  var g=s.getters||{}, sx=s.state||{}, va=g.applications||sx.applications, rows=document.querySelectorAll('a[href*="/cp/applications/"]').length;
  if(!ARGS.appId&&(va===undefined||va===null||(Array.isArray(va)&&va.length===0))&&rows===0) l={list:[],lh:400,lunavail:true,tshape:tshape};
}
if(!l.list) return l;
if(ARGS.appId){ var k=l.list.filter(function(a){return a&&a.kid===ARGS.appId})[0]; if(k) return {appId:k.kid,active:!!k.active,reused:true,lh:l.lh,tshape:tshape}; }
W='create'; var p=ARGS.payload; p.gdpr_email=ARGS.email;
var r=await F('/api/applications',{method:'POST',headers:Object.assign({'Content-Type':'application/json'},H),body:JSON.stringify(p)});
if(bad(r)) return bad(r); var tx=await bodyOf(r); W='parse-create'; var b=null; try{ b=JSON.parse(tx); }catch(x){}
if(r.ok&&!(b&&b.app_id)) return {code:'script',where:'parse-create',msg:'create answer without app_id',shape:shapeOf(b,tx),tshape:tshape,lunavail:!!l.lunavail};
if(!r.ok){ var w=why(tx); if(!w||r.status<400||r.status>=500) return {code:'http',http:r.status,ct:ct(r),lh:l.lh,lunavail:!!l.lunavail,body:tx}; return {code:'refused',reason:w,http:r.status,ct:ct(r),lh:l.lh,lunavail:!!l.lunavail,body:tx}; }
try{ await s.dispatch('getIdTokenResult', true); await s.dispatch('getApplications'); }catch(x){}
return {appId:b.app_id,active:false,reused:false,http:r.status,ct:ct(r),lh:l.lh,lunavail:!!l.lunavail,tshape:tshape};
""")

    /** The linking picker's banks for one country: name and usage types only. */
    val ASPSPS = guarded(TOKEN + """
var r=await fetch('/api/aspsps',{headers:H}); if(bad(r)) return bad(r); if(!r.ok){ var tx=await bodyOf(r); return {code:'http',http:r.status,ct:ct(r),body:tx}; }
var b=await r.json(), a=Array.isArray(b)?b:(b.aspsps||[]);
return {banks:a.filter(function(x){return x.country===ARGS.country}).map(function(x){return {name:x.name,psu:x.psu_types||[]}})};
""")

    /**
     * The application's state: active, how many accounts are whitelisted, how many of them mention ARGS.bank (case-insensitive,
     * count only) and the key names of the first one. A missing `whitelisted_accounts` is zero, never an error.
     */
    val STATUS = guarded(TOKEN + """
var l=await apps(); if(!l.list) return l;
var k=l.list.filter(function(a){return a.kid===ARGS.appId})[0]; if(!k) return {code:'refused',reason:'app'};
var w=k.whitelisted_accounts, arr=Array.isArray(w)?w:(w&&typeof w==='object'?Object.keys(w).map(function(x){return w[x]}):[]);
var nm=String(ARGS.bank||'').toLowerCase(), here=0;
if(nm) arr.forEach(function(x){ try{ if(JSON.stringify(x).toLowerCase().indexOf(nm)>=0) here++; }catch(e){} });
var f=arr[0], keys=(f&&typeof f==='object')?Object.keys(f).filter(function(x){return /^[A-Za-z0-9_]{1,40}$/.test(x)}).slice(0,30):[];
return {active:!!k.active,linked:count(w),here:here,keys:keys};
""")

    /** "Activate by linking accounts": the bank authorisation address. Linking an inactive app activates it, restricted. */
    val LINK = guarded(TOKEN + """
var f=new URLSearchParams({country:ARGS.country,aspsp:ARGS.aspsp,appId:ARGS.appId,psuType:ARGS.psuType,redirectUrl:ARGS.redirect});
var r=await fetch('/api/link_accounts',{method:'POST',headers:Object.assign({'Content-Type':'application/x-www-form-urlencoded'},H),body:f.toString()});
if(bad(r)) return bad(r); var b=null; try{ b=await r.json(); }catch(x){}
if(!r.ok||!b||!b.url) return {code:'refused',reason:String((b&&(b.message||b.error||b.detail))||('HTTP '+r.status))};
return {url:b.url};
""")
}

/**
 * Drives the hidden page through sign-in, registration and linking. Every wait has a bound except the person's own steps
 * (a challenge, an MFA code, the bank), where the page is shown and the clock does not run.
 */
class EbPanel(
    private val page: EbPage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val callBoundMs: Long = CALL_BOUND_MS,
) {
    companion object {
        const val FORM_TIMEOUT_MS = 20_000L
        const val SIGNED_IN_TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
        /** The bound of one call into the page (a script with its network calls), and of one state or snapshot read. */
        const val CALL_BOUND_MS = 25_000L
        const val STATE_BOUND_MS = 4_000L
        const val COUNTRY = "IT"
        private val TERMINAL = setOf("in", "rate", "invalid")
    }

    /** One line per step under `Pilastri.EB` in every build: step, page path (no query), outcome, duration. Never a value. */
    private fun log(step: String, outcome: String, since: Long) {
        EbLog.line(EbLog.EB, "$step outcome=$outcome ms=${clock() - since}")
    }

    private fun failOf(st: String): EbPanelResult.Failed = EbPanelResult.Failed(when (st) {
        "rate" -> EbPanelFailure.RATE_LIMIT
        "invalid" -> EbPanelFailure.LINK_INVALID
        "timeout" -> EbPanelFailure.UNREACHABLE
        else -> EbPanelFailure.PAGE_CHANGED
    })

    /** The debug log's snapshot of the page, in `Pilastri.EB`. */
    private suspend fun snapshot(step: String, why: String) {
        val d = try { kotlinx.coroutines.withTimeoutOrNull(minOf(callBoundMs, STATE_BOUND_MS)) { page.diagnose() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        EbLog.line(EbLog.EB, "$step snapshot($why) " + EbLog.json(d))
    }

    /**
     * One call into the page with its own bound (G-16). A call that never answers counts as "no answer", so the deadline of the
     * loop around it holds: a bound that is checked only between calls is not a bound.
     */
    private suspend fun pageRun(what: String, boundMs: Long, body: String, args: JSONObject = JSONObject()): JSONObject? {
        var answered = false
        val r = kotlinx.coroutines.withTimeoutOrNull(boundMs) { page.run(body, args).also { answered = true } }
        if (!answered) EbLog.line(EbLog.EB, "page call $what unanswered ms=$boundMs")
        return r
    }

    /** A script run with its name, its argument names and its whole result in the log (secrets by length only). */
    private suspend fun run(name: String, body: String, args: JSONObject = JSONObject()): JSONObject? {
        val t0 = clock()
        val r = pageRun(name, callBoundMs, body, args)
        EbLog.line(EbLog.EB, "script $name args=${args.keys().asSequence().toList()} result=${EbLog.json(r)} ms=${clock() - t0}")
        return r
    }

    /** Polls the page until [done] accepts its state, a terminal state, or the bound. A challenge or MFA shows the page meanwhile. */
    private suspend fun awaitState(timeoutMs: Long, done: (String) -> Boolean, step: String = "await", panelOnly: Boolean = false): String {
        val began = clock()
        var last = ""
        val r = try {
            awaitStateInner(timeoutMs, done, panelOnly) { st -> if (st != last) { last = st; log("$step state", st, began); snapshot(step, "state=$st") } }
        } catch (e: kotlinx.coroutines.CancellationException) { log("$step cancelled", "last=$last", began); throw e }
        log("$step end", r, began)
        if (r !in setOf("in", "form", "sent", "confirm")) snapshot(step, "end=$r")
        return r
    }

    private suspend fun awaitStateInner(timeoutMs: Long, done: (String) -> Boolean, panelOnly: Boolean, seen: suspend (String) -> Unit): String {
        var deadline = clock() + timeoutMs
        var shown: EbSheet? = null
        try {
            while (true) {
                val res = pageRun("STATE", minOf(callBoundMs, STATE_BOUND_MS), EbPanelScripts.STATE)
                var st = res?.optString("st")?.ifEmpty { null } ?: "loading"
                // A signed-in state read on another page (the previous one, the navigation not committed yet) is "still loading".
                if (panelOnly && st == "in" && res?.has("p") == true && !res.optString("p").startsWith("/cp")) st = "loading"
                seen(st)
                val sheet = when (st) { "captcha" -> EbSheet.CHALLENGE; "mfa" -> EbSheet.MFA; else -> null }
                if (sheet != shown) { page.show(sheet, if (sheet != null) "Enable Banking" else null); shown = sheet }
                if (sheet != null) deadline = clock() + timeoutMs
                else if (done(st) || st in TERMINAL) return st
                if (clock() >= deadline) return "timeout"
                sleep(POLL_MS)
            }
        } finally { if (shown != null) page.show(null) }
    }

    /** EB-1: the e-mail goes into FirebaseUI's own form. Ok(true) when the panel session was still there (no link needed). */
    suspend fun requestLink(email: String): EbPanelResult<Boolean> {
        EbLog.line(EbLog.EB, "requestLink load " + EbLog.link(EbPanelRules.SIGN_IN))
        page.load(EbPanelRules.SIGN_IN)
        when (val st = awaitState(FORM_TIMEOUT_MS, { it == "form" }, "requestLink form")) {
            "in" -> return EbPanelResult.Ok(true)
            "form" -> Unit
            else -> return failOf(st)
        }
        if (run("SUBMIT_EMAIL", EbPanelScripts.SUBMIT_EMAIL, JSONObject().put("email", email))?.optString("st") != "submitted") return EbPanelResult.Failed(EbPanelFailure.PAGE_CHANGED)
        return when (val st = awaitState(FORM_TIMEOUT_MS, { it == "sent" }, "requestLink sent")) {
            "sent" -> EbPanelResult.Ok(false)
            "in" -> EbPanelResult.Ok(true)
            else -> failOf(st)
        }
    }

    /** EB-2: the magic link opens in the same page; FirebaseUI completes, and confirms the e-mail if it asks. */
    suspend fun completeLink(link: String, email: String): EbPanelResult<Unit> {
        if (!EbPanelRules.isSignInLink(link)) return EbPanelResult.Failed(EbPanelFailure.LINK_INVALID)
        EbLog.line(EbLog.EB, "completeLink load " + EbLog.link(link))
        page.load(link)
        var confirmed = false
        while (true) {
            when (val st = awaitState(SIGNED_IN_TIMEOUT_MS, { it == "confirm" }, "completeLink")) {
                "in" -> { page.persist(); return EbPanelResult.Ok(Unit) }
                "confirm" -> {
                    if (confirmed) return EbPanelResult.Failed(EbPanelFailure.LINK_INVALID)
                    confirmed = true
                    if (run("SUBMIT_EMAIL", EbPanelScripts.SUBMIT_EMAIL, JSONObject().put("email", email))?.optString("st") != "submitted") return EbPanelResult.Failed(EbPanelFailure.PAGE_CHANGED)
                }
                else -> return failOf(st)
            }
        }
    }

    /** The panel with a live session, or why not: a sign-in form means the session is gone. */
    private suspend fun ensurePanel(): EbPanelResult.Failed? {
        if (EbPanelRules.onPanel(page.url())) {
            val r = run("STATE", EbPanelScripts.STATE)
            if (r?.optString("st") == "in" && (!r.has("p") || r.optString("p").startsWith("/cp"))) return null
        }
        EbLog.line(EbLog.EB, "ensurePanel load " + EbLog.link(EbPanelRules.PANEL))
        page.load(EbPanelRules.PANEL)
        return when (val st = awaitState(FORM_TIMEOUT_MS, { it == "form" }, "ensurePanel", panelOnly = true)) {
            "in" -> null
            "form" -> EbPanelResult.Failed(EbPanelFailure.EXPIRED)
            else -> failOf(st)
        }
    }

    /**
     * A script of a panel step. A result lost because the page changed under it is not the provider's failure: the panel is
     * ensured again and the script runs once more; a second loss is reported as ours ([lostAs]), never as "unreachable".
     */
    private suspend fun runOnPanel(name: String, body: String, args: JSONObject = JSONObject()): JSONObject? {
        var r = run(name, body, args)
        if (r?.optString("code") != "gone") return r
        EbLog.line(EbLog.EB, "script $name lost where=gone: ensure the panel and run once more")
        ensurePanel()?.let { return JSONObject().put("code", "gone-fail").put("fail", it.failure.name) }
        r = run(name, body, args)
        if (r?.optString("code") == "gone") EbLog.line(EbLog.EB, "script $name lost twice where=gone")
        return r
    }

    private fun <T> api(r: JSONObject?, lostAs: EbPanelFailure = EbPanelFailure.UNREACHABLE, ok: (JSONObject) -> T): EbPanelResult<T> = also(r) { apiInner(r, lostAs, ok) }

    private fun <T> also(r: JSONObject?, f: () -> EbPanelResult<T>): EbPanelResult<T> {
        val out = f()
        val o = when { r == null -> "no answer"; r.has("err") -> "script error"; else -> (if (r.optString("code").isNotEmpty()) "code=" + r.optString("code") else "ok") + (if (r.has("http")) " http=" + r.optInt("http") + " ct=" + r.optString("ct") else "") + (if (r.has("lh") && r.optInt("lh") == 404) " list http=404 treated=empty" else "") }
        if (r != null && r.has("tshape")) EbLog.line(EbLog.EB, "token shape " + EbLog.json(r.optJSONObject("tshape")) + (if (r.optBoolean("tbad")) " token shape invalid" else ""))
        if (r != null && r.optString("code") == "script") EbLog.line(EbLog.EB, "script error where=" + EbLog.scrub(r.optString("where")) + " msg=" + EbLog.scrub(r.optString("msg")))
        if (r != null && r.has("shape")) EbLog.line(EbLog.EB, "list shape " + EbLog.json(r.optJSONObject("shape")))
        if (r != null && r.optBoolean("lunavail")) EbLog.line(EbLog.EB, "list http=400 treated=unavailable")
        if (r != null && r.has("body")) EbLog.line(EbLog.EB, "api error body=" + EbLog.maskBody(r.optString("body")))
        EbLog.line(EbLog.EB, "api result=$o mapped=${if (out is EbPanelResult.Failed) "failed:${out.failure}" + (out.reason?.let { " reason=${EbLog.scrub(it)}" } ?: "") else "ok"}")
        return out
    }

    private fun <T> apiInner(r: JSONObject?, lostAs: EbPanelFailure, ok: (JSONObject) -> T): EbPanelResult<T> = when {
        r == null -> EbPanelResult.Failed(EbPanelFailure.UNREACHABLE)
        r.has("err") -> EbPanelResult.Failed(EbPanelFailure.PAGE_CHANGED)
        else -> when (r.optString("code")) {
            "" -> try { EbPanelResult.Ok(ok(r)) } catch (_: Exception) { EbPanelResult.Failed(EbPanelFailure.PAGE_CHANGED) }
            "auth" -> EbPanelResult.Failed(EbPanelFailure.EXPIRED)
            "http" -> EbPanelRules.shortReason(r.optString("reason")).let { w -> if (w != null && r.optInt("http") in 400..499) EbPanelResult.Failed(EbPanelFailure.REFUSED, w) else EbPanelResult.Failed(EbPanelFailure.HTTP_ERROR, status = r.optInt("http")) }
            "net" -> EbPanelResult.Failed(EbPanelFailure.UNREACHABLE)
            "gone" -> EbPanelResult.Failed(lostAs)
            "gone-fail" -> EbPanelResult.Failed(EbPanelFailure.valueOf(r.optString("fail")))
            "script" -> EbPanelResult.Failed(EbPanelFailure.INTERNAL)
            "refused" -> EbPanelResult.Failed(EbPanelFailure.REFUSED, EbPanelRules.shortReason(r.optString("reason")))
            else -> EbPanelResult.Failed(EbPanelFailure.PAGE_CHANGED)
        }
    }

    /** EB-3: the stored application if the account has it, else a PRODUCTION registration with [certificate]. */
    suspend fun register(knownAppId: String?, certificate: String, redirectUrl: String, email: String, config: EbProductionConfig): EbPanelResult<EbRegistration> {
        if (!config.complete) return EbPanelResult.Failed(EbPanelFailure.CONFIG_MISSING)
        ensurePanel()?.let { return it }
        val args = JSONObject().put("appId", knownAppId ?: JSONObject.NULL).put("email", email).put("payload", config.payload(certificate, redirectUrl))
        return api(runOnPanel("REGISTER", EbPanelScripts.REGISTER, args), EbPanelFailure.INTERNAL) { EbRegistration(it.getString("appId"), it.optBoolean("active"), it.optBoolean("reused")) }
    }

    /** EB-4: the panel's own bank list for [country]. */
    suspend fun banks(country: String = COUNTRY): EbPanelResult<List<EbPanelBank>> {
        ensurePanel()?.let { return it }
        return api(runOnPanel("ASPSPS", EbPanelScripts.ASPSPS, JSONObject().put("country", country))) { r ->
            val a = r.getJSONArray("banks")
            (0 until a.length()).map { i -> a.getJSONObject(i).let { b -> EbPanelBank(b.getString("name"), b.optJSONArray("psu")?.let { p -> (0 until p.length()).map(p::getString) }.orEmpty()) } }
        }
    }

    suspend fun status(appId: String, bank: String? = null): EbPanelResult<EbAppStatus> {
        ensurePanel()?.let { return it }
        return api(runOnPanel("STATUS", EbPanelScripts.STATUS, JSONObject().put("appId", appId).put("bank", bank ?: ""))) { r ->
            EbAppStatus(r.getBoolean("active"), r.optInt("linked"), r.optInt("here"), r.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
        }
    }

    /** EB-5, first half: link_accounts from inside the page; the bank's https address. */
    suspend fun linkAccounts(appId: String, bank: String, psuType: String, country: String = COUNTRY): EbPanelResult<String> {
        ensurePanel()?.let { return it }
        val args = JSONObject().put("country", country).put("aspsp", bank).put("appId", appId).put("psuType", psuType).put("redirect", EbPanelRules.LINK_REDIRECT)
        return when (val r = api(runOnPanel("LINK", EbPanelScripts.LINK, args)) { it.getString("url") }) {
            is EbPanelResult.Ok -> if (r.value.startsWith("https://")) r else EbPanelResult.Failed(EbPanelFailure.PAGE_CHANGED)
            is EbPanelResult.Failed -> r
        }
    }

    /** EB-5, second half: the bank in the shown page until it returns to the panel. The person's time is not bounded. */
    suspend fun approveAtBank(url: String, bank: String): EbPanelResult<Unit> {
        EbLog.line(EbLog.EB, "approveAtBank load " + EbLog.link(url))
        page.load(url)
        page.show(EbSheet.BANK, bank)
        try {
            var left = false
            var lastShape = ""; var nav = 0
            while (true) {
                val now = page.url()
                if (!EbPanelRules.isEb(now)) left = true
                val shape = EbLog.shape(now)
                if (shape != lastShape) { lastShape = shape; EbLog.line(EbLog.EB, "approveAtBank nav #${++nav} shape=$shape") }
                when (EbPanelRules.bankReturn(now, left)) {
                    true -> { EbLog.line(EbLog.EB, "approveAtBank return=clean shape=$shape navs=$nav"); return EbPanelResult.Ok(Unit) }
                    false -> {
                        val tail = listOfNotNull(now?.substringAfter('#', ""), now?.substringAfter('?', "")?.substringBefore('#')).joinToString("&")
                        val err = tail.split('&').firstOrNull { it.startsWith("error=") }?.substringAfter('=')
                        EbLog.line(EbLog.EB, "approveAtBank return=error name=${EbLog.errName(err)} shape=$shape navs=$nav")
                        return EbPanelResult.Failed(EbPanelFailure.NOT_LINKED)
                    }
                    null -> sleep(POLL_MS)
                }
            }
        } finally { page.show(null) }
    }

    /** EB-1's "Privacy" / "Termini": EB's own pages, opened in the shown page. False when the form does not offer them. */
    suspend fun openLegal(terms: Boolean): Boolean {
        page.load(EbPanelRules.SIGN_IN)
        if (awaitState(FORM_TIMEOUT_MS, { it == "form" }, "openLegal") != "form") return false
        val r = run("LEGAL_LINKS", EbPanelScripts.LEGAL_LINKS) ?: return false
        val href = r.optString(if (terms) "terms" else "privacy").takeIf { it.startsWith("https://") } ?: return false
        page.load(href)
        page.show(EbSheet.FALLBACK, "Enable Banking")
        return true
    }
}
