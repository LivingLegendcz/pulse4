@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation.lazy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
interface LazyListScope { fun <T> items(items: List<T>, key: ((T) -> Any)? = null, itemContent: @Composable (T) -> Unit) }
fun <T> LazyListScope.items(items: List<T>, key: ((T) -> Any)? = null, itemContent: @Composable (T) -> Unit) = this.items(items, key, itemContent)
@Composable fun LazyColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {}
