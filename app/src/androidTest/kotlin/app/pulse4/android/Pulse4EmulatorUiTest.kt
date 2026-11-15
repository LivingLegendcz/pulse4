package app.pulse4.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Pulse4EmulatorUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationTerminologyAndSharingOverviewWork() {
        // Bottom nav uses Kruhy, never Skupiny
        rule.onNodeWithText("Kruhy").assertIsDisplayed()
        assert(rule.onAllNodesWithText("Skupiny", substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())

        // Open Kruhy tab
        rule.onNodeWithText("Kruhy").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sdílení a oprávnění", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Přidat dítě", substring = true).assertIsDisplayed()

        // Open Sdílení a oprávnění screen
        rule.onNodeWithText("Sdílení a oprávnění", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sdílím", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sdílejí se mnou", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Žádosti", substring = true).assertIsDisplayed()
    }

    @Test
    fun rnsSettingsHasNoXaliAndShowsUnreachableNotificationBar() {
        val app = rule.activity.application as Pulse4App
        app.services.settings.networkMode = NetworkMode.AUTO

        // Trigger unreachable gateway event
        app.services.bridge.reportGatewayUnreachableForTest("dead-node.example.invalid", 4242)
        rule.waitForIdle()

        // Notification bar pops up with action button
        rule.onNodeWithText("Připojovací uzel dead-node.example.invalid:4242 je nedostupný", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Vybrat uzel").performClick()
        rule.waitForIdle()

        // Ensure Settings screen never shows xali.cz
        assert(rule.onAllNodesWithText("xali.cz", substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())
    }
}
