package androidx.activity
open class ComponentActivity : android.content.Context() {
    open fun onCreate(savedInstanceState: android.os.Bundle?) {}
    open fun onNewIntent(intent: android.content.Intent) {}
    val intent: android.content.Intent get() = TODO()
    val application: android.app.Application get() = TODO()
}
