package app.pulse4.sync

/** Evidence příloh, které jsme konkrétnímu příjemci skutečně nabídli. */
fun interface AttachmentIndex {
    fun isOffered(recipient: String, sha256: String): Boolean
}

sealed interface AttachmentDecision {
    data object Allow : AttachmentDecision
    data class Deny(val why: String) : AttachmentDecision
}

object AttachmentGate {
    /**
     * Příjem Reticulum Link: identita iniciátora i oprávnění ke konkrétní příloze se ověřují explicitně.
     * Znalost hashe souboru není oprávnění; neznámý kontakt nemůže zahájit velký přenos.
     */
    fun authorize(
        initiator: String,
        sha256: String,
        length: Long,
        isVerifiedContact: (String) -> Boolean,
        index: AttachmentIndex,
        limits: ProtocolLimits = ProtocolLimits(),
    ): AttachmentDecision = when {
        !isVerifiedContact(initiator) -> AttachmentDecision.Deny("neznámý kontakt")
        length <= 0 || length > limits.maxAttachmentBytes -> AttachmentDecision.Deny("velikost mimo limit")
        !index.isOffered(initiator, sha256) -> AttachmentDecision.Deny("příloha nebyla tomuto příjemci nabídnuta")
        else -> AttachmentDecision.Allow
    }

    /** Po přenosu: délka a hash musí sedět, jinak se soubor zahodí (obnovený přenos nesmí zaměnit verzi). */
    fun verifyReceived(bytes: ByteArray, expectedSha256: String, expectedLength: Long): Boolean =
        bytes.size.toLong() == expectedLength && Wire.sha256Hex(bytes) == expectedSha256
}
