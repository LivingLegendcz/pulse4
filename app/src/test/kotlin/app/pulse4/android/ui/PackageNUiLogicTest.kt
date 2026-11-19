package app.pulse4.android.ui

import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.di.WithingsCodeKind
import app.pulse4.android.di.WithingsLoginResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNUiLogicTest {

    @Test
    fun `R4-9 stored password is never placed into the visible field`() {
        val initial = WithingsPasswordFieldState().withStored(true)
        assertTrue(initial.usesStoredPassword)
        assertFalse(initial.showsPasswordField)
        assertFalse(initial.showsEyeToggle)
        assertEquals("", initial.typed)
        assertTrue(initial.canSubmit)
        assertNull("null = service uses the stored password itself", initial.passwordForSubmit())

        val changing = initial.startChange()
        assertTrue(changing.showsPasswordField)
        assertTrue("eye toggle only for a newly typed password", changing.showsEyeToggle)
        assertFalse(changing.canSubmit)
        val typed = changing.copy(typed = "new-pass")
        assertTrue(typed.canSubmit)
        assertEquals("new-pass", typed.passwordForSubmit())

        val back = typed.useStored()
        assertTrue(back.usesStoredPassword)
        assertEquals("", back.typed)

        // Without a stored password the normal field is shown.
        val none = WithingsPasswordFieldState().withStored(false)
        assertTrue(none.showsPasswordField)
        assertFalse(none.canSubmit)
        assertEquals("x", none.copy(typed = "x").passwordForSubmit())
    }

    @Test
    fun `R4-9 dialog never prefills or reads the remembered password`() {
        val src = File("src/main/kotlin/app/pulse4/android/ui/WithingsWebLoginDialog.kt").readText()
        assertFalse(src.contains("rememberedPassword"))
        assertFalse(src.contains("withings.web.password"))
        assertTrue(src.contains("loginPrefs()"))
    }

    @Test
    fun `R4-9 code input is digits only and limited per code kind`() {
        assertEquals("12345678", sanitizeWithingsCode("1234-5678-9", WithingsCodeKind.EMAIL))
        assertEquals("123456", sanitizeWithingsCode("123 456 78", WithingsCodeKind.TOTP))
        assertTrue(withingsCodeLabel(WithingsCodeKind.TOTP).contains("ověřovací aplikace (TOTP)"))
        assertFalse(withingsCodeLabel(WithingsCodeKind.TOTP).contains("SMS"))
        assertTrue(withingsCodeLabel(WithingsCodeKind.EMAIL).contains("8místný"))
    }

    @Test
    fun `R4-9 Keystore exception in the dialog call becomes an error result instead of a crash`() {
        val r = withingsSafeCall { throw IllegalStateException("KeyStoreException: locked") }
        assertEquals(WITHINGS_SECURE_STORE_ERROR, (r as WithingsLoginResult.Error).message)
    }

    @Test
    fun `K leftover shared multisport legs are hidden from the received list and weekly summary`() {
        val rows = listOf(
            ReceivedRow("peer", "activity", "garmin-777", 1, "{}"),
            ReceivedRow("peer", "activity", "garmin-777-s1", 1, "{}"),
            ReceivedRow("peer", "activity", "fit-abc-s2", 1, "{}"),
            ReceivedRow("peer", "activity", "garmin-888", 1, "{}"),
        )
        assertEquals(listOf("garmin-777", "garmin-888"), filterTopLevelReceivedActivities(rows).map { it.objectId })
        assertTrue(isSharedMultisportLeg("opaque-id", projectionActivityId = "garmin-777-s3"))
        assertFalse(isSharedMultisportLeg("garmin-777", projectionActivityId = "garmin-777"))
    }
}
