@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
fun Modifier.background(color: Color, shape: Any? = null): Modifier = this
fun Modifier.clickable(onClick: () -> Unit): Modifier = this
class ScrollState
@Composable fun rememberScrollState(): ScrollState = TODO()
fun Modifier.verticalScroll(state: ScrollState, enabled: Boolean = true): Modifier = this
@Composable fun isSystemInDarkTheme(): Boolean = false
@Composable fun Image(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier) {}
@Composable fun Canvas(modifier: Modifier, onDraw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {}
