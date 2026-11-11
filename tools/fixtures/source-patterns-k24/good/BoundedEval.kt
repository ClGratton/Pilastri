suspend fun eval(js: String): String? = withTimeoutOrNull(5000) {
    suspendCancellableCoroutine { c -> web.evaluateJavascript(js) { v -> c.resume(v) } }
}
