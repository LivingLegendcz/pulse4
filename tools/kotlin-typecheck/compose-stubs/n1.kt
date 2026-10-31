@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.navigation.compose
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
class NavDestination { val route: String? = null }
class Args { fun getString(k: String): String? = null }
class NavBackStackEntry { val destination = NavDestination(); val arguments: Args? = Args() }
class NavOptions { var launchSingleTop = false }
class NavHostController { fun navigate(route: String) {}; fun navigate(route: String, b: NavOptions.() -> Unit) {} }
@Composable fun rememberNavController(): NavHostController = TODO()
@Composable fun NavHostController.currentBackStackEntryAsState(): State<NavBackStackEntry?> = TODO()
class NavGraphBuilder
fun NavGraphBuilder.composable(route: String, content: @Composable (NavBackStackEntry) -> Unit) {}
@Composable fun NavHost(nav: NavHostController, startDestination: String, modifier: Modifier = Modifier, builder: NavGraphBuilder.() -> Unit) {}
