package app.pulse4.android.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityNavWhitelistTest {
    @Test
    fun `allows known top-level and metric routes`() {
        assertTrue(isAllowedNavigationRoute("today"))
        assertTrue(isAllowedNavigationRoute("metrics"))
        assertTrue(isAllowedNavigationRoute("activities"))
        assertTrue(isAllowedNavigationRoute("training"))
        assertTrue(isAllowedNavigationRoute("groups"))
        assertTrue(isAllowedNavigationRoute("sharing_overview"))
        assertTrue(isAllowedNavigationRoute("settings"))
        assertTrue(isAllowedNavigationRoute("today_metric/HRV"))
        assertTrue(isAllowedNavigationRoute("metrics/SLEEP_DURATION"))
    }

    @Test
    fun `rejects unknown or injection routes`() {
        assertFalse(isAllowedNavigationRoute(""))
        assertFalse(isAllowedNavigationRoute("unknown"))
        assertFalse(isAllowedNavigationRoute("today_metric/NOT_A_METRIC"))
        assertFalse(isAllowedNavigationRoute("activity/123"))
        assertFalse(isAllowedNavigationRoute("today/../settings"))
    }
}
