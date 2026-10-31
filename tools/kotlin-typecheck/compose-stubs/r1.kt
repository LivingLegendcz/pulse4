@file:Suppress("unused", "UNUSED_PARAMETER", "UNCHECKED_CAST")
package androidx.compose.runtime
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.CLASS)
annotation class Composable
interface State<out T> { val value: T }
interface MutableState<T> : State<T> { override var value: T }
operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }
fun <T> mutableStateOf(v: T): MutableState<T> = TODO()
@Composable fun <T> remember(calculation: () -> T): T = TODO()
@Composable fun <T> remember(key1: Any?, calculation: () -> T): T = TODO()
@Composable fun <T> remember(key1: Any?, key2: Any?, calculation: () -> T): T = TODO()
@Composable fun <T> remember(key1: Any?, key2: Any?, key3: Any?, calculation: () -> T): T = TODO()
interface ProduceStateScope<T> : MutableState<T>, CoroutineScope
@Composable fun <T> produceState(initialValue: T, key1: Any?, producer: suspend ProduceStateScope<T>.() -> Unit): State<T> = TODO()
@Composable fun rememberCoroutineScope(): CoroutineScope = TODO()
@Composable fun LaunchedEffect(key1: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun <T> Flow<T>.collectAsState(initial: T): State<T> = TODO()
@Composable fun <T> StateFlow<T>.collectAsState(): State<T> = TODO()
interface DisposableEffectScope { fun onDispose(f: () -> Unit): Any }
@Composable fun DisposableEffect(key1: Any?, key2: Any?, effect: DisposableEffectScope.() -> Any) {}
@Composable fun <T> staticCompositionLocalOf(f: () -> T): ProvidableCompositionLocal<T> = TODO()
class ProvidableCompositionLocal<T> { val current: T @Composable get() = TODO() }
