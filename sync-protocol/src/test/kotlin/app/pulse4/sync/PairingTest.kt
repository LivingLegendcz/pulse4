package app.pulse4.sync

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingTest {
    private val pub = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
    private val ok = PairingPayload(1, pub, "a".repeat(32), "Josef")

    @Test fun `roundtrip and fingerprint is stable and grouped`() {
        val r = assertIs<PairingResult.Ok>(Pairing.parse(Pairing.encode(ok)))
        assertEquals(ok, r.payload)
        assertEquals(r.fingerprint, Pairing.fingerprint(pub))
        assertTrue(Regex("([0-9a-f]{4} ){5}[0-9a-f]{4}").matches(r.fingerprint), r.fingerprint)
        assertTrue(Pairing.fingerprint(Base64.getEncoder().encodeToString(ByteArray(64) { 1 })) != r.fingerprint)

        // 32-bajtový klíč
        val pub32 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val ok32 = ok.copy(pub = pub32)
        val r32 = assertIs<PairingResult.Ok>(Pairing.parse(Pairing.encode(ok32)))
        assertEquals(ok32, r32.payload)
    }

    @Test fun `malformed, oversized, wrong version, short key and bad address are refused`() {
        fun bad(t: String) = assertIs<PairingResult.Invalid>(Pairing.parse(t))
        bad("nonsense"); bad("x".repeat(2000))
        bad(Pairing.encode(ok.copy(v = 2)))
        bad(Pairing.encode(ok.copy(pub = Base64.getEncoder().encodeToString(ByteArray(10)))))
        bad(Pairing.encode(ok.copy(addr = "xyz")))
        bad(Pairing.encode(ok.copy(name = " ")))
    }

    @Test fun `address claimed in the code must match what the transport derives`() {
        assertTrue(Pairing.addressMatches("ABCD", "abcd")); assertFalse(Pairing.addressMatches("abcd", "abce"))
    }
}
