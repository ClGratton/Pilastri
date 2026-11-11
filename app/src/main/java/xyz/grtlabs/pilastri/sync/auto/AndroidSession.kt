package xyz.grtlabs.pilastri.sync.auto

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** [SessionHttp] with OkHttp: no redirects followed, no cookie jar of its own (the fetcher adds the Cookie header per hop). */
class OkHttpSessionHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .build(),
) : SessionHttp {
    override fun execute(call: HttpCall, maxBytes: Int): HttpReply {
        val body = call.body?.toRequestBody(call.contentType?.toMediaTypeOrNull())
        val request = Request.Builder().url(call.url).apply {
            call.headers.forEach { (name, value) -> addHeader(name, value) }
        }.method(call.method, body).build()
        client.newCall(request).execute().use { response ->
            val out = ByteArrayOutputStream()
            var truncated = false
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) { truncated = true; break }
                    out.write(buffer, 0, n)
                }
            }
            return HttpReply(response.code, response.headers.toMultimap(), out.toByteArray(), truncated)
        }
    }
}

/** The WebView cookie jar, limited to the provider's https hosts. Values are never logged. */
class WebViewSessionCookies(private val site: ProviderSite) : SessionCookies {
    override fun header(url: String): String? = if (site.isProviderUrl(url)) CookieManager.getInstance().getCookie(url) else null

    override fun value(url: String, name: String): String? =
        header(url)?.split(';')?.map { it.trim() }?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }

    override fun store(url: String, setCookie: List<String>) {
        if (!site.isProviderUrl(url)) return
        val cookies = CookieManager.getInstance()
        setCookie.forEach { cookies.setCookie(url, it) }
        cookies.flush()
    }
}

/**
 * One GET that keeps a session warm (PayPal's `extendSession`): https provider URL, cookies for it only, no redirects followed.
 * True for a 2xx answer that is not a web page.
 */
fun keepAlive(site: ProviderSite, url: String, http: SessionHttp, cookies: SessionCookies, userAgent: String?): Boolean {
    if (!site.isProviderUrl(url)) return false
    val headers = buildList {
        add("Accept" to "application/json")
        userAgent?.let { add("User-Agent" to it) }
        cookies.header(url)?.takeIf { it.isNotBlank() }?.let { add("Cookie" to it) } ?: return false
    }
    return try {
        val reply = http.execute(HttpCall("GET", url, headers, null, null), 256 * 1024)
        reply.headers("Set-Cookie").takeIf { it.isNotEmpty() }?.let { cookies.store(url, it) }
        reply.code in 200..299 && !reply.header("Content-Type").orEmpty().startsWith("text/html")
    } catch (_: IOException) {
        false
    }
}
