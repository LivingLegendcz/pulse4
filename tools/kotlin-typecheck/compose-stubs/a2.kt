@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.compose
import androidx.compose.runtime.Composable
import androidx.activity.result.contract.ActivityResultContract
class ManagedActivityResultLauncher<I, O> { fun launch(i: I) {} }
@Composable fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ManagedActivityResultLauncher<I, O> = TODO()
fun androidx.activity.ComponentActivity.setContent(content: @Composable () -> Unit) {}
