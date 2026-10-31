@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.result.contract
open class ActivityResultContract<I, O>
object ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, android.net.Uri?>()
    class CreateDocument(mime: String) : ActivityResultContract<String, android.net.Uri?>()
    class RequestPermission : ActivityResultContract<String, Boolean>()
}
