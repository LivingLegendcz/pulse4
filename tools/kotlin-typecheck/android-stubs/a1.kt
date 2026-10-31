package android.content
open class Context {
    val assets: android.content.res.AssetManager get() = TODO()
    val noBackupFilesDir: java.io.File get() = TODO()
    val applicationContext: Context get() = TODO()
    fun getSystemService(name: String): Any? = null
    val contentResolver: android.content.ContentResolver get() = TODO()
    fun startActivity(i: android.content.Intent) {}
    companion object { const val WIFI_SERVICE = "wifi" }
}
