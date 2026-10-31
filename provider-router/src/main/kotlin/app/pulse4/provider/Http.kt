package app.pulse4.provider

data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: String)

/** Tenká vrstva nad HTTP (v aplikaci OkHttp). Síťové selhání = `IOException`. Sdílí ji všechny konektory. */
interface HttpGateway {
    fun get(url: String, headers: Map<String, String>): HttpResponse
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse
}
