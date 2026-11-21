package app.pulse4.android.di

import app.pulse4.sync.Wire
import java.net.URI
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Redacted trace of Withings login / silent-refresh attempts for the "Diagnostika Withings" card.
 *
 * Only structured, non-secret fields are accepted: flow name, step label, URL host + path (query and
 * fragment are always dropped, path segments that look like e-mails or tokens are masked), HTTP status,
 * whether the request ended on app.withings.com, and the result kind. Never pass cookies, passwords,
 * tokens, e-mails or free-form server text in here.
 */
class WithingsAuthDiagnostics(
    private val load: () -> String? = { null },
    private val persist: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val maxEntries: Int = MAX_ENTRIES,
) {
    @Serializable
    data class Entry(
        val atMs: Long,
        val flow: String,
        val step: String,
        val path: String? = null,
        val status: Int? = null,
        val toApp: Boolean? = null,
        val result: String? = null,
    )

    private val entries = ArrayDeque<Entry>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = runCatching { load() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val parsed = runCatching { Wire.json.decodeFromString(ListSerializer(Entry.serializer()), raw) }.getOrNull() ?: return
        parsed.takeLast(maxEntries).forEach { entries.addLast(it) }
    }

    /** Records one HTTP step of [flow]. [url] is reduced to host + path. */
    @Synchronized
    fun step(flow: Flow, step: String, url: String? = null, status: Int? = null, toApp: Boolean? = null) {
        add(Entry(nowMs(), flow.name, safeLabel(step), url?.let(::sanitizeUrl), status, toApp, null))
    }

    /** Records the final classification of [flow]. */
    @Synchronized
    fun result(flow: Flow, res: WithingsLoginResult) {
        add(Entry(nowMs(), flow.name, "result", result = resultKind(res)))
    }

    /** Records an informational marker without any payload (e.g. "start", "aborted", "exception:IOException"). */
    @Synchronized
    fun mark(flow: Flow, step: String) {
        add(Entry(nowMs(), flow.name, safeLabel(step)))
    }

    @Synchronized
    fun entries(): List<Entry> {
        ensureLoaded()
        return entries.toList()
    }

    @Synchronized
    fun clear() {
        loaded = true
        entries.clear()
        runCatching { persist("[]") }
    }

    /** Human-readable export for the clipboard. */
    fun export(): String {
        val list = entries()
        if (list.isEmpty()) return "Diagnostika Withings: zatím žádný záznam."
        return buildString {
            appendLine("Diagnostika Withings (${list.size} záznamů, UTC)")
            for (e in list) {
                append(Instant.ofEpochMilli(e.atMs).toString())
                append(' ').append(e.flow).append(' ').append(e.step)
                e.path?.let { append(' ').append(it) }
                e.status?.let { append(" HTTP ").append(it) }
                e.toApp?.let { append(" app=").append(if (it) "ano" else "ne") }
                e.result?.let { append(" → ").append(it) }
                appendLine()
            }
        }.trimEnd()
    }

    private fun add(e: Entry) {
        ensureLoaded()
        entries.addLast(e)
        while (entries.size > maxEntries) entries.removeFirst()
        val encoded = runCatching {
            Wire.json.encodeToString(ListSerializer(Entry.serializer()), entries.toList())
        }.getOrNull() ?: return
        runCatching { persist(encoded) }
    }

    enum class Flow { MANUAL_PASSWORD, MFA_CODE, EMAIL_CODE, SILENT_SSO, BACKGROUND_PASSWORD, EXTEND }

    companion object {
        const val MAX_ENTRIES = 30
        const val STORE_KEY = "withings.auth.diag"

        private val TOKENISH = Regex("^[A-Za-z0-9_\\-.%=]{24,}$")

        fun resultKind(res: WithingsLoginResult): String = when (res) {
            is WithingsLoginResult.Ok -> "Ok(profiles=${res.profiles.size})"
            is WithingsLoginResult.NeedsMfa -> "NeedsMfa"
            is WithingsLoginResult.Error -> "Error(${res.kind.name})"
        }

        /** Step labels come from code, but keep them short and free of anything e-mail-like anyway. */
        internal fun safeLabel(s: String): String =
            if (s.contains('@')) "<redacted>" else s.take(48).replace(Regex("[^A-Za-z0-9_:./\\- ]"), "_")

        /** Host + path only; query and fragment dropped; e-mail-like or token-like segments masked. */
        internal fun sanitizeUrl(url: String): String {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            val host = uri?.host.orEmpty()
            val rawPath = uri?.rawPath ?: url.substringBefore('?').substringBefore('#')
            val path = rawPath.split('/').joinToString("/") { seg ->
                when {
                    seg.contains('@') || seg.contains("%40", ignoreCase = true) -> "<redacted>"
                    TOKENISH.matches(seg) -> "<id>"
                    else -> seg
                }
            }
            return (host + path).ifBlank { "<url>" }
        }
    }
}
