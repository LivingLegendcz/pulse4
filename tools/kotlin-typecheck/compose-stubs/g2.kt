@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.ui.graphics
class Color { fun copy(alpha: Float = 1f): Color = this; companion object { val Unspecified = Color() } }
class ImageBitmap
class Path { fun moveTo(x: Float, y: Float) {}; fun lineTo(x: Float, y: Float) {} }
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = ImageBitmap()
