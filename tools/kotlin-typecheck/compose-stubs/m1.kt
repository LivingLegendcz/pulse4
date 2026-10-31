@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.material3
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.FILE) annotation class ExperimentalMaterial3Api
class TextStyle
class Typography { val titleLarge = TextStyle(); val titleMedium = TextStyle(); val titleSmall = TextStyle(); val headlineSmall = TextStyle(); val labelLarge = TextStyle(); val bodySmall = TextStyle(); val bodyMedium = TextStyle() }
class ColorScheme { val primary = Color(); val secondary = Color(); val tertiary = Color(); val error = Color(); val surfaceVariant = Color(); val onSurface = Color() }
object MaterialTheme { val typography = Typography(); val colorScheme = ColorScheme() }
@Composable fun MaterialTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {}
fun lightColorScheme() = ColorScheme(); fun darkColorScheme() = ColorScheme()
fun dynamicLightColorScheme(c: android.content.Context) = ColorScheme(); fun dynamicDarkColorScheme(c: android.content.Context) = ColorScheme()
@Composable fun Text(text: String, modifier: Modifier = Modifier, color: Color = Color(), style: TextStyle = TextStyle()) {}
@Composable fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {}
@Composable fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {}
@Composable fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {}
@Composable fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {}
@Composable fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {}
@Composable fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {}
@Composable fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null, visualTransformation: Any? = null, keyboardOptions: androidx.compose.foundation.text.KeyboardOptions? = null) {}
@Composable fun AlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit = {}, title: @Composable () -> Unit = {}, text: @Composable () -> Unit = {}) {}
@Composable fun Scaffold(topBar: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {}, content: @Composable (androidx.compose.foundation.layout.PaddingValuesStub) -> Unit) {}
@Composable fun TopAppBar(title: @Composable () -> Unit, actions: @Composable () -> Unit = {}) {}
@Composable fun NavigationBar(content: @Composable () -> Unit) {}
@Composable fun NavigationBarItem(selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit, label: @Composable () -> Unit) {}
@Composable fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {}
@Composable fun DropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {}
@Composable fun Icon(x: Any?, c: String?) {}
