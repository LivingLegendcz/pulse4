package app.pulse4.ui

import app.pulse4.sync.DeliveryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R4-8: permanent PN rejection is shown to the user with a readable reason. */
class DeliveryFailureLabelTest {
    @Test
    fun `permanent failure has its own label and readable reason`() {
        assertTrue(Labels.delivery(DeliveryState.FAILED_PERMANENT).contains("odmítnuto"))
        val line = Status.outboxLine("projection.upsert", "activity/garmin-1", "abcdef1234", DeliveryState.FAILED_PERMANENT, 1, "TOO_LARGE: 40 KB > 32 KB")
        assertEquals("pokusů 1 · zpráva je větší, než propagation uzel přijímá (40 KB > 32 KB)", line.detail)
        assertTrue(Labels.deliveryError("REJECTED_BY_NODE").contains("odmítl"))
        assertEquals("LINK_TEARDOWN", Labels.deliveryError("LINK_TEARDOWN"))
    }
}
