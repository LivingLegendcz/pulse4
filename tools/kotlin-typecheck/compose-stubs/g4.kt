@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.ui.input.pointer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
class PointerInputChange { val position: Offset get() = TODO() }
interface PointerInputScope { val size: androidx.compose.ui.unit.IntSize }
fun Modifier.pointerInput(key1: Any?, key2: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
fun Modifier.pointerInput(key1: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
