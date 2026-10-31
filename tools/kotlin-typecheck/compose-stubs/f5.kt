@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation.gestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
suspend fun PointerInputScope.detectDragGestures(onDragStart: (Offset) -> Unit = {}, onDrag: (PointerInputChange, Offset) -> Unit) {}
