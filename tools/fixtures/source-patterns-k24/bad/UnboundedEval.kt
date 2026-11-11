suspend fun eval(js: String): String? =
    suspendCancellableCoroutine { c -> web.evaluateJavascript(js) { v -> c.resume(v) } }
