package app.pulse4.android.reticulum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayRoutingStateTest {

    @Test
    fun gatewayTransportIdResetsOnConnectionAndEndpointChange() {
        val state = GatewayRoutingState()
        val tid1 = ByteArray(16) { (it + 1).toByte() }
        val tid2 = ByteArray(16) { (it + 20).toByte() }

        assertTrue(state.onGatewayEndpointSelected("gw1.example.org", 4242))
        assertTrue(state.updateGatewayTransportId(tid1))
        assertFalse(state.updateGatewayTransportId(tid1.copyOf()))
        assertEquals(tid1.toList(), state.gatewayTransportId?.toList())

        // Same endpoint should preserve transport ID
        assertFalse(state.onGatewayEndpointSelected("gw1.example.org", 4242))
        assertEquals(tid1.toList(), state.gatewayTransportId?.toList())

        // Changing port or host resets gatewayTransportId
        assertTrue(state.onGatewayEndpointSelected("gw1.example.org", 4243))
        assertNull(state.gatewayTransportId)

        assertTrue(state.updateGatewayTransportId(tid2))
        assertEquals(tid2.toList(), state.gatewayTransportId?.toList())

        // Connection drop/network change resets gatewayTransportId
        state.onConnectionChanged(clearEndpoint = false)
        assertNull(state.gatewayTransportId)
        assertTrue(state.isSameGatewayEndpoint("gw1.example.org", 4243))

        state.onConnectionChanged(clearEndpoint = true)
        assertFalse(state.isSameGatewayEndpoint("gw1.example.org", 4243))
    }

    @Test
    fun pathRequestBuiltForRecipientDestinationsWithCooldownAndDiscovery() {
        val state = GatewayRoutingState(pathRequestCooldownMs = 60_000L)
        val recipientHex = "0123456789abcdef0123456789abcdef"

        // Offline gateway -> no packet
        assertNull(state.buildPathRequestIfNeeded(recipientHex, isGatewayOnline = false, nowMs = 1_000L))

        // Invalid destination hash -> no packet
        assertNull(state.buildPathRequestIfNeeded("not-hex", isGatewayOnline = true, nowMs = 1_000L))

        // Online gateway & undiscovered recipient -> builds path request
        val pkt1 = state.buildPathRequestIfNeeded(recipientHex, isGatewayOnline = true, nowMs = 1_000L)
        assertNotNull(pkt1)
        state.markPathRequested(recipientHex, nowMs = 1_000L)

        // Within cooldown -> suppressed unless force=true
        assertNull(state.buildPathRequestIfNeeded(recipientHex, isGatewayOnline = true, nowMs = 30_000L))
        assertNotNull(state.buildPathRequestIfNeeded(recipientHex, isGatewayOnline = true, force = true, nowMs = 30_000L))

        // After cooldown -> allowed again
        assertNotNull(state.buildPathRequestIfNeeded(recipientHex, isGatewayOnline = true, nowMs = 62_000L))

        // Once destination is discovered -> suppressed unless force=true
        state.recordDiscoveredDestination(recipientHex, ByteArray(64) { 0x42 })
        assertTrue(state.hasDiscoveredDestination(recipientHex))
        assertNull(state.buildPathRequestIfNeeded(recipientHex, isGatewayOnline = true, nowMs = 120_000L))
        assertNotNull(state.buildPathRequestIfNeeded(recipientHex, isGatewayOnline = true, force = true, nowMs = 120_000L))
    }
}
