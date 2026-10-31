package android.content
class Intent(val action: String? = null, uri: android.net.Uri? = null) { var data: android.net.Uri? = null; companion object { const val ACTION_VIEW = "view" } }
class ContentResolver { fun openInputStream(u: android.net.Uri): java.io.InputStream? = null; fun openOutputStream(u: android.net.Uri): java.io.OutputStream? = null }
