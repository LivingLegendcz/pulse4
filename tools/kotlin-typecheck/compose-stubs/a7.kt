package android.graphics
class Bitmap { fun setPixel(x: Int, y: Int, c: Int) {}; enum class Config { ARGB_8888 }; companion object { fun createBitmap(w: Int, h: Int, c: Config): Bitmap = Bitmap() } }
object Color { const val BLACK = 0; const val WHITE = -1 }
