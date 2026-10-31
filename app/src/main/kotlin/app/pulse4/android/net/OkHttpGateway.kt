package app.pulse4.android.net

import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Síťová vrstva Garmin Personal konektoru. Tokeny ani klíče se nikdy neloguje. */
class OkHttpGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) : HttpGateway {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.build())
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val b = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.build())
    }

    private fun execute(req: Request): HttpResponse =
        client.newCall(req).execute().use { r ->
            HttpResponse(r.code, r.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }, r.body?.string().orEmpty())
        }
}
