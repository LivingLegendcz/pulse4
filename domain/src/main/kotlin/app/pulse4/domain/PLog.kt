package app.pulse4.domain

/**
 * Redacting logger shared across JVM modules and Android.
 *
 * When [isDebug] is false (release), [d] and [i] are suppressed, [w]/[e]
 * scrub PII (emails, bearer/session/JSON/camelCase tokens, tickets, cookies,
 * exception messages) and omit raw stack traces, and cap message length.
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
    private val COOKIE_HEADER_REGEX = Regex("""(?i)\b(Set-Cookie|Cookie)\s*:\s*[^\r\n]+""")
    private val JSON_SECRET_REGEX = Regex(
        """(?i)"(bearer|token|access_token|refresh_token|accessToken|refreshToken|sessionToken|authTicket|session_id|sessionId|csrf|csrf_token|csrfToken|password|passwd|secret|api_key|apiKey|ticket)"\s*:\s*"[^"]*""""
    )
    private val TICKET_REGEX = Regex("""(?i)\bticket\s*=\s*ST-[A-Za-z0-9_-]+""")
    private val SECRET_KV_REGEX = Regex(
        """(?i)\b(bearer|token|access_token|refresh_token|accessToken|refreshToken|sessionToken|authTicket|session_id|sessionid|csrf|csrf_token|csrfToken|password|passwd|secret|api_key|apikey|ticket)\s*[:=]\s*[^\s,;&"']+"""
    )

    fun redact(s: String, maxLen: Int = MAX_LEN): String {
        val scrubbed = s
            .replace(EMAIL_REGEX, "<email>")
            .replace(AUTH_HEADER_REGEX, "Bearer <redacted>")
            .replace(COOKIE_HEADER_REGEX) { m -> "${m.groupValues[1]}: <redacted>" }
            .replace(JSON_SECRET_REGEX) { m -> "\"${m.groupValues[1]}\":\"<redacted>\"" }
            .replace(TICKET_REGEX, "ticket=<redacted>")
            .replace(SECRET_KV_REGEX) { m -> "${m.groupValues[1]}=<redacted>" }
        return if (scrubbed.length > maxLen) scrubbed.take(maxLen) + "..." else scrubbed
    }

    fun formatThrowable(throwable: Throwable): String {
        val type = throwable::class.simpleName ?: "Throwable"
        val rawMsg = throwable.message?.takeIf { it.isNotBlank() }
        return if (rawMsg != null) "$type: ${redact(rawMsg)}" else type
    }

    @PublishedApi
    internal fun emit(level: Level, tag: String, throwable: Throwable?, msg: String) {
        if (isDebug) {
            sink.log(level, tag, msg, throwable)
        } else {
            val redactedMsg = redact(msg)
            val fullMsg = if (throwable != null) {
                "$redactedMsg (${formatThrowable(throwable)})"
            } else {
                redactedMsg
            }
            sink.log(level, tag, fullMsg, null)
        }
    }

    inline fun d(tag: String, msg: () -> String) {
        if (isDebug) sink.log(Level.DEBUG, tag, msg(), null)
    }

    inline fun i(tag: String, msg: () -> String) {
        if (isDebug) sink.log(Level.INFO, tag, msg(), null)
    }

    inline fun w(tag: String, throwable: Throwable? = null, msg: () -> String) {
        emit(Level.WARN, tag, throwable, msg())
    }

    inline fun e(tag: String, throwable: Throwable? = null, msg: () -> String) {
        emit(Level.ERROR, tag, throwable, msg())
    }
}
