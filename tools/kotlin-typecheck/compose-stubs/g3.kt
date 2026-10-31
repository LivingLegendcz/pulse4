@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.ui.graphics.drawscope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
class Stroke(val width: Float)
interface DrawScope {
    val size: Size
    fun drawRect(color: Color, topLeft: Offset = Offset(0f, 0f), size: Size = this.size)
    fun drawLine(color: Color, start: Offset, end: Offset, strokeWidth: Float = 1f)
    fun drawPath(path: Path, color: Color, style: Stroke = Stroke(1f))
    fun drawCircle(color: Color, radius: Float, center: Offset)
}
