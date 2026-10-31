@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation.layout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.FILE) annotation class ExperimentalLayoutApi
object Arrangement { val SpaceBetween = Any() }
@Composable fun Column(modifier: Modifier = Modifier, content: @Composable () -> Unit) {}
interface RowScope { fun Modifier.weight(w: Float): Modifier = this }
@Composable fun Row(modifier: Modifier = Modifier, horizontalArrangement: Any? = null, content: @Composable RowScope.() -> Unit) {}
@Composable fun FlowRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {}
fun Modifier.padding(all: Dp): Modifier = this
fun Modifier.padding(horizontal: Dp = Dp(), vertical: Dp = Dp()): Modifier = this
fun Modifier.padding(start: Dp = Dp(), top: Dp = Dp(), end: Dp = Dp(), bottom: Dp = Dp()): Modifier = this
fun Modifier.fillMaxWidth(f: Float = 1f): Modifier = this
fun Modifier.fillMaxSize(): Modifier = this
fun Modifier.height(h: Dp): Modifier = this
fun Modifier.size(s: Dp): Modifier = this
class PaddingValuesStub
fun Modifier.padding(p: PaddingValuesStub): Modifier = this
