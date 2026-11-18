package app.pulse4.domain

/**
 * Redacting logger shared across JVM modules and Android.
 *
 * When [isDebug] is false (release), [d] and [i] are suppressed and [w]/[e]
 * scrub PII (emails, bearer/session/password tokens) and cap message length.
 */
object PLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, throwable: Throwable?)
    }

    @Volatile
    var isDebug: Boolean = false

    @Volatile
    var sink: Sink = Sink { level, tag, message, throwable ->
        if (level == Level.ERROR || level == Level.WARN) {
            System.err.println("[$level/$tag] $message")
            throwable?.printStackTrace(System.err)
        }
    }

    private const val MAX_LEN = 200
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9_.+-]+@[A-Za-z0-9-]+\.[A-Za-z0-9.-]+""")
    private val AUTH_HEADER_REGEX = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val SECRET_KV_REGEX = Regex(
        """(?i)\b(bearer|token|access_token|refresh_token|sessionid|csrf|csrf_token|password|passwd|secret|api_key|apikey)\s*[:=]\s*[^\s,;]+"""
    )

    fun redact(s: String, maxLen: Int = MAX_LEN): String {
        val scrubbed = s
            .replace(EMAIL_REGEX, "<email>")
            .replace(AUTH_HEADER_REGEX, "Bearer <redacted>")
            .replace(SECRET_KV_REGEX) { m -> "${m.groupValues[1]}=<redacted>" }
        return if (scrubbed.length > maxLen) scrubbed.take(maxLen) + "..." else scrubbed
    }

    inline fun d(tag: String, msg: () -> String) {
        if (isDebug) sink.log(Level.DEBUG, tag, msg(), null)
    }

    inline fun i(tag: String, msg: () -> String) {
        if (isDebug) sink.log(Level.INFO, tag, msg(), null)
    }

    inline fun w(tag: String, throwable: Throwable? = null, msg: () -> String) {
        val text = if (isDebug) msg() else redact(msg())
        sink.log(Level.WARN, tag, text, throwable)
    }

    inline fun e(tag: String, throwable: Throwable? = null, msg: () -> String) {
        val text = if (isDebug) msg() else redact(msg())
        sink.log(Level.ERROR, tag, text, throwable)
    }
}
