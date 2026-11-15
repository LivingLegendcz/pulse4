package app.pulse4.android

import app.pulse4.sharing.Field
import app.pulse4.sync.RnsNodeConfig
import app.pulse4.sync.RnsNodeQr
import app.pulse4.sync.RnsTransportType
import app.pulse4.ui.GrantDraft
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptanceScenariosTest {
    @Test
    fun scenario1_uiLogicUsesKruhTerminologyNotSkupina() {
        val problems = GrantDraft(
            recipients = emptySet(),
            fields = setOf(Field.WEIGHT),
            groupId = null,
        ).problems()
        assertTrue(problems.any { it.contains("kruh", ignoreCase = true) })
        assertFalse(problems.any { it.contains("skupin", ignoreCase = true) })
    }

    @Test
    fun scenario10_noHardcodedXaliInSettingsOrRnsScreens() {
        val roots = listOf(
            File("src/main/kotlin/app/pulse4/android/data/Settings.kt"),
            File("src/main/kotlin/app/pulse4/android/reticulum/ReticulumBridge.kt"),
            File("src/main/kotlin/app/pulse4/android/ui/SettingsHubScreen.kt"),
            File("src/main/kotlin/app/pulse4/android/ui/ReticulumScreen.kt"),
            File("src/main/kotlin/app/pulse4/android/ui/RnsOnboardingSheet.kt"),
        ).filter { it.exists() }
        for (file in roots) {
            val content = file.readText()
            assertFalse(content.contains("xali.cz", ignoreCase = true), "${file.path} still references xali.cz")
        }
    }

    @Test
    fun scenario11And12_rnsQrConfigRoundtripAndRejectsSensitiveKeys() {
        val cfg = RnsNodeConfig(
            name = "Můj domácí uzel",
            host = "192.168.1.50",
            port = 4242,
            transport = RnsTransportType.TCP_CLIENT,
            propagationNode = "0123456789abcdef0123456789abcdef",
        )
        val encoded = RnsNodeQr.encode(cfg)
        val decoded = RnsNodeQr.decodeAndValidate(encoded)
        assertTrue(decoded.isSuccess)
        assertTrue(decoded.getOrThrow().host == "192.168.1.50")

        val malicious = """{"v":1,"type":"rns_node","name":"Bad","host":"example.com","port":4242,"privateKey":"secret"}"""
        assertTrue(RnsNodeQr.decodeAndValidate(malicious).isFailure)
    }
}
