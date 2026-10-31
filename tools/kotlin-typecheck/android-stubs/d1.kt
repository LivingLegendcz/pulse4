package okhttp3
import java.util.concurrent.TimeUnit
class Headers { fun toMultimap(): Map<String, List<String>> = emptyMap() }
class ResponseBody { fun string(): String = "" }
class Response : java.io.Closeable { val code: Int = 0; val headers = Headers(); val body: ResponseBody? = null; override fun close() {} }
class Call { fun execute(): Response = Response() }
class OkHttpClient { class Builder { fun callTimeout(n: Long, u: TimeUnit) = this; fun connectTimeout(n: Long, u: TimeUnit) = this; fun readTimeout(n: Long, u: TimeUnit) = this; fun build() = OkHttpClient() }
    fun newCall(r: Request) = Call() }
class FormBody { class Builder { fun add(k: String, v: String) = this; fun build(): RequestBody = RequestBody() } }
class RequestBody
class Request { class Builder { fun url(u: String) = this; fun get() = this; fun post(b: RequestBody) = this; fun header(k: String, v: String) = this; fun build() = Request() } }
